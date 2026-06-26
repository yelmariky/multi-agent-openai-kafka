# Infrastructure K8s & Environnement

## Namespaces K8s

| Namespace | Contenu |
|---|---|
| `multi-agent` | ai-core, invoice-service, PostgreSQL (pgvector) |
| `keycloak` | Keycloak 24 + PV/PVC hostPath |
| `kong` | Kong DB-less gateway |
| `agent-system` | Kafka KRaft 3 noeuds |

## Structure monorepo Maven

```
multi-agent-openai-kafka/
  ai-core/               <- pod K8s — port 8081 (owns Flyway schema)
  invoice-service/       <- pod K8s — port 8083 (shared DB, flyway disabled)
  shared/                <- contrats API (IntentRequest, ReasoningResult...)
  intent-agent/          <- module Maven non deploye
  reasoning-agent/       <- module Maven non deploye
  reassign-agent/        <- module Maven non deploye
  audit-agent/           <- module Maven non deploye
  frontend/              <- console admin — :3000/{slug}/
  frontend-consultant/   <- espace consultant — :3001/{slug}/
  frontend-platform/     <- console plateforme — :3002
  deploy/
    postgres/            <- pgvector/pgvector:pg16, PV/PVC, secret
    keycloak/            <- install.sh, uninstall.sh, realm-configmap, theme
    kafka/               <- KRaft 3 noeuds
    gateway/             <- Kong DB-less
```

## Variables d'environnement requises

```bash
# ai-core ET invoice-service
OPENAI_API_KEY=sk-...
POSTGRES_URL=jdbc:postgresql://localhost:5432/ia_insight
POSTGRES_USER=ia_insight
POSTGRES_PASSWORD=changeme

# ai-core uniquement
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KEYCLOAK_ADMIN_URL=http://localhost:8090       # pour KeycloakProvisioningService
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=changeme
# + tous les AI_CORE_PROMPT_* (voir configMap.yaml)

# invoice-service uniquement
AI_CORE_INVOICES_STORAGE_PATH=/tmp/invoices   # defaut /tmp/invoices en local
```

## OCR — prerequis locaux

```bash
# macOS
brew install tesseract poppler tesseract-lang

# Debian/Ubuntu
sudo apt-get install -y tesseract-ocr tesseract-ocr-fra poppler-utils
```
