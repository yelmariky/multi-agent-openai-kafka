# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Ce que fait ce projet

Plateforme d'automatisation d'entreprise **IA-INSIGHT** basée sur une architecture microservices IA :
- Notes de frais (texte libre, OCR) avec workflow approbation admin (PENDING/APPROVED/REFUSED)
- Factures PDF/Excel Consulting IT — microservice dédié `invoice-service`
- CRA mensuel (BROUILLON → SOUMIS → VALIDE/REFUSE) avec notifications SSE temps réel
- Pipelines RAG Weaviate 5.5 + OpenAI GPT-4o

Société par défaut : **IA-INSIGHT** (configurable via `AI_CORE_COMPANY_NAME`).

---

## Stack technique

| Couche | Technologie |
|---|---|
| LLM | OpenAI GPT-4o / GPT-4o-mini (`com.openai:openai-java:4.8.0`) |
| Vector DB | Weaviate 5.5 (classes : `Expense`, `Invoice`, `DocumentChunk`, `CRA`) |
| Messaging | Apache Kafka KRaft (namespace `agent-system`) |
| Backend | Spring Boot 3.3.4, Java 21, Maven multi-module |
| OCR | Tesseract + poppler (`pdftoppm`) |
| Auth | Keycloak 24.0.2 (namespace `keycloak`) |
| Gateway | Kong DB-less (namespace `kong`) |
| Frontend admin | Vanilla JS — `frontend/` — port 3000 |
| Frontend consultant | Vanilla JS — `frontend-consultant/` — port 3001 |
| Infra | Kubernetes (namespace `multi-agent`), Docker |

---

## Architecture microservices

```
[frontend/ :3000]     [frontend-consultant/ :3001]
        │                          │
        └──────── Kong :8000 ──────┘   ← API Gateway DB-less
                       │
            ┌──────────┴──────────┐
            ▼                     ▼
      ai-core :8081       invoice-service :8083
            │                     │
       Weaviate              Weaviate (Invoice)
       Kafka :9092           OpenAI API
       OpenAI API
            │
      Keycloak :8080  ← JWT jwk-set-uri (validation OAuth2)
```

**Kong routing** : `/invoices/*` → invoice-service | tout le reste → ai-core

---

## Commandes essentielles

### Dev local

```bash
# 1. Keycloak (port-forward depuis K8s)
kubectl port-forward svc/keycloak -n keycloak 8090:8080
# Console admin : http://localhost:8090  (admin / changeme)

# 2. Weaviate
kubectl port-forward svc/weaviate 8080:8080 -n weaviate

# 3. Kafka
kubectl port-forward svc/kafka 9092:9092 -n agent-system

# 4. ai-core
cd ai-core
export OPENAI_API_KEY=sk-...
export WEAVIATE_HOST=localhost:8080
export KEYCLOAK_JWK_SET_URI=http://localhost:8090/realms/ia-insight/protocol/openid-connect/certs
mvn spring-boot:run   # écoute :8081

# 5. invoice-service
cd invoice-service
export OPENAI_API_KEY=sk-...
export WEAVIATE_HOST=localhost:8080
export KEYCLOAK_JWK_SET_URI=http://localhost:8090/realms/ia-insight/protocol/openid-connect/certs
mvn spring-boot:run   # écoute :8083

# 6. Frontends
cd frontend && python -m http.server 3000
cd frontend-consultant && python -m http.server 3001
```

### Build & Docker

```bash
# Build Maven (depuis la racine)
mvn clean package -DskipTests
mvn package -DskipTests -pl invoice-service   # module seul

# Docker ai-core
cd ai-core
docker build -t dokeryelmariki/ai-core:latest .
docker push dokeryelmariki/ai-core:latest

# Docker invoice-service
cd invoice-service
docker build -t dokeryelmariki/invoice-service:latest .
docker push dokeryelmariki/invoice-service:latest
```

### K8s — Keycloak

