#!/usr/bin/env bash
set -euo pipefail

# Déploie la plateforme multi-tenant : namespace, topics Kafka, secrets,
# puis configMaps/deployments pour ai-service, expense-service, activity-service,
# notification-service et invoice-service.

kubectl >/dev/null 2>&1 || { echo "kubectl introuvable"; exit 1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Namespace multi-agent"
kubectl create ns multi-agent --dry-run=client -o yaml | kubectl apply -f -

echo "==> Topics Kafka (Job kafka-topic-init)"
kubectl apply -f "$ROOT/deploy/kafka/topics-events.yaml"
kubectl wait --for=condition=complete job/kafka-topic-init -n agent-system --timeout=120s || \
  echo "⚠️  kafka-topic-init pas encore terminé — vérifier: kubectl logs job/kafka-topic-init -n agent-system"

echo "==> Secrets (OpenAI, Postgres, Keycloak)"
source "$ROOT/ai-service/deploy/k8s/init-secret.sh"

# Ordre important : les configMaps doivent être appliquées avant les deployments
# qui les référencent (expense-service et activity-service réutilisent multi-agent-config).
MANIFESTS=(
  "$ROOT/ai-service/deploy/k8s/configMap.yaml"
  "$ROOT/ai-service/deploy/k8s/deployment.yaml"
  "$ROOT/expense-service/deploy/k8s/deployment.yaml"
  "$ROOT/activity-service/deploy/k8s/deployment.yaml"
  "$ROOT/notification-service/deploy/k8s/deployment.yaml"
  "$ROOT/invoice-service/deploy/k8s/configMap.yaml"
  "$ROOT/invoice-service/deploy/k8s/deployment.yaml"
  "$ROOT/invoice-service/deploy/k8s/service.yaml"
)

for f in "${MANIFESTS[@]}"; do
  echo "kubectl apply -f $f"
  kubectl apply -f "$f"
done

kubectl get pod -n multi-agent -w
