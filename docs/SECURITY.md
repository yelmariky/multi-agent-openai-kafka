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
