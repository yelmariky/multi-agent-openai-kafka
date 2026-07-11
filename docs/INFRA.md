# Infrastructure K8s & Environnement

## Namespaces K8s

| Namespace | Contenu |
|---|---|
| `multi-agent` | ai-service :8081, expense-service :8082, activity-service :8085, invoice-service :8083, notification-service :8084, PostgreSQL pgvector |
| `keycloak` | Keycloak 24.0.2 + PV/PVC hostPath |
| `kong` | Kong DB-less gateway |
| `agent-system` | Kafka KRaft 3 noeuds |

## Structure monorepo Maven

```
multi-agent-openai-kafka/
  ai-service/          ← pod K8s — port 8081 (owns Flyway schema)
  expense-service/     ← pod K8s — port 8082 (flyway disabled)
  activity-service/    ← pod K8s — port 8085 (flyway disabled)
  invoice-service/     ← pod K8s — port 8083 (flyway disabled)
  notification-service/← pod K8s — port 8084 (SSE hub, pas de DB)
  frontend/            ← console admin       — :3000/{slug}/
  frontend-consultant/ ← espace consultant   — :3001/{slug}/
  frontend-platform/   ← console plateforme  — :3002
  deploy/
    agents/            ← start-agents.sh / uninstall-agents.sh
    postgres/          ← pgvector/pgvector:pg16, PV/PVC, secret
    keycloak/          ← install.sh, uninstall.sh, realm-configmap, theme
    kafka/             ← KRaft 3 noeuds + topics-events.yaml (Job kafka-topic-init)
    gateway/           ← Kong DB-less
    secrets/           ← generate-sealed-secrets.sh (Bitnami Sealed Secrets)
```

## Variables d'environnement requises

```bash
# Tous les services Spring Boot (sauf notification-service)
POSTGRES_URL=jdbc:postgresql://localhost:5432/ia_insight
POSTGRES_USER=ia_insight
POSTGRES_PASSWORD=changeme
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# ai-service, expense-service (LLM)
OPENAI_API_KEY=sk-...           # OpenAI — embeddings text-embedding-3-large
OPENAI_LLM_API_KEY=gsk_...      # Groq   — LLM principal
OPENAI_LLM_BASE_URL=https://api.groq.com/openai/v1

# ai-service uniquement
KEYCLOAK_ADMIN_URL=http://localhost:8090
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=changeme
# + tous les AI_CORE_PROMPT_* (voir ai-service/deploy/k8s/configMap.yaml)

# invoice-service uniquement
AI_CORE_INVOICES_STORAGE_PATH=/tmp/invoices
```

Modèles LLM configurés via `configMap.yaml` (namespace multi-agent) :

| Variable | Valeur par défaut | Rôle |
|---|---|---|
| `OPENAI_MODEL` | `llama-3.1-8b-instant` | Intent / rewrite (Groq) |
| `OPENAI_EXPENSE_MODEL` | `openai/gpt-oss-120b` | Extraction JSON notes de frais (Groq) |
| `AI_CORE_RAG_MODEL` | `meta-llama/llama-4-scout-17b-16e-instruct` | RAG (Groq) |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-large` | Embeddings (OpenAI — toujours) |
| `OPENAI_FALLBACK_MODEL` | `gpt-4.1-mini` | Fallback si Groq KO (OpenAI) |

## OCR — prérequis locaux

```bash
# macOS
brew install tesseract poppler tesseract-lang

# Debian/Ubuntu (Dockerfile expense-service)
apt-get install -y tesseract-ocr tesseract-ocr-fra poppler-utils
```

## Kafka — topics & résilience

Voir `docs/KAFKA.md` pour la carte complète des interactions inter-services et l'analyse
de résilience (patterns producteur/consommateur, gaps identifiés, recommandations Phase 3).
