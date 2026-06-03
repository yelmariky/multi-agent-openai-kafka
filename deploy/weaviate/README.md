# Weaviate sur Kubernetes (namespace `weaviate`)

Weaviate est déployé via Helm avec un **volume persistant hostPath** pointant vers
`/Users/younes/data/new-weaviate` sur la machine locale (Docker Desktop / minikube).
Les données survivent aux crashs et redémarrages du pod.

---

## Architecture de stockage

```
Pod weaviate-0
  └── PVC weaviate-data-weaviate-0  (StorageClass: weaviate-hostpath)
        └── PV weaviate-pv
              └── hostPath → /Users/younes/data/new-weaviate  (nœud host)
```

- **StorageClass `weaviate-hostpath`** : provisionnement statique (`no-provisioner`), `reclaimPolicy: Retain`
- **PV `weaviate-pv`** : `DirectoryOrCreate`, le répertoire est créé s'il n'existe pas
- **Politique Retain** : les données restent sur le disque même si le PVC est supprimé

---

## 1. Pré-requis

```bash
# Créer le répertoire de données sur le host (une seule fois)
mkdir -p /Users/younes/data/weaviate-data
```

Le dépôt Helm Weaviate doit être ajouté :

```bash
helm repo add weaviate https://weaviate.github.io/weaviate-helm
helm repo update
```

---

## 2. Installation complète (première fois)

```bash
# Namespace
kubectl create namespace weaviate

# StorageClass + PersistentVolume
kubectl apply -f deploy/weaviate/pv-weaviate.yaml

# Weaviate via Helm
helm upgrade --install weaviate weaviate/weaviate \
  -n weaviate \
  -f deploy/weaviate/values.yaml
```

Vérifier que le PVC est bien lié au PV :

```bash
kubectl get pv,pvc -n weaviate
# Le PV weaviate-pv doit passer en STATUS = Bound
```

---

## 3. Mise à jour / réinstallation

```bash
helm upgrade --install weaviate weaviate/weaviate \
  -n weaviate \
  -f deploy/weaviate/values.yaml
```

Le PV et les données dans `/Users/younes/data/new-weaviate` ne sont jamais touchés
par `helm upgrade`.

---

## 4. Désinstallation (données conservées)

```bash
# Supprime le chart mais PAS les données (reclaimPolicy: Retain)
helm uninstall weaviate -n weaviate

# Pour réinstaller plus tard et récupérer les données :
#   1. kubectl apply -f deploy/weaviate/pv-weaviate.yaml   (recrée le PV)
#   2. helm upgrade --install ...                           (recrée le PVC et le pod)
```

Pour **effacer définitivement** les données :

```bash
kubectl delete pv weaviate-pv
rm -rf /Users/younes/data/new-weaviate
```

---

## 5. Exposition locale & vérifications

```bash
# État du pod
kubectl get pods -n weaviate

# Port-forward vers localhost:8080
kubectl port-forward svc/weaviate 8080:8080 -n weaviate

# Vérification readiness
curl http://localhost:8080/v1/.well-known/ready
# Réponse attendue : {}

# Version
curl http://localhost:8080/v1/meta | jq .version
```

---

## 6. Requêtes rapides

**GraphQL — lister les dépenses :**

```bash
curl http://localhost:8080/v1/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{Get{Expense(limit:5){description amount date}}}"}'
```

**REST — créer un objet :**

```bash
curl -X POST http://localhost:8080/v1/objects \
  -H "Content-Type: application/json" \
  -d '{
    "class": "Expense",
    "properties": {
      "description": "Taxi Paris",
      "amount": 42,
      "date": "2024-03-10"
    }
  }'
```

**REST — supprimer toute une classe (reset) :**

```bash
curl -X DELETE http://localhost:8080/v1/schema/Expense
```

---

## 7. Utilisation par les microservices

Dans les autres namespaces (ex. `multi-agent`), Weaviate est accessible via le DNS interne :

```
http://weaviate.weaviate.svc.cluster.local:8080
```

Variables d'environnement à configurer dans les ConfigMaps :

```bash
WEAVIATE_HOST=weaviate.weaviate.svc.cluster.local:8080
WEAVIATE_SCHEME=http
```

Vérifier que la `NetworkPolicy` du namespace `multi-agent` autorise le flux sortant
vers `weaviate` sur le port `8080`.

---

## 8. Fichiers de ce répertoire

| Fichier | Rôle |
|---|---|
| `pv-weaviate.yaml` | StorageClass `weaviate-hostpath` + PV `weaviate-pv` (hostPath → `/Users/younes/data/new-weaviate`) |
| `values.yaml` | Valeurs Helm : persistence activée sur `weaviate-hostpath`, 20 Gi, 1 réplica |
| `README.md` | Ce document |
