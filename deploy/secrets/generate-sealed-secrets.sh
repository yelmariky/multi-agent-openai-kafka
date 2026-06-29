#!/usr/bin/env bash
# ============================================================
# Génère les SealedSecrets pour le cluster multi-agent.
# Prérequis : kubeseal installé + sealed-secrets controller déployé
#   brew install kubeseal
#   kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.26.1/controller.yaml
#
# Usage :
#   export OPENAI_API_KEY="sk-proj-..."
#   export OPENAI_LLM_API_KEY="gsk_..."
#   export POSTGRES_PASSWORD="..."
#   ./generate-sealed-secrets.sh
# ============================================================
set -euo pipefail

NAMESPACE_APP="multi-agent"
NAMESPACE_DB="db"
OUT_DIR="$(dirname "$0")"

check_var() {
  if [ -z "${!1:-}" ]; then
    echo "❌ Variable manquante : $1" >&2
    exit 1
  fi
}

check_var OPENAI_API_KEY
check_var OPENAI_LLM_API_KEY
check_var POSTGRES_PASSWORD

KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-changeme}"
AI_CORE_ADMIN_KEY="${AI_CORE_ADMIN_KEY:-change-me-in-prod}"
POSTGRES_USER="${POSTGRES_USER:-ia_insight}"
POSTGRES_DB="${POSTGRES_DB:-ia_insight}"
POSTGRES_URL="${POSTGRES_URL:-jdbc:postgresql://postgres.db.svc.cluster.local:5432/ia_insight}"

echo "🔐 Génération du SealedSecret : openai-secret (namespace $NAMESPACE_APP)..."
kubectl create secret generic openai-secret \
  --namespace "$NAMESPACE_APP" \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --from-literal=OPENAI_LLM_API_KEY="$OPENAI_LLM_API_KEY" \
  --from-literal=KEYCLOAK_ADMIN_USER="$KEYCLOAK_ADMIN_USER" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  --from-literal=AI_CORE_ADMIN_KEY="$AI_CORE_ADMIN_KEY" \
  --from-literal=POSTGRES_URL="$POSTGRES_URL" \
  --from-literal=POSTGRES_USER="$POSTGRES_USER" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --dry-run=client -o yaml \
| kubeseal \
    --controller-name=sealed-secrets-controller \
    --controller-namespace=kube-system \
    --format yaml \
> "$OUT_DIR/sealed-openai-secret.yaml"

echo "✅ Généré : $OUT_DIR/sealed-openai-secret.yaml"

echo "🔐 Génération du SealedSecret : postgres-secret (namespace $NAMESPACE_DB)..."
kubectl create secret generic postgres-secret \
  --namespace "$NAMESPACE_DB" \
  --from-literal=POSTGRES_USER="$POSTGRES_USER" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=POSTGRES_DB="$POSTGRES_DB" \
  --dry-run=client -o yaml \
| kubeseal \
    --controller-name=sealed-secrets-controller \
    --controller-namespace=kube-system \
    --format yaml \
> "$OUT_DIR/sealed-postgres-secret.yaml"

echo "✅ Généré : $OUT_DIR/sealed-postgres-secret.yaml"

echo ""
echo "📦 Appliquer dans le cluster :"
echo "   kubectl apply -f $OUT_DIR/sealed-openai-secret.yaml"
echo "   kubectl apply -f $OUT_DIR/sealed-postgres-secret.yaml"
