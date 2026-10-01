#!/bin/sh
# Résout le chemin du script indépendamment du répertoire depuis lequel il est appelé
# (nécessaire car ce script est parfois exécuté directement, parfois "source"-é depuis
# start-agents.sh — dans ce dernier cas $0 pointe vers le script appelant, pas celui-ci).
if [ -n "${BASH_SOURCE:-}" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
fi

ENV_FILE="$SCRIPT_DIR/../../.openai/.env.local"
if [ ! -f "$ENV_FILE" ]; then
  echo "❌ Fichier introuvable : $ENV_FILE"
  echo "   Créez-le avec vos clés (voir docs/PRODUCTION-KEYS.md) :"
  echo "     OPENAI_API_KEY=sk-..."
  echo "     OPENAI_LLM_API_KEY=gsk_..."
  exit 1
fi
source "$ENV_FILE"
kubectl get secret postgres-secret --namespace=db -o yaml | grep -v 'namespace:\|uid:\|resourceVersion:\|creationTimestamp:' | kubectl apply --namespace=multi-agent -f -
kubectl get secret keycloak-secret -n keycloak -o yaml | grep -v 'namespace:\|uid:\|resourceVersion:\|creationTimestamp:' | kubectl apply --namespace=multi-agent -f -
kubectl create secret generic openai-secret \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --from-literal=OPENAI_LLM_API_KEY="$OPENAI_LLM_API_KEY" \
  -n multi-agent \
  --dry-run=client -o yaml | kubectl apply -f -
echo "Secret appliqué."

##kubectl rollout restart deployment/ai-service -n multi-agent
