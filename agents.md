# agents.md — Guide technique IA-INSIGHT

Document de référence pour tout développeur ou agent IA travaillant sur ce projet.
Couvre l'architecture, les décisions de conception, les workflows métier, les patterns
implémentés et les pièges connus.

> **Règle absolue pour tout agent IA** : avant de valider une livraison, exécuter
> la procédure de déploiement et tester le comportement attendu. Ne jamais marquer
> une tâche comme terminée sans avoir redémarré le backend et vérifié le résultat.

---

## 0. Procédure de déploiement et test (OBLIGATOIRE avant toute livraison)

Cette procédure s'applique à **chaque modification** du code Java ou du ConfigMap.
Un agent IA doit l'exécuter lui-même et vérifier le résultat avant de répondre
"c'est livré".

### 0.1 Déterminer ce qui a changé

| Ce qui a été modifié | Actions requises |
|---|---|
| Code Java (n'importe quel module) | Étapes 2 → 3 → 4 → 5 → 6 |
| ConfigMap uniquement (`ai-core/deploy/k8s/configMap.yaml`) | Étapes 1 → 5 → 6 |
| Les deux | Toutes les étapes dans l'ordre |

> **Note** : `ai-core` et `invoice-service` sont les deux pods déployés. Les modules `intent-agent`, `reasoning-agent`, `reassign-agent`, `audit-agent` sont des modules Maven du monorepo — pas des pods. Le build Maven depuis la racine (`mvn clean package`) compile tous les modules.

### 0.2 Procédure complète

```bash
# ── Étape 1 : supprimer l'ancien ConfigMap (si modifié)
kubectl delete -f ai-core/deploy/k8s/configMap.yaml -n multi-agent

# ── Étape 2 : supprimer le déploiement courant (si code Java modifié)
kubectl delete -f ai-core/deploy/k8s/deployment.yaml -n multi-agent

# ── Étape 3 : build Maven complet depuis la racine
mvn clean package -DskipTests

# ── Étape 4 : build et push de l'image Docker ai-core
docker build -t dokeryelmariki/ai-core:latest ai-core
docker push dokeryelmariki/ai-core:latest

# ── Étape 5 : appliquer le ConfigMap (si modifié)
kubectl apply -f ai-core/deploy/k8s/configMap.yaml -n multi-agent

# ── Étape 6 : appliquer le déploiement et attendre
kubectl apply -f ai-core/deploy/k8s/deployment.yaml -n multi-agent
kubectl get pods -n multi-agent -w   # attendre STATUS = Running
kubectl port-forward -n multi-agent svc/ai-core 8081:8081
```

### 0.3 Vérifier que le pod est prêt avant le port-forward

```bash
# Attendre que le pod soit Ready (1/1 Running)
kubectl rollout status deployment/ai-core -n multi-agent

# Puis seulement lancer le port-forward
kubectl port-forward -n multi-agent svc/ai-core 8081:8081
```

### 0.4 Tests minimaux après démarrage

Après le port-forward, exécuter au minimum :

```bash
# Health check
curl -s http://localhost:8081/actuator/health | grep UP

# Vérifier la classe CRA dans Weaviate (schéma synchronisé)
curl -s http://localhost:8080/v1/schema/CRA | grep '"class"'

# Test de l'endpoint modifié (exemple : CRA report)
curl -s "http://localhost:8081/cra/report?start=2026-05&end=2026-05&company=IA-INSIGHT"

# Test factures (exemple : liste pour juin 2026)
curl -s "http://localhost:8081/invoices/report?start=2026-06&end=2026-06&company=IA-INSIGHT"
```

Si un test échoue, **ne pas livrer**. Diagnostiquer, corriger, relancer depuis l'étape
correspondante.

### 0.5 Logs en cas d'erreur

```bash
# Logs du pod ai-core en temps réel
kubectl logs -f deployment/ai-core -n multi-agent

# Logs des 100 dernières lignes
kubectl logs deployment/ai-core -n multi-agent --tail=100
```

---

---

## 1. Vue d'ensemble

Plateforme d'automatisation administrative pour des consultants IT (société **IA-INSIGHT**,
configurable via `AI_CORE_COMPANY_NAME`).

| Fonctionnalité | Description |
|---|---|
| Notes de frais | Saisie texte libre / OCR photo/PDF, extraction LLM, frais km mensuel automatisé |
| CRA | Compte Rendu d'Activité mensuel : saisie consultant, soumission, validation/refus admin |
| Factures | Génération PDF/Excel depuis CRA validé ou texte libre |
| Rapports | Export PDF/Excel par plage de dates, filtré par consultant |
| Notifications | SSE temps réel : l'admin reçoit un événement à chaque soumission CRA ou création note de frais |

