// ============================================================
// KEYCLOAK CONFIG — lue depuis window.APP_CONFIG (config.js)
// ============================================================
const _cfg = globalThis.APP_CONFIG || {};
const DEFAULT_KEYCLOAK_URL = _cfg.keycloakUrl || 'http://localhost:30080';
const TENANT_SLUG = _cfg.slug || 'ia-insight';

function keycloakUrl() {
  return DEFAULT_KEYCLOAK_URL.replace(/\/$/, '');
}

let _keycloak = null;

// Tenant info loaded from backend after auth
let _tenantInfo = { name: TENANT_SLUG.toUpperCase(), slug: TENANT_SLUG };

/** Returns the current tenant company name. */
function tenantName() {
  return _tenantInfo.name || TENANT_SLUG.toUpperCase();
}

async function initKeycloak() {
  _keycloak = new Keycloak({
    url:      keycloakUrl(),
    realm:    _cfg.keycloakRealm    || TENANT_SLUG,
    clientId: _cfg.keycloakClientId || 'frontend-admin',
  });

  const authenticated = await _keycloak.init({
    onLoad: 'login-required',
    checkLoginIframe: false,
    pkceMethod: 'S256',
  });

  if (!authenticated) {
    _keycloak.login();
    return null;
  }

  // Rafraichit le token 30s avant expiration
  setInterval(() => {
    _keycloak.updateToken(30).catch(() => _keycloak.login());
  }, 60000);

  return _keycloak;
}

function getSession() {
  if (!_keycloak?.tokenParsed) return null;
  const p = _keycloak.tokenParsed;
  const roles = p.realm_access?.roles || [];
  return {
    email: p.email || p.preferred_username,
    name:  p.name  || p.preferred_username,
    role:  roles.includes('admin') ? 'admin' : roles.includes('manager') ? 'manager' : 'user',
  };
}

function clearSession() {
  _keycloak?.logout({ redirectUri: globalThis.location.origin + '/' });
}

/** Headers avec JWT Bearer pour tous les appels API. */
function authHeaders(extra = {}) {
  const token = _keycloak?.token;
  return {
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    ...extra,
  };
}

// ============================================================
// CONFIG API — lue depuis window.APP_CONFIG (config.js)
// ============================================================
const DEFAULT_BASE              = _cfg.apiBase          || 'http://localhost:8081';
const DEFAULT_INVOICE_BASE      = _cfg.invoiceBase      || 'http://localhost:8083';
const DEFAULT_NOTIFICATION_BASE = _cfg.notificationBase || 'http://localhost:8084';

function base() { return DEFAULT_BASE.replace(/\/$/, ''); }
function notifBase() { return DEFAULT_NOTIFICATION_BASE.replace(/\/$/, ''); }

// En local : invoice-service ecoute sur :8083.
// En prod  : Kong recoit tout sur le meme host et route /invoices/* vers invoice-service.
function invoiceBase() {
  return DEFAULT_INVOICE_BASE.replace(/\/$/, '');
}

