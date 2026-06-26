#!/usr/bin/env bash
set -euo pipefail

# Delete configs/secrets/deployments for ai-core, reasoning, reassign, audit.

kubectl >/dev/null 2>&1 || { echo "kubectl introuvable"; exit 1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

MANIFESTS=(
  "$ROOT/ai-core/deploy/k8s/deployment.yaml"
  "$ROOT/invoice-service/deploy/k8s/deployment.yaml"
)

for f in "${MANIFESTS[@]}"; do
  echo "kubectl delete -f $f --ignore-not-found"
  kubectl delete -f "$f" --ignore-not-found
done
