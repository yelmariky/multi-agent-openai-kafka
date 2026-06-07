#!/usr/bin/env bash
# =============================================================================
#  uninstall.sh — Désinstallation de postgres
#  Propose deux modes :
#    soft  : supprime les ressources K8s — données conservées sur le host
#            → réinstallation possible avec ./install.sh sans perte de realm/users
#    hard  : supprime tout + efface /Users/younes/data/postgres du disque
#            → IRRÉVERSIBLE, tous les users et sessions sont perdus
# =============================================================================

set -euo pipefail

NAMESPACE="db"
DATA_DIR="/Users/younes/data/db"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

echo ""
echo "════════════════════════════════════════════════════"
echo "   postgres — Désinstallation"
echo "════════════════════════════════════════════════════"
echo ""
echo "  Choisir le mode :"
echo "  [1] Soft  — supprime les ressources K8s, données conservées sur le host"
echo "              → réinstallation possible avec ./install.sh sans perte de realm/users"
echo "  [2] Hard  — supprime tout + efface $DATA_DIR du disque"
echo "              → IRRÉVERSIBLE, tous les users et sessions sont perdus"
echo "  [q] Annuler"
echo ""
read -rp "  Choix [1/2/q] : " MODE
echo ""

case "$MODE" in
  q|Q)
    info "Annulé."
    exit 0
    ;;
  1|2) ;;
  *)
    error "Choix invalide."
    exit 1
    ;;
esac

if [[ "$MODE" == "2" ]]; then
    echo -e "  ${RED}⚠️  ATTENTION — mode Hard sélectionné${NC}"
    echo "  Toutes les données dans $DATA_DIR seront effacées définitivement."
    read -rp "  Confirmer en tapant DELETE : " CONFIRM
    echo ""
    if [[ "$CONFIRM" != "DELETE" ]]; then
        warn "Confirmation incorrecte — annulé."
        exit 1
    fi
fi

# ─── 1. Deployment ────────────────────────────────────────────────────────────
info "Suppression du Deployment postgres..."
kubectl delete deployment postgres -n "$NAMESPACE" --ignore-not-found 2>/dev/null && \
    success "Deployment supprimé." || warn "Deployment introuvable."

# Attendre que le pod disparaisse
if kubectl get pods -n "$NAMESPACE" -l app=postgres --no-headers 2>/dev/null | grep -q .; then
    info "Attente de la terminaison du pod..."
    kubectl wait --for=delete pod -l app=postgres -n "$NAMESPACE" --timeout=60s 2>/dev/null || \
        kubectl delete pod -l app=postgres -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
fi
success "Pod terminé."

# ─── 2. Services ──────────────────────────────────────────────────────────────
info "Suppression des Services..."
kubectl delete service postgres postgres-nodeport -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "Services supprimés."

# ─── 3. Secret ────────────────────────────────────────────────────────────────
info "Suppression du Secret postgres-secret..."
kubectl delete secret postgres-secret -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "Secret supprimé."

# ─── 4. ConfigMap realm ───────────────────────────────────────────────────────
info "Suppression du ConfigMap postgres-realm-config..."
kubectl delete configmap postgres-realm-config -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "ConfigMap supprimé."

# ─── 5. PVC ───────────────────────────────────────────────────────────────────
info "Suppression du PersistentVolumeClaim postgres-pvc..."
kubectl delete pvc postgres-pvc -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "PVC supprimé."

# ─── 6. PV ────────────────────────────────────────────────────────────────────
info "Suppression du PersistentVolume postgres-pv..."
kubectl delete pv postgres-pv --ignore-not-found 2>/dev/null
kubectl delete -f "${SCRIPT_DIR}/pv-postgres.yaml" --ignore-not-found 2>/dev/null || true
success "PV supprimé."

# ─── 7. StorageClass ──────────────────────────────────────────────────────────
info "Suppression de la StorageClass postgres-hostpath..."
kubectl delete storageclass postgres-hostpath --ignore-not-found 2>/dev/null
success "StorageClass supprimée."

# ─── 8. Namespace ─────────────────────────────────────────────────────────────
info "Suppression du namespace $NAMESPACE..."
kubectl delete namespace "$NAMESPACE" --ignore-not-found 2>/dev/null
success "Namespace supprimé."

# ─── 9. Données sur le host (mode Hard uniquement) ────────────────────────────
if [[ "$MODE" == "2" ]]; then
    info "Suppression des données sur le host : $DATA_DIR ..."
    rm -rf "$DATA_DIR"
    success "Données effacées."
else
    echo ""
    echo -e "  ${GREEN}Données conservées${NC} dans $DATA_DIR"
    echo "  Pour réinstaller sans perte : ./install.sh"
    echo "  Pour effacer manuellement  : rm -rf $DATA_DIR"
fi

# ─── Résumé ───────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════"
if [[ "$MODE" == "2" ]]; then
    echo -e "   ${RED}Désinstallation complète (Hard) — données effacées${NC}"
else
    echo -e "   ${GREEN}Désinstallation Soft terminée — données préservées${NC}"
fi
echo "════════════════════════════════════════════════════"
echo ""
