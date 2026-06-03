// ============================================================
// Configuration externalisée — à surcharger via ConfigMap K8s
//
// DEV local (sans Kong) : le frontend appelle ai-core directement.
// PRODUCTION (avec Kong) : un seul point d'entrée Kong.
//   apiBase = URL publique de Kong (routes /expenses/*, /cra/*, etc. → ai-core)
//
// IMPORTANT : keycloakUrl doit correspondre exactement à l'issuer Keycloak
// déclaré dans KEYCLOAK_ISSUER_URI des ConfigMaps K8s (claim "iss" du JWT).
// ============================================================
globalThis.APP_CONFIG = {
  // --- DEV local ---
  keycloakUrl:      'http://localhost:8090',
  keycloakRealm:    'ia-insight',
  keycloakClientId: 'frontend-consultant',
  apiBase:          'http://localhost:8081',   // ai-core direct

  // --- PRODUCTION (Kong) — décommenter et adapter ---
  // keycloakUrl:      'https://auth.ia-insight.fr',
  // keycloakRealm:    'ia-insight',
  // keycloakClientId: 'frontend-consultant',
  // apiBase:          'https://api.ia-insight.fr',
};