function adminHeaders(extra = {}) {
  const token = _keycloak?.token;
  const h = { 'Content-Type': 'application/json', ...extra };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

// ============================================================
// UTILS
// ============================================================
function pad(n) { return String(n).padStart(2, '0'); }
function toMonthStr(y, m) { return `${y}-${pad(m + 1)}`; }

function setStatus(el, msg, type = '') {
  if (!el) return;
  el.textContent = msg;
  el.className   = 'status' + (type ? ` ${type}` : '');
}

/** Disable a button and show a loading label; returns a restore function. */
function setBtnLoading(btnId, label = 'Enregistrement…') {
  const btn = document.getElementById(btnId);
  if (!btn) return () => {};
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = label;
  return () => { btn.disabled = false; btn.textContent = orig; };
}

/** Returns a debounced version of fn that fires after ms milliseconds. */
function debounce(fn, ms) {
  let timer;
  return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
}

function escapeHtml(s) {
  return String(s ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

function getInitials(name) {
  if (!name) return '?';
  const parts = String(name).trim().split(/\s+/);
  return parts.length >= 2
    ? (parts[0][0] + parts[1][0]).toUpperCase()
    : String(name).slice(0, 2).toUpperCase();
}

function rowIconVariant(str) {
  const variants = ['', 'blue', 'purple'];
  let hash = 0;
  for (const c of (str || '')) hash = (hash * 31 + c.charCodeAt(0)) & 0xffffffff;
  return variants[Math.abs(hash) % 3];
}

function avatarColor(name) {
  const COLORS = ['#6366f1','#8b5cf6','#ec4899','#f59e0b','#10b981','#3b82f6','#14b8a6'];
  let h = 0;
  for (let i = 0; i < name.length; i++) h = name.charCodeAt(i) + ((h << 5) - h);
  return COLORS[Math.abs(h) % COLORS.length];
}

function initials(name) {
  const parts = (name || '').trim().split(/\s+/);
  return parts.length >= 2
    ? (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    : (name || '?').substring(0, 2).toUpperCase();
}

function showToast(msg, type = '') {
  const c = document.getElementById('toast-container');
  if (!c) return;
  const t = document.createElement('div');
  t.className = 'toast' + (type ? ` toast-${type}` : '');
  t.textContent = msg;
  c.appendChild(t);
  setTimeout(() => t.classList.add('toast-show'), 10);
  setTimeout(() => { t.classList.remove('toast-show'); setTimeout(() => t.remove(), 300); }, 3500);
}

// ============================================================
// TENANT INFO — loaded from backend after authentication
// ============================================================
async function loadTenantInfo() {
  try {
    const res = await fetch(`${base()}/organization/me`, { headers: authHeaders() });
    if (!res.ok) return;
    _tenantInfo = await res.json();
    // Update UI elements with tenant name
    const headerEl = document.getElementById('tenant-name');
    if (headerEl) headerEl.textContent = tenantName();
    document.title = `${tenantName()} — Admin`;
    // Pre-fill company inputs
    const companyInput = document.getElementById('cons-new-company');
    if (companyInput && !companyInput.value) companyInput.value = tenantName();
    const invCompanyInput = document.getElementById('cons-inv-company');
    if (invCompanyInput && !invCompanyInput.value) invCompanyInput.value = tenantName();
    // Persist in recent tenants for the selector page
    saveRecentTenant(TENANT_SLUG, tenantName());
  } catch { /* backend may not be reachable */ }
}

/** Saves the current tenant in localStorage for the tenant-select landing page. */
function saveRecentTenant(slug, name) {
  try {
    const KEY = 'recent_tenants';
    const existing = JSON.parse(localStorage.getItem(KEY) || '[]');
    const filtered = existing.filter(t => t.slug !== slug);
    localStorage.setItem(KEY, JSON.stringify([{ slug, name }, ...filtered].slice(0, 5)));
  } catch { /* localStorage unavailable */ }
}

// ============================================================
// BOOT — Keycloak puis app
// ============================================================
function startApp() {
  const user = getSession();
  if (!user) return; // ne devrait pas arriver : Keycloak force le login

  if (user.role !== 'admin' && user.role !== 'manager') {
    document.body.innerHTML = `
      <div style="min-height:100vh;display:flex;align-items:center;justify-content:center;
                  background:#0d1017;font-family:'Space Grotesk',system-ui">
        <div style="text-align:center;color:#f0f0f0;max-width:480px;padding:2rem">
          <div style="font-size:2.5rem;margin-bottom:1rem">🚫</div>
          <h2 style="color:#f87171;margin-bottom:.5rem">Accès refusé</h2>
          <p style="color:#8892a4;margin-bottom:1.5rem">
            Cette console est réservée aux administrateurs.<br>
            Votre compte <strong>${user.email}</strong> n'a pas les droits requis.
          </p>
          <button onclick="clearSession()" style="background:#2ce5a7;color:#0d1017;border:none;
            padding:.6rem 1.5rem;border-radius:6px;cursor:pointer;font-weight:600">
            Se déconnecter
          </button>
        </div>
      </div>`;
    return;
  }

  document.getElementById('admin-name').textContent  = user.name;
  document.getElementById('admin-email').textContent = user.email;
  const av = document.getElementById('admin-avatar');
  av.textContent = initials(user.name);
  av.style.background = avatarColor(user.name);

  document.getElementById('logout-btn').addEventListener('click', () => {
    clearConsultantsCache();
    clearSession();
  });

  initNotifications();
  initConsultants(user);
  initSettingsDrawer();
  initMainNavTabs();
  initProjects();
  initClients();

  // ESC closes any open modal
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (!document.getElementById('cons-edit-modal').classList.contains('hidden'))  { closeEditModal(); return; }
    if (!document.getElementById('assignment-modal').classList.contains('hidden')) { closeAssignmentModal(); return; }
    if (!document.getElementById('settings-drawer').classList.contains('hidden'))  { closeSettingsDrawer(); }
  });

  // Assignment modal wiring
  document.getElementById('cons-assign-add-btn').addEventListener('click', () => {
    if (currentConsultant) openAssignmentModal(currentConsultant, null);
  });
  document.getElementById('assignment-cancel').addEventListener('click', closeAssignmentModal);
  document.getElementById('assignment-modal-close').addEventListener('click', closeAssignmentModal);
  document.getElementById('assignment-overlay').addEventListener('click', closeAssignmentModal);
  document.getElementById('assignment-save').addEventListener('click', () => {
    if (currentConsultant) saveAssignment(currentConsultant);
  });

  document.getElementById('cons-vehicle-save').addEventListener('click', async () => {
    if (!currentConsultant) return;
    const vt  = document.getElementById('cons-vehicle-type').value;
    const fp  = parseInt(document.getElementById('cons-fiscal-power').value, 10) || 7;
    const km  = parseInt(document.getElementById('cons-km-annual').value, 10)    || 4999;
    const status = document.getElementById('cons-vehicle-status');
    currentConsultant.vehicleType  = vt;
    currentConsultant.fiscalPower  = fp;
    currentConsultant.kmAnnual     = km;
    const idx = allConsultants.findIndex(c => c.email === currentConsultant.email);
    if (idx >= 0) {
      allConsultants[idx].vehicleType = vt;
      allConsultants[idx].fiscalPower = fp;
      allConsultants[idx].kmAnnual    = km;
      saveConsultants(allConsultants);
    }
    try {
      await saveConsultantToBackend(currentConsultant);
      if (status) { status.textContent = '✓ Profil véhicule enregistré'; status.style.color = 'var(--accent)'; }
    } catch {
      if (status) { status.textContent = 'Erreur lors de l\'enregistrement'; status.style.color = '#f87171'; }
    }
    setTimeout(() => { if (status) status.textContent = ''; }, 3000);
  });

  loadSellerSettings();
  loadTenantInfo();
}

// app.js est injecte dynamiquement apres le chargement de keycloak.js —
// DOMContentLoaded a deja tire, on invoque directement.
function showOrgNotFound(slug) {
  document.body.innerHTML = `
    <style>
      @import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&display=swap');
      body { margin:0; font-family:'Space Grotesk',system-ui; }
    </style>
    <div style="
      min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px;
      background:
        radial-gradient(circle at 18% 18%, rgba(44,229,167,.13), transparent 34%),
        radial-gradient(circle at 82% 4%,  rgba(122,215,255,.11), transparent 30%),
        #0d1017;
    ">
      <div style="width:100%;max-width:480px;text-align:center;">
        <div style="
          width:60px;height:60px;border-radius:16px;margin:0 auto 24px;
          background:linear-gradient(135deg,rgba(239,68,68,.18),rgba(239,68,68,.08));
          border:1px solid rgba(239,68,68,.3);
          display:flex;align-items:center;justify-content:center;font-size:26px;
        ">🏢</div>
        <h1 style="font-size:22px;font-weight:700;color:#ecf1ff;margin:0 0 10px;">Organisation introuvable</h1>
        <p style="color:#a8b3c6;font-size:14px;margin:0 0 20px;line-height:1.6;">
          Aucune organisation ne correspond au slug<br>
          <code style="
            display:inline-block;margin-top:6px;padding:4px 12px;border-radius:8px;
            background:rgba(239,68,68,.12);border:1px solid rgba(239,68,68,.25);
            color:#fca5a5;font-size:13px;font-family:monospace;
          ">${slug}</code>
        </p>
        <p style="color:#64748b;font-size:13px;margin:0 0 32px;">
          Vérifiez l'orthographe ou contactez votre administrateur.
        </p>
        <a href="/tenant-select.html" style="
          display:inline-flex;align-items:center;gap:8px;
          background:#2ce5a7;color:#0d1017;font-weight:700;font-size:14px;
          border-radius:10px;padding:11px 24px;text-decoration:none;
        ">
          ← Choisir une autre organisation
        </a>
      </div>
    </div>`;
}

(async () => {
  try {
    const realmUrl = `${_cfg.keycloakUrl}/realms/${encodeURIComponent(TENANT_SLUG)}`;
    const realmCheck = await fetch(realmUrl).catch(() => null);
    if (!realmCheck || !realmCheck.ok) {
      showOrgNotFound(TENANT_SLUG);
      return;
    }
    await initKeycloak();
    startApp();
  } catch (e) {
    console.error('Keycloak init failed:', e);
    document.body.innerHTML = `
      <div style="min-height:100vh;display:flex;align-items:center;justify-content:center;
                  background:#0d1017;font-family:'Space Grotesk',system-ui">
        <div style="text-align:center;color:#f0f0f0;max-width:480px;padding:2rem">
          <div style="font-size:2.5rem;margin-bottom:1rem">⚠️</div>
          <h2 style="color:#2ce5a7;margin-bottom:.5rem">Service d'authentification indisponible</h2>
          <p style="color:#8892a4">
            La connexion au serveur d'authentification a échoué.<br>
            Veuillez contacter votre administrateur système.
          </p>
        </div>
      </div>`;
  }
})();

// ============================================================
// SETTINGS DRAWER
// ============================================================
async function loadSellerSettings() {
  try {
    const res = await fetch(`${base()}/settings/seller`, { headers: authHeaders() });
    if (!res.ok) return;
    sellerSettings = await res.json();
    // pre-fill the drawer form if already open
    fillSettingsForm(sellerSettings);
  } catch { /* backend may not be reachable */ }
}

function fillSettingsForm(s) {
  const v = s || {};
  const get = id => document.getElementById(id);
  if (get('set-company'))     get('set-company').value     = v.companyName       || tenantName();
  if (get('set-address'))     get('set-address').value     = v.address           || '';
  if (get('set-rcs'))         get('set-rcs').value         = v.rcs               || '';
  if (get('set-capital'))     get('set-capital').value     = v.capital           || '';
  if (get('set-email'))       get('set-email').value       = v.email             || '';
  if (get('set-iban'))        get('set-iban').value        = v.iban              || '';
  if (get('set-bic'))         get('set-bic').value         = v.bic               || '';
  if (get('set-late-clause')) get('set-late-clause').value = v.latePaymentClause || '';
}

function openSettingsDrawer() {
  fillSettingsForm(sellerSettings);
  const overlay = document.getElementById('settings-overlay');
  const drawer  = document.getElementById('settings-drawer');
  overlay.classList.remove('hidden');
  drawer.classList.remove('hidden');
  drawer.classList.add('slide-in');
  setStatus(document.getElementById('settings-status'), '');
}

function closeSettingsDrawer() {
  document.getElementById('settings-overlay').classList.add('hidden');
  document.getElementById('settings-drawer').classList.add('hidden');
  document.getElementById('settings-drawer').classList.remove('slide-in');
}

function initSettingsDrawer() {
  document.getElementById('settings-btn').addEventListener('click', openSettingsDrawer);
  document.getElementById('settings-close').addEventListener('click', closeSettingsDrawer);
  document.getElementById('settings-overlay').addEventListener('click', closeSettingsDrawer);

  document.getElementById('settings-save').addEventListener('click', async () => {
    const btn = document.getElementById('settings-save');
    const statusEl = document.getElementById('settings-status');
    btn.disabled = true;
    setStatus(statusEl, 'Enregistrement…', '');

    const payload = {
      companyName:       (document.getElementById('set-company').value     || '').trim() || tenantName(),
      address:            document.getElementById('set-address').value.trim(),
      rcs:                document.getElementById('set-rcs').value.trim(),
      capital:            document.getElementById('set-capital').value.trim(),
      email:              document.getElementById('set-email').value.trim(),
      iban:               document.getElementById('set-iban').value.trim(),
      bic:                document.getElementById('set-bic').value.trim(),
      latePaymentClause:  document.getElementById('set-late-clause').value.trim(),
    };

    try {
      const res = await fetch(`${base()}/settings/seller`, {
        method: 'POST',
        headers: adminHeaders(),
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
      sellerSettings = payload;
      setStatus(statusEl, 'Paramètres sauvegardés.', 'ok');
      showToast('Paramètres de facturation sauvegardés.', 'ok');
      setTimeout(closeSettingsDrawer, 800);
    } catch (e) {
      setStatus(statusEl, 'Erreur : ' + e.message, 'err');
    } finally {
      btn.disabled = false;
    }
  });
}

// ============================================================
// NOTIFICATIONS (SSE)
// ============================================================
let notifCount = 0;

function initNotifications() {
  const bell     = document.getElementById('notif-bell');
  const dropdown = document.getElementById('notif-dropdown');
  const readAll  = document.getElementById('notif-read-all');

  bell.addEventListener('click', e => {
    e.stopPropagation();
    dropdown.classList.toggle('hidden');
    if (!dropdown.classList.contains('hidden')) loadNotifications();
  });

  document.addEventListener('click', e => {
    if (!document.getElementById('notif-bell-wrap').contains(e.target)) {
      dropdown.classList.add('hidden');
    }
  });

  readAll.addEventListener('click', async () => {
    await fetch(`${base()}/admin/notifications/read-all`, { method: 'POST', headers: adminHeaders() });
    notifCount = 0;
    updateBadge();
    loadNotifications();
  });

  connectSSE();
}

function connectSSE() {
  try {
    const token = _keycloak?.token;
    const url   = token
      ? `${base()}/admin/notifications/stream?token=${encodeURIComponent(token)}`
      : `${base()}/admin/notifications/stream`;
    const es = new EventSource(url);
    es.addEventListener('init', e => {
      notifCount = parseInt(e.data, 10) || 0;
      updateBadge();
    });
    es.addEventListener('notification', e => {
      try {
        const n = JSON.parse(e.data);
        notifCount++;
        updateBadge();
        showToast(n.message, 'info');
        const dropdown = document.getElementById('notif-dropdown');
        if (!dropdown.classList.contains('hidden')) loadNotifications();
        // Rafraîchir les congés si le panel consultant est ouvert sur l'onglet congés
        if (n.type === 'LEAVE_REQUESTED' && currentConsultant) {
          const panel = document.getElementById('cons-panel-conges');
          if (panel && panel.style.display !== 'none') loadConsLeaves(currentConsultant);
        }
      } catch { /* ignore malformed */ }
    });
    es.onerror = () => setTimeout(connectSSE, 5000);
  } catch { /* SSE not available */ }
}

function updateBadge() {
  const badge = document.getElementById('notif-badge');
  if (notifCount > 0) {
    badge.textContent = notifCount > 99 ? '99+' : notifCount;
    badge.classList.remove('hidden');
  } else {
    badge.classList.add('hidden');
  }
}

async function loadNotifications() {
  const list = document.getElementById('notif-list');
  list.innerHTML = '<p style="padding:12px 16px;color:var(--muted);font-size:13px">Chargement…</p>';
  try {
    const res = await fetch(`${base()}/admin/notifications?all=false`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();
    if (!items.length) {
      list.innerHTML = '<p style="padding:12px 16px;color:var(--muted);font-size:13px">Aucune notification non lue.</p>';
      return;
    }
    list.innerHTML = items.map(n => `
      <div class="notif-item" data-id="${escapeHtml(n.id)}">
        <span class="notif-icon">${n.type === 'CRA_SUBMITTED' ? '📋' : n.type === 'LEAVE_REQUESTED' ? '🏖️' : '💶'}</span>
        <div class="notif-body">
          <p class="notif-msg">${escapeHtml(n.message)}</p>
          <p class="notif-ts">${formatTs(n.timestamp)}</p>
        </div>
      </div>`).join('');
    list.querySelectorAll('.notif-item').forEach(item => {
      item.addEventListener('click', async () => {
        await fetch(`${base()}/admin/notifications/${item.dataset.id}/read`, { method: 'POST', headers: adminHeaders() });
        item.style.opacity = '0.5';
        notifCount = Math.max(0, notifCount - 1);
        updateBadge();
      });
    });
  } catch (e) {
    list.innerHTML = `<p style="padding:12px 16px;color:#ff8a8a;font-size:13px">Erreur : ${escapeHtml(e.message)}</p>`;
  }
}

function formatTs(ts) {
  if (!ts) return '';
  try {
    return new Date(ts).toLocaleString('fr-FR', { day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit' });
  } catch { return ts; }
}

// ============================================================
// CONSULTANTS — donnees backend JPA
// ============================================================
const CONS_KEY = `adminConsultants_${TENANT_SLUG}`;
const CONS_CACHE_TS_KEY = `adminConsultants_ts_${TENANT_SLUG}`;
const CONS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 min

function isCacheValid() {
  const ts  = parseInt(localStorage.getItem(CONS_CACHE_TS_KEY) || '0', 10);
  if ((Date.now() - ts) >= CONS_CACHE_TTL_MS) return false;
  try {
    const stored = JSON.parse(localStorage.getItem(CONS_KEY) || '[]');
    return Array.isArray(stored) && stored.length > 0;
  } catch { return false; }
}

function setCacheTimestamp() {
  localStorage.setItem(CONS_CACHE_TS_KEY, Date.now().toString());
}

function clearConsultantsCache() {
  localStorage.removeItem(CONS_KEY);
  localStorage.removeItem(CONS_CACHE_TS_KEY);
}

function defaultConsultants() {
  return [];
}

function saveConsultants(list) {
  localStorage.setItem(CONS_KEY, JSON.stringify(list));
}

function loadConsultants() {
  try {
    const raw = localStorage.getItem(CONS_KEY);
    let list;
    if (!raw) {
      list = defaultConsultants();
    } else {
      const stored = JSON.parse(raw);
      list = Array.isArray(stored) && stored.length > 0 ? stored : defaultConsultants();
    }
    // Normalize: company defaults to tenant name if missing or blank
    list.forEach(c => {
      if (!c.company?.trim()) c.company = tenantName();
      if (c.clientName         === undefined) c.clientName         = '';
      if (c.clientAddress      === undefined) c.clientAddress      = '';
      if (c.clientRcs          === undefined) c.clientRcs          = '';
      if (c.clientContactEmail === undefined) c.clientContactEmail = '';
      if (c.active        === undefined) c.active        = true;
      if (c.tjm           === undefined || c.tjm === null) c.tjm = 0;
    });
    return list;
  } catch {
    return defaultConsultants();
  }
}

/** Rôles internes (non facturables) — exclus du dashboard. */
function isBillableRole(role) {
  const r = (role || '').toLowerCase();
  return r !== 'admin' && r !== 'manager' && r !== 'gestionnaire';
}

async function saveConsultantToBackend(cons) {
  try {
    // Dériver isConsultant depuis le rôle pour que le backend filtre correctement le dashboard
    const payload = { ...cons, isConsultant: isBillableRole(cons.role) };
    const res = await fetch(`${base()}/consultants/profiles`, {
      method: 'POST',
      headers: adminHeaders(),
      body: JSON.stringify(payload),
    });
    if (res.ok) {
      const saved = await res.json();
      if (saved?.id) {
        cons.id = saved.id;
        const idx = allConsultants.findIndex(c => c.email === cons.email);
        if (idx >= 0) allConsultants[idx].id = saved.id;
        saveConsultants(allConsultants);
      }
    }
  } catch { /* silent — local cache is already updated */ }
}

/**
 * On first load (or after cache expiry), fetch all consultant profiles from backend.
 * Merges local-only consultants (not yet synced).
 */
async function initConsultantsData() {
  if (isCacheValid()) return; // cache fresh, nothing to do
  try {
    const res = await fetch(`${base()}/consultants/profiles`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const list = await res.json();

    if (list.length > 0) {
      // Merge: keep any local-only consultants not yet synced
      const backendEmails = new Set(list.map(c => c.email));
      const localOnly = allConsultants.filter(c => !backendEmails.has(c.email));
      allConsultants = [...list, ...localOnly];
      // Normalize
      allConsultants.forEach(c => {
        if (!c.company?.trim()) c.company = tenantName();
        if (c.clientName    === undefined) c.clientName    = '';
        if (c.clientAddress === undefined) c.clientAddress = '';
        if (c.clientRcs     === undefined) c.clientRcs     = '';
        if (c.active        === undefined) c.active        = true;
        if (c.tjm           === undefined || c.tjm === null) c.tjm = 0;
      });
      saveConsultants(allConsultants);
      setCacheTimestamp();
      renderConsultantsGrid();
    } else {
      // Backend returned empty — keep local cache intact, re-fetch on next load
      renderConsultantsGrid();
    }
  } catch { /* backend unreachable — keep local cache */ }
}

let allConsultants = loadConsultants();
let currentConsultant = null;
let adminUser = null;
let editingConsEmail = null;
let sellerSettings = {};   // cached from GET /settings/seller

// ============================================================
// BOOT — must run after allConsultants/currentConsultant/adminUser are initialised
// ============================================================
const session = getSession();
if (session) showApp(session);

function initConsultants(user) {
  adminUser = user;
  const now = new Date();
  const curMonth = toMonthStr(now.getFullYear(), now.getMonth());

  renderConsultantsGrid();

  document.getElementById('cons-search').addEventListener('input', debounce(e => {
    renderConsultantsGrid(e.target.value);
  }, 150));

  // --- Add form: mode toggle (existing vs invite) ---
  let addMode = 'existing'; // 'existing' or 'invite'

  function setAddMode(mode) {
    addMode = mode;
    const existingBlock = document.getElementById('cons-existing-block');
    const inviteBlock   = document.getElementById('cons-invite-block');
    const btnExisting   = document.getElementById('cons-mode-existing');
    const btnInvite     = document.getElementById('cons-mode-invite');
    if (mode === 'invite') {
      existingBlock.style.display = 'none';
      inviteBlock.style.display   = '';
      btnExisting.className = 'btn-ghost';
      btnInvite.className   = 'btn-secondary cons-mode-active';
      // Default company for invite
      const invCompany = document.getElementById('cons-invite-company');
      if (invCompany && !invCompany.value) invCompany.value = tenantName();
    } else {
      existingBlock.style.display = '';
      inviteBlock.style.display   = 'none';
      btnExisting.className = 'btn-secondary cons-mode-active';
      btnInvite.className   = 'btn-ghost';
    }
  }

  document.getElementById('cons-mode-existing').addEventListener('click', () => setAddMode('existing'));
  document.getElementById('cons-mode-invite').addEventListener('click', () => setAddMode('invite'));

  // Auth type toggle — show/hide password field
  document.getElementById('cons-invite-authtype').addEventListener('change', (e) => {
    const pwdLabel = document.getElementById('cons-invite-password-label');
    pwdLabel.style.display = e.target.value === 'external' ? 'none' : '';
  });

  document.getElementById('cons-add-btn').addEventListener('click', async () => {
    document.getElementById('cons-add-form').style.display = '';
    document.getElementById('cons-add-btn').style.display  = 'none';
    setAddMode('existing');
    loadBillingPreview();
    const companyInput = document.getElementById('cons-new-company');
    if (companyInput && !companyInput.value) companyInput.value = tenantName();
    // Pré-charger les clients si pas encore chargés
    if (!allClients.length) await loadClients();
    populateClientSelects();
    // Load Keycloak users into dropdown
    const sel = document.getElementById('cons-new-kc-user');
    const statusEl = document.getElementById('cons-add-kc-status');
    sel.innerHTML = '<option value="">-- Chargement --</option>';
    statusEl.textContent = '';
    try {
      const res = await fetch(`${base()}/consultants/keycloak-users`, { headers: authHeaders() });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const users = await res.json();
      const existingEmails = new Set(allConsultants.filter(c => c.email).map(c => c.email.toLowerCase()));
      if (adminUser?.email) existingEmails.add(adminUser.email.toLowerCase());
      const available = users.filter(u => u.email && !existingEmails.has(u.email.toLowerCase()));
      if (!available.length) {
        sel.innerHTML = '<option value="">Tous les utilisateurs sont déjà ajoutés</option>';
      } else {
        sel.innerHTML = '<option value="">-- Sélectionnez --</option>' +
          available.map(u => `<option value="${escapeHtml(u.email)}" data-name="${escapeHtml(u.name)}">${escapeHtml(u.name)} (${escapeHtml(u.email)})</option>`).join('');
      }
      statusEl.textContent = '';
    } catch {
      sel.innerHTML = '<option value="">-- Erreur chargement --</option>';
      statusEl.textContent = 'Impossible de charger les utilisateurs internes.';
    }
  });

  document.getElementById('cons-add-cancel').addEventListener('click', () => {
    document.getElementById('cons-add-form').style.display = 'none';
    document.getElementById('cons-add-btn').style.display  = '';
    document.getElementById('cons-inline-client-form').style.display = 'none';
  });

  // populateClientSelects est défini au niveau module (voir ci-dessous)

  // Ouvre le mini-form inline et ferme au clic "Annuler"
  function openInlineClientForm() {
    ['inline-client-name','inline-client-address','inline-client-rcs','inline-client-contact','inline-client-email']
      .forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
    document.getElementById('inline-client-status').textContent = '';
    document.getElementById('cons-inline-client-form').style.display = '';
  }

  document.getElementById('cons-new-client-create-btn')?.addEventListener('click', openInlineClientForm);
  document.getElementById('cons-invite-client-create-btn')?.addEventListener('click', openInlineClientForm);

  document.getElementById('inline-client-cancel')?.addEventListener('click', () => {
    document.getElementById('cons-inline-client-form').style.display = 'none';
  });

  document.getElementById('inline-client-save')?.addEventListener('click', async () => {
    const name    = document.getElementById('inline-client-name').value.trim();
    const address = document.getElementById('inline-client-address').value.trim();
    const rcs     = document.getElementById('inline-client-rcs').value.trim();
    const contact = document.getElementById('inline-client-contact').value.trim();
    const email   = document.getElementById('inline-client-email').value.trim();
    const statusEl = document.getElementById('inline-client-status');

    if (!name) { statusEl.textContent = 'Le nom du client est requis.'; statusEl.className = 'status error'; return; }
    statusEl.textContent = 'Création…'; statusEl.className = 'status';

    try {
      const res = await fetch(`${base()}/clients`, {
        method: 'POST',
        headers: adminHeaders(),
        body: JSON.stringify({ name, address, rcs, contactName: contact, contactEmail: email }),
      });
      if (!res.ok) { const t = await res.text(); throw new Error(t || `HTTP ${res.status}`); }
      const created = await res.json();
      allClients.push(created);
      allClients.sort((a, b) => a.name.localeCompare(b.name));
      populateClientSelects(created.name);
      document.getElementById('cons-inline-client-form').style.display = 'none';
      showToast(`Client "${created.name}" créé et sélectionné.`, 'ok');
    } catch (e) {
      statusEl.textContent = 'Erreur : ' + e.message; statusEl.className = 'status error';
    }
  });

  // Impact tarifaire de l'ajout d'un consultant (abonnement du tenant — comptage dynamique)
  async function loadBillingPreview() {
    const el = document.getElementById('cons-billing-preview');
    if (!el) return;
    el.style.display = 'none';
    try {
      const res = await fetch(`${base()}/billing/preview`, { headers: authHeaders() });
      if (!res.ok) return;
      const p = await res.json();
      if (!p.subscribed) return;
      const fmt = v => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2 }).format(v);
      const billingNote = p.billingPeriod === 'MENSUEL'
        ? (p.nextInvoiceDate ? ` — pris en compte à la facture du ${escapeHtml(p.nextInvoiceDate)}.` : '.')
        : ` — un ajustement prorata (mois restants de la période) sera facturé automatiquement.`;
      el.innerHTML =
        `Offre <strong>${escapeHtml(p.offer)}</strong> (${escapeHtml(p.billingPeriod.toLowerCase())}) — ` +
        `${p.billableConsultants} consultant(s) facturable(s) soit ${fmt(p.currentMonthlyHt)}&nbsp;€ HT/mois. ` +
        `Avec ce consultant&nbsp;: <strong style="color:var(--accent)">${fmt(p.projectedMonthlyHt)}&nbsp;€ HT/mois</strong>` +
        billingNote;
      el.style.display = '';
    } catch { /* aperçu non bloquant */ }
  }

  // Edit form
  document.getElementById('cons-edit-save').addEventListener('click', async () => {
    if (!editingConsEmail) return;
    const name    = document.getElementById('cons-edit-name').value.trim();
    const role    = document.getElementById('cons-edit-role').value;
    const company = document.getElementById('cons-edit-company').value.trim();
    const restore = setBtnLoading('cons-edit-save');
    const idx = allConsultants.findIndex(c => c.email === editingConsEmail);
    if (idx >= 0) {
      if (name)    allConsultants[idx].name    = name;
      if (role)    allConsultants[idx].role    = role;
      if (company) allConsultants[idx].company = company;
      saveConsultants(allConsultants);
      await saveConsultantToBackend(allConsultants[idx]);
      if (currentConsultant?.email === editingConsEmail) {
        currentConsultant = allConsultants[idx];
        document.getElementById('cons-detail-name').textContent    = allConsultants[idx].name;
        document.getElementById('cons-detail-company').textContent = allConsultants[idx].company;
      }
      showToast('Consultant mis à jour.', 'ok');
    }
    restore();
    closeEditModal();
    renderConsultantsGrid(document.getElementById('cons-search').value);
  });

  document.getElementById('cons-edit-cancel').addEventListener('click', closeEditModal);
  document.getElementById('cons-edit-modal-close').addEventListener('click', closeEditModal);
  document.getElementById('cons-edit-overlay').addEventListener('click', closeEditModal);

  document.getElementById('cons-add-save').addEventListener('click', async () => {
    if (addMode === 'invite') {
      // --- Invite new user (Keycloak + profile) ---
      const email     = (document.getElementById('cons-invite-email').value || '').trim().toLowerCase();
      const firstName = (document.getElementById('cons-invite-firstname').value || '').trim();
      const lastName  = (document.getElementById('cons-invite-lastname').value || '').trim();
      const authType  = document.getElementById('cons-invite-authtype').value;
      const password  = (document.getElementById('cons-invite-password').value || '').trim();
      const role      = document.getElementById('cons-invite-role').value;
      const company   = document.getElementById('cons-invite-company').value.trim() || tenantName();
      const clientName = document.getElementById('cons-invite-clientname').value.trim();

      if (!email) { showToast('Email obligatoire.', 'err'); return; }
      if (authType === 'internal' && !password) { showToast('Mot de passe temporaire obligatoire pour un utilisateur interne.', 'err'); return; }
      if (allConsultants.some(c => c.email.toLowerCase() === email)) {
        showToast('Ce consultant est déjà enregistré.', 'err'); return;
      }

      try {
        const res = await fetch(`${base()}/consultants/invite`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify({
            email, firstName, lastName,
            tempPassword: authType === 'internal' ? password : null,
            role, company, clientName
          })
        });
        const data = await res.json();
        if (!res.ok) { showToast(data.error || 'Erreur invitation', 'err'); return; }

        const name = (firstName + ' ' + lastName).trim() || email;
        const newCons = { name, email, role, company, clientName, clientAddress: '', clientRcs: '', tjm: 0, active: true };
        allConsultants.push(newCons);
        saveConsultants(allConsultants);
        document.getElementById('cons-add-form').style.display = 'none';
        document.getElementById('cons-add-btn').style.display  = '';
        renderConsultantsGrid();
        showToast(`${name} invité et enregistré.`, 'ok');
      } catch (e) {
        showToast('Erreur : ' + e.message, 'err');
      }
    } else {
      // --- Existing Keycloak user ---
      const sel        = document.getElementById('cons-new-kc-user');
      const email      = sel.value.trim().toLowerCase();
      const name       = sel.selectedOptions[0]?.dataset.name || email;
      const role       = document.getElementById('cons-new-role').value;
      const company    = document.getElementById('cons-new-company').value.trim() || tenantName();
      const clientName = document.getElementById('cons-new-clientname').value.trim();
      if (!email) { showToast('Sélectionnez un utilisateur.', 'err'); return; }
      if (allConsultants.some(c => c.email.toLowerCase() === email)) {
        showToast('Ce consultant est déjà enregistré.', 'err'); return;
      }
      const newCons = { name, email, role, company, clientName, clientAddress: '', clientRcs: '', tjm: 0, active: true };
      allConsultants.push(newCons);
      saveConsultants(allConsultants);
      saveConsultantToBackend(newCons);
      document.getElementById('cons-add-form').style.display = 'none';
      document.getElementById('cons-add-btn').style.display  = '';
      renderConsultantsGrid();
      showToast(`${name} ajoute.`, 'ok');
    }
  });

  document.getElementById('cons-back-btn').addEventListener('click', () => {
    // Flush any unsaved field edits before leaving the detail view
    flushDetailEdits();
    document.getElementById('cons-detail-view').style.display = 'none';
    document.getElementById('cons-grid-view').style.display   = '';
    currentConsultant = null;
  });

  // Sub-tabs — auto-load on switch
  document.querySelectorAll('.cons-stab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.cons-stab').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.cons-tab-panel').forEach(p => p.style.display = 'none');
      btn.classList.add('active');
      document.getElementById(`cons-panel-${btn.dataset.consTab}`).style.display = 'block';
      if (!currentConsultant) return;
      if (btn.dataset.consTab === 'notes')    loadConsNotes(currentConsultant);
      if (btn.dataset.consTab === 'factures') loadConsInvoices(currentConsultant);
      if (btn.dataset.consTab === 'conges')   loadConsLeaves(currentConsultant);
    });
  });

  // CRA panel
  document.getElementById('cons-cra-month').value = curMonth;
  document.getElementById('cons-cra-load').addEventListener('click', () => {
    if (currentConsultant) loadConsCra(currentConsultant);
  });

  // Notes panel
  document.getElementById('cons-notes-month').value = curMonth;
  document.getElementById('cons-notes-load').addEventListener('click', () => {
    if (currentConsultant) loadConsNotes(currentConsultant);
  });
  document.getElementById('cons-notes-approve-all').addEventListener('click', () => {
    if (currentConsultant) approveAllNotes(currentConsultant);
  });
  document.getElementById('cons-notes-pdf').addEventListener('click', () => {
    if (currentConsultant) downloadNotesPdf(currentConsultant);
  });
  document.getElementById('cons-notes-excel').addEventListener('click', () => {
    if (currentConsultant) downloadNotesExcel(currentConsultant);
  });

  // Invoices panel
  document.getElementById('cons-inv-start').value = toMonthStr(now.getFullYear(), Math.max(0, now.getMonth() - 2));
  document.getElementById('cons-inv-end').value   = curMonth;
  document.getElementById('cons-inv-load').addEventListener('click', () => {
    if (currentConsultant) loadConsInvoices(currentConsultant);
  });
  document.getElementById('cons-inv-delete-all').addEventListener('click', () => {
    if (currentConsultant) deleteAllInvoices(currentConsultant);
  });

  // Kick off async backend sync (non-blocking)
  initConsultantsData();
}

function renderConsultantsGrid(filter = '') {
  const grid = document.getElementById('cons-grid');
  const q    = filter.trim().toLowerCase();
  const staffEmails = new Set((allConsultants.filter(c => c.role === 'admin' || c.role === 'manager').map(c => c.email?.toLowerCase())));
  if (adminUser?.email) staffEmails.add(adminUser.email.toLowerCase());
  const activeList = allConsultants.filter(c => c.active !== false && !staffEmails.has(c.email?.toLowerCase()));
  const filterFn = c => !q || c.name.toLowerCase().includes(q) || c.email.toLowerCase().includes(q);
  const list = activeList.filter(filterFn);

  if (!list.length) {
    const msg = q
      ? 'Aucun consultant trouve.'
      : 'Aucun consultant. Cliquez sur « + Ajouter » pour enregistrer un consultant avec son email Keycloak.';
    grid.innerHTML = `<div class="cons-empty" style="grid-column:1/-1;padding:48px 24px;text-align:center;color:var(--muted);border:1px dashed var(--border);border-radius:var(--radius)">
      ${msg}</div>`;
    return;
  }

  grid.innerHTML = list.map(c => `
    <div class="consultant-card" data-email="${escapeHtml(c.email)}">
      <div class="card-inner">
        <div class="cons-avatar" style="background:${avatarColor(c.name)}">${initials(c.name)}</div>
        <p class="cons-card-name">${escapeHtml(c.name)}</p>
        <p class="cons-card-email">${escapeHtml(c.email)}</p>
        <div class="cons-card-badges">
          <span class="cons-role-badge ${c.role.toLowerCase()}">${escapeHtml(c.role)}</span>
          <span class="cons-company-tag">${escapeHtml(c.company)}</span>
        </div>
        ${c.clientName ? `<p class="cons-card-client">↳ ${escapeHtml(c.clientName)}</p>` : ''}
        <div class="cons-card-kpis" id="kpis-${btoa(c.email).replace(/[^a-zA-Z0-9]/g,'')}">
          <div class="cons-kpi-chip"><span class="kpi-v">…</span><span class="kpi-l">CRA soumis</span></div>
          <div class="cons-kpi-chip"><span class="kpi-v">…</span><span class="kpi-l">Frais</span></div>
        </div>
        <div class="cons-card-actions">
          <button class="btn-card-edit" data-email="${escapeHtml(c.email)}">
            <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            Modifier
          </button>
          <button class="btn-card-toggle" data-email="${escapeHtml(c.email)}">
            <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
            Desactiver
          </button>
        </div>
      </div>
    </div>`).join('');

  grid.querySelectorAll('.consultant-card').forEach(card => {
    const email = card.dataset.email;
    const cons  = allConsultants.find(c => c.email === email);
    if (!cons) return;
    if (!card.dataset.inactive) {
      card.addEventListener('click', () => openConsultantDetail(cons));
      loadCardKpis(cons);
    }
  });

  grid.querySelectorAll('.btn-card-edit').forEach(btn => {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      const cons = allConsultants.find(c => c.email === btn.dataset.email);
      if (cons) openEditConsultant(cons);
    });
  });

  grid.querySelectorAll('.btn-card-toggle').forEach(btn => {
    btn.addEventListener('click', e => {
      e.stopPropagation();
      const cons = allConsultants.find(c => c.email === btn.dataset.email);
      if (cons) toggleConsultantActive(cons);
    });
  });
}

