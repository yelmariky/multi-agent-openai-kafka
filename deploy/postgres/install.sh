#!/usr/bin/env bash
# install.sh — Déploiement complet de postgres sur K8s
#
# Prérequis :
#   - kubectl configuré (kubectl config current-context)
#
# Usage :
#   cp deploy/postgres/.env.postgres deploy/postgres/.env
#   vim deploy/postgres/.env   # remplir postgres_ADMIN et postgres_ADMIN_PASSWORD
#   ./install.sh
#
# Ou sans .env (export manuel) :
#   export postgres_ADMIN=admin
#   export postgres_ADMIN_PASSWORD=monMotDePasse
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
  echo "       Copier .env.db → .env et remplir les valeurs, ou exporter manuellement."
fi

# --- Validation des prérequis ---
if [[ -z "${POSTGRES_USER:-}" ]]; then
  echo "[ERROR] La variable postgres_ADMIN est requise."
  echo "        Définir dans ${ENV_FILE} ou : export POSTGRES_USER=user"
  exit 1
fi

if [[ -z "${POSTGRES_PASSWORD:-}" ]]; then
  echo "[ERROR] La variable POSTGRES_PASSWORD est requise."
  echo "        Définir dans ${ENV_FILE} ou : export POSTGRES_PASSWORD=monMotDePasse"
  exit 1
fi

if [[ -z "${POSTGRES_DB:-}" ]]; then
  echo "[ERROR] La variable POSTGRES_DB est requise."
  echo "        Définir dans ${ENV_FILE} ou : export POSTGRES_DB=DB"
  exit 1
fi

echo "==> [1/6] Création du namespace db"
#kubectl apply -f "${SCRIPT_DIR}/namespace.yaml"

echo "==> [2/6] Création du répertoire de données postgresql sur le nœud host"
mkdir -p /Users/younes/data/postgres

echo "==> [3/6] Création du PersistentVolume et du PersistentVolumeClaim"
kubectl apply -f "${SCRIPT_DIR}/pv-postgresql.yaml"

echo "==> [4/6] Création du Secret Postgresql depuis les variables d'environnement"
for NS in db multi-agent; do
  kubectl create secret generic postgres-secret \
    --namespace="${NS}" \
    --from-literal=POSTGRES_USER="${POSTGRES_USER}" \
    --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
    --from-literal=POSTGRES_DB="${POSTGRES_DB}" \
    --from-literal=POSTGRES_URL="${POSTGRES_URL}" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "  → postgres-secret créé dans namespace ${NS}"
done

echo "==> [5/6] Déploiement postgresql"
kubectl apply -f "${SCRIPT_DIR}/deployment.yaml"

echo "==> [6/6] Application des Services (ClusterIP )"
kubectl apply -f "${SCRIPT_DIR}/service.yaml"

echo ""
echo "[OK] Postgresql déployé."