Deux frontends **Vanilla JS** indépendants, même backend, même design :
- `frontend/` — console admin (port 3000)
- `frontend-consultant/` — espace consultant (port 3001)

---

## 2. Stack technique

| Couche | Technologie |
|---|---|
| LLM | OpenAI GPT-4o / GPT-4o-mini — SDK Java `com.openai:openai-java:4.8.0` |
| Vector DB | Weaviate 5.5 — classes : `Expense`, `Invoice`, `DocumentChunk`, `CRA` |
| Messaging | Apache Kafka KRaft — namespace K8s `agent-system` |
| Backend | Spring Boot 3.3.4, Java 21, Maven — port **8081** |
| OCR | Tesseract + poppler (`pdftoppm`) |
| Frontend | Vanilla JS, `Space Grotesk`, pas de framework |
| Infra | Kubernetes — namespace `multi-agent` (ai-core), `weaviate`, `agent-system` |

---

## 3. Architecture

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
      Keycloak :8080  ← JWT jwk-set-uri
```

**Pods déployés** : `ai-core` (expenses, CRA, reasoning, notifications SSE) et `invoice-service` (factures PDF/Excel). `intent-agent`, `reasoning-agent`, `reassign-agent`, `audit-agent` sont des **modules Maven du monorepo** — ils ne sont pas des pods K8s.

**Kong routing** : `/invoices/*` → invoice-service:8083 | tout le reste → ai-core:8081

### Structure monorepo Maven

```
multi-agent-openai-kafka/
  shared/          ← contrats API (IntentRequest, IntentResult, ReasoningResult,
  │                   AssignmentResult, ExpenseItem) — dépendance légère
  ai-core/         ← service déployé (dépend de shared)
  intent-agent/    ← module Maven non déployé (dépend de shared)
  reasoning-agent/ ← module Maven non déployé (dépend de shared)
  reassign-agent/  ← module Maven non déployé (dépend de shared)
  audit-agent/     ← module Maven non déployé
```

---

## 4. Tous les endpoints (état courant)

### Notes de frais — `/expenses` et `/reasoning`

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/reasoning/analyze` | Pipeline complet : intent → RAG → extraction → indexation. Body : `{"text":"...","consultantEmail":"..."}` |
| POST | `/receipts/upload` | OCR + extraction depuis fichier. Multipart : `file`, `paymentMode`, `consultantEmail` |
| GET | `/expenses/report` | JSON `?start=YYYY-MM-DD&end=YYYY-MM-DD[&consultantEmail=]` |
| GET | `/expenses/report/excel` | Export Excel `?start&end[&consultantEmail=]` |
| GET | `/expenses/report/pdf` | Export PDF `?start&end[&consultantEmail=]` |
| GET | `/expenses/report/pdf/month` | Export PDF mensuel `?month=YYYY-MM[&consultantEmail=]` |
| DELETE | `/expenses/delete` | Suppression par date |
| POST | `/expenses/approve` | `{"weaviateId":"...","note":"..."}` → `approvalStatus = APPROVED` |
| POST | `/expenses/refuse` | `{"weaviateId":"...","note":"..."}` → `approvalStatus = REFUSED` |

### Factures — `/invoices`

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/invoices/generate` | Crée et indexe une facture depuis `SimpleInvoiceRequest` — **upsert par invoiceName** |
| POST | `/invoices/generate/from-text` | Extraction LLM depuis texte libre puis génération |
| POST | `/invoices/generate/pdf/from-text` | Idem → retourne PDF binaire |
| POST | `/invoices/generate/excel/from-text` | Idem → retourne Excel binaire |
| POST | `/invoices/pdf` | **Lookup** : re-télécharge le PDF d'une facture déjà indexée dans Weaviate |
| POST | `/invoices/excel` | **Lookup** : idem pour Excel |
| GET | `/invoices/report` | `?start=YYYY-MM&end=YYYY-MM&company=...&consultantEmail=...` |
| POST | `/invoices/delete` | Body : `InvoiceLookupRequest { billingMonth, sellerCompanyName, invoiceName }` |

> `/invoices/pdf` et `/invoices/excel` lèvent HTTP 500 (`IllegalStateException`) si la facture
> n'a jamais été générée via `/invoices/generate`. Ce sont des endpoints de **re-téléchargement**,
> pas de génération.

**Convention de nommage** :
```
billingMonth = 2026-05
→ invoiceDate = 2026-07-01  (billingMonth + 2 mois)
→ invoiceName = F-202607-01  (YYYYMM de invoiceDate + séquence 01)
```

### CRA — `/cra`

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/cra/save` | Upsert — calcule `totalDays` à partir des entries. Statut par défaut : `BROUILLON` |
| POST | `/cra/submit` | `BROUILLON` / `REFUSE` → `SOUMIS` + push SSE `CRA_SUBMITTED` vers admin |
| POST | `/cra/validate` | `SOUMIS` → `VALIDE`. Query param : `?validatedBy=Nom` |
| POST | `/cra/refuse` | `SOUMIS` → `REFUSE`. Query param : `?reason=Motif`. Efface `submittedAt` |
| POST | `/cra/recall` | `SOUMIS` → `BROUILLON`. Le consultant retire sa soumission avant que l'admin agisse |
| POST | `/cra/reopen` | `VALIDE` / `REFUSE` → `SOUMIS`. L'admin annule sa décision pour re-traitement |
| GET | `/cra/report` | `?start=YYYY-MM&end=YYYY-MM[&consultant=Nom][&company=]` |
| GET | `/cra/absences` | Fusion km + CRA ABSENT. `?month=YYYY-MM&company=&consultant=Nom` |
| POST | `/cra/delete` | Body : `{"id":"<weaviate-uuid>"}` |

### Notifications SSE — `/admin/notifications` et `/consultant/notifications`

**Pattern SSE avec token** : `EventSource` ne supporte pas les headers HTTP personnalisés.
Les endpoints `/stream` sont en `permitAll`. Le JWT est passé en query param `?token=<jwt>`.

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/admin/notifications/stream` | SSE persistante admin. `?token=<jwt>`. Events : `init` + `notification` |
| GET | `/admin/notifications` | `?all=false` (non lus) ou `?all=true` |
| POST | `/admin/notifications/{id}/read` | Marque une notification comme lue |
| POST | `/admin/notifications/read-all` | Marque toutes les notifications comme lues |
| GET | `/consultant/notifications/stream` | SSE persistante consultant. `?consultant=Nom&token=<jwt>` |
| GET | `/consultant/notifications` | `?consultant=Nom` — non lus |
| POST | `/consultant/notifications/{id}/read` | `?consultant=Nom` |
| POST | `/consultant/notifications/read-all` | `?consultant=Nom` |

**Events consultant** : `CRA_VALIDATED` (à la validation admin) et `CRA_REFUSED` (avec le motif).

### Paramètres — `/settings`

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/settings/seller` | `?company=IA-INSIGHT` → `SellerProfile` (IBAN, BIC, adresse…) |
| POST | `/settings/seller` | Upsert `SellerProfile` dans Weaviate |

### Consultants — `/consultants`

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/consultants/profiles` | Liste tous les profils consultants indexés dans Weaviate |
| POST | `/consultants/profiles` | Upsert un profil consultant |
| DELETE | `/consultants/profiles/{email}` | Supprime un profil consultant |

---

## 5. Workflow CRA (machine d'état)

```
         ┌─────────────────────────────┐
         │                             │
         ▼                             │
   [BROUILLON] ──/cra/submit──► [SOUMIS] ──/cra/validate──► [VALIDE]
         ▲                       │    ▲                          │
         │                       │    └──────/cra/reopen─────────┘
         └──/cra/refuse◄─────────┘    └──────/cra/reopen──► [SOUMIS]
         └──/cra/recall (consultant annule avant décision admin)
```

**Règles** :

| État | Consultant | Admin |
|---|---|---|
| BROUILLON | Éditable — peut soumettre | Visible en lecture |
| SOUMIS | Lecture seule — peut rappeler (`/cra/recall`) | Peut valider ou refuser |
| VALIDE | Lecture seule | Peut annuler (`/cra/reopen` → SOUMIS) |
| REFUSE | Affiche badge rouge + motif — grille ré-éditable | Peut annuler (`/cra/reopen` → SOUMIS) |

**Effets de bord à `/cra/submit`** :
- Notification SSE `CRA_SUBMITTED` poussée à tous les emitters admin connectés.

**Effets de bord à `/cra/refuse` côté admin frontend** :
- Appel automatique de `deleteInvoiceForCra()` : interroge `/invoices/report` pour ce
  consultant+mois et supprime la facture associée si elle existe (silencieux si aucune).

---

## 6. Modèle CraRequest — 12 champs (record Java immuable)

```json
{
  "id":           "<weaviate-uuid | null>",
  "consultant":   "Alice Martin",
  "company":      "IA-INSIGHT",
  "clientCompany":"INFOGENE DIGITAL",
  "billingMonth": "2026-05",
  "entries":      [{ "date": "2026-05-02", "value": 1.0, "type": "TRAVAIL" }],
  "totalDays":    21.0,
  "status":       "BROUILLON",
  "submittedAt":  null,
  "validatedAt":  null,
  "validatedBy":  null,
  "refusedReason":null
}
```

> **Critique** : tout `new CraRequest(...)` doit avoir exactement **12 arguments**.
> `refusedReason` est le 12ème. Un oubli provoque une erreur de compilation silencieuse
> si le record est reconstruit par position.

### Types d'entrée `CraDayEntry`

| `type` | `value` | Signification |
|---|---|---|
| `TRAVAIL` | 1.0 | Journée complète |
| `TRAVAIL` | 0.5 | Demi-journée |
| `ABSENT` | 0.0 | Absent (congé, maladie…) |
| `FERIE` | 0.0 | Jour férié |
| `WEEKEND` | 0.0 | Samedi ou dimanche |

### Calcul de `totalDays`

`CraService.save()` recalcule `totalDays` à partir de `entries` à chaque upsert.
Ne jamais faire confiance à la valeur envoyée par le client.

---

## 7. Absences — fusion de deux sources

`GET /cra/absences?month=YYYY-MM&company=...&consultant=Nom` retourne la liste fusionnée de :
1. `absencePeriodsJson` stocké dans les dépenses km (classe `Expense` Weaviate)
2. Jours `type=ABSENT` du CRA sauvegardé pour ce consultant/mois

**Règle demi-journée** : une entrée `TRAVAIL 0.5` compte comme **0.5j d'absence** dans le
calendrier visuel, mais le frais km du jour **n'est pas exclu** (le consultant est venu).

**Paramètre obligatoire** : toujours passer `consultant: user.name` à `/cra/absences`.
Sans ce paramètre, les absences issues du CRA sauvegardé ne sont pas incluses.

---

## 8. Frais km mensuel

Déclenchés quand le LLM met `"monthly": true` dans le JSON extrait.

- **Java ne fait aucune détection par regex ou nom de mois** — c'est entièrement piloté
  par le prompt (`configMap.yaml`).
- `RAGService.expandKmMonthly()` génère les lignes journalières en excluant :
  weekends + jours fériés français fixes + `absencePeriods`.
- Jours fériés codés dans `RAGService.frenchFixedHolidays()` :
  1/1, 1/5, 8/5, 14/7, 15/8, 1/11, 11/11, 25/12
- `copyExpense()` propage **tous les champs**, dont `consultantEmail`.
  Si un nouveau champ est ajouté à `ExpenseItem`, l'ajouter dans `copyExpense()`.

### Règle `paymentMode`

| Signal | Mode |
|---|---|
| "compte business", "carte business", "carte société", "payé par l'entreprise" | `Business` |
| Mention société seule ("pour IA-INSIGHT") | `Personnel` |
| Aucune indication | `Personnel` par défaut |
| `location` / `domiciliation` | `Personnel` (sauf signal Business explicite) |

---

## 9. Weaviate — patterns et pièges

### Upsert

Weaviate ne supporte pas le vrai upsert sur UUID aléatoire. Le pattern utilisé :

```java
// 1. Chercher l'objet existant par un champ unique
// 2. Supprimer si trouvé
// 3. Recréer avec les nouvelles données
```

Implémenté dans :
- `WeaviateService.indexCra()` — upsert par `cra.id()`
- `SimpleInvoiceService.generate()` — upsert par `invoiceName + sellerCompanyName`
  (suppression avant réindexation pour éviter les doublons)

### Schema sync au démarrage

`WeaviateService.synchronizeSchema()` est appelé au démarrage. Il :
- Crée les classes manquantes (`Expense`, `Invoice`, `CRA`, `SellerProfile`, `ConsultantProfile`)
- Ajoute les propriétés manquantes sur les classes existantes via `ensureXxxProperties()`

> Ne jamais supposer qu'une propriété Weaviate est présente si elle a été ajoutée
> après le déploiement initial. Toujours passer par `ensureXxxProperties()`.

### Backward compatibility `consultantEmail` sur Invoice

Les factures indexées avant l'ajout du champ `consultantEmail` ont ce champ vide.
`findInvoicesByPeriod` les inclut quand même (filtre permissif si email stocké est vide)
pour ne pas casser la liste admin. Ce comportement est intentionnel.

### Serialisation des entries CRA

Les `CraDayEntry[]` sont sérialisés en JSON string dans `entriesJson` (pas en objet imbriqué
Weaviate). Côté JS : toujours `JSON.parse(item.entriesJson || '[]')` dans un `try/catch`.

### `totalDays` — fallback côté backend

`findCrasByPeriod` recalcule `totalDays` depuis les entries si la valeur stockée est ≤ 0
(données créées avant l'ajout du calcul automatique).

---

## 10. Notifications SSE — pattern et pièges

### `NotificationService` (admin)

Store **in-memory** (`CopyOnWriteArrayList`). Perdu au redémarrage.
Pour de la persistence, brancher sur Weaviate ou une DB.

### `ConsultantNotificationService` (consultant)

Store in-memory keyed par nom de consultant (`Map<String, List<SseEmitter>>`).
Déclenché par `CraService.validate()` (`CRA_VALIDATED`) et `CraService.refuse()` (`CRA_REFUSED`).
Le `CraRequest` record n'a pas de champ email — les notifications sont keyed par `consultant` (nom).

### Broken pipe

Quand un navigateur ferme la connexion SSE, la prochaine écriture lève
`java.io.IOException: Broken pipe`. Spring log cette exception au niveau ERROR avant
que le `catch` de `push()` ne la récupère.

**Ce n'est pas un bug applicatif.** La gestion est correcte :
```java
// Dans NotificationService.push()
List<SseEmitter> dead = new ArrayList<>();
for (SseEmitter e : emitters) {
    try { e.send(...); }
    catch (Exception ex) { dead.add(e); }   // emitter mort collecté
}
emitters.removeAll(dead);                   // nettoyage
```

Le log Spring est silencé dans `application.yml` :
```yaml
logging.level:
  "[org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler]": WARN
  "[org.apache.catalina.connector.ClientAbortException]": WARN
```

### Reconnexion frontend

Le frontend admin reconnecte automatiquement l'`EventSource` toutes les 5 secondes
si la connexion est perdue.

---

## 11. Invoice generation — pattern upsert

### Problème historique (corrigé)

Cliquer deux fois sur "Générer la facture" créait deux objets Weaviate distincts
(`F-202607-01` et `F-202607-02`) car `resolveInvoiceName()` incrémentait la séquence
en voyant la première facture déjà existante.

### Fix

`SimpleInvoiceService.generate()` supprime toute facture existante portant le même
`invoiceName + sellerCompanyName` **avant** de ré-indexer :

```java
if (!isBlank(normalized.invoiceName()) && !isBlank(normalized.sellerCompanyName())) {
    weaviateService.deleteInvoices(new InvoiceLookupRequest(
        normalized.billingMonth(), normalized.sellerCompanyName(), normalized.invoiceName()
    ));
}
weaviateService.indexSimpleInvoice(null, normalized, ...);
```

`deleteInvoices` est idempotent (retourne 0 si rien à supprimer).

---

## 12. CORS

**Ne jamais** configurer CORS via un bean `CorsConfigurationSource` seul sans Spring Security
— il est inactif dans Spring MVC pur.

Toujours passer par `WebMvcConfigurer.addCorsMappings()` dans `WebCorsConfig.java`.

```java
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");
    }
}
```

---

## 13. Frontend — patterns et conventions

### Design tokens partagés (les deux `style.css`)

```css
--bg:      #0d1017;
--card:    #111725;
--accent:  #2ce5a7;   /* vert menthe — actions primaires, liens */
--accent-2:#7ad7ff;   /* bleu ciel — badges secondaires */
--text:    #ecf1ff;
--muted:   #a8b3c6;
--border:  #1f2a3f;
--font:    'Space Grotesk', system-ui;
```

### Authentification — Keycloak OIDC

Les deux frontends utilisent Keycloak réel (plus de mock). PKCE S256, `onLoad: 'login-required'`.
- `authHeaders()` → `{ Authorization: 'Bearer <token>' }` — injecté sur **tous** les fetch
- `getSession()` → `{ email, name, role }` depuis `tokenParsed.realm_access.roles`
- Token rafraîchi toutes les 60s (`updateToken(30)`)

**Comptes Keycloak** :
| Email | Mot de passe | Rôle |
|---|---|---|
| `admin@ia-insight.fr` | `admin123` | admin |
| `manager@ia-insight.fr` | `admin123` | manager |
| `alice.martin@ia-insight.fr` | `demo123` | consultant |
| `bob.dupont@ia-insight.fr` | `demo123` | consultant |
| `charlie.bernard@freelance.com` | `demo123` | consultant |

### config.js — source de vérité unique

Aucun champ de saisie dans l'UI. Toutes les URLs viennent de `APP_CONFIG` (config.js) :
```js
function base() { return (APP_CONFIG.apiBase || 'http://localhost:8081').replace(/\/$/, ''); }
function invoiceBase() { return (APP_CONFIG.invoiceBase || 'http://localhost:8083').replace(/\/$/, ''); }
```

### Notifications SSE frontend

**Admin** : `EventSource('/admin/notifications/stream?token=<jwt>')` — reconnexion auto 5s.
**Consultant** : `EventSource('/consultant/notifications/stream?consultant=Nom&token=<jwt>')`.
Le token est récupéré depuis `_keycloak.token` au moment de la connexion SSE.

### Navigation admin

Fil d'ariane `← Consultants / Nom du consultant` — le bouton "Consultants" (`#cons-back-btn`)
est stylé comme un lien accent (pas un bouton bordé) pour indiquer une navigation, pas une action.

