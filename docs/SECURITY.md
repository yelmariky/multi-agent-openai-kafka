# Securite — Keycloak JWT + Protection LLM

## Couche de protection LLM (PromptGuard)

**Filtre global** `PromptGuardFilter` (`@Order(1)`) — s'exécute avant tout controller.

Protège contre : prompt injection, token flooding, rate limit abus, jailbreak, chars cachés.

Fichiers : `ai-core/…/security/PromptGuard.java` | `PromptGuardFilter.java` | `CachedBodyHttpServletRequest.java`

Config :
```yaml
ai-core.guard.max-chars: 4000              # env: AI_CORE_GUARD_MAX_CHARS
ai-core.guard.max-requests-per-minute: 20  # env: AI_CORE_GUARD_MAX_RPM
```

Réponse de rejet : **HTTP 429** + `{"error":"..."}`. Logs : `🛡️ [GUARD FILTER]`.

Voir skill `/security` pour le détail complet.

## Clés API — Permissions minimales production

Voir `docs/PRODUCTION-KEYS.md` pour la procédure complète.

**OpenAI** (`platform.openai.com/api-keys`) — Restricted :
- Chat completions `/v1/chat/completions` → **Request** (fallback LLM si Groq down)
- Embeddings `/v1/embeddings` → **Request** (usage principal)
- Tout le reste → **None**
- Alerte billing : activer à **$1** restant

**Groq** (`console.groq.com`) — clé standard :
- Modèles : `llama-3.1-8b-instant` + `meta-llama/llama-4-scout-17b-16e-instruct`
- Surveiller quota : console.groq.com → Usage
- En cas de dépassement : fallback automatique vers OpenAI (log `"bascule sur OpenAI fallback"`)

---

# Securite — Keycloak JWT

**Multi-realm** : `JwtIssuerAuthenticationManagerResolver` — valide JWT de n'importe quel realm matchant `*/realms/*`. Cache ConcurrentHashMap d'`AuthenticationManager` par issuer.

Roles extraits de `realm_access.roles` -> `ROLE_<role>`.

## Regles ai-core

- `/actuator/health/**`, `/actuator/prometheus` -> public
- `/admin/notifications/stream`, `/consultant/notifications/stream` -> public (EventSource ne peut pas envoyer Authorization)
- `POST /expenses/approve|refuse` -> admin | manager
- `POST /cra/validate|refuse|reopen` -> admin | manager
- `/admin/notifications/**` -> admin | manager
- `/settings/seller-profile`, `/settings/seller` -> admin
- `/platform/**` -> platform_admin
- reste -> authenticated

## Regles invoice-service

`POST /invoices/delete*` -> admin | reste -> authenticated

## SSE et token

Endpoints `/stream` en `permitAll`. JWT passe via `?token=<jwt>`.

## Frontends — config.js

Chaque frontend a un `config.js` charge avant `app.js`. **Source de verite unique** pour toutes les URLs.
Slug extrait de l'URL : `/{slug}/`.

```js
const _slug = window.location.pathname.split('/').filter(Boolean)[0] || 'ia-insight';
globalThis.APP_CONFIG = {
  slug: _slug,
  keycloakRealm: _slug,  // realm = slug
  keycloakClientId: 'frontend-admin',
  apiBase: 'http://localhost:8081',
  invoiceBase: 'http://localhost:8083',
};
```

`app.js` injecte dynamiquement dans le callback `onload` du script Keycloak (evite "Keycloak is not defined").
