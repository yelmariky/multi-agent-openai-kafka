# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Detailed reference docs are in `docs/` — read them on demand, not systematically.

## Ce que fait ce projet

Plateforme SaaS **multi-tenant** d'automatisation d'entreprise pour ESN/cabinets de conseil IT :
- Notes de frais (texte libre, OCR) avec workflow approbation admin (PENDING/APPROVED/REFUSED)
- Factures PDF/Excel Consulting IT — microservice dedie `invoice-service`
- CRA mensuel (BROUILLON -> SOUMIS -> VALIDE/REFUSE) avec notifications SSE temps reel
- Pipelines RAG pgvector + OpenAI GPT-4o
- Multi-tenant : realm-per-tenant Keycloak, slug-based URLs, provisioning automatise

## Stack technique

| Couche | Technologie |
|---|---|
| LLM | OpenAI GPT-4o / GPT-4o-mini (`com.openai:openai-java:4.8.0`) |
| DB + Vector | PostgreSQL 16 + pgvector (colonnes `vector(3072)`, index HNSW cosine) |
| ORM | Spring Data JPA + Flyway (ai-core owns schema, invoice-service `flyway.enabled=false`) |
| Embedding | `text-embedding-3-large` (3072 dims) |
| Messaging | Apache Kafka KRaft (namespace `agent-system`) |
| Backend | Spring Boot 3.3.4, Java 21, Maven multi-module |
| OCR | Tesseract + poppler (`pdftoppm`) |
| Auth | Keycloak 24.0.2 — realm-per-tenant + `JwtIssuerAuthenticationManagerResolver` |
| Gateway | Kong DB-less (namespace `kong`) |
| Frontends | Vanilla JS — `frontend/` :3000, `frontend-consultant/` :3001, `frontend-platform/` :3002 |
| Infra | Kubernetes, Docker |

## Architecture microservices

```
[frontend/ :3000]  [frontend-consultant/ :3001]  [frontend-platform/ :3002]
        |                      |                          |
        +--------- Kong :8000 -+--------------------------+
                       |
            +----------+----------+
            v                     v
      ai-core :8081       invoice-service :8083
            |                     |
       PostgreSQL <---------------+   (shared DB, ai-core owns Flyway)
       + pgvector
       Kafka :9092
       OpenAI API
            |
      Keycloak :8080  <- JwtIssuerAuthenticationManagerResolver (multi-realm)
```

**Kong routing** : `/invoices/*` -> invoice-service | tout le reste -> ai-core

## Multi-tenant

- **Realm-per-tenant** : chaque organisation = un realm Keycloak dedie
- URL slug : `localhost:3000/{slug}/` -> realm Keycloak `{slug}`
- `TenantFilter` (OncePerRequestFilter) : JWT `iss` -> realm -> Organization -> `TenantContext` (ThreadLocal)
- Toutes les requetes JPA filtrent par `tenant_id`
- `TenantFilter` existe dans ai-core ET invoice-service
- Console plateforme (`frontend-platform/` :3002) : realm `platform`, role `platform_admin`

## Commandes essentielles

```bash
# Dev local — port-forwards
kubectl port-forward svc/postgres -n multi-agent 5432:5432
kubectl port-forward svc/keycloak -n keycloak 8090:8080
kubectl port-forward svc/kafka 9092:9092 -n agent-system

# Backend (env vars: OPENAI_API_KEY, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD)
cd ai-core && mvn spring-boot:run          # :8081, Flyway cree le schema
cd invoice-service && mvn spring-boot:run  # :8083, flyway disabled

# Frontends
cd frontend && python -m http.server 3000
cd frontend-consultant && python -m http.server 3001
cd frontend-platform && python -m http.server 3002

# Build & Docker
mvn clean package -DskipTests
cd ai-core && docker build -t dokeryelmariki/ai-core:latest . && docker push dokeryelmariki/ai-core:latest
cd invoice-service && docker build -t dokeryelmariki/invoice-service:latest . && docker push dokeryelmariki/invoice-service:latest

# K8s deploy
kubectl apply -f ai-core/deploy/k8s/
kubectl apply -f invoice-service/deploy/k8s/
kubectl rollout restart deployment/ai-core -n multi-agent
kubectl rollout restart deployment/invoice-service -n multi-agent
```

## Conventions critiques

- **CORS** : ne jamais utiliser `CorsConfigurationSource` bean seul — toujours `WebMvcConfigurer.addCorsMappings()` dans `WebCorsConfig.java`
- **Prompts LLM** : tous dans `ai-core/deploy/k8s/configMap.yaml` (variables `AI_CORE_PROMPT_*`). Apres modif : `kubectl apply` + restart pod
- **copyExpense()** : propager `consultantEmail` (et tout nouveau champ) a chaque ligne lors de `expandKmMonthly()`
- **Flyway** : ai-core owns schema (`flyway.enabled=true`), invoice-service `flyway.enabled=false`

## Reference docs (lire a la demande)

| Fichier | Contenu |
|---|---|
| `docs/DEPLOYMENT.md` | Procedure de deploiement K8s obligatoire avant livraison |
| `docs/ENDPOINTS.md` | Tous les endpoints ai-core + invoice-service |
| `docs/SECURITY.md` | Regles SecurityConfig, roles, SSE token, config.js frontend |
| `docs/FILES.md` | Arborescence complete des fichiers cles |
| `docs/BUSINESS-RULES.md` | paymentMode, frais km, workflow CRA, modele CraRequest, absences |
| `docs/INFRA.md` | Namespaces K8s, structure monorepo, variables d'env, OCR prerequis |
| `docs/DATABASE.md` | Tables, pgvector, Flyway, SSE store, WeaviateService facade |