### Absences auto-chargées (frontend consultant)

À l'entrée dans l'onglet "Notes de frais", les absences sont auto-chargées depuis
`/cra/absences`. Ne jamais supprimer ce listener — le frais km mensuel en dépend.

### Utilitaires JS partagés

```js
base()                  // URL ai-core depuis APP_CONFIG
invoiceBase()           // URL invoice-service depuis APP_CONFIG
authHeaders(extra)      // { Authorization: 'Bearer ...', ...extra }
escapeHtml(s)           // encode HTML pour affichage sûr
showToast(msg,type)     // toast bas-droite, type : 'ok' | 'err' | ''
avatarColor(name)       // couleur avatar stable par hash du nom
initials(name)          // 2 initiales
setStatus(el,msg,type)  // zone de statut inline
```

---

## 14. SonarLint — règles actives sur ce projet

Ces patterns ont été corrigés au moins une fois — les éviter proactivement.

| Règle | Problème | Fix |
|---|---|---|
| S2189 | `while (cur <= end) { cur.setDate(+1) }` apparaît comme boucle infinie | `for (let ms = start.getTime(); ms <= end.getTime(); ms += 86400000)` |
| S2486 | `catch {}` vide | `catch (_) { /* raison */ }` avec commentaire |
| S3358 | Ternaire imbriqué | Extraire en `if/else` |
| S3776 | Complexité cognitive > 15 | Extraire des fonctions helper |
| S6582 | `a && a.b` | `a?.b` |
| S2871 | `arr.sort()` sans comparateur | `sort((a,b) => a < b ? -1 : a > b ? 1 : 0)` |
| S7766 | `n < 0 ? 0 : n` | `Math.max(0, n)` |
| S1192 (Java) | Littéral de chaîne dupliqué > 3 fois | Extraire en constante |