function openEditConsultant(cons) {
  editingConsEmail = cons.email;
  document.getElementById('cons-edit-cons-name').textContent = `${cons.name} — ${cons.email}`;
  document.getElementById('cons-edit-name').value    = cons.name    || '';
  document.getElementById('cons-edit-role').value    = cons.role    || 'Freelance';
  document.getElementById('cons-edit-company').value = cons.company || '';
  document.getElementById('cons-edit-overlay').classList.remove('hidden');
  document.getElementById('cons-edit-modal').classList.remove('hidden');
}

function closeEditModal() {
  document.getElementById('cons-edit-overlay').classList.add('hidden');
  document.getElementById('cons-edit-modal').classList.add('hidden');
  editingConsEmail = null;
}

function toggleConsultantActive(cons) {
  const isActive = cons.active !== false;
  if (!confirm(`${isActive ? 'Desactiver' : 'Reactiver'} ${cons.name} ?`)) return;
  const idx = allConsultants.findIndex(c => c.email === cons.email);
  if (idx < 0) return;
  allConsultants[idx].active = !isActive;
  saveConsultants(allConsultants);
  saveConsultantToBackend(allConsultants[idx]);
  if (currentConsultant?.email === cons.email) {
    currentConsultant = allConsultants[idx];
  }
  renderConsultantsGrid(document.getElementById('cons-search')?.value || '');
  showToast(`${cons.name} ${isActive ? 'desactive' : 'reactive'}.`, 'ok');
}


