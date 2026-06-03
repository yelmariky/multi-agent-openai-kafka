#!/bin/bash
# =============================================================================
#  uninstall.sh — Désinstallation de Kafka KRaft
#  Propose deux modes :
#    soft  : supprime pods/services/PVCs/PVs — données conservées sur le host
#    hard  : supprime tout + efface /Users/younes/data/kafka du disque
# =============================================================================

set -euo pipefail

NAMESPACE="agent-system"
CONFIGMAP_NAME="kafka-cluster-id"
DATA_BASE="/Users/younes/data/kafka"
PV_MANIFEST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pv-kafka.yaml"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

echo ""
echo "════════════════════════════════════════════════════"
echo "   Kafka KRaft — Désinstallation"
echo "════════════════════════════════════════════════════"
echo ""
echo "  Choisir le mode :"
echo "  [1] Soft  — supprime les ressources k8s, données conservées sur le host"
echo "              → réinstallation possible avec ./install.sh sans perte de topics"
echo "  [2] Hard  — supprime tout + efface $DATA_BASE du disque"
echo "              → IRRÉVERSIBLE, tous les topics sont perdus"
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
    echo "  Toutes les données dans $DATA_BASE seront effacées définitivement."
    read -rp "  Confirmer en tapant DELETE : " CONFIRM
    echo ""
    if [[ "$CONFIRM" != "DELETE" ]]; then
        warn "Confirmation incorrecte — annulé."
        exit 1
    fi
fi

# ─── 1. StatefulSet ───────────────────────────────────────────────────────────
info "Suppression du StatefulSet kafka..."
kubectl delete statefulset kafka -n "$NAMESPACE" --ignore-not-found 2>/dev/null && \
    success "StatefulSet supprimé." || warn "StatefulSet introuvable."

# Attendre que les pods disparaissent
if kubectl get pods -n "$NAMESPACE" -l app=kafka --no-headers 2>/dev/null | grep -q .; then
    info "Attente de la terminaison des pods..."
    kubectl wait --for=delete pod -l app=kafka -n "$NAMESPACE" --timeout=60s 2>/dev/null || \
        kubectl delete pod -l app=kafka -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
fi
success "Pods terminés."

# ─── 2. Services ──────────────────────────────────────────────────────────────
info "Suppression des services..."
kubectl delete service kafka kafka-headless -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "Services supprimés."

# ─── 3. ConfigMap cluster.id ──────────────────────────────────────────────────
info "Suppression du ConfigMap $CONFIGMAP_NAME..."
kubectl delete configmap "$CONFIGMAP_NAME" -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "ConfigMap supprimé."

# ─── 4. PVCs ──────────────────────────────────────────────────────────────────
info "Suppression des PersistentVolumeClaims..."
kubectl delete pvc -l app=kafka -n "$NAMESPACE" --ignore-not-found 2>/dev/null
success "PVCs supprimés."

# ─── 5. PVs ───────────────────────────────────────────────────────────────────
info "Suppression des PersistentVolumes (kafka-pv-{0,1,2})..."
kubectl delete pv kafka-pv-0 kafka-pv-1 kafka-pv-2 --ignore-not-found 2>/dev/null
kubectl delete -f "$PV_MANIFEST" --ignore-not-found 2>/dev/null || true
success "PVs supprimés."

# ─── 6. StorageClass ──────────────────────────────────────────────────────────
info "Suppression de la StorageClass kafka-hostpath..."
kubectl delete storageclass kafka-hostpath --ignore-not-found 2>/dev/null
success "StorageClass supprimée."

# ─── 7. Données sur le host (mode Hard uniquement) ────────────────────────────
if [[ "$MODE" == "2" ]]; then
    info "Suppression des données sur le host : $DATA_BASE ..."
    rm -rf "$DATA_BASE"
    success "Données effacées."
else
    echo ""
    echo -e "  ${GREEN}Données conservées${NC} dans $DATA_BASE"
    echo "  Pour réinstaller sans perte : ./install.sh"
    echo "  Pour effacer manuellement  : rm -rf $DATA_BASE"
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
