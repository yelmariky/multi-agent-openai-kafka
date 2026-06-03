#!/usr/bin/env bash
# install.sh — Déploiement complet de Keycloak sur K8s
#
# Prérequis :
#   - kubectl configuré (kubectl config current-context)
#
# Usage :
#   cp deploy/keycloak/.env.keycloak deploy/keycloak/.env
#   vim deploy/keycloak/.env   # remplir KEYCLOAK_ADMIN et KEYCLOAK_ADMIN_PASSWORD
#   ./install.sh
#
# Ou sans .env (export manuel) :
#   export KEYCLOAK_ADMIN=admin
#   export KEYCLOAK_ADMIN_PASSWORD=monMotDePasse
#   ./install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Chargement du fichier .env si présent ---
ENV_FILE="${SCRIPT_DIR}/.env"
if [[ -f "${ENV_FILE}" ]]; then
  echo "[INFO] Chargement des variables depuis ${ENV_FILE}"
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
else
  echo "[WARN] Aucun fichier .env trouvé (${ENV_FILE})"
  echo "       Copier .env.keycloak → .env et remplir les valeurs, ou exporter manuellement."
fi

# --- Validation des prérequis ---
if [[ -z "${KEYCLOAK_ADMIN:-}" ]]; then
  echo "[ERROR] La variable KEYCLOAK_ADMIN est requise."
  echo "        Définir dans ${ENV_FILE} ou : export KEYCLOAK_ADMIN=admin"
  exit 1
fi

if [[ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]]; then
  echo "[ERROR] La variable KEYCLOAK_ADMIN_PASSWORD est requise."
  echo "        Définir dans ${ENV_FILE} ou : export KEYCLOAK_ADMIN_PASSWORD=monMotDePasse"
  exit 1
fi

echo "==> [1/7] Création du namespace keycloak"
kubectl apply -f "${SCRIPT_DIR}/namespace.yaml"

echo "==> [2/7] Création du répertoire de données Keycloak sur le nœud host"
mkdir -p /Users/younes/data/keycloak

echo "==> [3/7] Création du PersistentVolume et du PersistentVolumeClaim"
kubectl apply -f "${SCRIPT_DIR}/pv-keycloak.yaml"
kubectl apply -f "${SCRIPT_DIR}/pvc-keycloak.yaml"

echo "==> [4/7] Création du Secret Keycloak depuis les variables d'environnement"
kubectl create secret generic keycloak-secret \
  --namespace=keycloak \
  --from-literal=KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN}" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> [5/7] Application du ConfigMap realm ia-insight"
kubectl apply -f "${SCRIPT_DIR}/realm-configmap.yaml"

echo "==> [6/7] Déploiement Keycloak"
kubectl apply -f "${SCRIPT_DIR}/deployment.yaml"

echo "==> [7/7] Application des Services (ClusterIP + NodePort)"
kubectl apply -f "${SCRIPT_DIR}/service.yaml"

echo ""
echo "[OK] Keycloak déployé."
echo "     Accès dev local : http://localhost:30080"
echo "     Attendre que le pod soit prêt :"
echo "       kubectl rollout status deployment/keycloak -n keycloak"
echo ""
echo "     JWKS endpoint (valider les JWT depuis Kong/ai-core) :"
echo "       http://keycloak.keycloak.svc.cluster.local:8080/realms/ia-insight/protocol/openid-connect/certs"
echo "     Ou en accès local :"
echo "       http://localhost:30080/realms/ia-insight/protocol/openid-connect/certs"