async function loadCardKpis(cons) {
  const kpiId  = `kpis-${btoa(cons.email).replace(/[^a-zA-Z0-9]/g,'')}`;
  const kpiEl  = document.getElementById(kpiId);
  if (!kpiEl) return;
  const now    = new Date();
  const month  = toMonthStr(now.getFullYear(), now.getMonth());

  let craPending   = 0;
  let expenseCount = 0;

  try {
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.email });
    const r = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (r.ok) {
      const items = await r.json();
      craPending = items.filter(c => c.status === 'SOUMIS').length;
    }
  } catch { /* ignore */ }

  try {
    const [ky, km] = month.split('-').map(Number);
    const s   = `${month}-01`;
    const e   = `${month}-${new Date(ky, km, 0).getDate()}`;
    const p   = new URLSearchParams({ start: s, end: e, consultantEmail: cons.email });
    const res = await fetch(`${base()}/expenses/report?${p}`, { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      expenseCount = Array.isArray(data) ? data.length : (data.expenses ? data.expenses.length : 0);
    }
  } catch { /* ignore */ }

  kpiEl.innerHTML = `
    <div class="cons-kpi-chip${craPending > 0 ? ' kpi-alert' : ''}">
      <span class="kpi-v" style="color:${craPending>0?'#fde68a':'var(--accent)'}">${craPending}</span>
      <span class="kpi-l">CRA soumis</span>
    </div>
    <div class="cons-kpi-chip"><span class="kpi-v">${expenseCount}</span><span class="kpi-l">Frais ce mois</span></div>`;

  // Show/hide the alert dot on the card header
  const card = kpiEl.closest('.consultant-card');
  if (card) {
    let dot = card.querySelector('.cons-alert-dot');
    if (craPending > 0) {
      if (!dot) {
        dot = document.createElement('span');
        dot.className = 'cons-alert-dot';
        dot.title = `${craPending} CRA en attente de validation`;
        card.querySelector('.cons-avatar')?.after(dot);
      }
      dot.textContent = craPending;
    } else if (dot) {
      dot.remove();
    }
  }
}

/**
 * Saves the current values of all inline-editable fields in the detail view
 * before the user navigates away. Guards against the user not blurring a field.
 */
function flushDetailEdits() {
  // No inline edits remain in the detail view — all edits go through modals.
}

function openConsultantDetail(cons) {
  currentConsultant = cons;
  document.getElementById('cons-grid-view').style.display   = 'none';
  document.getElementById('cons-detail-view').style.display = '';

  const av = document.getElementById('cons-detail-avatar');
  av.textContent = initials(cons.name);
  av.style.background = avatarColor(cons.name);

  document.getElementById('breadcrumb-name').textContent     = cons.name;
  document.getElementById('cons-detail-name').textContent    = cons.name;
  document.getElementById('cons-detail-email').textContent   = cons.email;
  document.getElementById('cons-detail-role').textContent    = cons.role;
  document.getElementById('cons-detail-role').className      = `cons-role-badge ${cons.role.toLowerCase()}`;
  document.getElementById('cons-detail-company').textContent = cons.company;

  document.getElementById('kpi-cra-pending').textContent     = '…';
  document.getElementById('kpi-expense-pending').textContent = '…';

  // Reset to first sub-tab (CRA) and auto-load it
  document.querySelectorAll('.cons-stab').forEach((b,i) => b.classList.toggle('active', i===0));
  document.querySelectorAll('.cons-tab-panel').forEach((p,i) => { p.style.display = i === 0 ? 'block' : 'none'; });
  document.getElementById('cons-cra-result').style.display   = 'none';
  document.getElementById('cons-notes-result').style.display = 'none';
  document.getElementById('cons-inv-result').style.display   = 'none';
  document.getElementById('cons-notes-approve-all').style.display = 'none';
  document.getElementById('cons-notes-pdf').style.display         = 'none';
  document.getElementById('cons-notes-excel').style.display       = 'none';

  // Populate vehicle profile form
  const vtSel = document.getElementById('cons-vehicle-type');
  const fpInp = document.getElementById('cons-fiscal-power');
  const kmInp = document.getElementById('cons-km-annual');
  if (vtSel) vtSel.value = cons.vehicleType || 'CAR';
  if (fpInp) fpInp.value = cons.fiscalPower  ?? 7;
  if (kmInp) kmInp.value = cons.kmAnnual     ?? 4999;
  const vStatus = document.getElementById('cons-vehicle-status');
  if (vStatus) vStatus.textContent = '';

  loadDetailKpis(cons);
  loadConsCra(cons);
  loadConsultantAssignments(cons);
}

async function loadDetailKpis(cons) {
  const now   = new Date();
  const month = toMonthStr(now.getFullYear(), now.getMonth());

  try {
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.email });
    const r = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (r.ok) {
      const items = await r.json();
      document.getElementById('kpi-cra-pending').textContent = items.filter(c => c.status === 'SOUMIS').length;
    }
  } catch { document.getElementById('kpi-cra-pending').textContent = '?'; }

  try {
    const [dy, dm] = month.split('-').map(Number);
    const s   = `${month}-01`;
    const e   = `${month}-${new Date(dy, dm, 0).getDate()}`;
    const p   = new URLSearchParams({ start: s, end: e, consultantEmail: cons.email });
    const res = await fetch(`${base()}/expenses/report?${p}`, { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      const expenses = Array.isArray(data) ? data : (data.expenses || []);
      const pending  = expenses.filter(x => !x.approvalStatus || x.approvalStatus === 'PENDING').length;
      document.getElementById('kpi-expense-pending').textContent = pending;
    }
  } catch { document.getElementById('kpi-expense-pending').textContent = '?'; }
}

