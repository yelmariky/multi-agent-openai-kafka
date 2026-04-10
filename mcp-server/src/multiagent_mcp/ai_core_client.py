"""
Client HTTP vers AI-Core (Spring Boot).
Chaque méthode correspond à un endpoint REST exposé par ai-core.
"""

import json
from pathlib import Path
from typing import Optional

import httpx


# Types MIME courants pour les justificatifs
_MIME_BY_SUFFIX = {
    ".pdf":  "application/pdf",
    ".png":  "image/png",
    ".jpg":  "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif":  "image/gif",
    ".webp": "image/webp",
    ".tiff": "image/tiff",
    ".tif":  "image/tiff",
}


class AICoreError(Exception):
    """Erreur retournée par AI-Core (statut HTTP != 2xx)."""
    def __init__(self, status: int, message: str):
        super().__init__(f"AI-Core error {status}: {message}")
        self.status = status


class AICoreClient:
    """
    Thin client HTTP synchrone vers l'API REST d'AI-Core.

    Configuration via variables d'environnement (lues dans server.py) :
      AI_CORE_URL      : URL de base (défaut http://localhost:8081)
      AI_CORE_TIMEOUT  : Timeout en secondes (défaut 60)
    """

    def __init__(self, base_url: str, timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ------------------------------------------------------------------
    # Notes de frais
    # ------------------------------------------------------------------

    def analyze(self, text: str) -> dict:
        """
        POST /reasoning/analyze
        Soumet un texte libre au pipeline complet RAG → LLM → extraction.
        Utilisé pour create_expense et delete_expense.
        """
        with self._http() as client:
            resp = client.post(
                f"{self.base_url}/reasoning/analyze",
                content=text.encode("utf-8"),
                headers={"Content-Type": "text/plain; charset=utf-8"},
            )
            self._raise(resp)
            return resp.json()

    def upload_receipt(self, file_path: str) -> dict:
        """
        POST /receipts/upload (multipart/form-data)
        Upload un justificatif image/PDF et retourne l'extraction OCR + flags doublon.
        """
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Justificatif introuvable : {file_path}")

        mime = _MIME_BY_SUFFIX.get(path.suffix.lower(), "application/octet-stream")
        with self._http() as client:
            with open(path, "rb") as fh:
                resp = client.post(
                    f"{self.base_url}/receipts/upload",
                    files={"file": (path.name, fh, mime)},
                )
            self._raise(resp)
            return resp.json()

    def expense_report_json(
        self,
        start: str,
        end: str,
        company: str,
        expense_type: Optional[str] = None,
        currency: Optional[str] = None,
    ) -> dict:
        """
        GET /expenses/report
        Retourne les dépenses entre deux dates au format JSON.
        """
        params: dict = {"start": start, "end": end, "company": company}
        if expense_type:
            params["type"] = expense_type
        if currency:
            params["currency"] = currency

        with self._http() as client:
            resp = client.get(f"{self.base_url}/expenses/report", params=params)
            self._raise(resp)
            return resp.json()

    def expense_report_excel(self, start: str, end: str, company: str) -> bytes:
        """
        GET /expenses/report/excel
        Retourne le rapport Excel en bytes.
        """
        params = {"start": start, "end": end, "company": company}
        with self._http() as client:
            resp = client.get(f"{self.base_url}/expenses/report/excel", params=params)
            self._raise(resp)
            return resp.content

    def expense_report_pdf(self, start: str, end: str, company: str) -> bytes:
        """
        GET /expenses/report/pdf
        Retourne le rapport PDF en bytes.
        """
        params = {"start": start, "end": end, "company": company}
        with self._http() as client:
            resp = client.get(f"{self.base_url}/expenses/report/pdf", params=params)
            self._raise(resp)
            return resp.content

    def expense_report_pdf_month(self, month: str, company: str) -> bytes:
        """
        GET /expenses/report/pdf/month?month=YYYY-MM
        Raccourci PDF pour un mois complet.
        """
        params = {"month": month, "company": company}
        with self._http() as client:
            resp = client.get(
                f"{self.base_url}/expenses/report/pdf/month", params=params
            )
            self._raise(resp)
            return resp.content

    def delete_expense_by_date(self, date: str) -> dict:
        """
        DELETE /expenses/delete?date=YYYY-MM-DD
        Supprime toutes les dépenses d'une date donnée.
        """
        with self._http() as client:
            resp = client.delete(
                f"{self.base_url}/expenses/delete", params={"date": date}
            )
            self._raise(resp)
            return resp.json()

    # ------------------------------------------------------------------
    # Factures
    # ------------------------------------------------------------------

    def generate_invoice_from_text(self, text: str) -> dict:
        """
        POST /invoices/generate/from-text
        Génère une facture PDF + Excel depuis un texte libre.
        Retourne les chemins vers les fichiers générés sur le serveur.
        """
        with self._http() as client:
            resp = client.post(
                f"{self.base_url}/invoices/generate/from-text",
                json={"text": text},
            )
            self._raise(resp)
            return resp.json()

    def generate_invoice_pdf_from_text(self, text: str) -> bytes:
        """
        POST /invoices/generate/pdf/from-text
        Retourne directement le PDF en bytes (pour sauvegarde locale).
        """
        with self._http() as client:
            resp = client.post(
                f"{self.base_url}/invoices/generate/pdf/from-text",
                json={"text": text},
            )
            self._raise(resp)
            return resp.content

    # ------------------------------------------------------------------
    # Santé
    # ------------------------------------------------------------------

    def health(self) -> dict:
        """GET /actuator/health — vérifie que AI-Core est disponible."""
        with self._http() as client:
            resp = client.get(f"{self.base_url}/actuator/health", timeout=5)
            self._raise(resp)
            return resp.json()

    # ------------------------------------------------------------------
    # Helpers privés
    # ------------------------------------------------------------------

    def _http(self) -> httpx.Client:
        return httpx.Client(timeout=self.timeout)

    @staticmethod
    def _raise(resp: httpx.Response) -> None:
        if resp.is_error:
            try:
                body = resp.text[:500]
            except Exception:
                body = "<unreadable>"
            raise AICoreError(resp.status_code, body)
