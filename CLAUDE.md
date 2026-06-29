# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Detailed reference docs are in `docs/` — read them on demand, not systematically.

## Ce que fait ce projet

Plateforme SaaS **multi-tenant** d'automatisation d'entreprise pour ESN/cabinets de conseil IT :
- Notes de frais (texte libre, OCR) avec workflow approbation admin (PENDING/APPROVED/REFUSED)
- Factures PDF/Excel Consulting IT — microservice dedie `invoice-service`
- CRA mensuel (BROUILLON -> SOUMIS -> VALIDE/REFUSE) avec notifications SSE temps reel
- Pipelines RAG pgvector + Groq LLM (fallback OpenAI)
- Multi-tenant : realm-per-tenant Keycloak, slug-based URLs, provisioning automatise

## Stack technique

| Couche | Technologie |
|---|---|
| LLM principal | Groq — `llama-3.1-8b-instant` (intent/rewrite) + `llama-4-scout-17b` (RAG) — gratuit |
| LLM fallback | OpenAI `gpt-4.1-mini` — si Groq indisponible (`com.openai:openai-java:4.8.0`) |
| DB + Vector | PostgreSQL 16 + pgvector (colonnes `vector(2000)`, index HNSW cosine) |
| ORM | Spring Data JPA + Flyway (ai-service owns schema, invoice-service `flyway.enabled=false`) |
| Embedding | OpenAI `text-embedding-3-large` (toujours OpenAI — Groq ne supporte pas les embeddings) |
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
      +---------+------+--------+----------+
      v         v               v          v
ai-service  expense-service  activity-service  invoice-service
  :8081        :8082              :8085           :8083
      |            |                 |               |
      +------------+-----------------+---------------+
                          |
                     PostgreSQL (shared DB, ai-service owns Flyway)
                     + pgvector / Kafka / Groq / OpenAI
                          |
                    Keycloak :8080
