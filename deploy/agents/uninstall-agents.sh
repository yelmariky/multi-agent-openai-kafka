#!/usr/bin/env bash
set -euo pipefail

# Supprime les deployments/services de la plateforme multi-tenant :
# ai-service, expense-service, activity-service, notification-service, invoice-service.
# Les secrets, configMaps et le namespace multi-agent sont conservés (pas de perte de données).

kubectl >/dev/null 2>&1 || { echo "kubectl introuvable"; exit 1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

MANIFESTS=(
  "$ROOT/ai-service/deploy/k8s/deployment.yaml"
  "$ROOT/expense-service/deploy/k8s/deployment.yaml"
  "$ROOT/activity-service/deploy/k8s/deployment.yaml"
  "$ROOT/notification-service/deploy/k8s/deployment.yaml"
  "$ROOT/invoice-service/deploy/k8s/deployment.yaml"
  "$ROOT/invoice-service/deploy/k8s/service.yaml"
)

for f in "${MANIFESTS[@]}"; do
  echo "kubectl delete -f $f --ignore-not-found"
  kubectl delete -f "$f" --ignore-not-found
done

echo "kubectl delete -f $ROOT/deploy/kafka/topics-events.yaml --ignore-not-found"
kubectl delete -f "$ROOT/deploy/kafka/topics-events.yaml" --ignore-not-found