```bash
# Installer (crée PV/PVC, secret, realm, thème, deployment, services)
cp deploy/keycloak/.env.example deploy/keycloak/.env
# éditer .env : KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD
./deploy/keycloak/install.sh

# Désinstaller
./deploy/keycloak/uninstall.sh   # soft (données conservées) ou hard (tout effacer)

# Appliquer changements realm
kubectl apply -f deploy/keycloak/realm-configmap.yaml
kubectl rollout restart deployment/keycloak -n keycloak

# Appliquer changements thème
kubectl apply -f deploy/keycloak/theme-configmap.yaml
kubectl rollout restart deployment/keycloak -n keycloak
```

### K8s — ai-core & invoice-service

```bash
kubectl apply -f ai-core/deploy/k8s/
kubectl apply -f invoice-service/deploy/k8s/
kubectl rollout restart deployment/ai-core -n multi-agent
kubectl rollout restart deployment/invoice-service -n multi-agent
```

---

## Sécurité — Keycloak JWT

### Realm `ia-insight`
- Clients publics (PKCE S256) : `frontend-admin`, `frontend-consultant`
- Clients bearerOnly : `ai-core-service`, `invoice-service`
- Rôles : `admin`, `manager`, `consultant`
- Thème login personnalisé : `ia-insight` (dark, chart graphique des deux consoles)

### Backend (ai-core & invoice-service)
Tous deux ont `SecurityConfig.java` avec `SessionCreationPolicy.STATELESS` + `oauth2ResourceServer`.
Le converter extrait les rôles depuis `realm_access.roles` → `ROLE_<role>`.

**Validation JWT** : `jwk-set-uri` (pas `issuer-uri`) — évite le mismatch URL interne/externe K8s
(le JWT émis contient l'URL browser, le pod validerait contre l'URL cluster DNS).

**Règles ai-core** :
- `/actuator/health/**`, `/actuator/prometheus` → public
- `/admin/notifications/stream`, `/consultant/notifications/stream` → public (EventSource ne peut pas envoyer Authorization)
- `POST /expenses/approve|refuse` → admin | manager
- `POST /cra/validate|refuse|reopen` → admin | manager
- `/admin/notifications/**` → admin | manager
- `/settings/seller-profile`, `/settings/seller` → admin
- reste → authenticated

**Règles invoice-service** :
- `POST /invoices/delete*` → admin
- reste → authenticated

**SSE et token** : les endpoints `/stream` sont en `permitAll`. Le frontend passe le JWT via
`?token=<jwt>` dans l'URL de l'`EventSource`. Le backend lit `@RequestParam("token")`.

### Frontends — config.js
Chaque frontend a un `config.js` chargé avant `app.js`. **Source de vérité unique** pour toutes les URLs.
Aucun champ de saisie dans l'UI — tout est dans `config.js`.

```js
// frontend/config.js
globalThis.APP_CONFIG = {
  keycloakUrl:      'http://localhost:8090',
  keycloakRealm:    'ia-insight',
  keycloakClientId: 'frontend-admin',
  apiBase:          'http://localhost:8081',   // dev direct
  invoiceBase:      'http://localhost:8083',   // dev direct
  // PROD (Kong) : apiBase = invoiceBase = 'https://api.ia-insight.fr'
};
```

`app.js` est injecté dynamiquement dans le callback `onload` du script Keycloak (évite "Keycloak is not defined").

---

## Endpoints