notification-service :8084  ← consomme platform.notifications (Kafka)
```

**Kong routing** :
- `/invoices/*` → invoice-service :8083
- `/expenses/*`, `/receipts/*`, `/reasoning/*` → expense-service :8082
- `/cra/*`, `/leaves/*` → activity-service :8085
- `/notifications/*` → notification-service :8084
- tout le reste (settings, org, dashboard) → ai-service :8081

**LLM fallback** : `LLMAIClient.chatJson()` essaie Groq → si KO bascule sur OpenAI `gpt-4.1-mini`. Voir `docs/PRODUCTION-KEYS.md`.

**Sécurité LLM** : `PromptGuardFilter` (`@Order(1)`) intercepte tous les POST/PUT — taille / rate-limit / injection / sanitize — avant tout controller. Voir skill `/security`.

## Multi-tenant

- **Realm-per-tenant** : chaque organisation = un realm Keycloak dedie
- URL slug : `localhost:3000/{slug}/` -> realm Keycloak `{slug}`
- `TenantFilter` (OncePerRequestFilter) : JWT `iss` -> realm -> Organization -> `TenantContext` (ThreadLocal)
- Toutes les requetes JPA filtrent par `tenant_id`
- `TenantFilter` existe dans ai-service ET invoice-service
- Console plateforme (`frontend-platform/` :3002) : realm `platform`, role `platform_admin`

## Commandes essentielles

```bash
# Dev local — port-forwards
kubectl port-forward svc/postgres -n multi-agent 5432:5432
kubectl port-forward svc/keycloak -n keycloak 8090:8080
kubectl port-forward svc/kafka 9092:9092 -n agent-system

# Backend (env vars: OPENAI_API_KEY, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD)
cd ai-service       && mvn spring-boot:run   # :8081, Flyway owns schema
cd expense-service  && mvn spring-boot:run   # :8082, flyway disabled
cd activity-service && mvn spring-boot:run   # :8085, flyway disabled
cd invoice-service  && mvn spring-boot:run   # :8083, flyway disabled

# Frontends
cd frontend && python server.py
cd frontend-consultant && python server.py
cd frontend-platform && python server.py

# Build & Docker
mvn clean package -DskipTests
cd ai-service       && docker build -t dokeryelmariki/ai-service:latest       . && docker push dokeryelmariki/ai-service:latest
cd expense-service  && docker build -t dokeryelmariki/expense-service:latest  . && docker push dokeryelmariki/expense-service:latest
cd activity-service && docker build -t dokeryelmariki/activity-service:latest . && docker push dokeryelmariki/activity-service:latest
cd invoice-service  && docker build -t dokeryelmariki/invoice-service:latest  . && docker push dokeryelmariki/invoice-service:latest

# K8s deploy
kubectl apply -f ai-service/deploy/k8s/       && kubectl rollout restart deployment/ai-service       -n multi-agent
kubectl apply -f expense-service/deploy/k8s/  && kubectl rollout restart deployment/expense-service  -n multi-agent
kubectl apply -f activity-service/deploy/k8s/ && kubectl rollout restart deployment/activity-service -n multi-agent
kubectl apply -f invoice-service/deploy/k8s/  && kubectl rollout restart deployment/invoice-service  -n multi-agent
```

## Conventions critiques

- **CORS** : toujours `WebMvcConfigurer.addCorsMappings()` dans `WebCorsConfig.java` — jamais `CorsConfigurationSource` bean seul
- **Prompts LLM** : tous dans `ai-service/deploy/k8s/configMap.yaml` (`AI_CORE_PROMPT_*`). Apres modif : `kubectl apply` + restart pod
- **copyExpense()** : propager `consultantEmail` (et tout nouveau champ) dans `expandKmMonthly()`
- **Flyway** : ai-service owns schema (`flyway.enabled=true`), invoice-service `flyway.enabled=false`
- **PromptGuard** : tout nouvel endpoint texte libre doit être couvert — ne jamais ajouter à `EXCLUDED_PREFIXES` sauf binaire/multipart

## Reference docs (lire a la demande)

| Fichier | Contenu |
|---|---|
| `docs/DEPLOYMENT.md` | Procedure de deploiement K8s obligatoire avant livraison |
| `docs/ENDPOINTS.md` | Tous les endpoints ai-service + invoice-service |
| `docs/SECURITY.md` | Keycloak RBAC, PromptGuard LLM, SSE token, config.js frontend |
| `docs/PRODUCTION-KEYS.md` | Creation clés API prod — permissions OpenAI + Groq + fallback |
| `docs/PITCH.md` | Présentation projet clients/investisseurs (10 slides PowerPoint) |
| `docs/FILES.md` | Arborescence complete des fichiers cles |
| `docs/BUSINESS-RULES.md` | paymentMode, frais km, workflow CRA, modele CraRequest, absences |
| `docs/INFRA.md` | Namespaces K8s, structure monorepo, variables d'env, OCR prerequis |
| `docs/DATABASE.md` | Tables, pgvector, Flyway, SSE store, WeaviateService facade |
| `docs/GUIDELINES-KARPATHY.md` | Guidelines complètes Claude Code (7 règles détaillées) |

## Comportement attendu de Claude (Karpathy Guidelines)

1. **Réfléchir avant de coder** — énoncer les hypothèses, exposer les ambiguïtés, demander si flou
2. **Simplicité d'abord** — minimum de code qui résout le problème, rien de spéculatif ni de configurable sans raison
3. **Changements chirurgicaux** — ne toucher que ce qui est demandé, correspondre au style existant, supprimer uniquement les orphelins créés par ses propres changements
4. **Exécution orientée objectifs** — définir des critères de succès vérifiables avant d'implémenter

## Skills projet (commandes slash)

| Commande | Domaine |
|---|---|
| `/expense` | Notes de frais — texte libre, OCR, frais km, workflow PENDING/APPROVED/REFUSED, paymentMode, absences |
| `/invoices` | Factures PDF/Excel — invoice-service :8083, suivi paiement (mark-sent, mark-paid) |
| `/cra` | CRA mensuel — workflow BROUILLON→SOUMIS→VALIDE/REFUSE, SSE, retour client |
| `/conge` | Congés CP/RTT — demandes, approbation, solde par consultant, tables leave_request/leave_balance |
| `/rag` | Pipelines RAG — pgvector, embeddings, Groq/OpenAI |
| `/tenant` | Multi-tenant — Keycloak, TenantFilter, realm-per-tenant |
| `/deploy` | Déploiement — Docker build, K8s apply, port-forwards |
| `/security` | Sécurité LLM — PromptGuard, injection, rate limit, clés API prod |
