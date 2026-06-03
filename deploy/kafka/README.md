# Kafka KRaft sur Kubernetes (namespace `agent-system`)

Kafka 3 nœuds en mode KRaft (sans Zookeeper) avec **persistance hostPath** :
les topics et données survivent aux crashs du cluster Kubernetes.

---

## Pourquoi les données étaient perdues avant

Deux causes :

| Cause | Détail |
|---|---|
| **Pas de PVs hostPath** | Les PVCs utilisaient le StorageClass par défaut de Docker Desktop (données dans la VM Docker, perdues au crash) |
| **`KAFKA_LOG_RETENTION_HOURS: 1`** | Les topics étaient purgés après **1 heure** — corrigé à 168 h (7 jours) |

---

## Architecture de stockage

```
kafka-0  →  PVC data-kafka-0  →  PV kafka-pv-0  →  /Users/younes/data/kafka/kafka-0
kafka-1  →  PVC data-kafka-1  →  PV kafka-pv-1  →  /Users/younes/data/kafka/kafka-1
kafka-2  →  PVC data-kafka-2  →  PV kafka-pv-2  →  /Users/younes/data/kafka/kafka-2
```

- **StorageClass `kafka-hostpath`** : `provisioner: kubernetes.io/no-provisioner`, `reclaimPolicy: Retain`
- **`claimRef`** : chaque PV est pré-lié à son PVC exact — pas de risque d'échange entre nœuds
- **`DirectoryOrCreate`** : le répertoire est créé automatiquement s'il n'existe pas
- **`Retain`** : les données restent sur disque même si le PVC ou le pod est supprimé

---

## 1. Installation / Mise à jour

```bash
cd deploy/kafka
./install.sh
```

Le script fait dans l'ordre :

1. `mkdir -p /Users/younes/data/kafka/kafka-{0,1,2}` sur le host
2. Crée le namespace `agent-system` si absent
3. Applique `pv-kafka.yaml` (StorageClass + 3 PVs hostPath)
4. **Récupère le `cluster.id`** depuis `meta.properties` dans les données existantes (priorité absolue) → aucune perte de topics au redémarrage
5. Crée/met à jour le ConfigMap `kafka-cluster-id`
6. Applique `statefulset.yaml`

---

## 2. Survie à un crash du cluster Kubernetes

Au redémarrage du cluster :

```bash
# Ré-appliquer les PVs (ils ont pu être perdus du registre k8s, pas les données)
kubectl apply -f deploy/kafka/pv-kafka.yaml

# Relancer Kafka — cluster.id récupéré automatiquement depuis meta.properties
./install.sh
```

Les topics et leurs messages sont intacts dans `/Users/younes/data/kafka/kafka-{0,1,2}`.

---

## 3. Vérifications

```bash
# État des pods
kubectl get pods -n agent-system -l app=kafka

# PVs et PVCs
kubectl get pv -l app=kafka
kubectl get pvc -n agent-system

# Vérifier que les PVs sont Bound
kubectl get pv kafka-pv-0 kafka-pv-1 kafka-pv-2

# Port-forward pour tester localement
kubectl port-forward svc/kafka 9092:9092 -n agent-system
```

---

## 4. Lister / vérifier les topics

```bash
# Via port-forward (depuis le host)
kubectl port-forward svc/kafka 9092:9092 -n agent-system &

# Lister les topics
kafka-topics --bootstrap-server localhost:9092 --list

# Décrire un topic
kafka-topics --bootstrap-server localhost:9092 --describe --topic intent-input-topic
```

Ou via un pod temporaire :

```bash
kubectl run kafka-client --rm -ti --restart=Never \
  --image=confluentinc/cp-kafka:7.7.0 \
  --namespace=agent-system \
  -- kafka-topics --bootstrap-server kafka.agent-system.svc.cluster.local:9092 --list
```

---

## 5. Topics du projet

| Topic | Producteur | Consommateur |
|---|---|---|
| `intent-input-topic` | Externe / scheduler | intent-agent |
| `reasoning-input-topic` | intent-agent | reasoning-agent |
| `reassign-input-topic` | reasoning-agent | reassign-agent |
| `audit.events.in` | tous agents | audit-agent |

Créer un topic manuellement :

```bash
kubectl run kafka-client --rm -ti --restart=Never \
  --image=confluentinc/cp-kafka:7.7.0 --namespace=agent-system \
  -- kafka-topics --bootstrap-server kafka.agent-system.svc.cluster.local:9092 \
     --create --topic intent-input-topic --partitions 1 --replication-factor 1
```

---

## 6. Configuration

| Paramètre | Valeur | Raison |
|---|---|---|
| `KAFKA_LOG_RETENTION_HOURS` | `168` (7 jours) | corrigé (était `1`h) |
| `KAFKA_DEFAULT_REPLICATION_FACTOR` | `1` | local dev mono-nœud k8s |
| `KAFKA_MIN_INSYNC_REPLICAS` | `1` | cohérent avec RF=1 |
| `KAFKA_NUM_PARTITIONS` | `1` | dev — augmenter en prod |
| `KAFKA_HEAP_OPTS` | `-Xms1g -Xmx1g` | dev |
| `storageClassName` | `kafka-hostpath` | PVs dédiés sur macOS |

---

## 7. Désinstallation (données conservées)

```bash
# Supprime pods + PVCs mais PAS les données sur le host (Retain)
kubectl delete statefulset kafka -n agent-system
kubectl delete service kafka kafka-headless -n agent-system
kubectl delete pvc -l app=kafka -n agent-system
kubectl delete configmap kafka-cluster-id -n agent-system

# Les PVs passent en Released (données intactes dans /Users/younes/data/kafka/)
kubectl delete pv kafka-pv-0 kafka-pv-1 kafka-pv-2
```

Pour **effacer définitivement** les données :

```bash
rm -rf /Users/younes/data/kafka
```

---

## 8. Connexion depuis les microservices

DNS interne Kubernetes :

```
kafka.agent-system.svc.cluster.local:9092
```

Variable d'environnement dans les ConfigMaps :

```bash
KAFKA_BOOTSTRAP_SERVERS=kafka.agent-system.svc.cluster.local:9092
```

---

## 9. Fichiers

| Fichier | Rôle |
|---|---|
| `pv-kafka.yaml` | StorageClass `kafka-hostpath` + 3 PVs hostPath (nouveau) |
| `statefulset.yaml` | StatefulSet + Services (storageClass + rétention corrigés) |
| `install.sh` | Script d'installation avec récupération cluster.id depuis hostPath |
| `uninstall.sh` | Désinstallation propre |
| `README.md` | Ce document |

---

## 10. Troubleshooting

### Pod en `Pending` après install

```bash
kubectl describe pod kafka-0 -n agent-system | grep -A5 Events
# → vérifier que les PVs sont Bound
kubectl get pv -l app=kafka
```

Si les PVs sont en `Released` (après un crash), les recréer :

```bash
kubectl apply -f deploy/kafka/pv-kafka.yaml
```

### `ErrImagePull` sur l'image Confluent

```bash
# Pré-charger l'image dans Docker local
docker pull confluentinc/cp-kafka:7.7.0
# Puis supprimer le pod bloqué pour qu'il redémarre avec l'image en cache
kubectl delete pod kafka-0 -n agent-system
```

### Cluster ID mismatch au redémarrage

Le script `install.sh` lit automatiquement le `cluster.id` depuis
`/Users/younes/data/kafka/kafka-N/meta.properties` — prioritaire sur le ConfigMap.
En cas de doute :

```bash
grep cluster.id /Users/younes/data/kafka/kafka-0/meta.properties
```