### Contraste CSS minimal

| Couleur texte | Fond | Remplacement si insuffisant |
|---|---|---|
| `#a78bfa` | `rgba(167,139,250,.13)` | `#c4b0ff` |
| `#ff8a8a` / `#ff5252` | rouge semi-transparent sombre | `#ffadad` |
| `#fbbf24` (badge SOUMIS) | `rgba(251,191,36,.12)` | `#fde68a` |

---

## 15. Variables d'environnement requises

```bash
# ai-core ET invoice-service
OPENAI_API_KEY=sk-...                        # obligatoire
WEAVIATE_HOST=localhost:8080                  # format host:port, sans scheme
WEAVIATE_SCHEME=http                          # défaut : http
AI_CORE_COMPANY_NAME=IA-INSIGHT               # défaut
KEYCLOAK_JWK_SET_URI=http://localhost:8090/realms/ia-insight/protocol/openid-connect/certs
# En K8s : http://keycloak.keycloak.svc.cluster.local:8080/realms/ia-insight/protocol/openid-connect/certs

# ai-core uniquement — tous les AI_CORE_PROMPT_* (voir ai-core/deploy/k8s/configMap.yaml)
AI_CORE_PROMPT_CLASSIFIER_SYSTEM=...
AI_CORE_PROMPT_SINGLE_EXPENSE=...
AI_CORE_PROMPT_OCR_SINGLE_EXPENSE=...
AI_CORE_PROMPT_REWRITE_SYSTEM=...
AI_CORE_PROMPT_INVOICE=...
AI_CORE_PROMPT_KM_7CV=...
AI_CORE_PROMPT_EXPENSE_LIST=...
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# invoice-service uniquement
AI_CORE_INVOICES_STORAGE_PATH=/tmp/invoices
```

