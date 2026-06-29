// ============================================================
// Configuration externalisee — a surcharger via ConfigMap K8s
//
// MULTI-TENANT : le slug est extrait de l'URL (/{slug}/...).
// Il correspond au realm Keycloak et identifie le tenant.
//
// DEV local (sans Kong) : les deux frontends appellent les services directement.
// PRODUCTION (avec Kong) : un seul point d'entree, Kong route selon le path :
//   - /invoices/*  -> invoice-service
//   - /expenses/*, /cra/*, /reasoning/*, /admin/*, /settings/* -> ai-service
//   Dans ce cas, apiBase = invoiceBase = URL publique de Kong.
//
// IMPORTANT : keycloakUrl doit correspondre exactement a l'issuer Keycloak
// declare dans KEYCLOAK_ISSUER_URI des ConfigMaps K8s (claim "iss" du JWT).
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
  keycloakClientId: 'frontend-admin',
  apiBase:          'http://localhost:8081',   // ai-service direct
  invoiceBase:      'http://localhost:8083',   // invoice-service direct
  notificationBase: 'http://localhost:8084',   // notification-service direct

  // --- PRODUCTION (Kong) — decommenter et adapter ---
  // keycloakUrl:      'https://auth.ia-insight.fr',
  // keycloakRealm:    _slug,
  // keycloakClientId: 'frontend-admin',
  // apiBase:          'https://api.ia-insight.fr',
  // invoiceBase:      'https://api.ia-insight.fr',
  // notificationBase: 'https://api.ia-insight.fr',  // Kong route /notifications/*
};