### ai-core (:8081)

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/reasoning/analyze` | Pipeline complet (intent → RAG → extraction). Body : `{"text":"...","consultantEmail":"..."}` |
| POST | `/receipts/upload` | Upload justificatif (multipart `file` + `paymentMode` + `consultantEmail`) |
| GET | `/expenses/report` | Rapport JSON `?start=YYYY-MM-DD&end=YYYY-MM-DD[&consultantEmail=]` |
| GET | `/expenses/report/pdf`, `/excel`, `/pdf/month` | Exports |
| DELETE | `/expenses/delete` | Suppression par date |
| POST | `/expenses/approve` / `/expenses/refuse` | Body `{"weaviateId":"...","note":"..."}` |
| POST | `/cra/save` | Upsert CRA. Statut défaut : BROUILLON |
| POST | `/cra/submit` | BROUILLON/REFUSE → SOUMIS + notification SSE admin |
| POST | `/cra/validate?validatedBy=X` | SOUMIS → VALIDE + notification SSE consultant |
| POST | `/cra/refuse?reason=X` | SOUMIS → BROUILLON + `refusedReason` + notification SSE consultant |
| POST | `/cra/reopen` | VALIDE/REFUSE → SOUMIS (admin annule sa décision) |
| GET | `/cra/report` | `?start=YYYY-MM&end=YYYY-MM[&consultant=][&company=]` |
| GET | `/cra/absences` | Fusion km + CRA ABSENT `?month=YYYY-MM&company=&consultant=` |
| GET | `/admin/notifications/stream` | SSE persistant — events `init` + `notification`. Token via `?token=` |
| GET | `/consultant/notifications/stream` | SSE consultant — `?consultant=Nom&token=<jwt>` |
| GET | `/consultant/notifications` | `?consultant=Nom` — non lus |
| POST | `/consultant/notifications/read-all` | `?consultant=Nom` |
| POST | `/consultant/notifications/{id}/read` | `?consultant=Nom` |

### invoice-service (:8083)

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/invoices/generate` | Crée facture depuis `SimpleInvoiceRequest` |
| POST | `/invoices/generate/pdf/from-text` | Texte libre → PDF |
| POST | `/invoices/generate/excel/from-text` | Texte libre → Excel |
| POST | `/invoices/pdf` / `/excel` | LOOKUP Weaviate (500 si jamais générée) |
| POST | `/invoices/delete` | Admin only |
| GET | `/invoices/report` | `?start=YYYY-MM&end=YYYY-MM&company=` |

Convention nommage : `billingMonth + 2 mois → invoiceDate → invoiceName = F-YYYYMM-01`

---

## Fichiers clés

```
ai-core/src/main/java/io/multiagent/core/
  expense/
    controller/  ExpenseController.java, ExpenseApprovalController.java, ReceiptController.java
    service/     RAGService.java (expandKmMonthly), ReasoningService.java, OcrService.java
    repository/  ExpenseWeaviateRepository.java
  invoice/
    client/      InvoiceClient.java  ← WebClient vers invoice-service:8083 (30s timeout)
  cra/
    controller/  CraController.java
    service/     CraService.java  ← push CRA_VALIDATED/CRA_REFUSED à ConsultantNotificationService
    repository/  CraWeaviateRepository.java
  notification/
    controller/  NotificationController.java       ← SSE admin
                 ConsultantNotificationController.java ← SSE consultant (/consultant/notifications/*)
    service/     NotificationService.java          ← hub admin in-memory
                 ConsultantNotificationService.java    ← hub consultant in-memory (keyed by name)
  config/
    SecurityConfig.java   ← JWT OAuth2, règles d'accès par rôle (pas d'AdminKeyInterceptor)
    WebCorsConfig.java    ← CORS via WebMvcConfigurer uniquement
  infrastructure/kafka/
    EventPublisher.java   ← best-effort, ne propage jamais d'exception
    KafkaTopics.java      ← constantes topics
  deploy/k8s/
    configMap.yaml        ← TOUS LES PROMPTS LLM (AI_CORE_PROMPT_*) + KEYCLOAK_JWK_SET_URI

invoice-service/src/main/java/io/multiagent/invoice/
  controller/  InvoiceController.java
  service/     InvoiceService.java, DeleteInvoiceService.java
  repository/  InvoiceWeaviateRepository.java
  schema/      InvoiceSchemaInitializer.java  ← ApplicationRunner, retry au démarrage
  config/      SecurityConfig.java, WebCorsConfig.java
  deploy/k8s/  configMap.yaml, deployment.yaml, service.yaml

deploy/keycloak/
  install.sh / uninstall.sh      ← scripts avec mode soft/hard
  realm-configmap.yaml           ← realm ia-insight complet (users, clients, rôles, loginTheme)
  theme-configmap.yaml           ← thème CSS dark ia-insight (theme.properties + login.css)
  deployment.yaml                ← KC_HEALTH_ENABLED=true, probes sur :8080, initContainer fix-permissions
  .env.example                   ← template credentials (ne pas committer .env)

frontend/
  config.js   ← URLs externalisées (source de vérité, aucun input dans l'UI)
  index.html  ← charge config.js → keycloak.js (onload) → app.js
  app.js

frontend-consultant/
  config.js / index.html / app.js / style.css
```