Après modification du ConfigMap K8s :
```bash
kubectl apply -f ai-core/deploy/k8s/configMap.yaml -n multi-agent
kubectl rollout restart deployment/ai-core -n multi-agent
```

**Important** : utiliser `jwk-set-uri` (pas `issuer-uri`) pour éviter le mismatch URL interne/externe K8s.
L'`iss` claim du JWT contient l'URL vue par le browser (ex: `localhost:8090`), mais le pod validerait
contre l'URL cluster DNS si on utilisait `issuer-uri`.

---

## 16. Commandes essentielles

```bash
# Backend (depuis la racine du monorepo)
export OPENAI_API_KEY=sk-... && export WEAVIATE_HOST=localhost:8080
mvn clean package -DskipTests          # compile tous les modules
java -jar ai-core/target/ai-core-*.jar # port 8081

# Ou en mode dev
cd ai-core && mvn spring-boot:run

# Frontends
cd frontend && python -m http.server 3000           # admin  → http://localhost:3000
cd frontend-consultant && python -m http.server 3001 # consultant → http://localhost:3001

# Weaviate (K8s port-forward)
kubectl port-forward svc/weaviate 8080:8080 -n weaviate
curl http://localhost:8080/v1/.well-known/ready

# OCR (prérequis locaux macOS)
brew install tesseract poppler tesseract-lang
```

