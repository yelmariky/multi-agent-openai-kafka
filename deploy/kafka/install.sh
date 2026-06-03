#!/bin/bash
# =============================================================================
#  install.sh — Kafka KRaft sur Kubernetes avec persistance hostPath
#  - Crée les répertoires de données sur le host macOS
#  - Applique StorageClass + PersistentVolumes (hostPath)
#  - Déploie ou met à jour le StatefulSet
#
#  cluster.id FIXE : toujours le même id → aucun risque de mismatch après
#  crash, uninstall/reinstall ou redémarrage Docker Desktop.
# =============================================================================

set -euo pipefail

NAMESPACE="agent-system"
KAFKA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$KAFKA_DIR/statefulset.yaml"
PV_MANIFEST="$KAFKA_DIR/pv-kafka.yaml"
CONFIGMAP_NAME="kafka-cluster-id"
DATA_BASE="/Users/younes/data/kafka"

# ─── cluster.id fixe ─────────────────────────────────────────────────────────
# Valeur fixe réutilisée à chaque install — évite tout mismatch avec les
# données existantes dans les PVCs. Ne pas changer sauf reset complet.
CLUSTER_ID="zDf0-khXTvCGa6r8jP1tRS"

# ─── couleurs ────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }

# =============================================================================
echo ""
echo "════════════════════════════════════════════════════"
echo "   Kafka KRaft — Installation / Mise à jour         "
echo "════════════════════════════════════════════════════"
echo ""

# ─── 1. Répertoires de données sur le host ───────────────────────────────────
info "Création des répertoires de données hostPath..."
for i in 0 1 2; do
    mkdir -p "$DATA_BASE/kafka-$i"
done
success "Répertoires prêts : $DATA_BASE/kafka-{0,1,2}"

# ─── 2. Namespace ─────────────────────────────────────────────────────────────
if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
    info "Création du namespace $NAMESPACE..."
    kubectl create namespace "$NAMESPACE"
    success "Namespace $NAMESPACE créé."
else
    success "Namespace $NAMESPACE existant."
fi

# ─── 3. StorageClass + PersistentVolumes ──────────────────────────────────────
info "Application de la StorageClass et des PersistentVolumes (hostPath)..."
kubectl apply -f "$PV_MANIFEST"
success "StorageClass kafka-hostpath + PVs kafka-pv-{0,1,2} appliqués."

# ── Cas post-crash/uninstall : PV en état Released ────────────────────────
# claimRef.uid pointe vers l'ancien PVC supprimé → bloque la liaison.
# kubectl apply ne supprime pas l'uid → patch explicite requis.
for i in 0 1 2; do
    PV_PHASE=$(kubectl get pv "kafka-pv-$i" -o jsonpath='{.status.phase}' 2>/dev/null || echo "NotFound")
    if [[ "$PV_PHASE" == "Released" ]]; then
        warn "kafka-pv-$i est en Released — patch claimRef pour déblocage..."
        kubectl patch pv "kafka-pv-$i" --type=json -p='[
            {"op":"remove","path":"/spec/claimRef/uid"},
            {"op":"remove","path":"/spec/claimRef/resourceVersion"}
        ]' 2>/dev/null \
        || kubectl patch pv "kafka-pv-$i" --type=merge \
            -p='{"spec":{"claimRef":{"uid":null,"resourceVersion":null}}}' 2>/dev/null \
        || true
        success "kafka-pv-$i débloqué → Available."
    fi
done

echo ""
kubectl get pv -l app=kafka 2>/dev/null || true
echo ""

# ─── 4. ConfigMap cluster.id ──────────────────────────────────────────────────
info "Application du ConfigMap cluster.id = $CLUSTER_ID ..."
kubectl create configmap "$CONFIGMAP_NAME" \
    --from-literal="cluster.id=$CLUSTER_ID" \
    -n "$NAMESPACE" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
success "ConfigMap $CONFIGMAP_NAME appliqué."

# ─── 5. Déploiement du StatefulSet ────────────────────────────────────────────
echo ""
info "Application du StatefulSet Kafka..."
kubectl apply -f "$MANIFEST"

# Supprimer les pods en état anormal pour forcer une recréation propre
CRASH_PODS=$(kubectl get pods -n "$NAMESPACE" -l app=kafka \
    --field-selector='status.phase!=Running' \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)
if [[ -n "$CRASH_PODS" ]]; then
    warn "Pods en état anormal — suppression pour recréation : $CRASH_PODS"
    # shellcheck disable=SC2086
    kubectl delete pod $CRASH_PODS -n "$NAMESPACE" --ignore-not-found 2>/dev/null || true
fi

# ─── 6. Attente que les pods soient prêts ─────────────────────────────────────
echo ""
info "Attente que les pods Kafka soient prêts (timeout 5 min)..."
if kubectl wait --for=condition=ready pod -l app=kafka -n "$NAMESPACE" --timeout=300s 2>/dev/null; then
    success "Tous les pods Kafka sont prêts !"
else
    warn "Timeout — vérifiez : kubectl get pods -n $NAMESPACE -l app=kafka"
fi

# ─── 7. Résumé ────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════"
echo -e "   ${GREEN}Installation Kafka terminée${NC}"
echo "════════════════════════════════════════════════════"
echo ""
echo "  Namespace  : $NAMESPACE"
echo "  Bootstrap  : kafka.$NAMESPACE.svc.cluster.local:9092"
echo "  Cluster ID : $CLUSTER_ID  (fixe)"
echo "  Données    : PVCs data-kafka-{0,1,2} → hostPath $DATA_BASE/"
echo "  Rétention  : 2 jours (48h)"
echo ""
echo "  Vérifier   : kubectl get pods,pvc,pv -n $NAMESPACE"
echo "  Port-fwd   : kubectl port-forward svc/kafka 9092:9092 -n $NAMESPACE"
echo ""