---

## Règles métier importantes

### paymentMode
- "compte business", "carte business", "carte société" = `Business` (PRIORITÉ ABSOLUE)
- Mentionner uniquement une société = `Personnel`
- Sans indication = `Personnel` par défaut

### Frais km mensuels
- Déclenchés par un nom de mois français sans jour explicite
- `RAGService.expandKmMonthly()` génère les lignes journalières (Java, pas le LLM)
- Exclut : weekends + jours fériés FR fixes + `absencePeriods`
- `copyExpense()` propage `consultantEmail` à chaque ligne — ne pas oublier si nouveau champ

### Jours fériés (codés dans `RAGService.frenchFixedHolidays()`)
1/1, 1/5, 8/5, 14/7, 15/8, 1/11, 11/11, 25/12

### CRA — absences et demi-journées
- `/cra/absences` fusionne : `absencePeriodsJson` (frais km) + jours `ABSENT` du CRA
- Demi-journées (`TRAVAIL 0.5`) = 0.5j d'absence dans le calendrier, mais NON exclues des frais km
- Absences auto-chargées à l'entrée dans l'onglet Notes de frais (frontend consultant)

---

## CORS
Ne jamais utiliser `CorsConfigurationSource` bean seul — inactif dans Spring MVC pur sans Spring Security active.
Toujours `WebMvcConfigurer.addCorsMappings()` dans `WebCorsConfig.java` (présent dans ai-core ET invoice-service).

---

## Prompts LLM
Tous dans `ai-core/deploy/k8s/configMap.yaml` (variables `AI_CORE_PROMPT_*`).
Après modification : `kubectl apply -f configMap.yaml -n multi-agent` + redémarrer le pod.
En local : exporter les variables dans le shell avant `mvn spring-boot:run`.

---

## Variables d'environnement requises

```bash
# ai-core ET invoice-service
OPENAI_API_KEY=sk-...
WEAVIATE_HOST=localhost:8080         # format host:port sans scheme
KEYCLOAK_JWK_SET_URI=http://localhost:8090/realms/ia-insight/protocol/openid-connect/certs
# En K8s : http://keycloak.keycloak.svc.cluster.local:8080/realms/ia-insight/protocol/openid-connect/certs

# ai-core uniquement
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
AI_CORE_COMPANY_NAME=IA-INSIGHT
# + tous les AI_CORE_PROMPT_* (voir configMap.yaml)

# invoice-service uniquement
AI_CORE_INVOICES_STORAGE_PATH=/tmp/invoices   # défaut /tmp/invoices en local
```

---

## Infra K8s

| Namespace | Contenu |
|---|---|
| `multi-agent` | ai-core, invoice-service |
| `keycloak` | Keycloak 24 + PV/PVC hostPath |
| `kong` | Kong DB-less gateway |
| `agent-system` | Kafka KRaft 3 nœuds |
| `weaviate` | Weaviate via Helm |

### Structure monorepo Maven

```
multi-agent-openai-kafka/
  ai-core/             ← pod K8s — port 8081
  invoice-service/     ← pod K8s — port 8083
  shared/              ← contrats API (IntentRequest, ReasoningResult…)
  intent-agent/        ← module Maven non déployé
  reasoning-agent/     ← module Maven non déployé
  reassign-agent/      ← module Maven non déployé
  audit-agent/         ← module Maven non déployé
  frontend/            ← console admin — port 3000
  frontend-consultant/ ← espace consultant — port 3001
  deploy/
    keycloak/          ← install.sh, uninstall.sh, realm-configmap.yaml, theme-configmap.yaml
    kafka/             ← KRaft 3 nœuds
    gateway/           ← Kong DB-less configmap
```

---

## OCR — prérequis locaux

```bash
# macOS
brew install tesseract poppler tesseract-lang

# Debian/Ubuntu
sudo apt-get install -y tesseract-ocr tesseract-ocr-fra poppler-utils
```
