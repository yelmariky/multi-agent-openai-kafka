#!/usr/bin/env bash
# install.sh — Déploiement complet de Kong API Gateway sur K8s
#
# Kong fonctionne en mode DB-less : toute la configuration est dans le ConfigMap kong-config.
# Toute modification de la config nécessite :
#   kubectl rollout restart deployment/kong -n gateway
#
# Usage :
#   ./install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> [1/4] Création du namespace gateway"
kubectl apply -f "${SCRIPT_DIR}/namespace.yaml"

echo "==> [2/4] Application du ConfigMap Kong (config déclarative DB-less)"
kubectl apply -f "${SCRIPT_DIR}/configmap.yaml"

echo "==> [3/4] Déploiement Kong 3.6"
kubectl apply -f "${SCRIPT_DIR}/deployment.yaml"

echo "==> [4/4] Application des Services (ClusterIP proxy+admin, NodePort dev)"
kubectl apply -f "${SCRIPT_DIR}/service.yaml"

echo ""
echo "[OK] Kong API Gateway déployé."
echo "     Attendre que le pod soit prêt :"
echo "       kubectl rollout status deployment/kong -n gateway"
echo ""
echo "     Accès dev local (proxy) : http://localhost:30000"
echo "     Exemple de requête via Kong :"
echo "       curl http://localhost:30000/expenses/report?start=2026-01-01&end=2026-06-30"
echo ""
echo "     Admin API (depuis le cluster) :"
echo "       kubectl exec -n gateway deploy/kong -- curl http://localhost:8001/routes"
echo ""
echo "     IMPORTANT — JWT Plugin :"
echo "       En dev, désactiver le plugin JWT dans configmap.yaml pour éviter les rejets 401."
echo "       En prod, configurer le consumer JWT avec la clé publique Keycloak (RS256) :"
echo "         JWKS : http://keycloak.keycloak.svc.cluster.local:8080/realms/ia-insight/protocol/openid-connect/certs"