---

## 17. Fichiers clés

```
ai-core/src/main/java/io/multiagent/core/

  expense/
    controller/
      ExpenseController.java          # GET /expenses/report + exports PDF/Excel
      ExpenseApprovalController.java  # POST /expenses/approve|refuse
      ReceiptController.java          # POST /receipts/upload (OCR)
    service/
      RAGService.java                 # Pipeline principal : extraction + indexation + expandKmMonthly()
      OcrService.java                 # Tesseract → texte brut
      OcrExtractionService.java       # LLM extraction depuis texte OCR
      DuplicateDetectorService.java   # Détection doublons binaire + texte
      ExpensePdfService.java          # Export PDF notes de frais
      ExpenseExcelService.java        # Export Excel notes de frais
      ExpenseReportService.java       # Agrégation rapport
      ExpenseIdGenerator.java         # ID incrémental par mois
      MonthlyLocationScheduler.java   # Scheduler 1er du mois → appel ReasoningService
      ReceiptUploadService.java       # Orchestration OCR → extraction → indexation
      ReceiptStorageService.java      # Stockage fichier sur disque
    repository/
      ExpenseWeaviateRepository.java  # CRUD Expense dans Weaviate

  invoice/
    controller/
      SimpleInvoiceController.java    # /invoices/* — génération, lookup, suppression
    service/
      SimpleInvoiceService.java       # Génération PDF/Excel — upsert avant indexation
      DeleteInvoiceService.java       # Suppression par InvoiceLookupRequest
    repository/
      InvoiceWeaviateRepository.java  # CRUD Invoice dans Weaviate

  cra/
    controller/
      CraController.java              # /cra/* — save/submit/validate/refuse/recall/reopen/absences
    service/
      CraService.java                 # Machine d'état CRA + push SSE à chaque submit
    repository/
      CraWeaviateRepository.java      # CRUD CRA dans Weaviate

  notification/
    controller/
      NotificationController.java              # GET /admin/notifications/stream (SSE) + REST
      ConsultantNotificationController.java    # GET /consultant/notifications/stream + REST
    service/
      NotificationService.java                 # Hub SSE admin in-memory
      ConsultantNotificationService.java       # Hub SSE consultant in-memory (keyed par nom)

  settings/
    controller/
      SettingsController.java         # GET|POST /settings/seller
      ConsultantController.java       # GET|POST|DELETE /consultants/profiles
    repository/
      SettingsWeaviateRepository.java # SellerProfile + ConsultantProfile Weaviate

  reasoning/
    controller/
      ReasoningController.java        # POST /reasoning/analyze (point d'entrée LLM)
    service/
      ReasoningService.java           # Switch intent → route vers le bon domaine
      IntentClassifierService.java    # LLM intent detection (GPT-4o-mini)
      QueryRewriteService.java        # Réécriture requête pour RAG
      SemanticSearchService.java      # Recherche vectorielle dans Weaviate
      ReRankService.java              # Re-ranking des résultats RAG

  weaviate/
    WeaviateService.java              # Façade (~668 lignes) + synchronizeSchema() au démarrage
    WeaviateUtils.java                # Helpers static partagés (parse, format, toFloatArray…)
    WeaviateResponseParser.java       # Parsing réponses GraphQL Weaviate

  model/
    CraRequest.java                   # Record 12 champs — refusedReason est le 12ème
    CraDayEntry.java                  # Record { date, value, type }
    ExpenseItem.java                  # Modèle dépense (approvalStatus, AbsencePeriod…)
    ExpenseReportResponse.java        # inclut consultantEmail en en-tête PDF/Excel
    Notification.java                 # SSE notification (id, type, message, read, refId)
    SimpleInvoiceRequest.java         # Record 21 champs — consultantEmail est le 21ème
    InvoiceLookupRequest.java         # { billingMonth, sellerCompanyName, invoiceName }
    SellerProfile.java                # Profil émetteur (IBAN, BIC, adresse, latePaymentClause)
    IntentRequest.java / IntentResult.java  # Pipeline LLM interne
    ReasoningResult.java              # Résultat reasoning (status, expenses, metadata)

  config/
    WeaviateConfig.java               # @Bean WeaviateClient (scheme + host)
    SecurityConfig.java               # JWT OAuth2 — jwk-set-uri, règles par rôle (pas d'AdminKeyInterceptor)
    WebCorsConfig.java                # CORS via WebMvcConfigurer uniquement (pas de CorsConfigurationSource)
    AsyncConfig.java                  # ThreadPoolTaskExecutor pour @Async (upload OCR)

ai-core/src/main/resources/
  application.yml                     # Config Spring Boot — logging SSE silencé

ai-core/deploy/k8s/
  configMap.yaml                      # TOUS LES PROMPTS LLM + KEYCLOAK_JWK_SET_URI
  deployment.yaml                     # Pod ai-core K8s

deploy/keycloak/
  realm-configmap.yaml                # Realm ia-insight (users, clients, rôles, loginTheme)
  theme-configmap.yaml                # Thème CSS dark ia-insight (theme.properties + login.css)
  deployment.yaml                     # Keycloak pod + initContainer + volumes thème

frontend/                             # Console admin
  index.html / app.js / style.css

frontend-consultant/                  # Espace consultant
  index.html / app.js / style.css
```

