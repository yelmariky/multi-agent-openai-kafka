// ============================================================
// Configuration externalisée — à surcharger via ConfigMap K8s
//
// Console plateforme : gestion des tenants (organisations).
// Realm Keycloak dédié : "platform" avec rôle "platform_admin".
//
// IMPORTANT : keycloakUrl doit correspondre exactement à l'issuer Keycloak
// déclaré dans KEYCLOAK_ISSUER_URI des ConfigMaps K8s (claim "iss" du JWT).
// ============================================================
globalThis.APP_CONFIG = {
  // --- DEV local ---
  keycloakUrl:      'http://localhost:8090',
  keycloakRealm:    'platform',
  keycloakClientId: 'frontend-platform',
  apiBase:          'http://localhost:8081',   // ai-service direct

  // --- PRODUCTION (Kong) — décommenter et adapter ---
  // keycloakUrl:      'https://auth.ia-insight.fr',
  // keycloakRealm:    'platform',
  // keycloakClientId: 'frontend-platform',
  // apiBase:          'https://api.ia-insight.fr',
};
