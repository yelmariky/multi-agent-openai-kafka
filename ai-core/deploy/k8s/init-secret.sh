#!/bin/sh
source "../../.openai/.env.local"
kubectl get secret postgres-secret --namespace=db -o yaml | grep -v 'namespace:\|uid:\|resourceVersion:\|creationTimestamp:' | kubectl apply --namespace= -f -
kubectl get secret keycloak-secret -n keycloak -o yaml | grep -v 'namespace:\|uid:\|resourceVersion:\|creationTimestamp:' | kubectl apply --namespace= -f -
kubectl create secret generic openai-secret \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --from-literal=OPENAI_LLM_API_KEY="$OPENAI_LLM_API_KEY" \
  -n multi-agent \
  --dry-run=client -o yaml | kubectl apply -f -
echo "Secret appliqué."

##kubectl rollout restart deployment/ai-core -n multi-agent