---

## 18. Bugs historiques résolus (et leçons)

| Bug | Cause | Fix appliqué |
|---|---|---|
| CRA refuse → statut BROUILLON au lieu de REFUSE | `CraService.refuse()` mettait `"BROUILLON"` | Changé en `"REFUSE"` |
| Admin voit 0j pour un CRA, consultant voit 22j | `findCrasByPeriod` ne retournait pas `entriesJson` + `totalDays` stocké à 0 | Ajout `entriesJson` dans la réponse + fallback calcul depuis entries |
| Double facture à chaque clic "Générer" | `resolveInvoiceName()` incrémentait la séquence à chaque appel | Suppression de la facture existante avant réindexation dans `generate()` |
| Facture consultant absente de l'onglet Factures | `indexSimpleInvoice` ne stockait pas `consultantEmail`, `findInvoicesByPeriod` filtrait tout | Stockage `consultantEmail` dans Weaviate + filtre backward-compat |
| CORS bloqué malgré bean configuré | Utilisation de `CorsConfigurationSource` sans Spring Security | Migration vers `WebMvcConfigurer.addCorsMappings()` |
| Broken pipe loggé en ERROR à chaque reconnexion SSE | Spring logue avant que le `catch` n'efface l'emitter mort | Silencé via `logging.level` dans `application.yml` |
| `HealthController` crash au démarrage | Package déclaré `io.multiagent.gateway.web` au lieu de `io.multiagent.core.web` | Correction du package |

---

## 19. Prochaines évolutions connues

| Évolution | Statut | Notes |
|---|---|---|
| Auth Keycloak | ✅ Terminé | OIDC PKCE intégré dans les deux frontends, JWT Bearer sur tous les fetch, `jwk-set-uri` backend |
| Notifications consultant | ✅ Terminé | SSE `ConsultantNotificationService`, events CRA_VALIDATED/CRA_REFUSED |
| Thème Keycloak | ✅ Terminé | CSS dark `ia-insight` via ConfigMap + subPath volumes |
| Persistence notifications SSE | Backlog | Store in-memory → Weaviate ou DB |
| PVC pour les fichiers PDF/Excel | Prod | `/data/invoices` et `/data/receipts` nécessitent un PersistentVolume en K8s |
| Multi-société | Backlog | `AI_CORE_COMPANY_NAME` est déjà configurable |
