// ============================================================
// KEYCLOAK CONFIG — lue depuis window.APP_CONFIG (config.js)
// ============================================================
const _cfg = globalThis.APP_CONFIG || {};
const DEFAULT_KEYCLOAK_URL = _cfg.keycloakUrl || 'http://localhost:30080';

let _keycloak = null;

async function initKeycloak() {
  _keycloak = new Keycloak({
    url:      DEFAULT_KEYCLOAK_URL,
    realm:    _cfg.keycloakRealm    || 'platform',
    clientId: _cfg.keycloakClientId || 'frontend-platform',
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

  setInterval(() => {
    _keycloak.updateToken(30).catch(() => _keycloak.login());
  }, 60000);

  return _keycloak;
}

function authHeaders(extra = {}) {
  const token = _keycloak?.token;
  return {
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    'Content-Type': 'application/json',
    ...extra,
  };
}

// ============================================================
// CONFIG API
// ============================================================
const DEFAULT_BASE = _cfg.apiBase || 'http://localhost:8081';

function base() { return DEFAULT_BASE.replace(/\/$/, ''); }

// ============================================================
// UTILITAIRES
// ============================================================
function setStatus(el, msg, type = '') {
  el.textContent = msg;
  el.className   = 'status' + (type ? ` ${type}` : '');
}

function escapeHtml(s) {
  return String(s)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

// ============================================================
// BOOT
// ============================================================
document.getElementById('logout-btn')?.addEventListener('click', () => {
  _keycloak?.logout({ redirectUri: window.location.origin + window.location.pathname });
});

(async () => {
  try {
    await initKeycloak();
    const p = _keycloak?.tokenParsed;
    if (p) {
      document.getElementById('user-email-badge').textContent = p.email || p.preferred_username || '';
      document.getElementById('app').style.display = '';
      initApp();
    }
  } catch (e) {
    console.error('Keycloak init failed:', e);
    document.body.innerHTML = '<div style="min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0d1017;font-family:Space Grotesk,system-ui"><div style="text-align:center;color:#f0f0f0;max-width:480px;padding:2rem"><h2 style="color:#2ce5a7">Service d\'authentification indisponible</h2><p style="color:#8892a4">La connexion au serveur d\'authentification a échoué.</p></div></div>';
  }
})();

// ============================================================
// APP
// ============================================================
function initApp() {
  document.getElementById('create-btn').addEventListener('click', createTenant);
  document.getElementById('refresh-btn').addEventListener('click', loadTenants);
  document.getElementById('edit-save').addEventListener('click', saveEdit);
  document.getElementById('edit-cancel').addEventListener('click', closeEditModal);
  document.getElementById('edit-close').addEventListener('click', closeEditModal);

  // Auto-génère le slug et le placeholder email admin à partir du nom
  document.getElementById('new-name').addEventListener('input', () => {
    const name = document.getElementById('new-name').value;
    const slug = name
      .toLowerCase()
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
    document.getElementById('new-slug').value = slug;
    document.getElementById('new-admin-email').placeholder = `admin@${slug || 'tenant'}.local`;
  });

  loadTenants();
}

// ============================================================
// CRUD — LIST
// ============================================================
async function loadTenants() {
  const statusEl = document.getElementById('list-status');
  const listEl   = document.getElementById('tenant-list');

  setStatus(statusEl, 'Chargement...');
  listEl.innerHTML = '';

  try {
    const res = await fetch(`${base()}/platform/tenants`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const tenants = await res.json();

    if (!tenants.length) {
      setStatus(statusEl, 'Aucun tenant.', '');
      return;
    }

    setStatus(statusEl, `${tenants.length} tenant${tenants.length > 1 ? 's' : ''}.`, 'ok');
    listEl.innerHTML = renderTenantList(tenants);
    wireListActions();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function renderTenantList(tenants) {
  let html = '<div class="tenant-grid">';
  for (const t of tenants) {
    const planCls = (t.plan || 'starter').toLowerCase();
    const active  = t.active !== false;
    const created = t.createdAt
      ? new Date(t.createdAt).toLocaleDateString('fr-FR', { day:'2-digit', month:'2-digit', year:'numeric' })
      : '—';
    html += `
      <div class="tenant-row" data-id="${t.id}">
        <div class="status-dot ${active ? 'active' : 'inactive'}" title="${active ? 'Actif' : 'Inactif'}"></div>
        <div class="tenant-info">
          <p class="tenant-name">${escapeHtml(t.name)}</p>
          <div class="tenant-meta">
            <span>Slug : <code>${escapeHtml(t.slug)}</code></span>
            <span>Realm : <code>${escapeHtml(t.keycloakRealm)}</code></span>
            <span>Créé le ${created}</span>
          </div>
        </div>
        <span class="plan-badge ${planCls}">${escapeHtml(t.plan || 'STARTER')}</span>
        <div class="tenant-actions">
          ${active ? `
          <a class="btn-open" href="http://localhost:3000/${escapeHtml(t.slug)}/" target="_blank" rel="noopener" title="Ouvrir la console admin de ce tenant">
            <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
            Admin
          </a>
          <a class="btn-open btn-open-consultant" href="http://localhost:3001/${escapeHtml(t.slug)}/" target="_blank" rel="noopener" title="Ouvrir l'espace consultant de ce tenant">
            <svg width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
            Consultant
          </a>` : ''}
          <button class="btn-edit" data-action="edit" data-tenant='${escapeHtml(JSON.stringify(t))}'>Modifier</button>
          ${active
            ? `<button class="btn-danger" data-action="deactivate" data-id="${t.id}" data-name="${escapeHtml(t.name)}">Désactiver</button>`
            : ''}
        </div>
      </div>`;
  }
  html += '</div>';
  return html;
}

function wireListActions() {
  document.querySelectorAll('[data-action="edit"]').forEach(btn => {
    btn.addEventListener('click', () => {
      const tenant = JSON.parse(btn.dataset.tenant);
      openEditModal(tenant);
    });
  });
  document.querySelectorAll('[data-action="deactivate"]').forEach(btn => {
    btn.addEventListener('click', () => deactivateTenant(btn.dataset.id, btn.dataset.name));
  });
}

// ============================================================
// CRUD — CREATE
// ============================================================
async function createTenant() {
  const statusEl = document.getElementById('create-status');
  const name      = document.getElementById('new-name').value.trim();
  const slug      = document.getElementById('new-slug').value.trim();
  const plan      = document.getElementById('new-plan').value;
  const adminEmail = document.getElementById('new-admin-email').value.trim() || null;
  const adminPwd   = document.getElementById('new-admin-pwd').value.trim()   || null;

  if (!name)              { setStatus(statusEl, 'Le nom est requis.', 'err'); return; }
  if (!slug)              { setStatus(statusEl, 'Le slug est requis.', 'err'); return; }
  if (!/^[a-z0-9-]+$/.test(slug)) { setStatus(statusEl, 'Le slug ne peut contenir que des lettres minuscules, chiffres et tirets.', 'err'); return; }

  setStatus(statusEl, 'Création en cours (base + Keycloak)...');

  try {
    const res = await fetch(`${base()}/platform/tenants`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        name, slug, plan,
        keycloakRealm: slug,
        adminEmail: adminEmail,
        adminPassword: adminPwd,
      }),
    });
    if (!res.ok && res.status !== 207) {
      throw new Error(`Erreur serveur (HTTP ${res.status})`);
    }
    const result = await res.json();
    const org = result.organization || result;
    if (result.keycloakProvisioned === false) {
      setStatus(statusEl, `Tenant "${org.name}" créé en base, mais le provisioning Keycloak a échoué : ${result.keycloakError || 'erreur inconnue'}`, 'err');
    } else {
      setStatus(statusEl, `Tenant "${org.name}" créé avec succès (slug: ${org.slug}, realm Keycloak provisionné).`, 'ok');
    }
    document.getElementById('new-name').value = '';
    document.getElementById('new-slug').value = '';
    document.getElementById('new-plan').value = 'STARTER';
    document.getElementById('new-admin-email').value = '';
    document.getElementById('new-admin-pwd').value = '';
    loadTenants();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

// ============================================================
// CRUD — EDIT (modal)
// ============================================================
function openEditModal(tenant) {
  document.getElementById('edit-id').value     = tenant.id;
  document.getElementById('edit-name').value   = tenant.name || '';
  document.getElementById('edit-slug').value   = tenant.slug || '';
  document.getElementById('edit-plan').value   = tenant.plan || 'STARTER';
  document.getElementById('edit-active').checked = tenant.active !== false;
  document.getElementById('edit-status').textContent = '';
  document.getElementById('edit-modal').style.display = '';
}

function closeEditModal() {
  document.getElementById('edit-modal').style.display = 'none';
}

async function saveEdit() {
  const statusEl = document.getElementById('edit-status');
  const id     = document.getElementById('edit-id').value;
  const name   = document.getElementById('edit-name').value.trim();
  const plan   = document.getElementById('edit-plan').value;
  const active = document.getElementById('edit-active').checked;

  if (!name) { setStatus(statusEl, 'Le nom est requis.', 'err'); return; }
  setStatus(statusEl, 'Enregistrement...');

  try {
    const res = await fetch(`${base()}/platform/tenants/${id}`, {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ name, plan, active }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    setStatus(statusEl, 'Modifications enregistrées.', 'ok');
    setTimeout(() => { closeEditModal(); loadTenants(); }, 600);
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

// ============================================================
// CRUD — DEACTIVATE
// ============================================================
async function deactivateTenant(id, name) {
  if (!confirm(`Désactiver le tenant "${name}" ? Les utilisateurs ne pourront plus se connecter.`)) return;

  const statusEl = document.getElementById('list-status');
  setStatus(statusEl, 'Désactivation...');

  try {
    const res = await fetch(`${base()}/platform/tenants/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    setStatus(statusEl, `Tenant "${name}" désactivé.`, 'ok');
    loadTenants();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}
