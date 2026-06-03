// ============================================================
// Configuration externalisée — à surcharger via ConfigMap K8s
//
// DEV local (sans Kong) : les deux frontends appellent les services directement.
// PRODUCTION (avec Kong) : un seul point d'entrée, Kong route selon le path :
//   - /invoices/*  → invoice-service
//   - /expenses/*, /cra/*, /reasoning/*, /admin/*, /settings/* → ai-core
//   Dans ce cas, apiBase = invoiceBase = URL publique de Kong.
//
// IMPORTANT : keycloakUrl doit correspondre exactement à l'issuer Keycloak
// déclaré dans KEYCLOAK_ISSUER_URI des ConfigMaps K8s (claim "iss" du JWT).
// ============================================================
globalThis.APP_CONFIG = {
  // --- DEV local ---
  keycloakUrl:      'http://localhost:8090',
  keycloakRealm:    'ia-insight',
  keycloakClientId: 'frontend-admin',
  apiBase:          'http://localhost:8081',   // ai-core direct
  invoiceBase:      'http://localhost:8083',   // invoice-service direct

  // --- PRODUCTION (Kong) — décommenter et adapter ---
  // keycloakUrl:      'https://auth.ia-insight.fr',
  // keycloakRealm:    'ia-insight',
  // keycloakClientId: 'frontend-admin',
  // apiBase:          'https://api.ia-insight.fr',
  // invoiceBase:      'https://api.ia-insight.fr',  // même URL, Kong route /invoices/*
};
