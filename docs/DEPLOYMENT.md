# Procedure de deploiement et test (OBLIGATOIRE avant toute livraison)

## Determiner ce qui a change

| Ce qui a ete modifie | Actions requises |
|---|---|
| Code Java (n'importe quel module) | Etapes 2 -> 3 -> 4 -> 5 -> 6 |
| ConfigMap uniquement (`ai-core/deploy/k8s/configMap.yaml`) | Etapes 1 -> 5 -> 6 |
| Les deux | Toutes les etapes dans l'ordre |

## Procedure complete

```bash
# -- Etape 1 : supprimer l'ancien ConfigMap (si modifie)
kubectl delete -f ai-core/deploy/k8s/configMap.yaml -n multi-agent

# -- Etape 2 : supprimer le deploiement courant (si code Java modifie)
kubectl delete -f ai-core/deploy/k8s/deployment.yaml -n multi-agent

# -- Etape 3 : build Maven complet depuis la racine
mvn clean package -DskipTests

# -- Etape 4 : build et push de l'image Docker ai-core
docker build -t dokeryelmariki/ai-core:latest ai-core
docker push dokeryelmariki/ai-core:latest

# -- Etape 5 : appliquer le ConfigMap (si modifie)
kubectl apply -f ai-core/deploy/k8s/configMap.yaml -n multi-agent

# -- Etape 6 : appliquer le deploiement et attendre
kubectl apply -f ai-core/deploy/k8s/deployment.yaml -n multi-agent
kubectl rollout status deployment/ai-core -n multi-agent
kubectl port-forward -n multi-agent svc/ai-core 8081:8081
```

## Tests minimaux apres demarrage

```bash
# Health check
curl -s http://localhost:8081/actuator/health | grep UP

# Verifier que PostgreSQL est accessible (Flyway a cree le schema)
curl -s http://localhost:8081/actuator/health | grep db

# Test d'un endpoint (exemple : organization/me avec un JWT valide)
curl -s -H "Authorization: Bearer <token>" http://localhost:8081/organization/me
```

## Logs en cas d'erreur

```bash
kubectl logs -f deployment/ai-core -n multi-agent
kubectl logs deployment/ai-core -n multi-agent --tail=100
```