// ============================================================
// CRA VALIDATION
// ============================================================
async function loadConsCra(cons) {
  const month   = document.getElementById('cons-cra-month').value;
  const statusEl = document.getElementById('cons-cra-status');
  const resultEl = document.getElementById('cons-cra-result');

  if (!month) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Chargement…');
  resultEl.style.display = 'none';

  try {
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.email });
    const res = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    if (!items.length) {
      setStatus(statusEl, 'Aucun CRA pour ce mois.', '');
      return;
    }

    setStatus(statusEl, `${items.length} CRA trouve${items.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.innerHTML = renderCraList(items);
    resultEl.style.display = '';

    wireCraActions(resultEl, cons);
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

/** Construit un résumé des jours travaillés par projet pour l'affichage CRA. */
function _craDaysSummary(cra) {
  const entries = Array.isArray(cra.entries) ? cra.entries : [];
  const projectDays = {};
  for (const e of entries) {
    if (e.type === 'TRAVAIL' && e.value > 0 && e.projectId) {
      projectDays[e.projectId] = (projectDays[e.projectId] || 0) + e.value;
    }
  }
  const projectIds = Object.keys(projectDays);
  if (projectIds.length < 2) {
    const d = cra.totalDays != null
      ? (cra.totalDays % 1 === 0 ? String(cra.totalDays) : Number(cra.totalDays).toFixed(1))
      : '—';
    return `${d}j`;
  }
  // Use projects embedded in CRA response first, fallback to allProjects store
  const craProjects = Array.isArray(cra.projects) ? cra.projects : [];
  const parts = projectIds.map(pid => {
    const proj = craProjects.find(p => p.id === pid) || allProjects.find(p => p.id === pid);
    const name = proj?.name || (pid.slice(0,6) + '…');
    const d = projectDays[pid] % 1 === 0 ? String(projectDays[pid]) : Number(projectDays[pid]).toFixed(1);
    return `${escapeHtml(name)} : ${d}j`;
  });
  return parts.join(' | ');
}

function isCloture(billingMonth) {
  if (!billingMonth) return false;
  const [y, m] = billingMonth.split('-').map(Number);
  return new Date() >= new Date(y, m, 5);
}

function renderCraList(items) {
  const STATUS_LABEL = {
    BROUILLON: '<span class="status-badge grey">BROUILLON</span>',
    SOUMIS:    '<span class="status-badge orange">SOUMIS</span>',
    VALIDE:    '<span class="status-badge green">VALIDE</span>',
    REFUSE:    '<span class="status-badge red">REFUSE</span>',
    CLOTURE:   '<span class="status-badge purple">CLÔTURÉ</span>',
  };

  return '<div class="approval-list">' + items.map(cra => {
    const days   = _craDaysSummary(cra);
    const rawStatus = cra.status || 'BROUILLON';
    const status = (rawStatus === 'VALIDE' && isCloture(cra.billingMonth)) ? 'CLOTURE' : rawStatus;
    const badge  = STATUS_LABEL[status] || `<span class="status-badge grey">${escapeHtml(status)}</span>`;
    const craJson = escapeHtml(JSON.stringify(cra));

    let actions = '';
    if (status === 'BROUILLON') {
      actions = `
        <div class="cra-action-row">
          <p class="muted-sm" style="margin:0;font-size:12px">En cours de saisie — en attente de soumission par le consultant.</p>
        </div>`;
    } else if (status === 'SOUMIS') {
      // Détecter les congés en attente (⏳) dans les entrées du CRA
      const pendingLeaves = (() => {
        try {
          const entries = cra.entries || (cra.entriesJson ? JSON.parse(cra.entriesJson) : []);
          return entries.filter(e => e.projectId === '__LEAVE_PENDING__' && e.type === 'ABSENT').length;
        } catch { return 0; }
      })();
      const pendingBadge = pendingLeaves > 0
        ? `<span class="leave-pending-badge" title="${pendingLeaves} congé${pendingLeaves > 1 ? 's' : ''} en attente de validation RH">⚠️ ${pendingLeaves} congé${pendingLeaves > 1 ? 's' : ''} ⏳ en attente</span>`
        : '';
      actions = `
        <div class="cra-action-row">
          ${pendingBadge}
          <button class="btn-approve" data-cra='${craJson}'>✓ Valider</button>
          <div class="refuse-inline">
            <input type="text" class="refuse-reason-input" placeholder="Motif de refus…">
            <button class="btn-refuse" data-cra='${craJson}'>✗ Refuser</button>
          </div>
        </div>`;
    } else if (status === 'VALIDE') {
      actions = `
        <div class="cra-action-row">
          <p class="validated-info" style="margin:0">Validé par <strong>${escapeHtml(cra.validatedBy || '—')}</strong>${cra.validatedAt ? ` le ${formatTs(cra.validatedAt)}` : ''}${cra.clientValidationRef ? ` · Réf client : <em>${escapeHtml(cra.clientValidationRef)}</em>` : ''}${cra.clientValidationDate ? ` (${escapeHtml(cra.clientValidationDate)})` : ''}</p>
          <button class="btn-gen-invoice" data-cra='${craJson}'>📄 Generer la facture</button>
          <button class="btn-reopen-cra" data-cra='${craJson}' title="Remettre en SOUMIS pour re-traitement">↩ Annuler validation</button>
        </div>`;
    } else if (status === 'CLOTURE') {
      actions = `
        <div class="cra-action-row">
          <p class="validated-info" style="margin:0">Clôturé — validé par <strong>${escapeHtml(cra.validatedBy || '—')}</strong>${cra.validatedAt ? ` le ${formatTs(cra.validatedAt)}` : ''}</p>
          <button class="btn-gen-invoice" data-cra='${craJson}'>📄 Generer la facture</button>
        </div>`;
    } else if (status === 'REFUSE') {
      actions = `
        <div class="cra-action-row">
          <p class="refused-reason" style="margin:0">Motif : ${escapeHtml(cra.refusedReason || '—')}</p>
          <button class="btn-reopen-cra" data-cra='${craJson}' title="Remettre en SOUMIS pour re-traitement">↩ Annuler refus</button>
        </div>`;
    }

    const missionLabel = cra.missionTitle ? ' — ' + escapeHtml(cra.missionTitle) : '';
    const projs = Array.isArray(cra.projects) ? cra.projects.filter(p => p.id) : [];
    let pdfBtn = '';
    if (cra.id && status !== 'BROUILLON') {
      if (projs.length > 1) {
        const projsJson = escapeHtml(JSON.stringify(projs));
        pdfBtn = `<button class="btn-secondary btn-pdf-cra-multi" data-cra-id="${escapeHtml(cra.id)}" data-projects="${projsJson}" style="padding:4px 10px;font-size:11px;white-space:nowrap" title="Choisir le PDF à télécharger">PDF ▾</button>`;
      } else {
        const pid   = projs[0]?.id   ? `data-pid="${escapeHtml(projs[0].id)}"` : '';
        const pname = projs[0]?.name ? `data-pname="${escapeHtml(projs[0].name)}"` : '';
        pdfBtn = `<button class="btn-secondary btn-pdf-cra" data-cra-id="${escapeHtml(cra.id)}" ${pid} ${pname} style="padding:4px 10px;font-size:11px" title="Telecharger PDF">PDF</button>`;
      }
    }

    const consultantLabel = cra.consultant ? `<span class="approval-consultant">${escapeHtml(cra.consultant)}</span>` : '';
    return `
      <div class="approval-row">
        <div class="approval-meta">
          <span class="approval-name">${escapeHtml(cra.billingMonth || '—')}${consultantLabel}</span>
          <span class="approval-detail">${escapeHtml(cra.clientCompany || '—')}${missionLabel} — ${days}</span>
        </div>
        <div class="approval-right">
          ${badge}
          ${pdfBtn}
          ${actions}
        </div>
      </div>`;
  }).join('') + '</div>';
}

/**
 * Génère une seule facture vers invoice-service.
 * opts: { clientName, clientAddress, clientRcs, projectName, tjm, days }
 */
async function generateOneInvoice(cra, cons, { clientName, clientAddress, clientRcs, projectName, tjm, days }) {
  const totalHt  = Math.round(tjm * days * 100) / 100;
  const vatRate  = 0.20;
  const totalTtc = Math.round(totalHt * (1 + vatRate) * 100) / 100;

  try {
    const res = await fetch(`${invoiceBase()}/invoices/generate`, {
      method:  'POST',
      headers: adminHeaders(),
      body: JSON.stringify({
        // invoiceDate et paymentDueDate omis : le backend les calcule
        // depuis billingMonth (+1 mois) et consultant_assignment.payment_terms_days
        billingMonth:      cra.billingMonth,
        sellerCompanyName: tenantName(),
        sellerAddress:     sellerSettings.address || '',
        sellerRcs:         sellerSettings.rcs     || '',
        clientCompanyName: clientName,
        clientAddress,
        clientRcs,
        projectName,
        invoiceTitle:      `Prestation informatique — ${cra.billingMonth}`,
        daysCount:         days,
        unitPriceHt:       tjm,
        totalHt,
        vatRate,
        totalTtc,
        currency:          'EUR',
        latePaymentClause: '',
        notes:             null,
        absencePeriods:    null,
        consultantEmail:   cons.email || null,
      }),
    });
    if (res.ok) {
      const result = await res.json().catch(() => ({}));
      const name = result.invoiceName ? ' ' + result.invoiceName : '';
      showToast(`Facture${name} générée — ${clientName} (${totalHt.toFixed(2)} € HT).`, 'ok');
    } else {
      showToast(`CRA validé — facture non générée pour ${clientName}. Contactez votre administrateur.`, 'err');
    }
  } catch (e) {
    showToast(`CRA validé — erreur génération facture : ${e.message}`, 'err');
  }
}

/**
 * Génère automatiquement les factures après validation d'un CRA.
 * — Flux multi-projets : une facture par (projectId → assignment) si les entries ont des projectIds
 * — Fallback : une seule facture globale (cons.tjm × totalDays) pour les anciens CRA
 */
async function generateInvoiceOnValidation(cra, cons) {
  await loadSellerSettings();

  // Grouper les entries TRAVAIL par projectId
  const projectDays = {};
  for (const entry of (cra.entries || [])) {
    if (entry.type === 'TRAVAIL' && entry.value > 0 && entry.projectId) {
      projectDays[entry.projectId] = (projectDays[entry.projectId] || 0) + entry.value;
    }
  }

  const projectIds = Object.keys(projectDays);
  if (projectIds.length > 0 && cons?.id) {
    // Récupérer les assignments du consultant pour avoir client + TJM par projet
    let assignments = [];
    try {
      const aRes = await fetch(`${base()}/consultants/${cons.id}/assignments`, { headers: adminHeaders() });
      if (aRes.ok) assignments = await aRes.json();
    } catch { /* non-bloquant */ }

    let generated = 0;
    for (const projectId of projectIds) {
      const assignment = assignments.find(a => a.project?.id === projectId);
      if (!assignment) continue;
      const days = projectDays[projectId];
      const tjm  = parseFloat(assignment.tjm) || 0;
      if (tjm <= 0 || days <= 0) continue;
      await generateOneInvoice(cra, cons, {
        clientName:    assignment.client?.name    || '',
        clientAddress: assignment.client?.address || '',
        clientRcs:     assignment.client?.rcs     || '',
        projectName:   assignment.project?.name   || '',
        tjm,
        days,
      });
      generated++;
    }
    if (generated > 0) return;
  }

  // Fallback — CRA sans projectId par entry (ancienne saisie) ou sans assignments
  const tjm  = parseFloat(cons.tjm) || 0;
  const days = parseFloat(cra.totalDays) || 0;
  if (tjm <= 0 || days <= 0) return;
  await generateOneInvoice(cra, cons, {
    clientName:    (cons.clientName || '').trim() || (cra.clientCompany || '').trim(),
    clientAddress: cons.clientAddress || '',
    clientRcs:     cons.clientRcs     || '',
    projectName:   '',
    tjm,
    days,
  });
}

/**
 * Apres un refus de CRA, cherche et supprime la facture associee si elle existe.
 * Silencieux si aucune facture n'est trouvee.
 */
async function deleteInvoiceForCra(cra, cons) {
  try {
    const bm    = cra.billingMonth;
    const email = cons.email || '';
    const p     = new URLSearchParams({ start: bm, end: bm });
    if (email) p.set('consultantEmail', email);

    const res = await fetch(`${invoiceBase()}/invoices/report?${p}`, { headers: authHeaders() });
    if (!res.ok) return;

    const invoices = await res.json();
    if (!invoices || invoices.length === 0) return;

    for (const inv of invoices) {
      const invoiceName = inv.invoiceName;
      if (!invoiceName) continue;
      const delRes = await fetch(`${invoiceBase()}/invoices/delete`, {
        method:  'POST',
        headers: adminHeaders(),
        body:    JSON.stringify({ billingMonth: bm, sellerCompanyName: tenantName(), invoiceName }),
      });
      if (delRes.ok) showToast(`Facture ${invoiceName} supprimee (CRA refuse).`, 'ok');
    }
  } catch (e) {
    // non-bloquant — le refus CRA a deja reussi
  }
}

function wireCraActions(container, cons) {
  container.querySelectorAll('.btn-approve').forEach(btn => {
    btn.addEventListener('click', () => {
      // Afficher un mini-formulaire inline pour le retour client (optionnel)
      const existingForm = btn.parentElement.querySelector('.client-validation-form');
      if (existingForm) { existingForm.remove(); return; }

      const form = document.createElement('div');
      form.className = 'client-validation-form';
      form.innerHTML = `
        <div style="margin-top:10px;padding:12px;background:rgba(44,229,167,.06);border:1px solid rgba(44,229,167,.2);border-radius:10px;display:flex;flex-direction:column;gap:8px">
          <p style="margin:0;font-size:12px;color:var(--accent);font-weight:600">Retour client (optionnel)</p>
          <input class="cv-ref"  type="text"  placeholder="Référence client (BC, email…)" style="padding:6px 10px;border-radius:8px;border:1px solid var(--border);background:#0f1421;color:var(--text);font-size:12px;font-family:inherit;width:100%">
          <input class="cv-date" type="date"  placeholder="Date de validation client"      style="padding:6px 10px;border-radius:8px;border:1px solid var(--border);background:#0f1421;color:var(--text);font-size:12px;font-family:inherit;width:100%">
          <div style="display:flex;gap:8px">
            <button class="cv-confirm btn-primary" style="padding:6px 14px;font-size:12px">✓ Confirmer la validation</button>
            <button class="cv-cancel  btn-secondary" style="padding:6px 14px;font-size:12px">Annuler</button>
          </div>
        </div>`;
      btn.parentElement.appendChild(form);

      form.querySelector('.cv-cancel').addEventListener('click', () => form.remove());
      form.querySelector('.cv-confirm').addEventListener('click', async () => {
        const cra     = JSON.parse(btn.dataset.cra);
        const ref     = form.querySelector('.cv-ref').value.trim();
        const dateVal = form.querySelector('.cv-date').value;
        const craBody = { ...cra, clientValidationRef: ref || null, clientValidationDate: dateVal || null };
        form.remove();
        btn.disabled = true; btn.textContent = '…';
        try {
          const p = new URLSearchParams({ validatedBy: adminUser?.email || adminUser?.name || 'Admin' });
          const res = await fetch(`${base()}/cra/validate?${p}`, {
            method: 'POST', headers: adminHeaders(), body: JSON.stringify(craBody),
          });
          if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
          showToast(`CRA ${cra.billingMonth} validé${ref ? ' · Réf: ' + ref : ''}.`, 'ok');
          await generateInvoiceOnValidation(cra, cons);
          loadConsCra(cons); loadDetailKpis(cons);
          const invStart = document.getElementById('cons-inv-start');
          if (cra.billingMonth && (!invStart.value || cra.billingMonth < invStart.value)) invStart.value = cra.billingMonth;
          loadConsInvoices(cons);
        } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✓ Valider'; }
      });
    });
  });

  container.querySelectorAll('.btn-refuse').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cra    = JSON.parse(btn.dataset.cra);
      const input  = btn.closest('.refuse-inline')?.querySelector('.refuse-reason-input');
      const reason = input?.value?.trim() || '';
      btn.disabled = true;
      btn.textContent = '…';
      try {
        const p = new URLSearchParams();
        if (reason) p.set('reason', reason);
        const res = await fetch(`${base()}/cra/refuse?${p}`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify(cra),
        });
        if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
        showToast(`CRA ${cra.billingMonth} refuse.`, 'ok');
        await deleteInvoiceForCra(cra, cons);
        loadConsCra(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✗ Refuser'; }
    });
  });

  container.querySelectorAll('.btn-gen-invoice').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cra = JSON.parse(btn.dataset.cra);
      btn.disabled = true;
      btn.textContent = '…';
      try {
        await generateInvoiceOnValidation(cra, cons);
      } finally {
        btn.disabled = false;
        btn.textContent = '📄 Generer la facture';
      }
    });
  });

  container.querySelectorAll('.btn-reopen-cra').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cra = JSON.parse(btn.dataset.cra);
      if (!confirm(`Remettre le CRA de ${cra.billingMonth} en SOUMIS pour re-traitement ?`)) return;
      btn.disabled = true;
      btn.textContent = '…';
      try {
        const res = await fetch(`${base()}/cra/reopen`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify(cra),
        });
        if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
        showToast(`CRA ${cra.billingMonth} remis en SOUMIS.`, 'ok');
        loadConsCra(cons);
        loadDetailKpis(cons);
      } catch (e) {
        showToast('Erreur : ' + e.message, 'err');
        btn.disabled = false;
        btn.textContent = '↩ Annuler';
      }
    });
  });

  container.querySelectorAll('.btn-pdf-cra').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id    = btn.dataset.craId;
      const pid   = btn.dataset.pid   || '';
      const pname = btn.dataset.pname || '';
      btn.disabled = true; btn.textContent = '…';
      try {
        const url = pid ? `${base()}/cra/pdf/${id}?projectId=${pid}` : `${base()}/cra/pdf/${id}`;
        const res = await fetch(url, { headers: authHeaders() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const blob = await res.blob();
        const objUrl = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = objUrl;
        a.download = 'CRA-' + id.substring(0, 8) + (pname ? `-${pname.replace(/\s+/g,'_')}` : '') + '.pdf';
        a.click();
        URL.revokeObjectURL(objUrl);
      } catch (e) { showToast('Erreur PDF : ' + e.message, 'err'); }
      btn.disabled = false; btn.textContent = 'PDF';
    });
  });

  container.querySelectorAll('.btn-pdf-cra-multi').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      document.getElementById('admin-pdf-menu')?.remove();
      const craId = btn.dataset.craId;
      const projs = JSON.parse(btn.dataset.projects || '[]');
      const menu  = document.createElement('div');
      menu.id = 'admin-pdf-menu'; menu.className = 'cra-pdf-menu';

      const allOpt = document.createElement('button');
      allOpt.textContent = 'Tout le CRA (tous clients)';
      allOpt.addEventListener('click', () => { menu.remove(); downloadAdminPdf(craId); });
      menu.appendChild(allOpt);

      projs.forEach(proj => {
        const opt = document.createElement('button');
        opt.textContent = proj.name;
        opt.addEventListener('click', () => { menu.remove(); downloadAdminPdf(craId, proj.id, proj.name); });
        menu.appendChild(opt);
      });

      // Fixed positioning to escape any overflow:hidden parent
      const rect = btn.getBoundingClientRect();
      menu.style.position = 'fixed';
      menu.style.top  = (rect.bottom + 4) + 'px';
      menu.style.left = (rect.right - 240) + 'px';
      menu.style.right = 'auto';

      const close = (ev) => { if (!menu.contains(ev.target) && ev.target !== btn) { menu.remove(); document.removeEventListener('click', close); } };
      document.addEventListener('click', close);
      document.body.appendChild(menu);
    });
  });
}

async function downloadAdminPdf(craId, projectId, projectName) {
  try {
    const url = projectId ? `${base()}/cra/pdf/${craId}?projectId=${projectId}` : `${base()}/cra/pdf/${craId}`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const blob = await res.blob();
    const objUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objUrl;
    a.download = 'CRA-' + craId.substring(0, 8) + (projectName ? `-${projectName.replace(/\s+/g,'_')}` : '') + '.pdf';
    a.click();
    URL.revokeObjectURL(objUrl);
  } catch (e) { showToast('Erreur PDF : ' + e.message, 'err'); }
}

// ============================================================
// NOTES DE FRAIS — APPROBATION
// ============================================================
let loadedExpenses = [];

async function loadConsNotes(cons) {
  const month    = document.getElementById('cons-notes-month').value;
  const statusEl = document.getElementById('cons-notes-status');
  const resultEl = document.getElementById('cons-notes-result');
  const approveAllBtn = document.getElementById('cons-notes-approve-all');

  if (!month) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Chargement…');
  resultEl.style.display = 'none';
  approveAllBtn.style.display = 'none';
  loadedExpenses = [];

  try {
    const [ny, nm] = month.split('-').map(Number);
    const start = `${month}-01`;
    const end   = `${month}-${new Date(ny, nm, 0).getDate()}`;
    const p     = new URLSearchParams({ start, end, consultantEmail: cons.email });
    const res   = await fetch(`${base()}/expenses/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data  = await res.json();
    const expenses = Array.isArray(data) ? data : (data.expenses || []);
    loadedExpenses = expenses;

    if (!expenses.length) {
      setStatus(statusEl, 'Aucune note de frais ce mois.', '');
      return;
    }

    setStatus(statusEl, `${expenses.length} depense${expenses.length > 1 ? 's' : ''} trouvee${expenses.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.innerHTML = renderExpenseList(expenses);
    resultEl.style.display = '';

    // "Tout approuver" uniquement si des depenses sont encore PENDING
    const hasPending = expenses.some(x => !x.approvalStatus || x.approvalStatus === 'PENDING');
    approveAllBtn.style.display = hasPending ? '' : 'none';

    // PDF / Excel uniquement quand toutes les depenses ont un statut final
    const allSettled = expenses.every(x => x.approvalStatus === 'APPROVED' || x.approvalStatus === 'REFUSED');
    document.getElementById('cons-notes-pdf').style.display   = allSettled ? '' : 'none';
    document.getElementById('cons-notes-excel').style.display = allSettled ? '' : 'none';

    wireExpenseActions(resultEl, cons);
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function renderExpenseList(expenses) {
  const APPROVAL_BADGE = {
    APPROVED: '<span class="status-badge green">Approuve</span>',
    REFUSED:  '<span class="status-badge red">Refuse</span>',
    PENDING:  '<span class="status-badge orange">En attente</span>',
  };

  return '<div class="approval-list">' + expenses.map(exp => {
    const approvalStatus = exp.approvalStatus || null;
    const badge = approvalStatus
      ? (APPROVAL_BADGE[approvalStatus] || '<span class="status-badge grey">—</span>')
      : '<span class="status-badge grey">Non soumis</span>';

    const expenseId = exp.weaviateId || (exp.id ? String(exp.id) : '') || '';

    let actions = '';
    if (expenseId) {
      actions = `
        <div class="cra-action-row">
          <button class="btn-approve" data-id="${escapeHtml(expenseId)}">✓</button>
          <div class="refuse-inline">
            <input type="text" class="refuse-reason-input" placeholder="Motif…">
            <button class="btn-refuse" data-id="${escapeHtml(expenseId)}">✗</button>
          </div>
        </div>`;
    }

    return `
      <div class="approval-row">
        <div class="approval-meta">
          <span class="approval-name">${escapeHtml(exp.date || '—')} — ${escapeHtml(exp.type || '—')}</span>
          <span class="approval-detail">${escapeHtml(exp.description || '—')}</span>
        </div>
        <div class="approval-right">
          <span class="approval-amount">${exp.amount != null ? Number(exp.amount).toFixed(2) + ' ' + (exp.currency || 'EUR') : '—'}</span>
          <span class="approval-mode">${escapeHtml(exp.paymentMode || '')}</span>
          ${badge}
          ${approvalStatus === 'REFUSED' && exp.approvalNote ? `<p class="refused-reason">${escapeHtml(exp.approvalNote)}</p>` : ''}
          ${actions}
        </div>
      </div>`;
  }).join('') + '</div>';
}

function wireExpenseActions(container, cons) {
  container.querySelectorAll('.btn-approve').forEach(btn => {
    btn.addEventListener('click', async () => {
      const expenseId = btn.dataset.id;
      if (!expenseId) { showToast('ID manquant pour cet enregistrement.', 'err'); return; }
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${base()}/expenses/approve`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify({ weaviateId: expenseId }),
        });
        if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
        showToast('Depense approuvee.', 'ok');
        loadConsNotes(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✓'; }
    });
  });

  container.querySelectorAll('.btn-refuse').forEach(btn => {
    btn.addEventListener('click', async () => {
      const expenseId = btn.dataset.id;
      if (!expenseId) { showToast('ID manquant pour cet enregistrement.', 'err'); return; }
      const input = btn.closest('.refuse-inline')?.querySelector('.refuse-reason-input');
      const note  = input?.value?.trim() || '';
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${base()}/expenses/refuse`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify({ weaviateId: expenseId, note }),
        });
        if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
        showToast('Depense refusee.', 'ok');
        loadConsNotes(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✗'; }
    });
  });
}

async function approveAllNotes(cons) {
  if (!loadedExpenses.length) return;
  const toApprove = loadedExpenses.filter(e => (e.weaviateId || e.id) && e.approvalStatus !== 'APPROVED');
  if (!toApprove.length) { showToast('Toutes les dépenses sont déjà approuvées.'); return; }
  if (!confirm(`Approuver les ${toApprove.length} depenses de ${cons.name} ?`)) return;

  let ok = 0, err = 0;
  for (const exp of toApprove) {
    try {
      const res = await fetch(`${base()}/expenses/approve`, {
        method: 'POST',
        headers: adminHeaders(),
        body: JSON.stringify({ weaviateId: exp.weaviateId || String(exp.id || '') }),
      });
      if (res.ok) ok++; else err++;
    } catch { err++; }
  }
  showToast(`${ok} approuvee(s)${err ? `, ${err} erreur(s)` : ''}.`, err ? '' : 'ok');
  loadConsNotes(cons);
  loadDetailKpis(cons);
}

function downloadNotesPdf(cons) {
  const month = document.getElementById('cons-notes-month').value;
  if (!month) { showToast('Sélectionnez un mois.', 'err'); return; }
  const p = new URLSearchParams({ month, consultantEmail: cons.email });
  window.open(`${base()}/expenses/report/pdf/month?${p}`, '_blank');
}

function downloadNotesExcel(cons) {
  const month = document.getElementById('cons-notes-month').value;
  if (!month) { showToast('Sélectionnez un mois.', 'err'); return; }
  const p = new URLSearchParams({ month, consultantEmail: cons.email });
  window.open(`${base()}/expenses/report/excel?${p}`, '_blank');
}

// ============================================================
// FACTURES
// ============================================================
let loadedInvoices = [];

async function loadConsInvoices(cons) {
  const start    = document.getElementById('cons-inv-start').value;
  const end      = document.getElementById('cons-inv-end').value;
  const statusEl = document.getElementById('cons-inv-status');
  const resultEl = document.getElementById('cons-inv-result');
  const delAllBtn = document.getElementById('cons-inv-delete-all');

  setStatus(statusEl, 'Chargement…');
  resultEl.style.display = 'none';
  delAllBtn.style.display = 'none';
  loadedInvoices = [];

  try {
    const p   = new URLSearchParams({ start, end, consultantEmail: cons?.email || '' });
    const res = await fetch(`${invoiceBase()}/invoices/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    if (!items.length) { setStatus(statusEl, 'Aucune facture trouvee.', ''); return; }

    loadedInvoices = items;
    setStatus(statusEl, `${items.length} facture${items.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.replaceChildren(renderInvoiceList(items, cons));
    resultEl.style.display = '';
    delAllBtn.style.display = '';
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

async function downloadInvoiceFile(inv, format) {
  const body = JSON.stringify({
    billingMonth:      inv.billingMonth      || '',
    sellerCompanyName: inv.sellerCompanyName || '',
    invoiceName:       inv.invoiceName       || '',
  });
  const url      = `${invoiceBase()}/invoices/${format}`;
  const mimeType = format === 'pdf'
    ? 'application/pdf'
    : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  const ext      = format === 'pdf' ? 'pdf' : 'xlsx';
  try {
    const res = await fetch(url, {
      method:  'POST',
      headers: adminHeaders(),
      body,
    });
    if (!res.ok) { showToast(`Erreur telechargement ${format.toUpperCase()} : HTTP ${res.status}`, 'err'); return; }
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(new Blob([blob], { type: mimeType }));
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = `${inv.invoiceName || 'facture'}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(blobUrl), 5000);
  } catch (e) {
    showToast(`Erreur telechargement : ${e.message}`, 'err');
  }
}

const INV_STATUS_LABEL = { EN_ATTENTE: 'En attente', ENVOYEE: 'Envoyée', PAYEE: 'Payée', EN_RETARD: 'En retard' };
const INV_STATUS_COLOR = { EN_ATTENTE: 'grey', ENVOYEE: 'orange', PAYEE: 'green', EN_RETARD: 'red' };

function renderInvoiceList(items, cons) {
  const rows = items.map((inv, idx) => {
    const name    = inv.invoiceName || '—';
    const bm      = inv.billingMonth || '—';
    const client  = inv.clientCompanyName || '—';
    const total   = inv.totalTtc != null ? Number(inv.totalTtc).toFixed(2) + ' ' + (inv.currency || 'EUR') : '—';
    const status  = inv.paymentStatus || 'EN_ATTENTE';
    const slabel  = INV_STATUS_LABEL[status] || status;
    const scolor  = INV_STATUS_COLOR[status] || 'grey';
    const due     = inv.paymentDueDate ? ` · Éch. ${escapeHtml(inv.paymentDueDate)}` : '';
    const invId   = inv.id || '';

    const actionSent = status === 'EN_ATTENTE'
      ? `<button class="btn-secondary inv-mark-sent" data-id="${escapeHtml(invId)}" data-inv-idx="${idx}" style="padding:4px 10px;font-size:11px" title="Marquer comme envoyée">Envoyée</button>` : '';
    const actionPaid = (status === 'ENVOYEE' || status === 'EN_RETARD')
      ? `<button class="btn-primary inv-mark-paid" data-id="${escapeHtml(invId)}" data-inv-idx="${idx}" style="padding:4px 10px;font-size:11px;background:linear-gradient(120deg,#16a34a,#15803d)" title="Marquer comme payée">Payée ✓</button>` : '';

    return `
      <div class="approval-row" data-inv-idx="${idx}">
        <div class="approval-meta">
          <span class="approval-name">${escapeHtml(name)}</span>
          <span class="approval-detail">${escapeHtml(bm)} — ${escapeHtml(client)}${due}</span>
        </div>
        <div class="approval-right">
          <span class="status-badge ${scolor}">${escapeHtml(slabel)}</span>
          <span class="approval-amount">${escapeHtml(total)}</span>
          ${actionSent}
          ${actionPaid}
          <button class="btn-secondary inv-dl-pdf" data-inv-idx="${idx}" style="padding:4px 10px;font-size:11px">PDF</button>
          <button class="btn-inv-delete inv-delete" data-inv-idx="${idx}" title="Supprimer">
            <svg width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>
          </button>
        </div>
      </div>`;
  }).join('');

  const wrap = document.createElement('div');
  wrap.className = 'approval-list';
  wrap.innerHTML = rows;

  wrap.querySelectorAll('.inv-dl-pdf').forEach(btn => {
    btn.addEventListener('click', () => downloadInvoiceFile(items[+btn.dataset.invIdx], 'pdf'));
  });
  wrap.querySelectorAll('.inv-delete').forEach(btn => {
    btn.addEventListener('click', () => deleteInvoice(items[+btn.dataset.invIdx], cons));
  });
  wrap.querySelectorAll('.inv-mark-sent').forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${invoiceBase()}/invoices/${btn.dataset.id}/mark-sent`, { method: 'PUT', headers: adminHeaders() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        showToast('Facture marquée comme envoyée.', 'ok');
        loadConsInvoices(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = 'Envoyée'; }
    });
  });
  wrap.querySelectorAll('.inv-mark-paid').forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${invoiceBase()}/invoices/${btn.dataset.id}/mark-paid`, {
          method: 'PUT', headers: adminHeaders(), body: JSON.stringify({})
        });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        showToast('Facture marquée comme payée ✓', 'ok');
        loadConsInvoices(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = 'Payée ✓'; }
    });
  });

  return wrap;
}

