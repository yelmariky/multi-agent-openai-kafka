# MultiAgent MCP Server — Notes de frais & Factures

Serveur MCP (Model Context Protocol) qui expose les capacités du système
multi-agent directement dans Claude Desktop (ou tout client MCP compatible).

## Ce que ça fait

Une société peut piloter l'intégralité de sa gestion de frais et facturation
via Claude, en langage naturel :

```
"Crée une note de frais pour un repas au restaurant hier, 45€, pour IA-INSIGHT"
→ Expense créée, indexée, prête pour le rapport mensuel.

"Génère le rapport d'avril 2026 pour IA-INSIGHT en PDF"
→ Rapport téléchargé localement avec totaux et synthèse.

"Facture ACME Corp pour 5 jours de dev à 600€/jour + TVA 20%"
→ Facture PDF + Excel générés automatiquement.
```

## Outils disponibles

| Outil | Description |
|---|---|
| `create_expense` | Crée une note de frais depuis du texte libre |
| `upload_receipt` | Upload un justificatif (image/PDF) avec OCR automatique |
| `list_expenses` | Liste les dépenses entre deux dates (JSON) |
| `download_expense_report` | Génère un rapport Excel ou PDF |
| `generate_invoice` | Génère une facture depuis du texte libre (PDF + Excel) |
| `download_invoice_pdf` | Télécharge directement le PDF de la facture |
| `delete_expense` | Supprime les dépenses d'une date donnée |
| `check_ai_core_health` | Vérifie que le service AI-Core est disponible |

## Prérequis

- Python 3.11+
- Le service **AI-Core** (Spring Boot) doit être lancé et accessible
- Claude Desktop (ou tout client MCP compatible)

## Installation

### Option 1 — Depuis PyPI (recommandé)

```bash
pip install multiagent-mcp
```

### Option 2 — Depuis les sources

```bash
cd mcp-server
pip install -e .
```

## Configuration Claude Desktop

### macOS
Éditer `~/Library/Application Support/Claude/claude_desktop_config.json`

### Windows
Éditer `%APPDATA%\Claude\claude_desktop_config.json`

### Linux
Éditer `~/.config/claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "multiagent": {
      "command": "multiagent-mcp",
      "env": {
        "AI_CORE_URL": "http://localhost:8081",
        "AI_CORE_TIMEOUT": "60",
        "MCP_OUTPUT_DIR": "/tmp/multiagent-reports"
      }
    }
  }
}
```

Redémarrer Claude Desktop après modification du fichier de config.

## Variables d'environnement

| Variable | Défaut | Description |
|---|---|---|
| `AI_CORE_URL` | `http://localhost:8081` | URL du service AI-Core |
| `AI_CORE_TIMEOUT` | `60` | Timeout HTTP en secondes |
| `MCP_OUTPUT_DIR` | `/tmp` | Dossier de sortie pour les rapports et factures |

## Multi-société

Chaque outil accepte un paramètre `company` qui identifie la société.
Les données sont isolées par société dans Weaviate.

Exemples de sociétés :
- `"IA-INSIGHT"` — société par défaut dans la config AI-Core
- `"ACME Corp"` — autre société cliente
- `"BNP Paribas"` — pour la facturation client

## Exemples d'utilisation dans Claude

### Notes de frais

```
"Enregistre un taxi de 23.50€ pris ce matin pour IA-INSIGHT, trajet domicile-bureau"

"Upload le justificatif /Users/john/tickets/resto-2026-04-09.jpg pour IA-INSIGHT"

"Liste les dépenses de IA-INSIGHT pour mars 2026"

"Génère le rapport Excel des notes de frais d'avril 2026 pour IA-INSIGHT"

"Supprime les notes de frais du 2026-04-01 pour IA-INSIGHT"
```

### Factures

```
"Génère une facture pour ACME Corp :
  - 5 jours de développement back-end à 600€/jour
  - 1 jour de gestion de projet à 800€/jour
  TVA 20%, paiement à 30 jours"

"Télécharge le PDF de la facture pour BNP Paribas :
  mission consulting IA 3 jours à 1200€, TVA 20%"
```

### Diagnostic

```
"Vérifie que le système de gestion de frais est opérationnel"
```

## Architecture

```
Claude Desktop
     │  MCP Protocol (stdio)
     ▼
multiagent-mcp (Python)
  ├── server.py        ← Outils MCP (FastMCP)
  └── ai_core_client.py ← Client HTTP vers AI-Core
     │  HTTP REST
     ▼
AI-Core (Spring Boot :8081)
  ├── /reasoning/analyze
  ├── /receipts/upload
  ├── /expenses/report/*
  └── /invoices/generate/*
     │
     ▼
Weaviate (Vector DB) + OpenAI GPT-5.4
```

## Développement

```bash
cd mcp-server

# Installation avec dépendances de dev
pip install -e ".[dev]"

# Tests
pytest

# Test manuel du serveur MCP
python -m multiagent_mcp.server
```

## Déploiement Kubernetes

Pour exposer le MCP server depuis un cluster K8s, deux options :

**Option A — sidecar** : déployer `multiagent-mcp` comme sidecar du pod `ai-core`

**Option B — SSE** (Server-Sent Events) : utiliser `mcp.run(transport="sse")` pour exposer
le serveur via HTTP et le connecter depuis l'extérieur du cluster.

```python
# server.py — mode SSE pour Kubernetes
def main():
    mcp.run(transport="sse", host="0.0.0.0", port=8090)
```
