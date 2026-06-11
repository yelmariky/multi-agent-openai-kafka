// ============================================================
// Configuration externalisée — à surcharger via ConfigMap K8s
//
// MULTI-TENANT : le slug est extrait de l'URL (/{slug}/...).
// Il correspond au realm Keycloak et identifie le tenant.
//
// DEV local (sans Kong) : le frontend appelle ai-core directement.
// PRODUCTION (avec Kong) : un seul point d'entrée Kong.
//   apiBase = URL publique de Kong (routes /expenses/*, /cra/*, etc. → ai-core)
//
// IMPORTANT : keycloakUrl doit correspondre exactement à l'issuer Keycloak
// déclaré dans KEYCLOAK_ISSUER_URI des ConfigMaps K8s (claim "iss" du JWT).
// ============================================================

// Derive tenant slug from URL path: /{slug}/...
// Redirects to tenant-select.html if no slug in URL.
const _rawSlug = window.location.pathname.split('/').filter(Boolean)[0];
if (!_rawSlug) { window.location.replace('/tenant-select.html'); }
const _slug = _rawSlug || 'ia-insight';

globalThis.APP_CONFIG = {
  // Tenant slug — drives Keycloak realm + backend tenant resolution
  slug:             _slug,

  // --- DEV local ---
  keycloakUrl:      'http://localhost:8090',
  keycloakRealm:    _slug,                    // realm = slug
  keycloakClientId: 'frontend-consultant',
  apiBase:          'http://localhost:8081',   // ai-core direct

  // --- PRODUCTION (Kong) — décommenter et adapter ---
  // keycloakUrl:      'https://auth.ia-insight.fr',
  // keycloakRealm:    _slug,
  // keycloakClientId: 'frontend-consultant',
  // apiBase:          'https://api.ia-insight.fr',
};
