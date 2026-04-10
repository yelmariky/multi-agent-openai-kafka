"""
MultiAgent MCP Server — Notes de frais & Factures

Expose 7 outils MCP qui délèguent à l'API REST d'AI-Core :

  create_expense          → POST /reasoning/analyze
  upload_receipt          → POST /receipts/upload
  list_expenses           → GET  /expenses/report (JSON)
  download_expense_report → GET  /expenses/report/excel ou /pdf
  generate_invoice        → POST /invoices/generate/from-text
  download_invoice_pdf    → POST /invoices/generate/pdf/from-text
  delete_expense          → DELETE /expenses/delete?date=...
  check_ai_core_health    → GET  /actuator/health

Variables d'environnement:
  AI_CORE_URL      : URL de l'API AI-Core (défaut: http://localhost:8081)
  AI_CORE_TIMEOUT  : Timeout HTTP en secondes (défaut: 60)
  MCP_OUTPUT_DIR   : Dossier de sortie pour les fichiers téléchargés (défaut: /tmp)
"""

import json
import os
import tempfile
from pathlib import Path

from mcp.server.fastmcp import FastMCP

from .ai_core_client import AICoreClient, AICoreError

# ---------------------------------------------------------------------------
# Initialisation
# ---------------------------------------------------------------------------

mcp = FastMCP(
    name="MultiAgent Expense & Invoice",
    instructions="""
Tu es un assistant spécialisé dans la gestion des notes de frais et des factures pour les sociétés.

Tu peux :
- Créer des notes de frais depuis une description en langage naturel
- Uploader et analyser des justificatifs (tickets, factures, images, PDF) via OCR
- Lister et filtrer les dépenses par période et par société
- Générer des rapports de notes de frais en Excel ou en PDF
- Générer des factures clients complètes (PDF + Excel) depuis une description textuelle
- Supprimer des dépenses par date
- Vérifier que le système est opérationnel

Toujours demander le nom de la société si il n'est pas précisé.
Les dates doivent être au format ISO : YYYY-MM-DD.
""",
)

_client = AICoreClient(
    base_url=os.environ.get("AI_CORE_URL", "http://localhost:8081"),
    timeout=int(os.environ.get("AI_CORE_TIMEOUT", "60")),
)

_output_dir = Path(os.environ.get("MCP_OUTPUT_DIR", tempfile.gettempdir()))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _save(content: bytes, filename: str) -> str:
    """Sauvegarde des bytes dans MCP_OUTPUT_DIR et retourne le chemin."""
    _output_dir.mkdir(parents=True, exist_ok=True)
    path = _output_dir / filename
    path.write_bytes(content)
    return str(path)


def _ok(data: dict | list | str) -> str:
    """Sérialise la réponse en JSON lisible."""
    if isinstance(data, str):
        return data
    return json.dumps(data, ensure_ascii=False, indent=2)


def _err(e: Exception) -> str:
    if isinstance(e, AICoreError):
        return f"Erreur AI-Core (HTTP {e.status}) : {e}"
    return f"Erreur : {e}"


# ---------------------------------------------------------------------------
# Outils MCP
# ---------------------------------------------------------------------------

@mcp.tool()
def create_expense(text: str, company: str) -> str:
    """
    Crée une note de frais à partir d'une description en langage naturel
    et l'indexe automatiquement dans la base vectorielle (Weaviate).

    Le système extrait automatiquement : montant, devise, type de dépense,
    date, adresse, mode de paiement.

    Exemples de descriptions :
      - "J'ai payé 45€ au restaurant Le Marais hier soir"
      - "Taxi Uber 23.50€ ce matin, trajet domicile-client"
      - "Hôtel Mercure Lyon, 3 nuits à 120€/nuit, payé par carte pro"
      - "Frais kilométriques Paris-Lyon, 450 km, barème 7CV"
      - "Domiciliation bureau 450€ ce mois-ci"

    Args:
        text:    Description libre de la dépense
        company: Nom de la société (ex: "IA-INSIGHT", "ACME Corp")

    Returns:
        JSON contenant la dépense créée avec son identifiant, montant,
        type, date, et statut d'indexation.
    """
    try:
        enriched = f"{text} — société: {company}" if company.strip() else text
        result = _client.analyze(enriched)
        return _ok(result)
    except Exception as e:
        return _err(e)


@mcp.tool()
def upload_receipt(file_path: str, company: str) -> str:
    """
    Uploade un justificatif (ticket de caisse, facture, note de restaurant)
    au format image ou PDF. L'OCR extrait automatiquement les données.

    Détecte également les doublons (même fichier déjà uploadé).

    Formats acceptés : .pdf, .jpg, .jpeg, .png, .tiff, .webp

    Args:
        file_path: Chemin absolu vers le fichier justificatif
                   (ex: "/home/user/ticket-restaurant.jpg")
        company:   Nom de la société (ex: "IA-INSIGHT")

    Returns:
        JSON avec les données extraites par OCR (montant, date, type, devise,
        adresse), les hash de déduplication, et un flag si doublon détecté.
    """
    try:
        result = _client.upload_receipt(file_path)
        return _ok(result)
    except FileNotFoundError as e:
        return f"Fichier introuvable : {e}"
    except Exception as e:
        return _err(e)


