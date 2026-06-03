// ============================================================
// KEYCLOAK CONFIG — lue depuis window.APP_CONFIG (config.js)
// ============================================================
const _cfg = globalThis.APP_CONFIG || {};
const DEFAULT_KEYCLOAK_URL = _cfg.keycloakUrl || 'http://localhost:30080';

function keycloakUrl() {
  return DEFAULT_KEYCLOAK_URL.replace(/\/$/, '');
}

let _keycloak = null;

async function initKeycloak() {
  _keycloak = new Keycloak({
    url:      keycloakUrl(),
    realm:    _cfg.keycloakRealm    || 'ia-insight',
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

  // Rafraîchit le token 30s avant expiration
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
  _keycloak?.logout({ redirectUri: globalThis.location.origin });
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
const DEFAULT_BASE         = _cfg.apiBase     || 'http://localhost:8081';
const DEFAULT_INVOICE_BASE = _cfg.invoiceBase || 'http://localhost:8083';

function base() {
  return DEFAULT_BASE.replace(/\/$/, '');
}

// En local : invoice-service écoute sur :8083.
// En prod  : Kong reçoit tout sur le même host et route /invoices/* vers invoice-service.
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

function escapeHtml(s) {
  return String(s ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
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
// BOOT — Keycloak puis app
// ============================================================
function startApp() {
  const user = getSession();
  if (!user) return; // ne devrait pas arriver : Keycloak force le login



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
  loadSellerSettings();
}

// app.js est injecté dynamiquement après le chargement de keycloak.js —
// DOMContentLoaded a déjà tiré, on invoque directement.
(async () => {
  try {
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
    const company = 'IA-INSIGHT';
    const res = await fetch(`${base()}/settings/seller?company=${encodeURIComponent(company)}`, { headers: authHeaders() });
    if (!res.ok) return;
    sellerSettings = await res.json();
    // pre-fill the drawer form if already open
    fillSettingsForm(sellerSettings);
  } catch { /* backend may not be reachable */ }
}

function fillSettingsForm(s) {
  const v = s || {};
  const get = id => document.getElementById(id);
  if (get('set-company'))     get('set-company').value     = v.companyName       || 'IA-INSIGHT';
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
      companyName:       (document.getElementById('set-company').value     || '').trim() || 'IA-INSIGHT',
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
      if (!res.ok) throw new Error(await res.text());
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
        showToast(n.message, n.type === 'CRA_SUBMITTED' ? 'info' : 'ok');
        const dropdown = document.getElementById('notif-dropdown');
        if (!dropdown.classList.contains('hidden')) loadNotifications();
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
        <span class="notif-icon">${n.type === 'CRA_SUBMITTED' ? '📋' : '💶'}</span>
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
// CONSULTANTS — données localStorage + Weaviate backend
// ============================================================
const CONS_KEY = 'adminConsultants';
const CONS_CACHE_TS_KEY = 'adminConsultants_ts';
const CONS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 min

function isCacheValid() {
  const ts  = parseInt(localStorage.getItem(CONS_CACHE_TS_KEY) || '0', 10);
  const raw = localStorage.getItem(CONS_KEY);
  return !!raw && (Date.now() - ts) < CONS_CACHE_TTL_MS;
}

function setCacheTimestamp() {
  localStorage.setItem(CONS_CACHE_TS_KEY, Date.now().toString());
}

function clearConsultantsCache() {
  localStorage.removeItem(CONS_KEY);
  localStorage.removeItem(CONS_CACHE_TS_KEY);
}

const DEFAULT_CONSULTANTS = [
  { name: 'Alice Martin',    email: 'alice.martin@ia-insight.fr',    role: 'Salarié',   company: 'IA-INSIGHT', clientName: 'INFOGENE DIGITAL', tjm: 0, active: true },
  { name: 'Bob Dupont',      email: 'bob.dupont@ia-insight.fr',      role: 'Salarié',   company: 'IA-INSIGHT', clientName: 'INFOGENE DIGITAL', tjm: 0, active: true },
  { name: 'Charlie Bernard', email: 'charlie.bernard@freelance.com', role: 'Freelance', company: 'IA-INSIGHT', clientName: '', tjm: 0, active: true },
];

function saveConsultants(list) {
  localStorage.setItem(CONS_KEY, JSON.stringify(list));
}

function loadConsultants() {
  try {
    const raw = localStorage.getItem(CONS_KEY);
    let list;
    if (!raw) {
      list = DEFAULT_CONSULTANTS.map(c => ({ ...c }));
    } else {
      const stored = JSON.parse(raw);
      list = Array.isArray(stored) && stored.length > 0 ? stored : DEFAULT_CONSULTANTS.map(c => ({ ...c }));
    }
    // Merge: ensure default consultants are always present (by email)
    const emails = new Set(list.map(c => c.email));
    DEFAULT_CONSULTANTS.forEach(d => { if (!emails.has(d.email)) list.push({ ...d }); });
    // Normalize: company defaults to 'IA-INSIGHT' if missing or blank
    list.forEach(c => {
      if (!c.company?.trim()) c.company = 'IA-INSIGHT';
      if (c.clientName    === undefined) c.clientName    = '';
      if (c.clientAddress === undefined) c.clientAddress = '';
      if (c.clientRcs     === undefined) c.clientRcs     = '';
      if (c.active        === undefined) c.active        = true;
      if (c.tjm           === undefined || c.tjm === null) c.tjm = 0;
    });
    return list;
  } catch {
    return DEFAULT_CONSULTANTS.map(c => ({ ...c }));
  }
}

async function saveConsultantToBackend(cons) {
  try {
    await fetch(`${base()}/consultants/profiles`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(cons),
    });
  } catch { /* silent — local cache is already updated */ }
}

/**
 * On first load (or after cache expiry), fetch all consultant profiles from Weaviate.
 * Merges local-only consultants (not yet synced) and bootstraps DEFAULT_CONSULTANTS
 * to Weaviate if it comes back empty.
 */
async function initConsultantsData() {
  if (isCacheValid()) return; // cache fresh, nothing to do
  try {
    const res = await fetch(`${base()}/consultants/profiles?company=IA-INSIGHT`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const list = await res.json();

    if (list.length > 0) {
      // Merge: keep any local-only consultants not yet synced
      const backendEmails = new Set(list.map(c => c.email));
      const localOnly = allConsultants.filter(c => !backendEmails.has(c.email));
      allConsultants = [...list, ...localOnly];
      // Normalize
      allConsultants.forEach(c => {
        if (!c.company?.trim()) c.company = 'IA-INSIGHT';
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
      // Weaviate is empty — bootstrap DEFAULT_CONSULTANTS to it
      for (const cons of DEFAULT_CONSULTANTS) {
        saveConsultantToBackend(cons);
      }
      setCacheTimestamp();
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

  document.getElementById('cons-search').addEventListener('input', e => {
    renderConsultantsGrid(e.target.value);
  });

  document.getElementById('cons-add-btn').addEventListener('click', () => {
    document.getElementById('cons-add-form').style.display = '';
    document.getElementById('cons-add-btn').style.display  = 'none';
  });

  document.getElementById('cons-add-cancel').addEventListener('click', () => {
    document.getElementById('cons-add-form').style.display = 'none';
    document.getElementById('cons-add-btn').style.display  = '';
    ['cons-new-name','cons-new-email','cons-new-clientname'].forEach(id => document.getElementById(id).value = '');
  });

  // Edit form
  document.getElementById('cons-edit-save').addEventListener('click', () => {
    if (!editingConsEmail) return;
    const clientName    = document.getElementById('cons-edit-clientname').value.trim();
    const clientAddress = document.getElementById('cons-edit-clientaddress').value.trim();
    const clientRcs     = document.getElementById('cons-edit-clientrcs').value.trim();
    const tjmVal        = parseFloat(document.getElementById('cons-edit-tjm').value);
    const idx = allConsultants.findIndex(c => c.email === editingConsEmail);
    if (idx >= 0) {
      allConsultants[idx].clientName    = clientName;
      allConsultants[idx].clientAddress = clientAddress;
      allConsultants[idx].clientRcs     = clientRcs;
      allConsultants[idx].tjm           = isNaN(tjmVal) ? (allConsultants[idx].tjm || 0) : tjmVal;
      saveConsultants(allConsultants);
      saveConsultantToBackend(allConsultants[idx]);
      if (currentConsultant?.email === editingConsEmail) {
        currentConsultant = allConsultants[idx];
        document.getElementById('cons-detail-clientname').textContent = clientName || '—';
        document.getElementById('cons-clientname-input').value = clientName;
        // Refresh read-only display spans in detail view
        document.getElementById('cons-tjm-display').textContent             = allConsultants[idx].tjm || '—';
        document.getElementById('cons-clientaddress-display').textContent   = clientAddress || '—';
        document.getElementById('cons-clientrcs-display').textContent       = clientRcs     || '—';
      }
      showToast(`Client mis à jour : ${clientName || '—'}`, 'ok');
    }
    closeEditModal();
    renderConsultantsGrid(document.getElementById('cons-search').value);
  });

  document.getElementById('cons-edit-cancel').addEventListener('click', closeEditModal);
  document.getElementById('cons-edit-modal-close').addEventListener('click', closeEditModal);
  document.getElementById('cons-edit-overlay').addEventListener('click', closeEditModal);

  document.getElementById('cons-add-save').addEventListener('click', () => {
    const name       = document.getElementById('cons-new-name').value.trim();
    const email      = document.getElementById('cons-new-email').value.trim().toLowerCase();
    const role       = document.getElementById('cons-new-role').value;
    const company    = document.getElementById('cons-new-company').value.trim() || 'IA-INSIGHT';
    const clientName = document.getElementById('cons-new-clientname').value.trim();
    if (!name || !email) { showToast('Nom et email requis.', 'err'); return; }
    const newCons = { name, email, role, company, clientName, clientAddress: '', clientRcs: '', tjm: 0, active: true };
    allConsultants.push(newCons);
    saveConsultants(allConsultants);
    saveConsultantToBackend(newCons);
    document.getElementById('cons-add-form').style.display = 'none';
    document.getElementById('cons-add-btn').style.display  = '';
    ['cons-new-name','cons-new-email'].forEach(id => document.getElementById(id).value = '');
    renderConsultantsGrid();
    showToast(`${name} ajouté.`, 'ok');
  });

  document.getElementById('cons-back-btn').addEventListener('click', () => {
    // Flush any unsaved field edits before leaving the detail view
    flushDetailEdits();
    document.getElementById('cons-detail-view').style.display = 'none';
    document.getElementById('cons-grid-view').style.display   = '';
    currentConsultant = null;
  });

  document.getElementById('cons-edit-detail-btn').addEventListener('click', () => {
    if (currentConsultant) openEditConsultant(currentConsultant);
  });

  document.getElementById('cons-disable-btn').addEventListener('click', () => {
    if (!currentConsultant) return;
    const isActive = currentConsultant.active !== false;
    const action   = isActive ? 'Désactiver' : 'Réactiver';
    if (!confirm(`${action} ${currentConsultant.name} ?`)) return;
    const idx = allConsultants.findIndex(c => c.email === currentConsultant.email);
    if (idx >= 0) {
      allConsultants[idx].active = !isActive;
      currentConsultant = allConsultants[idx];
      saveConsultants(allConsultants);
      saveConsultantToBackend(allConsultants[idx]);
      updateDisableBtn(currentConsultant);
      renderConsultantsGrid();
      showToast(`${currentConsultant.name} ${isActive ? 'désactivé' : 'réactivé'}.`, 'ok');
    }
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
  const list = q ? allConsultants.filter(c =>
    c.name.toLowerCase().includes(q) || c.email.toLowerCase().includes(q)
  ) : allConsultants;

  if (!list.length) {
    grid.innerHTML = `<div class="cons-empty" style="grid-column:1/-1;padding:48px 24px;text-align:center;color:var(--muted);border:1px dashed var(--border);border-radius:var(--radius)">
      Aucun consultant trouvé.</div>`;
    return;
  }

  grid.innerHTML = list.map(c => {
    const inactive = c.active === false;
    return `
    <div class="consultant-card${inactive ? ' cons-card-inactive' : ''}" data-email="${escapeHtml(c.email)}">
      <div class="card-inner">
        <div class="cons-avatar" style="background:${avatarColor(c.name)}">${initials(c.name)}</div>
        <p class="cons-card-name">${escapeHtml(c.name)}</p>
        <p class="cons-card-email">${escapeHtml(c.email)}</p>
        <div class="cons-card-badges">
          <span class="cons-role-badge ${c.role.toLowerCase()}">${escapeHtml(c.role)}</span>
          <span class="cons-company-tag">${escapeHtml(c.company)}</span>
          ${inactive ? '<span class="status-badge red" style="font-size:10px;padding:2px 6px">Désactivé</span>' : ''}
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
          <button class="btn-card-toggle${inactive ? ' reactivate' : ''}" data-email="${escapeHtml(c.email)}">
            <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">${inactive ? '<path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/>' : '<circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>'}</svg>
            ${inactive ? 'Réactiver' : 'Désactiver'}
          </button>
        </div>
      </div>
    </div>`;
  }).join('');

  grid.querySelectorAll('.consultant-card').forEach(card => {
    const email = card.dataset.email;
    const cons  = allConsultants.find(c => c.email === email);
    if (!cons) return;
    card.addEventListener('click', () => openConsultantDetail(cons));
    loadCardKpis(cons);
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
  document.getElementById('cons-edit-name-display').textContent = `${cons.name} — ${cons.company}`;
  document.getElementById('cons-edit-clientname').value    = cons.clientName    || '';
  document.getElementById('cons-edit-clientaddress').value = cons.clientAddress || '';
  document.getElementById('cons-edit-clientrcs').value     = cons.clientRcs     || '';
  document.getElementById('cons-edit-tjm').value           = (cons.tjm != null && cons.tjm !== '') ? cons.tjm : '';

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
  if (!confirm(`${isActive ? 'Désactiver' : 'Réactiver'} ${cons.name} ?`)) return;
  const idx = allConsultants.findIndex(c => c.email === cons.email);
  if (idx < 0) return;
  allConsultants[idx].active = !isActive;
  saveConsultants(allConsultants);
  saveConsultantToBackend(allConsultants[idx]);
  if (currentConsultant?.email === cons.email) {
    currentConsultant = allConsultants[idx];
    updateDisableBtn(currentConsultant);
  }
  renderConsultantsGrid(document.getElementById('cons-search')?.value || '');
  showToast(`${cons.name} ${isActive ? 'désactivé' : 'réactivé'}.`, 'ok');
}

function updateDisableBtn(cons) {
  const btn     = document.getElementById('cons-disable-btn');
  if (!btn) return;
  const isActive = cons.active !== false;
  btn.innerHTML = isActive
    ? `<svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg> Désactiver`
    : `<svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg> Réactiver`;
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
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.name, company: cons.company });
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
    <div class="cons-kpi-chip"><span class="kpi-v" style="color:${craPending>0?'#fde68a':'var(--accent)'}">${craPending}</span><span class="kpi-l">CRA soumis</span></div>
    <div class="cons-kpi-chip"><span class="kpi-v">${expenseCount}</span><span class="kpi-l">Frais ce mois</span></div>`;
}

/**
 * Saves the current values of all inline-editable fields in the detail view
 * before the user navigates away. Guards against the user not blurring a field.
 */
function flushDetailEdits() {
  if (!currentConsultant) return;
  const idx = allConsultants.findIndex(c => c.email === currentConsultant.email);
  if (idx < 0) return;

  // TJM, clientAddress and clientRcs are now read-only in the detail view.
  // They can only be changed via the card "Modifier" form which saves immediately.
  // Only clientName remains inline-editable here.
  const nameVal = document.getElementById('cons-clientname-input')?.value.trim() ?? '';
  allConsultants[idx].clientName = nameVal || allConsultants[idx].clientName || '';
  saveConsultants(allConsultants);
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
  const clientNameBadge = document.getElementById('cons-detail-clientname');
  clientNameBadge.textContent = cons.clientName || '—';
  clientNameBadge.style.display = '';

  // Client name — éditable inline
  const clientNameInput = document.getElementById('cons-clientname-input');
  clientNameInput.value = cons.clientName || '';
  clientNameInput.onchange = () => {
    const v = clientNameInput.value.trim();
    const idx = allConsultants.findIndex(c => c.email === cons.email);
    if (idx >= 0) {
      allConsultants[idx].clientName = v;
      currentConsultant = allConsultants[idx];
      clientNameBadge.textContent = v || '—';
      saveConsultants(allConsultants);
      saveConsultantToBackend(allConsultants[idx]);
    }
  };

  // TJM — read-only display (edit via card "Modifier" form)
  document.getElementById('cons-tjm-display').textContent =
    (cons.tjm != null && cons.tjm !== '') ? cons.tjm : '—';

  // Client address & RCS — read-only display (edit via card "Modifier" form)
  document.getElementById('cons-clientaddress-display').textContent = cons.clientAddress || '—';
  document.getElementById('cons-clientrcs-display').textContent     = cons.clientRcs     || '—';

  updateDisableBtn(cons);

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

  loadDetailKpis(cons);
  loadConsCra(cons);
}

async function loadDetailKpis(cons) {
  const now   = new Date();
  const month = toMonthStr(now.getFullYear(), now.getMonth());

  try {
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.name, company: cons.company });
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
    const p = new URLSearchParams({ start: month, end: month, consultant: cons.name, company: cons.company });
    const res = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    if (!items.length) {
      setStatus(statusEl, 'Aucun CRA pour ce mois.', '');
      return;
    }

    setStatus(statusEl, `${items.length} CRA trouvé${items.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.innerHTML = renderCraList(items);
    resultEl.style.display = '';

    wireCraActions(resultEl, cons);
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function renderCraList(items) {
  const STATUS_LABEL = {
    BROUILLON: '<span class="status-badge grey">BROUILLON</span>',
    SOUMIS:    '<span class="status-badge orange">SOUMIS</span>',
    VALIDE:    '<span class="status-badge green">VALIDE</span>',
    REFUSE:    '<span class="status-badge red">REFUSÉ</span>',
  };

  return '<div class="approval-list">' + items.map(cra => {
    const days   = cra.totalDays != null
      ? (cra.totalDays % 1 === 0 ? String(cra.totalDays) : Number(cra.totalDays).toFixed(1))
      : '—';
    const status = cra.status || 'BROUILLON';
    const badge  = STATUS_LABEL[status] || `<span class="status-badge grey">${escapeHtml(status)}</span>`;
    const craJson = escapeHtml(JSON.stringify(cra));

    let actions = '';
    if (status === 'SOUMIS') {
      actions = `
        <div class="cra-action-row">
          <button class="btn-approve" data-cra='${craJson}'>✓ Valider</button>
          <div class="refuse-inline">
            <input type="text" class="refuse-reason-input" placeholder="Motif de refus…">
            <button class="btn-refuse" data-cra='${craJson}'>✗ Refuser</button>
          </div>
        </div>`;
    } else if (status === 'VALIDE') {
      actions = `
        <div class="cra-action-row">
          <p class="validated-info" style="margin:0">Validé par <strong>${escapeHtml(cra.validatedBy || '—')}</strong>${cra.validatedAt ? ` le ${formatTs(cra.validatedAt)}` : ''}</p>
          <button class="btn-gen-invoice" data-cra='${craJson}'>📄 Générer la facture</button>
          <button class="btn-reopen-cra" data-cra='${craJson}' title="Remettre en SOUMIS pour re-traitement">↩ Annuler validation</button>
        </div>`;
    } else if (status === 'REFUSE') {
      actions = `
        <div class="cra-action-row">
          <p class="refused-reason" style="margin:0">Motif : ${escapeHtml(cra.refusedReason || '—')}</p>
          <button class="btn-reopen-cra" data-cra='${craJson}' title="Remettre en SOUMIS pour re-traitement">↩ Annuler refus</button>
        </div>`;
    }

    return `
      <div class="approval-row">
        <div class="approval-meta">
          <span class="approval-name">${escapeHtml(cra.billingMonth || '—')}</span>
          <span class="approval-detail">${escapeHtml(cra.clientCompany || '—')} — ${escapeHtml(days)}j</span>
        </div>
        <div class="approval-right">
          ${badge}
          ${actions}
        </div>
      </div>`;
  }).join('') + '</div>';
}

/**
 * Génère automatiquement une facture après validation d'un CRA si le TJM est renseigné.
 * Convention : billingMonth + 2 mois → invoiceDate, invoiceName = F-YYYYMM-01
 */
async function generateInvoiceOnValidation(cra, cons) {
  const tjm = parseFloat(cons.tjm) || 0;
  if (tjm <= 0) return;

  const days = parseFloat(cra.totalDays) || 0;
  if (days <= 0) return;

  // Re-fetch seller settings just before generation — avoids stale/empty cache
  // if backend restarted between login and now.
  await loadSellerSettings();

  try {
    const [y, m] = cra.billingMonth.split('-').map(Number);
    let iy = y, im = m + 2;
    if (im > 12) { im -= 12; iy += 1; }
    const imPad          = String(im).padStart(2,'0');
    const invoiceDateStr = `${iy}-${imPad}-01`;
    const invoiceName    = `F-${iy}${imPad}-01`;
    const dueStr         = `${iy}-${imPad}-${new Date(iy, im, 0).getDate()}`;

    const totalHt  = Math.round(tjm * days * 100) / 100;
    const vatRate  = 0.20;
    const totalTtc = Math.round(totalHt * (1 + vatRate) * 100) / 100;

    const sellerCompanyName = (cons.company || '').trim() || 'IA-INSIGHT';
    const clientCompanyName = (cons.clientName || '').trim() || (cra.clientCompany || '').trim() || sellerCompanyName;

    const payload = {
      invoiceName,
      invoiceDate:     invoiceDateStr,
      billingMonth:    cra.billingMonth,
      sellerCompanyName,
      sellerAddress:   sellerSettings.address         || '',
      sellerRcs:       sellerSettings.rcs             || '',
      clientCompanyName,
      clientAddress:   cons.clientAddress             || '',
      clientRcs:       cons.clientRcs                 || '',
      invoiceTitle:    `Prestation informatique — ${cra.billingMonth}`,
      daysCount:       days,
      unitPriceHt:     tjm,
      totalHt,
      vatRate,
      totalTtc,
      currency:        'EUR',
      paymentDueDate:  dueStr,
      latePaymentClause: '',   // backend uses SellerProfile.latePaymentClause if blank
      notes:           null,
      absencePeriods:  null,
      consultantEmail: cons.email || null,
    };

    const res = await fetch(`${invoiceBase()}/invoices/generate`, {
      method:  'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body:    JSON.stringify(payload),
    });
    if (res.ok) {
      showToast(`Facture ${invoiceName} générée automatiquement (${totalHt.toFixed(2)} € HT).`, 'ok');
    } else {
      showToast(`CRA validé — facture non générée : ${await res.text()}`, 'err');
    }
  } catch (e) {
    showToast(`CRA validé — erreur génération facture : ${e.message}`, 'err');
  }
}

/**
 * Après un refus de CRA, cherche et supprime la facture associée si elle existe.
 * Silencieux si aucune facture n'est trouvée.
 */
async function deleteInvoiceForCra(cra, cons) {
  try {
    const bm      = cra.billingMonth;
    const company = (cons.company || '').trim() || 'IA-INSIGHT';
    const email   = cons.email || '';
    const p       = new URLSearchParams({ start: bm, end: bm, company });
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
        body:    JSON.stringify({ billingMonth: bm, sellerCompanyName: company, invoiceName }),
      });
      if (delRes.ok) showToast(`Facture ${invoiceName} supprimée (CRA refusé).`, 'ok');
    }
  } catch (e) {
    // non-bloquant — le refus CRA a déjà réussi
  }
}

function wireCraActions(container, cons) {
  container.querySelectorAll('.btn-approve').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cra = JSON.parse(btn.dataset.cra);
      btn.disabled = true;
      btn.textContent = '…';
      try {
        const p = new URLSearchParams({ validatedBy: adminUser?.name || 'Admin' });
        const res = await fetch(`${base()}/cra/validate?${p}`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify(cra),
        });
        if (!res.ok) throw new Error(await res.text());
        showToast(`CRA ${cra.billingMonth} validé.`, 'ok');
        await generateInvoiceOnValidation(cra, cons);
        loadConsCra(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✓ Valider'; }
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
        if (!res.ok) throw new Error(await res.text());
        showToast(`CRA ${cra.billingMonth} refusé.`, 'ok');
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
        btn.textContent = '📄 Générer la facture';
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
        if (!res.ok) throw new Error(await res.text());
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

    setStatus(statusEl, `${expenses.length} dépense${expenses.length > 1 ? 's' : ''} trouvée${expenses.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.innerHTML = renderExpenseList(expenses);
    resultEl.style.display = '';

    // "Tout approuver" uniquement si des dépenses sont encore PENDING
    const hasPending = expenses.some(x => !x.approvalStatus || x.approvalStatus === 'PENDING');
    approveAllBtn.style.display = hasPending ? '' : 'none';

    // PDF / Excel uniquement quand toutes les dépenses ont un statut final
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
    APPROVED: '<span class="status-badge green">Approuvé</span>',
    REFUSED:  '<span class="status-badge red">Refusé</span>',
    PENDING:  '<span class="status-badge orange">En attente</span>',
  };

  return '<div class="approval-list">' + expenses.map(exp => {
    const approvalStatus = exp.approvalStatus || null;
    const badge = approvalStatus
      ? (APPROVAL_BADGE[approvalStatus] || '<span class="status-badge grey">—</span>')
      : '<span class="status-badge grey">Non soumis</span>';

    const weaviateId = exp.weaviateId || '';

    let actions = '';
    if (weaviateId) {
      actions = `
        <div class="cra-action-row">
          <button class="btn-approve" data-id="${escapeHtml(weaviateId)}">✓</button>
          <div class="refuse-inline">
            <input type="text" class="refuse-reason-input" placeholder="Motif…">
            <button class="btn-refuse" data-id="${escapeHtml(weaviateId)}">✗</button>
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
      const weaviateId = btn.dataset.id;
      if (!weaviateId) { showToast('ID Weaviate manquant pour cet enregistrement.', 'err'); return; }
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${base()}/expenses/approve`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify({ weaviateId }),
        });
        if (!res.ok) throw new Error(await res.text());
        showToast('Dépense approuvée.', 'ok');
        loadConsNotes(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✓'; }
    });
  });

  container.querySelectorAll('.btn-refuse').forEach(btn => {
    btn.addEventListener('click', async () => {
      const weaviateId = btn.dataset.id;
      if (!weaviateId) { showToast('ID Weaviate manquant pour cet enregistrement.', 'err'); return; }
      const input = btn.closest('.refuse-inline')?.querySelector('.refuse-reason-input');
      const note  = input?.value?.trim() || '';
      btn.disabled = true; btn.textContent = '…';
      try {
        const res = await fetch(`${base()}/expenses/refuse`, {
          method: 'POST',
          headers: adminHeaders(),
          body: JSON.stringify({ weaviateId, note }),
        });
        if (!res.ok) throw new Error(await res.text());
        showToast('Dépense refusée.', 'ok');
        loadConsNotes(cons);
        loadDetailKpis(cons);
      } catch (e) { showToast('Erreur : ' + e.message, 'err'); btn.disabled = false; btn.textContent = '✗'; }
    });
  });
}

async function approveAllNotes(cons) {
  if (!loadedExpenses.length) return;
  const toApprove = loadedExpenses.filter(e => e.weaviateId && e.approvalStatus !== 'APPROVED');
  if (!toApprove.length) { showToast('Toutes les dépenses sont déjà approuvées.'); return; }
  if (!confirm(`Approuver les ${toApprove.length} dépenses de ${cons.name} ?`)) return;

  let ok = 0, err = 0;
  for (const exp of toApprove) {
    try {
      const res = await fetch(`${base()}/expenses/approve`, {
        method: 'POST',
        headers: adminHeaders(),
        body: JSON.stringify({ weaviateId: exp.weaviateId }),
      });
      if (res.ok) ok++; else err++;
    } catch { err++; }
  }
  showToast(`${ok} approuvée(s)${err ? `, ${err} erreur(s)` : ''}.`, err ? '' : 'ok');
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
  const company  = cons?.company || document.getElementById('cons-inv-company').value.trim() || 'IA-INSIGHT';
  const statusEl = document.getElementById('cons-inv-status');
  const resultEl = document.getElementById('cons-inv-result');
  const delAllBtn = document.getElementById('cons-inv-delete-all');

  setStatus(statusEl, 'Chargement…');
  resultEl.style.display = 'none';
  delAllBtn.style.display = 'none';
  loadedInvoices = [];

  try {
    const p   = new URLSearchParams({ start, end, company, consultantEmail: cons?.email || '' });
    const res = await fetch(`${invoiceBase()}/invoices/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    if (!items.length) { setStatus(statusEl, 'Aucune facture trouvée.', ''); return; }

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
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body,
    });
    if (!res.ok) { showToast(`Erreur téléchargement ${format.toUpperCase()} : HTTP ${res.status}`, 'err'); return; }
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
    showToast(`Erreur téléchargement : ${e.message}`, 'err');
  }
}

function renderInvoiceList(items, cons) {
  const rows = items.map((inv, idx) => {
    const name   = inv.invoiceName || '—';
    const bm     = inv.billingMonth || '—';
    const client = inv.clientCompanyName || '—';
    const total  = inv.totalTtc != null ? Number(inv.totalTtc).toFixed(2) + ' ' + (inv.currency || 'EUR') : '—';
    return `
      <div class="approval-row" data-inv-idx="${idx}">
        <div class="approval-meta">
          <span class="approval-name">${escapeHtml(name)}</span>
          <span class="approval-detail">${escapeHtml(bm)} — ${escapeHtml(client)}</span>
        </div>
        <div class="approval-right">
          <span class="approval-amount">${escapeHtml(total)}</span>
          <button class="btn-secondary inv-dl-pdf" data-inv-idx="${idx}" style="padding:6px 12px;font-size:12px">PDF</button>
          <button class="btn-inv-delete inv-delete" data-inv-idx="${idx}" title="Supprimer cette facture">
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
    if (!res.ok) throw new Error(await res.text());
    showToast(`Facture ${inv.invoiceName} supprimée.`, 'ok');
    loadConsInvoices(cons);
  } catch (e) {
    showToast('Erreur suppression : ' + e.message, 'err');
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
  showToast(`${ok} supprimée${ok > 1 ? 's' : ''}${err ? `, ${err} erreur(s)` : ''}.`, err ? '' : 'ok');
  loadConsInvoices(cons);
}
