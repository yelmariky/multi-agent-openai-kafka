# Multi-Agent AI Platform — IA-INSIGHT

Plateforme d'automatisation RH et facturation basée sur une architecture **multi-agents IA** avec OpenAI GPT-4o, Weaviate 5.5 et Apache Kafka.

---

## Fonctionnalités

| Domaine | Fonctionnalité |
|---|---|
| Notes de frais | Saisie texte libre (IA), upload justificatif (OCR), frais km mensuels auto-expansés |
| CRA | Saisie mensuelle consultant, soumission, validation ou refus admin, motif de refus |
| Factures | Génération PDF/Excel depuis texte libre ou formulaire, lookup Weaviate |
| Approbation | Workflow admin : approuver / refuser les notes de frais par consultant |
| Notifications | SSE temps réel → admin notifié à chaque soumission CRA ou création de frais |
| Rapports | PDF et Excel par période, filtré par consultant, avec email en en-tête |

---

## Stack technique

| Couche | Technologie |
|---|---|
| LLM | OpenAI GPT-4o / GPT-4o-mini (`com.openai:openai-java:4.8.0`) |
| Vector DB | Weaviate 5.5 |
| Backend | Spring Boot 3.3.4, Java 21, Maven |
| OCR | Tesseract + poppler |
| Frontend | Vanilla JS (pas de build) |
| Infra | Kubernetes, Docker |

---

## Démarrage rapide

```bash
# 1. Backend
cd ai-core
export OPENAI_API_KEY=sk-...
export WEAVIATE_HOST=localhost:8080
mvn spring-boot:run            # → http://localhost:8081

# 2. Frontend admin
cd frontend && python -m http.server 3000   # → http://localhost:3000

# 3. Frontend consultant
cd frontend-consultant && python -m http.server 3001  # → http://localhost:3001
```

**Comptes démo**

| Frontend | Email | Mot de passe |
|---|---|---|
| Admin | `admin@ia-insight.fr` | `admin123` |
| Admin | `manager@ia-insight.fr` | `admin123` |
| Consultant | `alice.martin@ia-insight.fr` | `demo123` |
| Consultant | `bob.dupont@ia-insight.fr` | `demo123` |
| Consultant | `charlie.bernard@freelance.com` | `demo123` |

---

## Architecture

```
[Frontend admin :3000]           [Frontend consultant :3001]
  Login mock, cloche SSE 🔔         Login mock → Keycloak (phase 2)
         │                                     │
         └──────────────┬──────────────────────┘
                        ▼
                 [AI-Core :8081]          ← seul service déployé
                        │
                ┌───────┴───────┐
                ▼               ▼
           Weaviate          OpenAI
            :8080            GPT-4o
```

**Structure interne d'ai-core (modular monolith)** :

```
expense/     → RAG, OCR, frais km, PDF/Excel, approbation
invoice/     → génération PDF/Excel, lookup Weaviate
cra/         → workflow BROUILLON→SOUMIS→VALIDE/REFUSÉ
notification/→ SSE temps réel (in-memory)
settings/    → SellerProfile, ConsultantProfile
reasoning/   → pipeline LLM : intent → routing → extraction
```

---

## Workflow CRA

```
[Consultant]                          [Admin]
     │                                   │
     ├─ Saisit son CRA (BROUILLON)       │
     ├─ Soumet → POST /cra/submit ──────►│ 🔔 notification SSE
     │           (statut : SOUMIS)       │
     │                                   ├─ Valide → POST /cra/validate
     │                                   │           (statut : VALIDE)
     │◄──────── REFUSÉ + motif ──────────┤─ ou Refuse → POST /cra/refuse
     │           (statut : BROUILLON)    │              (statut : BROUILLON)
     ├─ Corrige et re-soumet             │
     └─ ...                              └─ ...
```

---

## Workflow Notes de frais

```
[Consultant]                          [Admin]
     │                                   │
     ├─ Crée note de frais              │
     │  POST /reasoning/analyze ────────►│ 🔔 notification SSE
     │  (approvalStatus: PENDING)        │
     │                                   ├─ Approuve → POST /expenses/approve
     │◄──────── APPROUVÉ ───────────────┤   (approvalStatus: APPROVED)
     │   ou                              │
     │◄──────── REFUSÉ + note ──────────┤─ ou Refuse → POST /expenses/refuse
     └─                                  └─  (approvalStatus: REFUSED)
```

---

## API Reference

### Notes de frais