@mcp.tool()
def list_expenses(
    start_date: str,
    end_date: str,
    company: str,
    expense_type: str = "",
    currency: str = "",
) -> str:
    """
    Liste toutes les notes de frais entre deux dates pour une société,
    avec les totaux par devise et par type de dépense.

    Args:
        start_date:   Date de début au format YYYY-MM-DD (ex: "2026-04-01")
        end_date:     Date de fin au format YYYY-MM-DD (ex: "2026-04-30")
        company:      Nom de la société (ex: "IA-INSIGHT")
        expense_type: Filtre optionnel par type (ex: "restaurant", "hotel",
                      "taxi", "frais_km", "location", "carburant")
        currency:     Filtre optionnel par devise (ex: "EUR", "USD")

    Returns:
        JSON avec la liste des dépenses, les totaux par devise,
        les totaux par type, et la période couverte.
    """
    try:
        result = _client.expense_report_json(
            start_date,
            end_date,
            company,
            expense_type or None,
            currency or None,
        )
        return _ok(result)
    except Exception as e:
        return _err(e)


@mcp.tool()
def download_expense_report(
    start_date: str,
    end_date: str,
    company: str,
    format: str = "excel",
) -> str:
    """
    Génère et télécharge un rapport complet de notes de frais.

    Le rapport Excel contient deux onglets :
      - "Dépenses" : liste détaillée (id, date, montant, type, mode de paiement…)
      - "Synthèse" : totaux par type et par devise, calcul des remboursements

    Le rapport PDF est une version imprimable avec en-tête société.

    Args:
        start_date: Date de début au format YYYY-MM-DD (ex: "2026-04-01")
        end_date:   Date de fin au format YYYY-MM-DD (ex: "2026-04-30")
        company:    Nom de la société (ex: "IA-INSIGHT")
        format:     "excel" (défaut) ou "pdf"

    Returns:
        Chemin absolu vers le fichier téléchargé sur cette machine.
    """
    try:
        fmt = format.lower().strip()
        safe_company = company.replace(" ", "-").replace("/", "-")
        filename = f"rapport-{safe_company}-{start_date}-{end_date}"

        if fmt == "pdf":
            content = _client.expense_report_pdf(start_date, end_date, company)
            path = _save(content, f"{filename}.pdf")
            return f"Rapport PDF généré : {path}"
        else:
            content = _client.expense_report_excel(start_date, end_date, company)
            path = _save(content, f"{filename}.xlsx")
            return f"Rapport Excel généré : {path}"
    except Exception as e:
        return _err(e)


@mcp.tool()
def generate_invoice(text: str) -> str:
    """
    Génère une facture complète (PDF + Excel) depuis une description
    en langage naturel. Le LLM extrait automatiquement les lignes de
    prestation, les quantités, le taux de TVA et les informations client.

    Exemples de descriptions :
      - "Facture pour ACME Corp, 5 jours de développement back-end à 600€/jour,
         1 jour de gestion de projet à 800€. TVA 20%. Échéance 30 jours."
      - "Facture BNP Paribas pour mission de consulting IA, 3 jours à 1200€,
         TVA 20%, à régler sous 15 jours."
      - "Facturer Société Générale pour : audit sécurité 2j×1500€,
         rapport 1j×800€, TVA 20%."

    Args:
        text: Description complète de la facture en langage naturel.
              Inclure : client, prestations, quantités, prix unitaires,
              taux TVA, échéance de paiement.

    Returns:
        JSON avec les chemins vers les fichiers PDF et Excel générés,
        et le nom de la facture.
    """
    try:
        result = _client.generate_invoice_from_text(text)
        return _ok(result)
    except Exception as e:
        return _err(e)


@mcp.tool()
def download_invoice_pdf(text: str, output_filename: str = "") -> str:
    """
    Génère une facture depuis une description texte et télécharge
    directement le PDF sur cette machine (sans passer par le serveur).

    Utile pour récupérer le fichier PDF immédiatement sans aller le chercher
    sur le serveur AI-Core.

    Args:
        text:            Description complète de la facture (voir generate_invoice)
        output_filename: Nom du fichier de sortie (optionnel).
                         Défaut: "facture-<timestamp>.pdf"

    Returns:
        Chemin absolu vers le fichier PDF téléchargé.
    """
    try:
        import time
        content = _client.generate_invoice_pdf_from_text(text)
        name = output_filename.strip() or f"facture-{int(time.time())}.pdf"
        if not name.endswith(".pdf"):
            name += ".pdf"
        path = _save(content, name)
        return f"Facture PDF téléchargée : {path}"
    except Exception as e:
        return _err(e)


@mcp.tool()
def delete_expense(date: str) -> str:
    """
    Supprime toutes les notes de frais enregistrées pour une date donnée.

    Attention : la suppression est définitive et supprime TOUTES les dépenses
    de la date indiquée (pas de suppression partielle par ID via cet outil).

    Args:
        date: Date au format YYYY-MM-DD (ex: "2026-04-10")

    Returns:
        Nombre de dépenses supprimées.
    """
    try:
        result = _client.delete_expense_by_date(date)
        return _ok(result)
    except Exception as e:
        return _err(e)


@mcp.tool()
def check_ai_core_health() -> str:
    """
    Vérifie que le service AI-Core est opérationnel et accessible.
    Utile pour diagnostiquer les problèmes de connexion avant d'utiliser
    les autres outils.

    Returns:
        Statut du service (UP / DOWN) et informations de santé.
    """
    try:
        result = _client.health()
        return _ok(result)
    except Exception as e:
        return f"AI-Core inaccessible à {_client.base_url} : {e}"


# ---------------------------------------------------------------------------
# Point d'entrée
# ---------------------------------------------------------------------------

def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