async function deleteInvoice(inv, cons) {
  if (!confirm(`Supprimer la facture ${inv.invoiceName || '—'} ?`)) return;
  try {
    const res = await fetch(`${invoiceBase()}/invoices/delete`, {
      method:  'POST',
      headers: adminHeaders(),
      body:    JSON.stringify({
        invoiceName:       inv.invoiceName       || '',
        billingMonth:      inv.billingMonth       || '',
        sellerCompanyName: inv.sellerCompanyName  || '',
      }),
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    showToast(`Facture ${inv.invoiceName} supprimee.`, 'ok');
    loadConsInvoices(cons);
  } catch (e) {
    showToast('Erreur suppression : ' + e.message, 'err');
  }
}

// ============================================================
// MAIN NAV TABS — Consultants | Projets
// ============================================================
function initMainNavTabs() {
  document.querySelectorAll('.main-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.main-tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const view = btn.dataset.view;
      document.getElementById('dashboard-view').style.display   = view === 'dashboard'    ? '' : 'none';
      document.getElementById('cons-grid-view').style.display   = view === 'consultants' ? '' : 'none';
      document.getElementById('cons-detail-view').style.display = 'none';
      document.getElementById('projects-view').style.display    = view === 'projects'    ? '' : 'none';
      document.getElementById('clients-view').style.display     = view === 'clients'     ? '' : 'none';
      if (view === 'dashboard') loadDashboard();
      if (view === 'projects')  loadProjects();
      if (view === 'clients')   loadClients();
    });
  });
}

// ============================================================
// DASHBOARD MANAGER
// ============================================================

(function initDashboard() {
  const curMonth = new Date().toISOString().substring(0, 7);
  const monthEl  = document.getElementById('dash-month');
  if (monthEl) monthEl.value = curMonth;
  document.getElementById('dash-load-btn')?.addEventListener('click', loadDashboard);
})();

