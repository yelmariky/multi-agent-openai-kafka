#!/usr/bin/env bash
# install.sh — Déploiement PostgreSQL + pgvector sur K8s
#
# Prérequis :
#   - kubectl configuré (kubectl config current-context)
#   - Répertoire de données créé sur le nœud host : /data/postgresql
#
# Usage :
#   cp deploy/postgresql/.env.example deploy/postgresql/.env
#   vim deploy/postgresql/.env   # remplir POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
#   ./install.sh
#
# Ou sans .env (export manuel) :
#   export POSTGRES_USER=pgadmin
#   export POSTGRES_PASSWORD=monMotDePasse
#   export POSTGRES_DB=iainsight
#   ./install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

# --- Chargement du fichier .env si présent ---
ENV_FILE="${SCRIPT_DIR}/.env"
if [[ -f "${ENV_FILE}" ]]; then
  info "Chargement des variables depuis ${ENV_FILE}"
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
else
  warn "Aucun fichier .env trouvé (${ENV_FILE})"
  warn "Copier .env.example → .env et remplir les valeurs, ou exporter manuellement."
fi

# --- Validation des prérequis ---
if [[ -z "${POSTGRES_USER:-}" ]]; then
  error "La variable POSTGRES_USER est requise."
  echo "    Définir dans ${ENV_FILE} ou : export POSTGRES_USER=pgadmin"
  exit 1
fi

if [[ -z "${POSTGRES_PASSWORD:-}" ]]; then
  error "La variable POSTGRES_PASSWORD est requise."
  echo "    Définir dans ${ENV_FILE} ou : export POSTGRES_PASSWORD=monMotDePasse"
  exit 1
fi

if [[ -z "${POSTGRES_DB:-}" ]]; then
  error "La variable POSTGRES_DB est requise."
  echo "    Définir dans ${ENV_FILE} ou : export POSTGRES_DB=iainsight"
  exit 1
fi

echo ""
echo "════════════════════════════════════════════════════"
echo "   PostgreSQL + pgvector — Installation"
echo "════════════════════════════════════════════════════"
echo ""

# --- Étape 1 : Namespace ---
info "[1/7] Création du namespace postgres"
kubectl apply -f "${SCRIPT_DIR}/namespace.yaml"
success "Namespace prêt."

# --- Étape 2 : Répertoire de données sur le host ---
info "[2/7] Création du répertoire de données sur le nœud host (/data/postgresql)"
mkdir -p /data/postgresql
success "Répertoire prêt."

# --- Étape 3 : StorageClass + PV + PVC ---
info "[3/7] Création du StorageClass, PersistentVolume et PersistentVolumeClaim"
kubectl apply -f "${SCRIPT_DIR}/pv-postgresql.yaml"
success "PV/PVC créés."

# --- Étape 4 : Secret ---
info "[4/7] Création du Secret postgres-secret"
kubectl create secret generic postgres-secret \
  --namespace=postgres \
  --from-literal=POSTGRES_USER="${POSTGRES_USER}" \
  --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  --from-literal=POSTGRES_DB="${POSTGRES_DB}" \
  --dry-run=client -o yaml | kubectl apply -f -
success "Secret créé."

# --- Étape 5 : ConfigMap init SQL ---
info "[5/7] Application du ConfigMap init SQL (pgvector + tables métier)"
kubectl apply -f "${SCRIPT_DIR}/configmap-init.yaml"
success "ConfigMap init SQL appliqué."

# --- Étape 6 : StatefulSet ---
info "[6/7] Déploiement du StatefulSet PostgreSQL (image pgvector/pgvector:pg16)"
kubectl apply -f "${SCRIPT_DIR}/statefulset.yaml"
success "StatefulSet déployé."

# --- Étape 7 : Services ---
info "[7/7] Application des Services (ClusterIP + NodePort 30432)"
kubectl apply -f "${SCRIPT_DIR}/service.yaml"
success "Services créés."

echo ""
echo "════════════════════════════════════════════════════"
echo -e "   ${GREEN}PostgreSQL + pgvector déployé avec succès${NC}"
echo "════════════════════════════════════════════════════"
echo ""
echo "  Attendre que le pod soit prêt :"
echo "    kubectl rollout status statefulset/postgresql -n postgres"
echo ""
echo "  Accès dev local (port-forward) :"
echo "    kubectl port-forward svc/postgresql -n postgres 5432:5432"
echo "    psql -h localhost -U ${POSTGRES_USER} -d ${POSTGRES_DB}"
echo ""
echo "  Accès direct via NodePort :"
echo "    psql -h <NODE_IP> -p 30432 -U ${POSTGRES_USER} -d ${POSTGRES_DB}"
echo ""
echo "  DNS interne K8s :"
echo "    postgresql.postgres.svc.cluster.local:5432"
echo ""
echo "  JDBC URL pour les services :"
echo "    jdbc:postgresql://postgresql.postgres.svc.cluster.local:5432/${POSTGRES_DB}"
echo ""
