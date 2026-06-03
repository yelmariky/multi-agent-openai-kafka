#!/bin/sh
source "../../.openai/.env.local"

kubectl create secret generic openai-secret \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  -n multi-agent \
  --dry-run=client -o yaml | kubectl apply -f -
echo "Secret appliqué."

##kubectl rollout restart deployment/ai-core -n multi-agent