async function loadDashboard() {
  const month    = document.getElementById('dash-month')?.value || new Date().toISOString().substring(0, 7);
  const statusEl = document.getElementById('dash-status');
  const kpisEl   = document.getElementById('dash-kpis');
  const tableEl  = document.getElementById('dash-table-wrap');

  setStatus(statusEl, 'Chargement…');
  kpisEl.style.display  = 'none';
  tableEl.style.display = 'none';

  try {
    const res = await fetch(`${base()}/dashboard/summary?month=${encodeURIComponent(month)}`, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const d = await res.json();

    kpisEl.innerHTML = renderDashKpis(d);
    kpisEl.style.display = '';

    tableEl.innerHTML = renderDashTable(d.consultants || []);
    tableEl.style.display = '';

    setStatus(statusEl, '');
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function renderDashKpis(d) {
  const fmtK = v => {
    if (v >= 1000000) return (v/1000000).toFixed(1).replace('.0','') + ' M€';
    if (v >= 1000)    return Math.round(v/1000) + ' k€';
    return new Intl.NumberFormat('fr-FR',{maximumFractionDigits:0}).format(v) + ' €';
  };
  const hasMarge   = d.margeNette > 0 || d.coutTotal > 0;
  const totalConts = (d.consultants || []).length;
  const valide     = (d.consultants || []).filter(c => c.craStatus === 'VALIDE').length;
  const soumis     = (d.consultants || []).filter(c => c.craStatus === 'SOUMIS').length;
  const absent     = (d.consultants || []).filter(c => c.craStatus === 'ABSENT').length;
  const actColor   = d.tauxActiviteMoyen >= 80 ? '#4ade80' : d.tauxActiviteMoyen >= 60 ? '#fb923c' : '#f87171';
  const mrgColor   = d.tauxMargeGlobal  >= 30 ? '#4ade80' : d.tauxMargeGlobal  >= 15 ? '#fb923c' : '#f87171';

  const kpiCard = (eyebrow, big, bigColor, sub, subColor='var(--text-muted)', accent='') => `
    <div class="dkpi-card${accent ? ' dkpi-accent' : ''}">
      <div class="dkpi-eyebrow">${eyebrow}</div>
      <div class="dkpi-big" style="color:${bigColor}">${big}</div>
      <div class="dkpi-sub" style="color:${subColor}">${sub}</div>
    </div>`;

  const bar = (pct, color) => {
    const c = 2*Math.PI*28, off = c*(1-Math.min(pct,100)/100);
    return `<svg width="72" height="72" viewBox="0 0 72 72" style="flex-shrink:0">
      <circle cx="36" cy="36" r="28" fill="none" stroke="rgba(255,255,255,.07)" stroke-width="6"/>
      <circle cx="36" cy="36" r="28" fill="none" stroke="${color}" stroke-width="6"
        stroke-dasharray="${c.toFixed(1)}" stroke-dashoffset="${off.toFixed(1)}"
        stroke-linecap="round" transform="rotate(-90 36 36)"/>
      <text x="36" y="40" text-anchor="middle" font-size="13" font-weight="700" fill="${color}">${pct.toFixed(0)}%</text>
    </svg>`;
  };

  const alertRow = d.facturesEnRetard > 0 || d.fraisEnAttenteCount > 0 ? `
    <div class="dkpi-alerts">
      ${d.facturesEnRetard > 0    ? `<span class="dkpi-pill red">⚠️ ${d.facturesEnRetard} facture${d.facturesEnRetard>1?'s':''} en retard · ${fmtK(d.montantFacturesEnRetard)}</span>` : ''}
      ${d.fraisEnAttenteCount > 0 ? `<span class="dkpi-pill orange">🧾 ${d.fraisEnAttenteCount} frais à approuver · ${fmtK(d.fraisEnAttente)}</span>` : ''}
    </div>` : '';

  return `
  <div class="dkpi-grid">
    ${kpiCard('CA FACTURABLE', fmtK(d.caFacturable), 'var(--accent)',
      hasMarge ? `Coût : ${fmtK(d.coutTotal)}` : `${totalConts} consultant${totalConts>1?'s':''}`, '', 'yes')}

    ${hasMarge
      ? kpiCard('MARGE NETTE', fmtK(d.margeNette), mrgColor,
          d.nbConsultantsAvecCout < totalConts
            ? `Taux ${d.tauxMargeGlobal.toFixed(0)}% · ${d.nbConsultantsAvecCout}/${totalConts} consultants`
            : `Taux de marge ${d.tauxMargeGlobal.toFixed(0)}%`, mrgColor)
      : kpiCard('MARGE NETTE', '—', 'var(--text-muted)', 'Prix d\'achat non renseigné')}

    <div class="dkpi-card dkpi-gauges">
      <div class="dkpi-eyebrow">TAUX D'ACTIVITÉ</div>
      <div style="display:flex;align-items:center;gap:12px;margin-top:6px">
        ${bar(d.tauxActiviteMoyen, actColor)}
        <div>
          <div style="font-size:22px;font-weight:800;color:${actColor}">${d.tauxActiviteMoyen.toFixed(0)}%</div>
          <div style="font-size:11px;color:var(--text-muted);margin-top:2px">${totalConts} consultant${totalConts>1?'s':''}</div>
          <div style="font-size:11px;color:var(--text-muted)">Objectif ≥ 80%</div>
        </div>
      </div>
    </div>

    <div class="dkpi-card">
      <div class="dkpi-eyebrow">PIPELINE CRA</div>
      <div class="dkpi-pipeline">
        <div><span style="color:#4ade80;font-size:26px;font-weight:800">${valide}</span><br><small>Validés</small></div>
        <div><span style="color:#fb923c;font-size:26px;font-weight:800">${soumis}</span><br><small>En attente</small></div>
        <div><span style="color:#6b7280;font-size:26px;font-weight:800">${absent}</span><br><small>Absents</small></div>
      </div>
    </div>
  </div>
  ${alertRow}`;
}

function renderDashTable(consultants) {
  if (!consultants.length) return '<p style="padding:24px;color:var(--text-muted);font-size:13px">Aucun consultant actif ce mois.</p>';
  const hasMarge = consultants.some(c => c.dailyCost != null);
  const maxCA    = Math.max(...consultants.map(c => c.caFacturable || 0), 1);
  const fmtE     = v => v != null && v > 0 ? new Intl.NumberFormat('fr-FR',{maximumFractionDigits:0}).format(v) + ' €' : '—';
  const DOT  = { VALIDE:'#4ade80', SOUMIS:'#fb923c', BROUILLON:'#6b7280', REFUSE:'#f87171', ABSENT:'#374151' };
  const LBLS = { VALIDE:'Validé', SOUMIS:'Soumis', BROUILLON:'Brouillon', REFUSE:'Refusé', ABSENT:'Absent' };

  let html = `<div class="dconsult-table">
    <div class="dconsult-header">
      <span>Consultant</span><span>Jours</span><span>Activité</span>
      <span>CA${hasMarge?' / Marge':''}</span><span>Barre CA</span>
    </div>`;

  consultants.forEach(c => {
    const taux    = c.tauxActivite || 0;
    const tColor  = taux >= 80 ? '#4ade80' : taux >= 60 ? '#fb923c' : '#f87171';
    const caW     = Math.round((c.caFacturable / maxCA) * 100);
    const coutW   = c.coutTotal != null ? Math.round((c.coutTotal / maxCA) * 100) : 0;
    const tm      = c.tauxMarge;
    const tmColor = tm != null ? (tm >= 30 ? '#4ade80' : tm >= 15 ? '#fb923c' : '#f87171') : '#6b7280';
    const dot     = DOT[c.craStatus] || '#6b7280';
    const lbl     = LBLS[c.craStatus] || c.craStatus;
    const ini     = (c.name || c.email).split(' ').map(w=>w[0]).join('').substring(0,2).toUpperCase();

    html += `<div class="dconsult-row">
      <div style="display:flex;align-items:center;gap:10px">
        <div class="dash-avatar-sm">${ini}</div>
        <div>
          <div style="font-weight:600;font-size:13px">${escapeHtml(c.name||c.email)}</div>
          <div style="display:flex;align-items:center;gap:5px;margin-top:2px">
            <span style="width:6px;height:6px;border-radius:50%;background:${dot};flex-shrink:0;display:inline-block"></span>
            <span style="font-size:11px;color:var(--text-muted)">${lbl}</span>
          </div>
        </div>
      </div>
      <span style="font-weight:700">${c.joursValides%1===0?c.joursValides:c.joursValides.toFixed(1)}j / ${c.joursOuvres}j</span>
      <span style="font-weight:700;color:${tColor}">${taux.toFixed(0)}%</span>
      <div>
        <div style="font-weight:700;font-size:14px">${fmtE(c.caFacturable)}</div>
        ${hasMarge ? `<div style="font-size:11px;color:${tmColor};margin-top:1px">${tm!=null?`${tm.toFixed(0)}% · ${fmtE(c.margeNette)}`:'Prix achat non renseigné'}</div>` : ''}
      </div>
      <div style="padding-right:8px">
        <div style="height:8px;background:rgba(255,255,255,.06);border-radius:4px;position:relative;overflow:hidden">
          <div style="position:absolute;inset:0;width:${caW}%;background:linear-gradient(90deg,#2ce5a7,#14b88a);border-radius:4px"></div>
          ${coutW>0?`<div style="position:absolute;inset:0;width:${coutW}%;background:rgba(251,146,60,.45);border-radius:4px"></div>`:''}
        </div>
        <div style="font-size:10px;color:var(--text-muted);margin-top:3px">${fmtE(c.caFacturable)}${c.coutTotal!=null?' · coût '+fmtE(c.coutTotal):''}</div>
      </div>
    </div>`;
  });
  return html + '</div>';
}

// ============================================================
// PROJECTS — CRUD
// ============================================================
let allProjects = [];

async function loadProjects() {
  const statusEl = document.getElementById('proj-list-status');
  setStatus(statusEl, 'Chargement…');
  try {
    const res = await fetch(`${base()}/projects`, { headers: authHeaders() });
    if (!res.ok) { setStatus(statusEl, 'Erreur lors du chargement.', 'error'); return; }
    allProjects = await res.json();
    setStatus(statusEl, '');
    renderProjectsTable();
  } catch {
    setStatus(statusEl, 'Impossible de joindre le serveur.', 'error');
  }
}

function renderProjectsTable(filter = '') {
  const wrap = document.getElementById('proj-table-wrap');
  const search = document.getElementById('proj-search')?.value.toLowerCase() || filter;
  const filtered = allProjects.filter(p =>
    p.name?.toLowerCase().includes(search) ||
    p.description?.toLowerCase().includes(search)
  );

  if (!filtered.length) {
    wrap.innerHTML = `<p style="color:var(--muted);font-size:13px;margin:16px 0">Aucun projet trouvé.</p>`;
    return;
  }

  wrap.innerHTML = `
    <div class="data-list">
      <div class="data-list-header dl-projects-grid">
        <span>Projet</span>
        <span>Description</span>
        <span></span>
      </div>
      ${filtered.map(p => `
        <div class="data-list-row dl-projects-grid">
          <div class="cell-primary">
            <div class="row-icon ${rowIconVariant(p.name)}">${getInitials(p.name)}</div>
            <span>${escapeHtml(p.name)}</span>
          </div>
          <div class="cell-sub">${escapeHtml(p.description || '—')}</div>
          <div class="cell-actions">
            <button class="btn-row-edit"
              onclick="openEditProject('${escapeHtml(p.id)}','${escapeHtml(p.name)}','${escapeHtml(p.description || '')}')">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              Modifier
            </button>
            <button class="btn-row-delete" title="Supprimer"
              onclick="deleteProject('${escapeHtml(p.id)}')">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/></svg>
            </button>
          </div>
        </div>`).join('')}
    </div>`;
}

function initProjects() {
  document.getElementById('proj-search').addEventListener('input', debounce(() => renderProjectsTable(), 150));

  document.getElementById('proj-add-btn').addEventListener('click', () => {
    document.getElementById('proj-form-id').value    = '';
    document.getElementById('proj-form-name').value  = '';
    document.getElementById('proj-form-desc').value  = '';
    document.getElementById('proj-form-title').textContent = 'Nouveau projet';
    setStatus(document.getElementById('proj-form-status'), '');
    document.getElementById('proj-form-wrap').style.display = '';
    document.getElementById('proj-form-name').focus();
  });

  document.getElementById('proj-form-cancel').addEventListener('click', () => {
    document.getElementById('proj-form-wrap').style.display = 'none';
  });

  document.getElementById('proj-form-save').addEventListener('click', saveProject);
}

function openEditProject(id, name, description) {
  document.getElementById('proj-form-id').value    = id;
  document.getElementById('proj-form-name').value  = name;
  document.getElementById('proj-form-desc').value  = description;
  document.getElementById('proj-form-title').textContent = 'Modifier le projet';
  setStatus(document.getElementById('proj-form-status'), '');
  document.getElementById('proj-form-wrap').style.display = '';
  document.getElementById('proj-form-name').focus();
}

async function saveProject() {
  const id   = document.getElementById('proj-form-id').value.trim();
  const name = document.getElementById('proj-form-name').value.trim();
  const desc = document.getElementById('proj-form-desc').value.trim();
  const statusEl = document.getElementById('proj-form-status');

  if (!name) { setStatus(statusEl, 'Le nom est obligatoire.', 'error'); return; }

  const isEdit  = !!id;
  const url     = isEdit ? `${base()}/projects/${id}` : `${base()}/projects`;
  const method  = isEdit ? 'PUT' : 'POST';
  const restore = setBtnLoading('proj-form-save');

  try {
    const res = await fetch(url, {
      method,
      headers: adminHeaders(),
      body: JSON.stringify({ name, description: desc }),
    });
    restore();
    if (!res.ok) { setStatus(statusEl, 'Erreur lors de la sauvegarde.', 'error'); return; }
    document.getElementById('proj-form-wrap').style.display = 'none';
    showToast(isEdit ? 'Projet modifié.' : 'Projet créé.', 'ok');
    loadProjects();
  } catch {
    restore();
    setStatus(statusEl, 'Impossible de joindre le serveur.', 'error');
  }
}

async function deleteProject(id) {
  if (!confirm('Supprimer ce projet ?')) return;
  try {
    const res = await fetch(`${base()}/projects/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    if (!res.ok) { showToast('Erreur lors de la suppression.', 'error'); return; }
    showToast('Projet supprimé.', 'ok');
    loadProjects();
  } catch {
    showToast('Impossible de joindre le serveur.', 'error');
  }
}

// ============================================================
// TRIOS PROJET / CLIENT / TJM — section assignments
// ============================================================
async function loadConsultantAssignments(cons) {
  if (!cons?.id) return;
  const listEl   = document.getElementById('cons-assignments-list');
  const statusEl = document.getElementById('cons-assignments-status');
  if (listEl) listEl.innerHTML = '<p style="color:var(--muted);font-size:13px">Chargement…</p>';
  if (statusEl) statusEl.textContent = '';
  try {
    const res = await fetch(`${base()}/consultants/${cons.id}/assignments`, { headers: adminHeaders() });
    const assignments = res.ok ? await res.json() : [];
    renderAssignmentsList(assignments);
  } catch {
    if (statusEl) statusEl.textContent = 'Impossible de joindre le serveur.';
    if (listEl)   listEl.innerHTML = '';
  }
}

function renderAssignmentsList(assignments) {
  const listEl = document.getElementById('cons-assignments-list');
  if (!listEl) return;
  if (!assignments.length) {
    listEl.innerHTML = `<p style="color:var(--muted);font-size:13px">Aucun trio assigné.</p>`;
    return;
  }
  listEl.innerHTML = `
    <div class="data-list">
      <div class="data-list-header dl-assign-grid">
        <span>Projet</span>
        <span>Client</span>
        <span>TJM</span>
        <span>Délai</span>
        <span></span>
      </div>
      ${assignments.map(a => `
        <div class="data-list-row dl-assign-grid">
          <div class="cell-primary">
            <div class="row-icon ${rowIconVariant(a.project?.name)}">${getInitials(a.project?.name)}</div>
            <span>${escapeHtml(a.project?.name || '—')}</span>
          </div>
          <div>
            <span class="dl-badge dl-badge-blue">${escapeHtml(a.client?.name || '—')}</span>
          </div>
          <div class="cell-amount">${a.tjm != null ? a.tjm + ' €/j' : '—'}</div>
          <div><span class="dl-badge dl-badge-muted">${a.paymentTermsDays === 45 ? '45j' : a.paymentTermsDays === 60 ? '2 mois' : '1 mois'}</span></div>
          <div class="cell-actions">
            <button class="btn-row-edit"
              onclick="openAssignmentModal(currentConsultant, ${JSON.stringify(a).replace(/"/g,'&quot;')})">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              Modifier
            </button>
            <button class="btn-row-delete"
              onclick="deleteAssignment(currentConsultant, '${escapeHtml(a.id)}', '${escapeHtml(a.project?.name || '')}', '${escapeHtml(a.client?.name || '')}')">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/></svg>
            </button>
          </div>
        </div>`).join('')}
    </div>`;
}

async function openAssignmentModal(_cons, assignment) {
  document.getElementById('assignment-modal-title').textContent = assignment ? 'Modifier le trio' : 'Ajouter un trio';
  document.getElementById('assignment-modal-id').value  = assignment?.id  || '';
  document.getElementById('assignment-tjm').value       = assignment?.tjm != null ? assignment.tjm : '';
  document.getElementById('assignment-daily-cost').value = currentConsultant?.dailyCost != null ? currentConsultant.dailyCost : '';
  document.getElementById('assignment-payment-terms').value = assignment?.paymentTermsDays ?? 30;

  // Load projects & clients in parallel
  const [projRes, cliRes] = await Promise.all([
    fetch(`${base()}/projects`, { headers: adminHeaders() }),
    fetch(`${base()}/clients`,  { headers: adminHeaders() }),
  ]);
  const projects = projRes.ok ? await projRes.json() : [];
  const clients  = cliRes.ok  ? await cliRes.json()  : [];

  const pSel = document.getElementById('assignment-project-select');
  pSel.innerHTML = '<option value="">-- Sélectionner un projet --</option>'
    + projects.map(p => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.name)}</option>`).join('');
  if (assignment?.project?.id) pSel.value = assignment.project.id;

  const cSel = document.getElementById('assignment-client-select');
  cSel.innerHTML = '<option value="">-- Sélectionner un client --</option>'
    + clients.map(c => `<option value="${escapeHtml(c.id)}">${escapeHtml(c.name)}</option>`).join('');
  if (assignment?.client?.id) cSel.value = assignment.client.id;

  document.getElementById('assignment-status').textContent = '';
  document.getElementById('assignment-overlay').classList.remove('hidden');
  document.getElementById('assignment-modal').classList.remove('hidden');
}

function closeAssignmentModal() {
  document.getElementById('assignment-overlay').classList.add('hidden');
  document.getElementById('assignment-modal').classList.add('hidden');
}

async function saveAssignment(cons) {
  const statusEl = document.getElementById('assignment-status');
  if (!cons?.id) {
    statusEl.textContent = 'Consultant non encore synchronisé — rechargez la page.';
    return;
  }
  const id        = document.getElementById('assignment-modal-id').value;
  const projectId = document.getElementById('assignment-project-select').value;
  const clientId  = document.getElementById('assignment-client-select').value;
  const tjm              = parseFloat(document.getElementById('assignment-tjm').value);
  const dailyCostRaw     = document.getElementById('assignment-daily-cost').value.trim();
  const dailyCost        = dailyCostRaw === '' ? null : parseFloat(dailyCostRaw);
  const paymentTermsDays = parseInt(document.getElementById('assignment-payment-terms').value, 10);

  if (!projectId) { statusEl.textContent = 'Le projet est obligatoire.'; return; }
  if (!clientId)  { statusEl.textContent = 'Le client est obligatoire.'; return; }
  if (!tjm || isNaN(tjm) || tjm <= 0) { statusEl.textContent = 'Le TJM est obligatoire et doit être supérieur à 0.'; return; }

  const url     = id ? `${base()}/consultants/${cons.id}/assignments/${id}` : `${base()}/consultants/${cons.id}/assignments`;
  const method  = id ? 'PUT' : 'POST';
  const restore = setBtnLoading('assignment-save');
  try {
    const res = await fetch(url, {
      method,
      headers: adminHeaders(),
      body: JSON.stringify({ projectId, clientId, tjm, paymentTermsDays }),
    });
    restore();
    if (!res.ok) { statusEl.textContent = 'Erreur lors de la sauvegarde.'; return; }

    // Sauvegarder le prix d'achat sur le profil consultant
    if (currentConsultant) {
      currentConsultant.dailyCost = dailyCost;
      const idx = allConsultants.findIndex(c => c.email === currentConsultant.email);
      if (idx >= 0) { allConsultants[idx].dailyCost = dailyCost; saveConsultants(allConsultants); }
      await saveConsultantToBackend(currentConsultant);
    }

    showToast(id ? 'Trio mis à jour.' : 'Trio ajouté.', 'ok');
    closeAssignmentModal();
    loadConsultantAssignments(cons);
  } catch {
    restore();
    statusEl.textContent = 'Impossible de joindre le serveur.';
  }
}

async function deleteAssignment(cons, assignmentId, projectName = '', clientName = '') {
  const label = [projectName, clientName].filter(Boolean).join(' / ');
  if (!confirm(`Supprimer le trio ${label} ?`)) return;
  try {
    const res = await fetch(`${base()}/consultants/${cons.id}/assignments/${assignmentId}`, {
      method: 'DELETE',
      headers: adminHeaders(),
    });
    if (!res.ok) { showToast('Erreur lors de la suppression.', 'error'); return; }
    showToast('Trio supprimé.', 'ok');
    loadConsultantAssignments(cons);
  } catch {
    showToast('Impossible de joindre le serveur.', 'error');
  }
}

async function deleteAllInvoices(cons) {
  if (!loadedInvoices.length) return;
  if (!confirm(`Supprimer les ${loadedInvoices.length} facture${loadedInvoices.length > 1 ? 's' : ''} de ${cons.name} ?`)) return;

  let ok = 0, err = 0;
  for (const inv of loadedInvoices) {
    try {
      const res = await fetch(`${invoiceBase()}/invoices/delete`, {
        method:  'POST',
        headers: adminHeaders(),
        body:    JSON.stringify({
          invoiceName:       inv.invoiceName       || '',
          billingMonth:      inv.billingMonth       || '',
          sellerCompanyName: inv.sellerCompanyName  || '',
        }),
      });
      if (res.ok) ok++; else err++;
    } catch { err++; }
  }
  showToast(`${ok} supprimee${ok > 1 ? 's' : ''}${err ? `, ${err} erreur(s)` : ''}.`, err ? '' : 'ok');
  loadConsInvoices(cons);
}

// ============================================================
// CLIENTS — CRUD
// ============================================================
let allClients = [];

function populateClientSelects(preselectName = '') {
  ['cons-new-clientname', 'cons-invite-clientname'].forEach(id => {
    const sel = document.getElementById(id);
    if (!sel || sel.tagName !== 'SELECT') return;
    const current = preselectName || sel.value;
    sel.innerHTML = '<option value="">-- Sélectionner --</option>' +
      allClients.map(c => `<option value="${escapeHtml(c.name)}"${c.name === current ? ' selected' : ''}>${escapeHtml(c.name)}</option>`).join('');
  });
}

async function loadClients() {
  const statusEl = document.getElementById('client-list-status');
  if (statusEl) setStatus(statusEl, 'Chargement…');
  try {
    const res = await fetch(`${base()}/clients`, { headers: adminHeaders() });
    if (!res.ok) { if (statusEl) setStatus(statusEl, 'Erreur lors du chargement.', 'error'); return; }
    allClients = await res.json();
    allClients.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    if (statusEl) setStatus(statusEl, '');
    renderClientsTable();
    populateClientSelects();
  } catch {
    if (statusEl) setStatus(statusEl, 'Impossible de joindre le serveur.', 'error');
  }
}

function renderClientsTable() {
  const wrap   = document.getElementById('client-table-wrap');
  const search = document.getElementById('client-search')?.value.toLowerCase() || '';
  const filtered = allClients.filter(c =>
    (c.name || '').toLowerCase().includes(search) ||
    (c.contactName || '').toLowerCase().includes(search) ||
    (c.contactEmail || '').toLowerCase().includes(search)
  );

  if (!filtered.length) {
    wrap.innerHTML = `<p style="color:var(--muted);font-size:13px;margin:16px 0">Aucun client trouvé.</p>`;
    return;
  }

  wrap.innerHTML = `
    <div class="data-list">
      <div class="data-list-header dl-clients-grid">
        <span>Client</span>
        <span>Adresse</span>
        <span>RCS / SIRET</span>
        <span>Contact</span>
        <span></span>
      </div>
      ${filtered.map(c => `
        <div class="data-list-row dl-clients-grid">
          <div class="cell-primary">
            <div class="row-icon ${rowIconVariant(c.name)}">${getInitials(c.name)}</div>
            <div>
              <div>${escapeHtml(c.name)}</div>
              ${c.contactEmail ? `<div class="cell-sub">${escapeHtml(c.contactEmail)}</div>` : ''}
            </div>
          </div>
          <div class="cell-sub">${escapeHtml(c.address || '—')}</div>
          <div>${c.rcs ? `<span class="dl-badge dl-badge-muted">${escapeHtml(c.rcs)}</span>` : '<span class="cell-sub">—</span>'}</div>
          <div class="cell-sub">${escapeHtml(c.contactName || '—')}</div>
          <div class="cell-actions">
            <button class="btn-row-edit"
              onclick="openEditClient('${escapeHtml(c.id)}','${escapeHtml(c.name)}','${escapeHtml(c.address||'')}','${escapeHtml(c.rcs||'')}','${escapeHtml(c.contactName||'')}','${escapeHtml(c.contactEmail||'')}')">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
              Modifier
            </button>
            <button class="btn-row-delete" title="Désactiver"
              onclick="deleteClient('${escapeHtml(c.id)}')">
              <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/></svg>
            </button>
          </div>
        </div>`).join('')}
    </div>`;
}

function initClients() {
  document.getElementById('client-search').addEventListener('input', debounce(() => renderClientsTable(), 150));

  document.getElementById('client-add-btn').addEventListener('click', () => {
    document.getElementById('client-form-id').value           = '';
    document.getElementById('client-form-name').value         = '';
    document.getElementById('client-form-address').value      = '';
    document.getElementById('client-form-rcs').value          = '';
    document.getElementById('client-form-contact-name').value = '';
    document.getElementById('client-form-contact-email').value = '';
    document.getElementById('client-form-title').textContent  = 'Nouveau client';
    setStatus(document.getElementById('client-form-status'), '');
    document.getElementById('client-form-wrap').style.display = '';
    document.getElementById('client-form-name').focus();
  });

  document.getElementById('client-form-cancel').addEventListener('click', () => {
    document.getElementById('client-form-wrap').style.display = 'none';
  });

  document.getElementById('client-form-save').addEventListener('click', saveClient);
}

function openEditClient(id, name, address, rcs, contactName, contactEmail) {
  document.getElementById('client-form-id').value            = id;
  document.getElementById('client-form-name').value          = name;
  document.getElementById('client-form-address').value       = address;
  document.getElementById('client-form-rcs').value           = rcs;
  document.getElementById('client-form-contact-name').value  = contactName;
  document.getElementById('client-form-contact-email').value = contactEmail;
  document.getElementById('client-form-title').textContent   = 'Modifier le client';
  setStatus(document.getElementById('client-form-status'), '');
  document.getElementById('client-form-wrap').style.display  = '';
  document.getElementById('client-form-name').focus();
}

async function saveClient() {
  const id           = document.getElementById('client-form-id').value.trim();
  const name         = document.getElementById('client-form-name').value.trim();
  const address      = document.getElementById('client-form-address').value.trim();
  const rcs          = document.getElementById('client-form-rcs').value.trim();
  const contactName  = document.getElementById('client-form-contact-name').value.trim();
  const contactEmail = document.getElementById('client-form-contact-email').value.trim();
  const statusEl     = document.getElementById('client-form-status');

  if (!name) { setStatus(statusEl, 'Le nom est obligatoire.', 'error'); return; }

  const isEdit  = !!id;
  const url     = isEdit ? `${base()}/clients/${id}` : `${base()}/clients`;
  const method  = isEdit ? 'PUT' : 'POST';
  const restore = setBtnLoading('client-form-save');

  try {
    const res = await fetch(url, {
      method,
      headers: adminHeaders(),
      body: JSON.stringify({ name, address, rcs, contactName, contactEmail }),
    });
    restore();
    if (!res.ok) { setStatus(statusEl, 'Erreur lors de la sauvegarde.', 'error'); return; }
    document.getElementById('client-form-wrap').style.display = 'none';
    allClients = [];  // force reload
    showToast(isEdit ? 'Client modifié.' : 'Client créé.', 'ok');
    loadClients();
  } catch {
    restore();
    setStatus(statusEl, 'Impossible de joindre le serveur.', 'error');
  }
}

async function deleteClient(id) {
  if (!confirm('Désactiver ce client ?')) return;
  try {
    const res = await fetch(`${base()}/clients/${id}`, {
      method: 'DELETE',
      headers: adminHeaders(),
    });
    if (!res.ok) { showToast('Erreur lors de la désactivation.', 'error'); return; }
    allClients = allClients.filter(c => c.id !== id);
    showToast('Client désactivé.', 'ok');
    renderClientsTable();
  } catch {
    showToast('Impossible de joindre le serveur.', 'error');
  }
}

// ============================================================
// CONGÉS — ADMIN
// ============================================================

async function loadConsLeaves(cons) {
  const statusEl  = document.getElementById('cons-leave-status');
  const listEl    = document.getElementById('cons-leave-list');
  const balanceEl = document.getElementById('cons-leave-balance');

  setStatus(statusEl, 'Chargement…');
  listEl.innerHTML = '';

  try {
    // Solde
    const year = new Date().getFullYear();
    const bRes = await fetch(`${base()}/leaves/balance?consultantEmail=${encodeURIComponent(cons.email)}&year=${year}`, { headers: authHeaders() });
    if (bRes.ok) {
      const b = await bRes.json();
      balanceEl.innerHTML = `
        <span style="color:var(--accent);font-weight:700">CP : ${Number(b.cpRemaining).toFixed(1)}j restants</span>
        <span style="color:var(--accent-2);font-weight:700">RTT : ${Number(b.rttRemaining).toFixed(1)}j restants</span>`;
    }

    // Demandes
    const res = await fetch(`${base()}/leaves/mine?consultantEmail=${encodeURIComponent(cons.email)}`, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const items = await res.json();
    setStatus(statusEl, '');

    if (!items.length) { listEl.innerHTML = '<p class="muted-sm" style="padding:12px 0">Aucune demande de congé.</p>'; return; }

    const STATUS_COLOR = { DEMANDEE: 'orange', APPROUVEE: 'green', REFUSEE: 'red' };
    const STATUS_LABEL = { DEMANDEE: 'En attente', APPROUVEE: 'Approuvée', REFUSEE: 'Refusée' };
    const COLS = '90px 90px 70px 70px 100px 1fr 120px';

    let html = `<div class="data-list">
      <div class="data-list-header" style="grid-template-columns:${COLS}">
        <span>Du</span><span>Au</span><span>Jours</span><span>Type</span><span>Statut</span><span>Motif</span><span>Actions</span>
      </div>`;

    items.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || '')).forEach(l => {
      const color  = STATUS_COLOR[l.status] || 'grey';
      const label  = STATUS_LABEL[l.status] || l.status;
      const actions = l.status === 'DEMANDEE'
        ? `<button class="btn-approve-leave btn-primary" data-id="${escapeHtml(l.id)}" style="padding:4px 10px;font-size:11px">✓ Approuver</button>
           <button class="btn-refuse-leave btn-secondary" data-id="${escapeHtml(l.id)}" style="padding:4px 8px;font-size:11px;color:#f87171">✗</button>`
        : '';
      html += `<div class="data-list-row" style="grid-template-columns:${COLS}">
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(l.startDate || '')}</span>
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(l.endDate || '')}</span>
        <span style="font-weight:600">${l.daysCount}j</span>
        <span class="expense-type-cell">${escapeHtml(l.type || '')}</span>
        <span><span class="status-badge ${color}">${escapeHtml(label)}</span></span>
        <span class="cell-muted" style="font-size:12px">${escapeHtml(l.reason || l.refusedReason || '')}</span>
        <div style="display:flex;gap:6px">${actions}</div>
      </div>`;
    });
    html += '</div>';
    listEl.innerHTML = html;

    listEl.querySelectorAll('.btn-approve-leave').forEach(btn => {
      btn.addEventListener('click', async () => {
        btn.disabled = true; btn.textContent = '…';
        try {
          const res = await fetch(`${base()}/leaves/${btn.dataset.id}/approve`, {
            method: 'PUT', headers: adminHeaders(),
            body: JSON.stringify({ approvedBy: adminUser?.email || 'Admin' }),
          });
          if (!res.ok) throw new Error('HTTP ' + res.status);
          showToast('Congé approuvé.', 'ok');
          loadConsLeaves(cons);
        } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✓ Approuver'; }
      });
    });

    listEl.querySelectorAll('.btn-refuse-leave').forEach(btn => {
      btn.addEventListener('click', async () => {
        const reason = prompt('Motif du refus (optionnel) :') || '';
        btn.disabled = true;
        try {
          const res = await fetch(`${base()}/leaves/${btn.dataset.id}/refuse`, {
            method: 'PUT', headers: adminHeaders(),
            body: JSON.stringify({ reason }),
          });
          if (!res.ok) throw new Error('HTTP ' + res.status);
          showToast('Congé refusé.', 'err');
          loadConsLeaves(cons);
        } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; }
      });
    });

  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}