```
POST /reasoning/analyze
  Body: { "text": "...", "consultantEmail": "alice@..." }
  → Extraction IA + indexation Weaviate + notification admin

POST /receipts/upload
  Multipart: file + paymentMode + consultantEmail
  → OCR + extraction + indexation

GET  /expenses/report?start=YYYY-MM-DD&end=YYYY-MM-DD[&consultantEmail=]
GET  /expenses/report/pdf?start&end[&consultantEmail]       → PDF (email en en-tête)
GET  /expenses/report/pdf/month?month=YYYY-MM[&consultantEmail]
GET  /expenses/report/excel?start&end[&consultantEmail]     → Excel (email en en-tête)
DELETE /expenses/delete

POST /expenses/approve   Body: { "weaviateId": "...", "note": "..." }
POST /expenses/refuse    Body: { "weaviateId": "...", "note": "..." }
```

### CRA

```
POST /cra/save      Body: CraRequest (12 champs)
POST /cra/submit    Body: CraRequest  → SOUMIS + notification admin
POST /cra/validate  Body: CraRequest  ?validatedBy=Nom  → VALIDE
POST /cra/refuse    Body: CraRequest  ?reason=Motif     → BROUILLON + refusedReason
GET  /cra/report    ?start=YYYY-MM&end=YYYY-MM[&consultant=][&company=]
GET  /cra/absences  ?month=YYYY-MM&company=...&consultant=...
POST /cra/delete    Body: { "id": "<uuid>" }
```

### Factures

```
POST /invoices/generate              Body: SimpleInvoiceRequest
POST /invoices/generate/pdf/from-text  Body: { "text": "..." }  → PDF direct
POST /invoices/generate/excel/from-text
POST /invoices/pdf                   Body: InvoiceLookupRequest → PDF lookup
POST /invoices/excel                 Body: InvoiceLookupRequest → Excel lookup
POST /invoices/delete
GET  /invoices/report  ?start=YYYY-MM&end=YYYY-MM&company=...
```

### Notifications (SSE)

```
GET  /admin/notifications/stream        → SSE (events: init, notification)
GET  /admin/notifications?all=false     → liste non lus
POST /admin/notifications/{id}/read
POST /admin/notifications/read-all
```

---

## Frais km — règles d'expansion

Quand le texte mentionne un mois français (ex : "40km en mai") sans jour précis :
1. `isKmMonthly()` détecte le pattern
2. `expandKmMonthly()` génère une ligne par jour ouvré du mois
3. Exclusions : week-ends + jours fériés FR (1/1, 1/5, 8/5, 14/7, 15/8, 1/11, 11/11, 25/12) + `absencePeriods` fournies
4. Chaque ligne hérite de `consultantEmail` via `copyExpense()`

Les absences sont **auto-chargées** dans le frontend consultant à l'entrée dans l'onglet Notes de frais (depuis `/cra/absences`).

---

## Sécurité — Suppressions réservées au terminal

L'interface web envoie `X-Source: web-ui`. Le backend bloque les intents `delete_*` avec HTTP 403 si ce header est présent.

```bash
# Depuis le terminal (sans le header) :
curl -X POST http://localhost:8081/reasoning/analyze \
  -H 'Content-Type: application/json' \
  -d '{"text":"supprime la note de frais du 15 mai pour IA-INSIGHT"}'
```

---

## Infra K8s

```bash
# Port-forwards
kubectl port-forward svc/weaviate 8080:8080 -n weaviate

# Déploiement ai-core
kubectl apply -f ai-core/deploy/k8s/
kubectl apply -f deploy/weaviate/

# Mise à jour des prompts LLM
kubectl apply -f ai-core/deploy/k8s/configMap.yaml -n multi-agent
kubectl rollout restart deployment/ai-core -n multi-agent
```

Namespaces : `multi-agent` (ai-core) · `weaviate`

---

## OCR — prérequis

```bash
# macOS
brew install tesseract poppler tesseract-lang

# Debian/Ubuntu
apt-get install -y tesseract-ocr tesseract-ocr-fra poppler-utils
```

---

## Variables d'environnement

```bash
OPENAI_API_KEY=sk-...                    # obligatoire
WEAVIATE_HOST=localhost:8080             # obligatoire (host:port sans scheme)
WEAVIATE_SCHEME=http                     # défaut : http
AI_CORE_COMPANY_NAME=IA-INSIGHT          # défaut
# AI_CORE_PROMPT_* → voir ai-core/deploy/k8s/configMap.yaml
```

---

## Évolutions prévues (phase 2)

- **Keycloak** : remplacer les mock logins par OIDC (JWT `email`/`name`/`realm_roles`), ajouter `Authorization: Bearer` côté frontend, `spring-security-oauth2-resource-server` côté backend
- **Génération automatique de facture** : déclencher `POST /invoices/generate` au moment de la validation d'un CRA
- **Notifications persistantes** : brancher `NotificationService` sur Weaviate pour survivre aux redémarrages

---

## Licence

MIT
