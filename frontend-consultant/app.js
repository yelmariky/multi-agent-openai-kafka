// ============================================================
// KEYCLOAK CONFIG — lue depuis window.APP_CONFIG (config.js)
// ============================================================
const _cfg = globalThis.APP_CONFIG || {};
const TENANT_SLUG = _cfg.slug || 'ia-insight';
const DEFAULT_KEYCLOAK_URL = _cfg.keycloakUrl || 'http://localhost:30080';

let _keycloak = null;
let _tenantInfo = { name: TENANT_SLUG.toUpperCase() };

function tenantName() { return _tenantInfo.name || TENANT_SLUG.toUpperCase(); }

async function initKeycloak() {
  _keycloak = new Keycloak({
    url:      DEFAULT_KEYCLOAK_URL,
    realm:    _cfg.keycloakRealm    || 'ia-insight',
    clientId: _cfg.keycloakClientId || 'frontend-consultant',
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
  return {
    email:   p.email || p.preferred_username,
    name:    p.name  || p.preferred_username,
    role:    'Consultant',
    company: p.company || tenantName(),
  };
}

function clearSession() {
  _keycloak?.logout({ redirectUri: `${window.location.origin}/${TENANT_SLUG}/` });
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
// CONFIG API — lue depuis globalThis.APP_CONFIG (config.js)
// ============================================================
const DEFAULT_BASE = _cfg.apiBase || 'http://localhost:8081';

function base() {
  return DEFAULT_BASE.replace(/\/$/, '');
}

// ============================================================
// BOOT — login ou app
// ============================================================
const loginScreen = document.getElementById('login-screen');
const appEl       = document.getElementById('app');

async function loadTenantInfo() {
  try {
    const res = await fetch(`${base()}/organization/me`, { headers: authHeaders() });
    if (!res.ok) return;
    const info = await res.json();
    _tenantInfo = info;
    const name = tenantName();
    document.title = `Espace consultant — ${name}`;
    const loginEl  = document.getElementById('login-tenant-name');
    const headerEl = document.getElementById('header-tenant-name');
    if (loginEl)  loginEl.textContent  = name;
    if (headerEl) headerEl.textContent = `${name} — Espace consultant`;
    // Persist in recent tenants for the tenant-select landing page
    saveRecentTenant(TENANT_SLUG, name);
  } catch { /* tenant info unavailable — use slug fallback */ }
}

function saveRecentTenant(slug, name) {
  try {
    const KEY = 'recent_tenants';
    const existing = JSON.parse(localStorage.getItem(KEY) || '[]');
    const filtered = existing.filter(t => t.slug !== slug);
    localStorage.setItem(KEY, JSON.stringify([{ slug, name }, ...filtered].slice(0, 5)));
  } catch { /* localStorage unavailable */ }
}

async function syncProfile(user) {
  try {
    const res = await fetch(`${base()}/consultants/profiles`, { headers: authHeaders() });
    if (res.ok) {
      const profiles = await res.json();
      if (profiles.some(p => p.email && p.email.toLowerCase() === user.email.toLowerCase())) {
        return; // profile already exists with correct email — don't overwrite admin data
      }
    }
    await fetch(`${base()}/consultants/profiles`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        email: user.email,
        name: user.name,
        role: user.role || 'Consultant',
        company: user.company || tenantName(),
        active: true
      })
    });
  } catch { /* best effort */ }
}

function showApp(user) {
  loginScreen.style.display = 'none';
  appEl.style.display = '';
  document.getElementById('user-display-name').textContent = user.name;
  document.getElementById('user-email-badge').textContent  = user.email;
  document.getElementById('user-role-badge').textContent   = user.role;
  document.getElementById('user-role-badge').className     =
    `user-role-badge ${user.role.toLowerCase()}`;
  loadTenantInfo();
  syncProfile(user);
  initNotifications(user);
  initApp(user);
}

// ============================================================
// BOOT — Keycloak puis app
// ============================================================
document.getElementById('logout-btn')?.addEventListener('click', () => clearSession());

(async () => {
  try {
    await initKeycloak();
    const user = getSession();
    if (user) showApp(user);
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
// TABS
// ============================================================
document.querySelectorAll('.tab').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById(`tab-${btn.dataset.tab}`).classList.add('active');
    if (btn.dataset.tab === 'notes') {
      const session = getSession();
      if (session) loadAbsencesForNotes(session);
    }
    if (btn.dataset.tab === 'absences') {
      const session = getSession();
      const craMonth = document.getElementById('cra-month')?.value;
      if (craMonth) document.getElementById('abs-month').value = craMonth;
      if (session) loadAbsences(session);
    }
  });
});

// ============================================================
// UTILITAIRES
// ============================================================
const MONTHS_FR = ['Janvier','Février','Mars','Avril','Mai','Juin',
                   'Juillet','Août','Septembre','Octobre','Novembre','Décembre'];

const DAY_MS = 86400000;

function pad(n) { return String(n).padStart(2, '0'); }
function toMonthStr(y, m) { return `${y}-${pad(m + 1)}`; }

function setStatus(el, msg, type = '') {
  el.textContent = msg;
  el.className   = 'status' + (type ? ` ${type}` : '');
}

function escapeHtml(s) {
  return String(s)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

function craHolidays(year) {
  return new Set([
    `${year}-01-01`, `${year}-05-01`, `${year}-05-08`,
    `${year}-07-14`, `${year}-08-15`, `${year}-11-01`,
    `${year}-11-11`, `${year}-12-25`,
  ]);
}

// ============================================================
// NOTIFICATIONS (SSE) — consultant
// ============================================================
let notifCount = 0;

function initNotifications(user) {
  const bell     = document.getElementById('notif-bell');
  const dropdown = document.getElementById('notif-dropdown');
  const readAll  = document.getElementById('notif-read-all');

  bell.addEventListener('click', e => {
    e.stopPropagation();
    dropdown.classList.toggle('hidden');
    if (!dropdown.classList.contains('hidden')) loadNotifications(user);
  });

  document.addEventListener('click', e => {
    if (!document.getElementById('notif-bell-wrap').contains(e.target)) {
      dropdown.classList.add('hidden');
    }
  });

  readAll.addEventListener('click', async () => {
    await fetch(`${base()}/consultant/notifications/read-all?consultant=${encodeURIComponent(user.email)}`,
      { method: 'POST', headers: authHeaders() });
    notifCount = 0;
    updateNotifBadge();
    loadNotifications(user);
  });

  connectConsultantSSE(user);
}

function connectConsultantSSE(user) {
  try {
    const token = _keycloak?.token;
    const url   = `${base()}/consultant/notifications/stream?consultant=${encodeURIComponent(user.email)}`
                + (token ? `&token=${encodeURIComponent(token)}` : '');
    const es = new EventSource(url);
    es.addEventListener('init', e => {
      notifCount = parseInt(e.data, 10) || 0;
      updateNotifBadge();
    });
    es.addEventListener('notification', e => {
      try {
        const n = JSON.parse(e.data);
        notifCount++;
        updateNotifBadge();
        showToast(n.message, n.type === 'CRA_VALIDATED' ? 'ok' : n.type === 'CRA_REFUSED' ? 'err' : 'info');
        const dropdown = document.getElementById('notif-dropdown');
        if (!dropdown.classList.contains('hidden')) loadNotifications(user);
      } catch { /* ignore malformed */ }
    });
    es.onerror = () => setTimeout(() => connectConsultantSSE(user), 5000);
  } catch { /* SSE not available */ }
}

function updateNotifBadge() {
  const badge = document.getElementById('notif-badge');
  if (!badge) return;
  if (notifCount > 0) {
    badge.textContent = notifCount > 99 ? '99+' : notifCount;
    badge.classList.remove('hidden');
  } else {
    badge.classList.add('hidden');
  }
}

async function loadNotifications(user) {
  const list = document.getElementById('notif-list');
  try {
    const res = await fetch(
      `${base()}/consultant/notifications?consultant=${encodeURIComponent(user.email)}`,
      { headers: authHeaders() }
    );
    if (!res.ok) throw new Error();
    const items = await res.json();
    if (!items.length) {
      list.innerHTML = '<p style="padding:12px 16px;color:var(--muted,#8892a4);font-size:13px">Aucune notification non lue.</p>';
      return;
    }
    list.innerHTML = items.map(n => `
      <div class="notif-item" data-id="${escapeHtml(n.id)}" style="padding:10px 16px;border-bottom:1px solid rgba(255,255,255,.06);cursor:pointer">
        <span style="font-size:16px">${n.type === 'CRA_VALIDATED' ? '✅' : '❌'}</span>
        <div style="flex:1;min-width:0">
          <p style="margin:0;font-size:13px;color:#f0f0f0">${escapeHtml(n.message)}</p>
          <p style="margin:2px 0 0;font-size:11px;color:var(--muted,#8892a4)">${formatNotifTs(n.timestamp)}</p>
        </div>
      </div>`).join('');
    list.querySelectorAll('.notif-item').forEach(item => {
      item.addEventListener('click', async () => {
        await fetch(
          `${base()}/consultant/notifications/${item.dataset.id}/read?consultant=${encodeURIComponent(user.email)}`,
          { method: 'POST', headers: authHeaders() }
        );
        notifCount = Math.max(0, notifCount - 1);
        updateNotifBadge();
        item.remove();
      });
    });
  } catch {
    list.innerHTML = '<p style="padding:12px 16px;color:#ff8a8a;font-size:13px">Erreur chargement.</p>';
  }
}

function formatNotifTs(ts) {
  if (!ts) return '';
  try {
    return new Date(ts).toLocaleString('fr-FR', { day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit' });
  } catch { return ts; }
}

function showToast(msg, type = '') {
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }
  const t = document.createElement('div');
  t.className = `toast${type ? ` toast-${type}` : ''}`;
  t.textContent = msg;
  container.appendChild(t);
  requestAnimationFrame(() => t.classList.add('toast-show'));
  setTimeout(() => {
    t.classList.remove('toast-show');
    t.addEventListener('transitionend', () => t.remove(), { once: true });
  }, 4000);
}

// ============================================================
// INIT APP — appelé après connexion
// ============================================================
function initApp(user) {
  const now      = new Date();
  const curMonth = toMonthStr(now.getFullYear(), now.getMonth());
  document.getElementById('cra-month').value          = curMonth;
  document.getElementById('hist-start').value         = toMonthStr(now.getFullYear(), Math.max(0, now.getMonth() - 2));
  document.getElementById('hist-end').value           = curMonth;
  document.getElementById('abs-month').value          = curMonth;
  document.getElementById('notes-km-month').value     = curMonth;
  document.getElementById('notes-report-month').value = curMonth;
  initCra(user);
  initAbsences(user);
  initNotes(user);
}

// ============================================================
// CRA
// ============================================================
let craEntries              = {};              // vue active : allProjectEntries[currentSelectedProjectId]
let allProjectEntries       = {};              // { projectId: { dateStr: {value,type} } }
let currentSelectedProjectId = null;
let craProjects             = [];              // [{ id, name }] — projets/missions du consultant
let craStatus               = 'BROUILLON';
let craId                   = null;
let craSubmittedAt          = null;
let currentUser             = null;
let currentProfile          = null;

// REFUSE is treated like BROUILLON for editability (consultant can re-edit after refusal)
const CRA_READONLY = () => craStatus === 'SOUMIS' || craStatus === 'VALIDE';

async function initCra(user) {
  currentUser = user;
  document.getElementById('cra-load').addEventListener('click',             () => loadCra(user));
  document.getElementById('cra-save').addEventListener('click',             () => saveCra('/cra/save'));
  document.getElementById('cra-submit').addEventListener('click',           submitCra);
  document.getElementById('cra-recall').addEventListener('click',           () => recallCra(user));
  document.getElementById('hist-load').addEventListener('click',            () => loadHistory(user));
  document.getElementById('cra-download-pdf').addEventListener('click',     downloadCraPdf);

  // Auto-fill client info when mission/project is selected
  document.getElementById('cra-mission').addEventListener('change', (e) => {
    const opt = e.target.selectedOptions[0];
    if (opt && opt.dataset.client) {
      document.getElementById('cra-client').value = opt.dataset.client;
    }
    if (opt && opt.dataset.contactEmail) {
      document.getElementById('cra-client-email').value = opt.dataset.contactEmail;
    }
  });

  // Load profile first (missions need it for project client info)
  currentProfile = await loadConsultantProfile(user);
  await loadMissions(user);

  // Pre-fill client fields from admin-configured profile (read-only for consultant)
  const clientEl = document.getElementById('cra-client');
  const emailEl  = document.getElementById('cra-client-email');
  if (currentProfile) {
    if (currentProfile.clientName)         clientEl.value = currentProfile.clientName;
    if (currentProfile.clientContactEmail) emailEl.value  = currentProfile.clientContactEmail;
  }
  clientEl.readOnly = true;
  emailEl.readOnly  = true;
  clientEl.style.background = '#f5f5f5';
  emailEl.style.background  = '#f5f5f5';
}

async function loadConsultantProfile(user) {
  try {
    const res = await fetch(`${base()}/consultants/profiles`, { headers: authHeaders() });
    if (!res.ok) return null;
    const profiles = await res.json();
    return profiles.find(p => p.email && p.email.toLowerCase() === user.email.toLowerCase()) || null;
  } catch { return null; }
}

async function loadMissions(user) {
  const sel = document.getElementById('cra-mission');
  try {
    const p = new URLSearchParams({ email: user.email });
    const [mRes, pRes] = await Promise.all([
      fetch(`${base()}/missions/by-email?${p}`, { headers: authHeaders() }),
      fetch(`${base()}/projects/by-email?${p}`, { headers: authHeaders() }),
    ]);
    const missions = mRes.ok ? await mRes.json() : [];
    const projects = pRes.ok ? await pRes.json() : [];

    sel.innerHTML = '<option value="">-- Aucune mission --</option>';
    craProjects = [];

    missions.forEach(m => {
      const opt = document.createElement('option');
      opt.value = m.id;
      opt.dataset.type = 'mission';
      const clientName         = m.client?.name         || '';
      const clientContactEmail = m.client?.contactEmail || '';
      opt.dataset.client = clientName;
      opt.dataset.contactEmail = clientContactEmail;
      opt.textContent = m.title + (clientName ? ' (' + clientName + ')' : '');
      sel.appendChild(opt);
      craProjects.push({ id: m.id, name: m.title, clientName, clientContactEmail, tjm: null });
    });
    projects.forEach(proj => {
      if (craProjects.some(cp => cp.id === proj.id)) return;
      const clientName         = currentProfile?.clientName         || '';
      const clientContactEmail = currentProfile?.clientContactEmail || '';
      const opt = document.createElement('option');
      opt.value = proj.id;
      opt.dataset.type = 'project';
      opt.dataset.client = clientName;
      opt.dataset.contactEmail = clientContactEmail;
      opt.textContent = proj.name;
      sel.appendChild(opt);
      craProjects.push({ id: proj.id, name: proj.name, clientName, clientContactEmail, tjm: null });
    });

    // Source principale : trios (assignments) configurés dans l'admin
    if (currentProfile?.id) {
      try {
        const aRes = await fetch(`${base()}/consultants/${currentProfile.id}/assignments`, { headers: authHeaders() });
        if (aRes.ok) {
          const assignments = await aRes.json();
          assignments.forEach(a => {
            if (!a.project?.id) return;
            const existing = craProjects.find(cp => cp.id === a.project.id);
            if (existing) {
              // Enrichir avec client + TJM si pas déjà renseigné
              if (!existing.clientName) existing.clientName = a.client?.name || '';
              if (!existing.tjm)        existing.tjm        = a.tjm || null;
            } else {
              const opt = document.createElement('option');
              opt.value = a.project.id;
              opt.dataset.type = 'assignment';
              opt.dataset.client = a.client?.name || '';
              opt.textContent = a.project.name + (a.client?.name ? ' — ' + a.client.name : '');
              sel.appendChild(opt);
              craProjects.push({
                id:                 a.project.id,
                name:               a.project.name,
                clientName:         a.client?.name    || '',
                clientContactEmail: '',
                tjm:                a.tjm || null,
              });
            }
          });
        }
      } catch { /* assignments non disponibles */ }
    }

    // Auto-select first option
    const options = Array.from(sel.options).filter(o => o.value);
    if (options.length >= 1) {
      sel.value = options[0].value;
      sel.dispatchEvent(new Event('change'));
    }

    // Consultant cannot change mission/project — set by admin
    sel.disabled = true;
    sel.style.background = 'transparent';
    sel.style.cursor = 'default';

    if (!currentSelectedProjectId && craProjects.length > 0) {
      currentSelectedProjectId = craProjects[0].id;
    }
    renderProjectTabs();
    renderProjectsInfoTable();
  } catch { /* missions unavailable */ }
}

async function downloadCraPdf() {
  if (!craId) return;
  await downloadCraPdfById(craId, document.getElementById('cra-month').value,
    document.getElementById('cra-action-status'));
}

async function downloadCraPdfById(id, month, statusEl) {
  if (!id) return;
  try {
    const res = await fetch(`${base()}/cra/pdf/${id}`, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `CRA-${month || id}.pdf`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    if (statusEl) setStatus(statusEl, 'Erreur PDF : ' + e.message, 'err');
    else showToast('Erreur PDF : ' + e.message, 'error');
  }
}

function buildBaseMonthMap(monthStr) {
  const [y, m]      = monthStr.split('-').map(Number);
  const holidays    = craHolidays(y);
  const daysInMonth = new Date(y, m, 0).getDate();
  const map         = {};
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    const dow     = new Date(y, m - 1, d).getDay();
    if (dow === 0 || dow === 6)       map[dateStr] = { value: 0, type: 'WEEKEND' };
    else if (holidays.has(dateStr))   map[dateStr] = { value: 0, type: 'FERIE'   };
    else                              map[dateStr] = { value: 0, type: 'EMPTY'   };
  }
  return map;
}

function initCraMonth(monthStr, savedEntries) {
  const baseMap = buildBaseMonthMap(monthStr);

  allProjectEntries = {};

  // Initialise une map vide pour chaque projet connu du consultant
  const defaultPid = craProjects.length > 0 ? craProjects[0].id : '__none__';
  craProjects.forEach(p => { allProjectEntries[p.id] = { ...baseMap }; });
  if (!allProjectEntries[defaultPid]) allProjectEntries['__none__'] = { ...baseMap };

  // Ligne dédiée aux absences
  allProjectEntries['__ABSENCE__'] = { ...baseMap };

  // Applique les entrées sauvegardées (chacune porte son projectId)
  if (savedEntries?.length) {
    savedEntries.forEach(e => {
      if (e.type === 'ABSENT') {
        // Rétrocompat : migrer les absences des lignes projet vers la ligne absence
        if (allProjectEntries['__ABSENCE__']?.[e.date]) {
          allProjectEntries['__ABSENCE__'][e.date] = { value: e.value || 1, type: 'ABSENT' };
        }
        // Vider la cellule projet si elle existait
        if (e.projectId && allProjectEntries[e.projectId]?.[e.date]) {
          allProjectEntries[e.projectId][e.date] = { value: 0, type: 'EMPTY' };
        }
      } else {
        const pid = e.projectId || defaultPid;
        if (!allProjectEntries[pid]) allProjectEntries[pid] = { ...baseMap };
        if (allProjectEntries[pid][e.date]) {
          allProjectEntries[pid][e.date] = { value: e.value, type: e.type };
        }
      }
    });
  }

  // Sélectionne le premier projet si rien n'est sélectionné ou projet inconnu
  if (!currentSelectedProjectId || !allProjectEntries[currentSelectedProjectId]) {
    currentSelectedProjectId = defaultPid;
  }
  craEntries = allProjectEntries[currentSelectedProjectId];
}

function renderProjectTabs() {
  const container = document.getElementById('cra-project-tabs');
  if (!container) return;
  if (craProjects.length <= 1) { container.style.display = 'none'; return; }

  container.style.display = 'flex';
  container.innerHTML = craProjects.map(p =>
    `<button class="btn-secondary" style="padding:5px 14px;font-size:12px;${p.id === currentSelectedProjectId ? 'background:var(--accent);color:#fff;border-color:var(--accent)' : ''}" data-pid="${p.id}">${escHtml(p.name)}</button>`
  ).join('');

  container.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('click', () => {
      currentSelectedProjectId = btn.dataset.pid;
      const monthStr = document.getElementById('cra-month').value;
      if (monthStr && !allProjectEntries[currentSelectedProjectId]) {
        allProjectEntries[currentSelectedProjectId] = buildBaseMonthMap(monthStr);
      }
      craEntries = allProjectEntries[currentSelectedProjectId] || {};
      renderProjectTabs();
      renderCraGrid();
    });
  });
}

function renderProjectsInfoTable() {
  const table = document.getElementById('cra-projects-table') || document.getElementById('cra-proj-cards');
  if (!table) return;

  if (!craProjects.length) { table.style.display = 'none'; return; }

  // Calculer les jours déjà saisis par projet
  function daysForProject(pid) {
    const entries = allProjectEntries[pid] || {};
    return Object.values(entries).reduce((s, e) => s + (e.type === 'TRAVAIL' ? e.value : 0), 0);
  }

  // Remplacer la table par des cartes projet
  table.outerHTML = `<div id="cra-proj-cards" class="cra-proj-cards">
    ${craProjects.map((p, i) => {
      const days = daysForProject(p.id);
      const daysLabel = days > 0 ? (days % 1 === 0 ? days + 'j' : days.toFixed(1) + 'j') : '—';
      const tjmLabel  = p.tjm ? p.tjm.toLocaleString('fr-FR') + ' €/j' : '—';
      const colors    = ['var(--accent)', 'var(--accent-2)', '#a78bfa', '#f59e0b'];
      const color     = colors[i % colors.length];
      return `
        <div class="cra-proj-card" style="--proj-color:${color}">
          <div class="cra-proj-card-dot" style="background:${color}"></div>
          <div class="cra-proj-card-body">
            <span class="cra-proj-card-name">${escHtml(p.name)}</span>
            <span class="cra-proj-card-client">${escHtml(p.clientName || '—')}</span>
          </div>
          <div class="cra-proj-card-kpis">
            <div class="cra-proj-kpi"><span>TJM</span><strong>${tjmLabel}</strong></div>
            <div class="cra-proj-kpi"><span>Jours</span><strong id="proj-days-${escHtml(p.id)}">${daysLabel}</strong></div>
          </div>
        </div>`;
    }).join('')}
  </div>`;
}

function refreshProjectDays() {
  craProjects.forEach(p => {
    const el = document.getElementById(`proj-days-${p.id}`);
    if (!el) return;
    const entries = allProjectEntries[p.id] || {};
    const days = Object.values(entries).reduce((s, e) => s + (e.type === 'TRAVAIL' ? e.value : 0), 0);
    el.textContent = days > 0 ? (days % 1 === 0 ? days + 'j' : days.toFixed(1) + 'j') : '—';
  });
}

function escHtml(s) {
  return (s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function renderCraCalendar() {
  const monthStr = document.getElementById('cra-month').value;
  if (!monthStr) return;

  const [y, m]      = monthStr.split('-').map(Number);
  const firstDow    = (new Date(y, m - 1, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(y, m, 0).getDate();
  const readonly    = CRA_READONLY();

  document.getElementById('cra-cal-title').textContent = `${MONTHS_FR[m - 1]} ${y}`;

  let html = '';
  for (let i = 0; i < firstDow; i++) html += '<div class="cra-day empty"></div>';

  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr          = `${y}-${pad(m)}-${pad(d)}`;
    const entry            = craEntries[dateStr] || { value: 1, type: 'TRAVAIL' };
    const { value, type }  = entry;
    const clickable        = !readonly && (type === 'TRAVAIL' || type === 'ABSENT');

    let cls = 'cra-day';
    let lbl = '';
    if      (type === 'WEEKEND')               { cls += ' weekend'; }
    else if (type === 'FERIE')                 { cls += ' holiday'; lbl = 'Férié'; }
    else if (type === 'ABSENT' || value === 0) { cls += ' absent';  lbl = 'Abs.'; }
    else if (value === 0.5)                    { cls += ' half';    lbl = '½j'; }
    else                                       { cls += ' full';    lbl = '1j'; }
    if (clickable) cls += ' clickable';

    html += `<div class="${cls}" data-date="${dateStr}">
      <span class="cra-day-num">${d}</span>
      <span class="cra-day-val">${lbl}</span>
    </div>`;
  }

  const grid = document.getElementById('cra-cal-grid');
  grid.innerHTML = html;

  grid.querySelectorAll('.cra-day.clickable').forEach(cell => {
    cell.addEventListener('click', () => {
      const e = craEntries[cell.dataset.date];
      if (!e) return;
      if (e.type === 'ABSENT' || e.value === 0) craEntries[cell.dataset.date] = { value: 1,   type: 'TRAVAIL' };
      else if (e.value === 1)                   craEntries[cell.dataset.date] = { value: 0.5, type: 'TRAVAIL' };
      else                                      craEntries[cell.dataset.date] = { value: 0,   type: 'ABSENT'  };
      renderCraCalendar();
    });
  });

  updateCraTotal();
  updateCraButtons();
}

function renderCraGrid() {
  const monthStr = document.getElementById('cra-month').value;
  const wrapper  = document.getElementById('cra-stacked-grid');
  if (!wrapper) { renderCraCalendar(); return; }  // fallback si HTML pas encore mis à jour

  if (!monthStr || !craProjects.length) {
    wrapper.innerHTML = '';
    updateCraButtons();
    return;
  }

  const [y, m]      = monthStr.split('-').map(Number);
  const daysInMonth = new Date(y, m, 0).getDate();
  const readonly    = CRA_READONLY();
  const holidays    = craHolidays(y);

  document.getElementById('cra-cal-title').textContent = `${MONTHS_FR[m - 1]} ${y}`;

  // Jours ouvrés incomplets (TRAVAIL + ABSENCE > 0 mais < 1)
  const incompleteSet = new Set();
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    const dow = new Date(y, m - 1, d).getDay();
    if (dow === 0 || dow === 6 || holidays.has(dateStr)) continue;
    const projSum = craProjects.reduce((s, p) => {
      const e = (allProjectEntries[p.id] || {})[dateStr];
      return s + (e?.type === 'TRAVAIL' ? e.value : 0);
    }, 0);
    const absVal = allProjectEntries['__ABSENCE__']?.[dateStr]?.value || 0;
    const dayTotal = projSum + absVal;
    if (dayTotal > 0 && dayTotal < 1) incompleteSet.add(d);
  }

  // En-tête : numéros de jours
  let hdr = '<thead><tr><th class="cra-g-label">Projet</th>';
  for (let d = 1; d <= daysInMonth; d++) {
    const dow = new Date(y, m - 1, d).getDay();
    const isWkd = dow === 0 || dow === 6;
    const cls = isWkd ? 'cra-g-wkd' : (incompleteSet.has(d) ? 'cra-g-hdr-warn' : '');
    hdr += `<th class="${cls}">${d}</th>`;
  }
  hdr += '</tr></thead>';

  // Lignes projets
  let body = '<tbody>';
  craProjects.forEach(proj => {
    const entries = allProjectEntries[proj.id] || {};
    body += `<tr><td class="cra-g-label">${escHtml(proj.name)}</td>`;
    for (let d = 1; d <= daysInMonth; d++) {
      const dateStr       = `${y}-${pad(m)}-${pad(d)}`;
      const e             = entries[dateStr] || { value: 0, type: 'EMPTY' };
      const dow           = new Date(y, m - 1, d).getDay();
      const isWkd         = dow === 0 || dow === 6;
      const isFerie       = holidays.has(dateStr);
      const absVal        = allProjectEntries['__ABSENCE__']?.[dateStr]?.value || 0;
      const projectDisabled = absVal >= 1;
      const clickable     = !readonly && !isWkd && !isFerie && !projectDisabled;
      let cls = isWkd           ? 'cra-g-wkd'
              : isFerie         ? 'cra-g-ferie'
              : projectDisabled ? 'cra-g-disabled'
              : e.type === 'EMPTY' ? 'cra-g-empty'
              : e.value === 0.5    ? 'cra-g-half'
              :                      'cra-g-full';
      const lbl = isWkd || isFerie || projectDisabled ? ''
                : e.type === 'EMPTY' ? ''
                : e.value === 0.5    ? '½'
                :                      '1';
      if (clickable) cls += ' clickable';
      body += `<td class="${cls}" data-pid="${escHtml(proj.id)}" data-date="${dateStr}">${lbl}</td>`;
    }
    body += '</tr>';
  });

  // Ligne Absence (avant Total)
  body += '<tr class="cra-g-absence-row"><td class="cra-g-label">Absence</td>';
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    const ae      = allProjectEntries['__ABSENCE__']?.[dateStr] || { value: 0, type: 'EMPTY' };
    const dow     = new Date(y, m - 1, d).getDay();
    const isWkd   = dow === 0 || dow === 6;
    const isFerie = holidays.has(dateStr);
    const projSum = craProjects.reduce((s, p) => {
      const oe = (allProjectEntries[p.id] || {})[dateStr];
      return s + (oe?.type === 'TRAVAIL' ? oe.value : 0);
    }, 0);
    const absDisabled = projSum >= 1;
    let cls = isWkd       ? 'cra-g-wkd'
            : isFerie     ? 'cra-g-ferie'
            : absDisabled ? 'cra-g-disabled'
            : ae.type === 'EMPTY' ? 'cra-g-empty'
            : ae.value === 0.5   ? 'cra-g-abs-half'
            :                      'cra-g-abs-full';
    const lbl = isWkd || isFerie || absDisabled || ae.type === 'EMPTY' ? ''
              : ae.value === 0.5 ? '½A' : 'A';
    const clickable = !readonly && !isWkd && !isFerie && !absDisabled;
    if (clickable) cls += ' clickable';
    body += `<td class="${cls}" data-pid="__ABSENCE__" data-date="${dateStr}">${lbl}</td>`;
  }
  body += '</tr>';

  // Ligne total
  body += '<tr class="cra-g-total"><td class="cra-g-label">Total</td>';
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    let sum = 0;
    craProjects.forEach(proj => {
      const e = (allProjectEntries[proj.id] || {})[dateStr];
      if (e && e.type === 'TRAVAIL') sum += e.value;
    });
    const dow          = new Date(y, m - 1, d).getDay();
    const isWkd        = dow === 0 || dow === 6;
    const isIncomplete = incompleteSet.has(d);
    const absVal       = isIncomplete ? (allProjectEntries['__ABSENCE__']?.[dateStr]?.value || 0) : 0;
    const displayVal   = isIncomplete ? sum + absVal : sum;
    const cls          = isWkd ? 'cra-g-wkd' : (isIncomplete ? 'cra-g-incomplete' : '');
    const lbl          = displayVal > 0 ? (displayVal % 1 ? displayVal.toFixed(1) : String(displayVal)) : '';
    body += `<td class="${cls}">${lbl}</td>`;
  }
  body += '</tr></tbody>';

  let warningHtml = '';
  if (incompleteSet.size > 0) {
    const daysList = [...incompleteSet].sort((a, b) => a - b).join(', ');
    warningHtml = `<p class="cra-incomplete-banner">Jours incomplets (total &lt; 1j) : <strong>${daysList}</strong> — vérifiez la saisie de ces jours.</p>`;
  }
  wrapper.innerHTML = `<table class="cra-stacked">${hdr}${body}</table>${warningHtml}`;

  if (!readonly) {
    wrapper.querySelectorAll('td.clickable').forEach(cell => {
      cell.addEventListener('click', () => {
        const pid  = cell.dataset.pid;
        const date = cell.dataset.date;
        if (!allProjectEntries[pid]) return;
        const e = allProjectEntries[pid][date];
        if (!e) return;

        if (pid === '__ABSENCE__') {
          // Cycle absence : EMPTY → ½A → A → EMPTY
          const projSum = craProjects.reduce((s, p) => {
            const oe = (allProjectEntries[p.id] || {})[date];
            return s + (oe?.type === 'TRAVAIL' ? oe.value : 0);
          }, 0);
          if (e.type === 'EMPTY') {
            if (projSum <= 0.5)
              allProjectEntries['__ABSENCE__'][date] = { value: 0.5, type: 'ABSENT' };
          } else if (e.value === 0.5) {
            if (projSum === 0)
              allProjectEntries['__ABSENCE__'][date] = { value: 1, type: 'ABSENT' };
          } else {
            // A → EMPTY + vider les projets du jour
            allProjectEntries['__ABSENCE__'][date] = { value: 0, type: 'EMPTY' };
            craProjects.forEach(p => {
              if (allProjectEntries[p.id]?.[date])
                allProjectEntries[p.id][date] = { value: 0, type: 'EMPTY' };
            });
          }
        } else {
          // Cellule projet — cycle EMPTY → 1j → ½j → EMPTY, sans ABSENT
          const absVal   = allProjectEntries['__ABSENCE__']?.[date]?.value || 0;
          const otherSum = craProjects
            .filter(p => p.id !== pid)
            .reduce((s, p) => {
              const oe = (allProjectEntries[p.id] || {})[date];
              return s + (oe?.type === 'TRAVAIL' ? oe.value : 0);
            }, 0);
          const budget = 1 - otherSum - absVal;

          if (e.type === 'EMPTY') {
            if (budget >= 1)
              allProjectEntries[pid][date] = { value: 1,   type: 'TRAVAIL' };
            else if (budget >= 0.5)
              allProjectEntries[pid][date] = { value: 0.5, type: 'TRAVAIL' };
          } else if (e.value === 1) {
            allProjectEntries[pid][date] = { value: 0.5, type: 'TRAVAIL' };
          } else if (e.value === 0.5) {
            allProjectEntries[pid][date] = { value: 0, type: 'EMPTY' };
          }
        }

        craEntries = allProjectEntries[currentSelectedProjectId] || {};
        renderCraGrid();
      });
    });
  }

  updateCraTotal();
  updateCraButtons();
  refreshProjectDays();
}

function updateCraTotal() {
  // Somme tous les jours TRAVAIL sur tous les projets
  let total = 0;
  Object.values(allProjectEntries).forEach(entries => {
    Object.values(entries).forEach(e => {
      if (e.type === 'TRAVAIL') total += e.value;
    });
  });
  document.getElementById('cra-total-days').textContent =
    total % 1 === 0 ? String(total) : total.toFixed(1);
}

function isCloture(billingMonth) {
  if (!billingMonth) return false;
  const [y, m] = billingMonth.split('-').map(Number);
  // CLOTURÉ le 5ème jour du mois suivant (m est 1-based, new Date(y, m, 5) = 5ème du mois m+1)
  return new Date() >= new Date(y, m, 5);
}

function isAllWorkdaysFilled() {
  const monthStr = document.getElementById('cra-month')?.value;
  if (!monthStr || !craProjects.length) return false;
  const [y, m] = monthStr.split('-').map(Number);
  const daysInMonth = new Date(y, m, 0).getDate();
  const holidays = craHolidays(y);
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    const dow = new Date(y, m - 1, d).getDay();
    if (dow === 0 || dow === 6 || holidays.has(dateStr)) continue;
    const projSum = craProjects.reduce((s, p) => {
      const e = (allProjectEntries[p.id] || {})[dateStr];
      return s + (e?.type === 'TRAVAIL' ? e.value : 0);
    }, 0);
    const absVal = allProjectEntries['__ABSENCE__']?.[dateStr]?.value || 0;
    if (projSum + absVal < 1) return false;
  }
  return true;
}

function updateCraButtons() {
  const monthStr = document.getElementById('cra-month')?.value;
  const effectiveStatus = (craStatus === 'VALIDE' && isCloture(monthStr)) ? 'CLOTURE' : craStatus;
  const badge = document.getElementById('cra-status-badge');
  const LABEL = { BROUILLON: 'BROUILLON', SOUMIS: 'SOUMIS', VALIDE: 'VALIDÉ', REFUSE: 'REFUSÉ', CLOTURE: 'CLÔTURÉ' };
  badge.textContent = LABEL[effectiveStatus] || effectiveStatus;
  badge.className   = `cra-status-badge ${effectiveStatus.toLowerCase()}`;
  // REFUSE resets to BROUILLON-like: consultant can re-edit and resubmit
  const canSubmit = effectiveStatus === 'BROUILLON' || effectiveStatus === 'REFUSE';
  const submitBtn = document.getElementById('cra-submit');
  if (canSubmit) {
    const allFilled = isAllWorkdaysFilled();
    submitBtn.style.display = '';
    submitBtn.disabled = !allFilled;
    submitBtn.title = allFilled ? '' : 'Tous les jours ouvrés du mois doivent être saisis avant de soumettre';
  } else {
    submitBtn.style.display = 'none';
    submitBtn.disabled = false;
    submitBtn.title = '';
  }
  document.getElementById('cra-save').disabled        = CRA_READONLY();
  // "Retirer ma soumission" uniquement quand SOUMIS (avant action admin)
  const recallBtn = document.getElementById('cra-recall');
  if (recallBtn) recallBtn.style.display = effectiveStatus === 'SOUMIS' ? '' : 'none';
  // PDF download available when CRA is VALIDE ou CLÔTURÉ
  const pdfBtn = document.getElementById('cra-download-pdf');
  if (pdfBtn) pdfBtn.style.display = (craId && (effectiveStatus === 'VALIDE' || effectiveStatus === 'CLOTURE')) ? '' : 'none';
}

async function loadCra(user) {
  const monthStr = document.getElementById('cra-month').value;
  const statusEl = document.getElementById('cra-load-status');

  if (!monthStr) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }

  craId = null; craStatus = 'BROUILLON'; craSubmittedAt = null;
  currentSelectedProjectId = null;
  initCraMonth(monthStr, []);
  document.getElementById('cra-cal-card').style.display = '';
  renderProjectTabs();
  renderProjectsInfoTable();
  renderCraGrid();
  setStatus(statusEl, 'Synchronisation avec le serveur…');

  try {
    const p = new URLSearchParams({ start: monthStr, end: monthStr, consultant: user.email });
    const res = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    const existing = items.find(item =>
      item.billingMonth === monthStr &&
      (item.consultant || '').trim().toLowerCase() === user.email.toLowerCase()
    );

    if (existing) {
      craId          = existing.id;
      craStatus      = existing.status      || 'BROUILLON';
      craSubmittedAt = existing.submittedAt || null;
      // CRA saved values take priority; fallback to admin profile
      document.getElementById('cra-client').value =
        existing.clientCompany || currentProfile?.clientName || '';
      document.getElementById('cra-client-email').value =
        existing.clientContactEmail || currentProfile?.clientContactEmail || '';
      if (existing.missionId) {
        document.getElementById('cra-mission').value = existing.missionId;
      } else if (existing.projectId) {
        document.getElementById('cra-mission').value = existing.projectId;
      }
      let saved = [];
      try { saved = JSON.parse(existing.entriesJson || '[]'); } catch { saved = []; }

      // Rétro-compat : si les entrées n'ont pas de projectId, injecter le projectId du CRA parent
      const fallbackPid = existing.projectId || existing.missionId || (craProjects[0]?.id) || '__none__';
      saved = saved.map(e => ({ ...e, projectId: e.projectId || fallbackPid }));

      // Réinitialiser la sélection pour forcer le premier projet
      currentSelectedProjectId = null;
      initCraMonth(monthStr, saved);
      renderProjectTabs();
      renderProjectsInfoTable();
      renderCraGrid();

      // Show refusal reason if CRA was refused
      const refuseEl = document.getElementById('cra-refused-notice');
      if (refuseEl) {
        if (craStatus === 'REFUSE' && existing.refusedReason) {
          refuseEl.textContent = `Motif de refus : ${existing.refusedReason}`;
          refuseEl.style.display = '';
        } else {
          refuseEl.style.display = 'none';
        }
      }

      const STATUS_LABEL = { BROUILLON: 'BROUILLON', SOUMIS: 'SOUMIS', VALIDE: 'VALIDÉ', REFUSE: 'REFUSÉ' };
      setStatus(statusEl, `CRA chargé — statut : ${STATUS_LABEL[craStatus] || craStatus}.`, 'ok');
    } else {
      // Restore craId from localStorage in case it was set in a previous session
      const stored = localStorage.getItem(`cra:${user.email}:${monthStr}`);
      if (stored) craId = stored;
      setStatus(statusEl, 'Nouveau CRA — saisissez vos jours puis sauvegardez.', 'ok');
    }
  } catch (e) {
    // Server unreachable — try to recover craId from localStorage
    const stored = localStorage.getItem(`cra:${user.email}:${monthStr}`);
    if (stored) craId = stored;
    setStatus(statusEl, `Serveur inaccessible — saisie locale possible (${e.message}).`, '');
  }
}


function buildCraPayload() {
  // Aplatir toutes les entrées de tous les projets avec leur projectId
  const entries = [];
  Object.entries(allProjectEntries).forEach(([pid, projectMap]) => {
    Object.entries(projectMap).forEach(([date, e]) => {
      if (e.type === 'EMPTY') return; // jour non saisi — ne pas envoyer
      entries.push({ date, value: e.value, type: e.type, projectId: (pid === '__none__') ? null : pid });
    });
  });
  entries.sort((a, b) => a.date.localeCompare(b.date));
  const totalDays = entries.filter(e => e.type === 'TRAVAIL').reduce((s, e) => s + e.value, 0);

  const sel = document.getElementById('cra-mission');
  return {
    id:                 craId,
    consultant:         currentUser.email,
    company:            currentUser.company,
    clientCompany:      document.getElementById('cra-client').value.trim(),
    clientContactEmail: document.getElementById('cra-client-email').value.trim(),
    billingMonth:       document.getElementById('cra-month').value,
    entries,
    totalDays,
    status:        craStatus,
    submittedAt:   craSubmittedAt,
    validatedAt:   null,
    validatedBy:   null,
    missionId:     sel.value && sel.selectedOptions[0]?.dataset.type === 'mission'  ? sel.value : null,
    projectId:     sel.value && sel.selectedOptions[0]?.dataset.type !== 'mission' ? sel.value : null,
  };
}

async function saveCra(endpoint) {
  const statusEl = document.getElementById('cra-action-status');
  setStatus(statusEl, 'Sauvegarde…');
  try {
    const res = await fetch(`${base()}${endpoint}`, {
      method:  'POST',
      headers: authHeaders(),
      body:    JSON.stringify(buildCraPayload()),
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    const saved = await res.json();
    craId = saved.id; craStatus = saved.status;
    localStorage.setItem(`cra:${currentUser.email}:${saved.billingMonth}`, saved.id);
    updateCraButtons();
    showToast('CRA sauvegardé.', 'ok');
    setStatus(statusEl, '');
  } catch (e) { setStatus(statusEl, 'Erreur de sauvegarde. Réessayez ou contactez votre administrateur.', 'err'); }
}

async function submitCra() {
  const statusEl = document.getElementById('cra-action-status');
  if (!confirm('Soumettre ce CRA pour validation ? Vous ne pourrez plus le modifier.')) return;
  setStatus(statusEl, 'Soumission…');
  try {
    const res = await fetch(`${base()}/cra/submit`, {
      method:  'POST',
      headers: authHeaders(),
      body:    JSON.stringify(buildCraPayload()),
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    const saved = await res.json();
    craId = saved.id; craStatus = saved.status; craSubmittedAt = saved.submittedAt;
    localStorage.setItem(`cra:${currentUser.email}:${saved.billingMonth}`, saved.id);
    updateCraButtons();
    showToast('CRA soumis. En attente de validation.', 'ok');
    setStatus(statusEl, '');
  } catch (e) { setStatus(statusEl, 'Erreur de soumission. Réessayez ou contactez votre administrateur.', 'err'); }
}

async function recallCra(_user) {
  const statusEl = document.getElementById('cra-action-status');
  if (!confirm('Retirer votre soumission ? Le CRA repassera en brouillon et pourra être modifié.')) return;
  setStatus(statusEl, 'Retrait en cours…');
  try {
    const res = await fetch(`${base()}/cra/recall`, {
      method:  'POST',
      headers: authHeaders(),
      body:    JSON.stringify(buildCraPayload()),
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    const saved = await res.json();
    craId = saved.id; craStatus = saved.status; craSubmittedAt = null;
    renderCraGrid();
    updateCraButtons();
    showToast('Soumission retirée. CRA repassé en brouillon.', 'info');
    setStatus(statusEl, '');
  } catch (e) { setStatus(statusEl, 'Erreur : ' + e.message, 'err'); }
}

async function loadHistory(user) {
  const start    = document.getElementById('hist-start').value;
  const end      = document.getElementById('hist-end').value;
  const statusEl = document.getElementById('hist-status');
  const resultEl = document.getElementById('hist-result');

  setStatus(statusEl, 'Chargement…');
  resultEl.style.display = 'none';

  try {
    const p = new URLSearchParams({ start, end, consultant: user.email });
    const res = await fetch(`${base()}/cra/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const items = await res.json();

    if (!items.length) { setStatus(statusEl, 'Aucun CRA trouvé sur cette période.', ''); return; }

    setStatus(statusEl, `${items.length} CRA trouvé${items.length > 1 ? 's' : ''}.`, 'ok');
    resultEl.innerHTML = renderHistoryList(items);
    resultEl.querySelectorAll('.hist-pdf-btn').forEach(btn => {
      btn.addEventListener('click', () => downloadCraPdfById(btn.dataset.id, btn.dataset.month));
    });
    resultEl.style.display = 'block';
  } catch (e) { setStatus(statusEl, 'Erreur : ' + e.message, 'err'); }
}

function renderHistoryList(items) {
  const STATUS_COLOR = { BROUILLON: 'grey', SOUMIS: 'orange', VALIDE: 'green', REFUSE: 'red', CLOTURE: 'purple' };
  const STATUS_LABEL = { BROUILLON: 'Brouillon', SOUMIS: 'Soumis', VALIDE: 'Validé', REFUSE: 'Refusé', CLOTURE: 'Clôturé' };
  const COLS = '1fr 1fr 80px 110px 1fr 60px';
  let html = `
    <div class="data-list">
      <div class="data-list-header" style="grid-template-columns:${COLS}">
        <span>Mois</span><span>Client</span><span>Jours</span><span>Statut</span><span>Validé par</span><span>PDF</span>
      </div>`;
  for (const cra of items) {
    const days   = cra.totalDays != null
      ? (cra.totalDays % 1 === 0 ? String(cra.totalDays) : Number(cra.totalDays).toFixed(1))
      : '—';
    const rawStatus = cra.status || 'BROUILLON';
    const status = (rawStatus === 'VALIDE' && isCloture(cra.billingMonth)) ? 'CLOTURE' : rawStatus;
    const color  = STATUS_COLOR[status] || 'grey';
    const label  = STATUS_LABEL[status] || status;
    const refusedNote = status === 'REFUSE' && cra.refusedReason
      ? `<span class="cell-sub refused-reason" style="font-style:italic">Motif : ${escapeHtml(cra.refusedReason)}</span>`
      : '';
    html += `
      <div class="data-list-row" style="grid-template-columns:${COLS}">
        <div class="cell-primary">
          <span class="cell-name">${escapeHtml(cra.billingMonth || '—')}</span>
          ${refusedNote}
        </div>
        <span class="cell-muted">${escapeHtml(cra.clientCompany || '—')}</span>
        <span style="font-weight:600;font-size:14px">${escapeHtml(days)}</span>
        <span><span class="status-badge ${color}">${escapeHtml(label)}</span></span>
        <span class="cell-muted">${cra.validatedBy ? escapeHtml(cra.validatedBy) : '—'}</span>
        <span>${cra.id ? `<button class="btn-pdf hist-pdf-btn" data-id="${escapeHtml(cra.id)}" data-month="${escapeHtml(cra.billingMonth || '')}" title="Télécharger PDF">
          <svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>
          PDF
        </button>` : ''}</span>
      </div>`;
  }
  html += '</div>';
  return html;
}

// ============================================================
// ABSENCES
// ============================================================
function initAbsences(user) {
  document.getElementById('abs-month').addEventListener('change', () => loadAbsences(user));
  document.getElementById('abs-goto-cra').addEventListener('click', () => {
    const monthStr = document.getElementById('abs-month').value;
    if (monthStr) document.getElementById('cra-month').value = monthStr;
    document.querySelector('.tab[data-tab="cra"]')?.click();
  });
}

async function loadAbsences(user) {
  const monthStr = document.getElementById('abs-month').value;
  const statusEl = document.getElementById('abs-status');
  const calCard  = document.getElementById('abs-cal-card');

  if (!monthStr) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Chargement…');
  calCard.style.display = 'none';

  try {
    const absenceMap = await buildAbsenceMap(monthStr, user);
    renderAbsenceCalendar(monthStr, absenceMap);

    // Badge statut CRA
    const craMonth    = document.getElementById('cra-month')?.value;
    const statusBadge = document.getElementById('abs-cra-status');
    const gotoBtn     = document.getElementById('abs-goto-cra');
    const LABELS      = { BROUILLON: 'BROUILLON', SOUMIS: 'SOUMIS', VALIDE: 'VALIDÉ', REFUSE: 'REFUSÉ' };
    if (craMonth === monthStr && craStatus) {
      statusBadge.textContent = LABELS[craStatus] || craStatus;
      statusBadge.className   = `status-badge ${craStatus.toLowerCase()}`;
      gotoBtn.style.display   = (craStatus === 'BROUILLON' || craStatus === 'REFUSE') ? '' : 'none';
    } else {
      statusBadge.textContent = '';
      gotoBtn.style.display   = 'none';
    }

    const count = [...absenceMap.values()].reduce((s, v) => s + v, 0);
    setStatus(statusEl, count > 0 ? `${count % 1 ? count.toFixed(1) : count} jour${count > 1 ? 's' : ''} d'absence ce mois.` : 'Aucune absence ce mois.', count ? 'ok' : '');
    calCard.style.display = '';
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

/**
 * Construit une Map<dateStr, 1|0.5> depuis trois sources (par priorité) :
 *  0. allProjectEntries['__ABSENCE__'] si le même mois est chargé en mémoire (source de vérité)
 *  1. /cra/absences — périodes km (toujours 1j, ne remplace pas la source 0)
 *  2. /cra/report   — fallback API si le CRA d'un autre mois est en mémoire
 */
async function buildAbsenceMap(monthStr, user) {
  const map = new Map();

  // Source 0 (prioritaire) : CRA chargé en mémoire pour ce mois exact
  const craMonth = document.getElementById('cra-month')?.value;
  if (craMonth === monthStr && allProjectEntries['__ABSENCE__']) {
    Object.entries(allProjectEntries['__ABSENCE__']).forEach(([dateStr, ae]) => {
      if (ae.type === 'ABSENT' && ae.value > 0) map.set(dateStr, ae.value);
    });
  }

  // Source 1 : absences km (notes de frais) — toujours 1j, ne remplace pas les entrées CRA
  const pAbs = new URLSearchParams({ month: monthStr, consultant: user.email });
  const resAbs = await fetch(`${base()}/cra/absences?${pAbs}`, { headers: authHeaders() });
  if (!resAbs.ok) throw new Error(`HTTP ${resAbs.status}`);
  const kmPeriods = await resAbs.json();
  kmPeriods.forEach(p => {
    for (let ms = new Date(p.from).getTime(); ms <= new Date(p.to).getTime(); ms += DAY_MS) {
      const d = new Date(ms).toISOString().substring(0, 10);
      if (!map.has(d)) map.set(d, 1);
    }
  });

  // Source 2 : CRA API — fallback si un autre mois est chargé en mémoire
  if (craMonth !== monthStr) {
    try {
      const pCra = new URLSearchParams({ start: monthStr, end: monthStr, consultant: user.email });
      const resCra = await fetch(`${base()}/cra/report?${pCra}`, { headers: authHeaders() });
      if (resCra.ok) {
        const items = await resCra.json();
        const myCra = items.find(item =>
          item.billingMonth === monthStr &&
          (item.consultant || '').trim().toLowerCase() === user.email.toLowerCase()
        );
        if (myCra?.entriesJson) {
          JSON.parse(myCra.entriesJson).forEach(e => {
            if (e.type === 'ABSENT' && e.value > 0) map.set(e.date, e.value);
          });
        }
      }
    } catch {
      // CRA indisponible — on continue avec les données km uniquement
    }
  }

  return map;
}

function renderAbsenceCalendar(monthStr, absenceMap) {
  const [y, m]      = monthStr.split('-').map(Number);
  const firstDow    = (new Date(y, m - 1, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(y, m, 0).getDate();
  const holidays    = craHolidays(y);

  document.getElementById('abs-cal-title').textContent = `${MONTHS_FR[m - 1]} ${y}`;

  let html = '';
  for (let i = 0; i < firstDow; i++) html += '<div class="cra-day empty"></div>';

  let totalAbsent = 0;
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr   = `${y}-${pad(m)}-${pad(d)}`;
    const dow       = new Date(y, m - 1, d).getDay();
    const isWeekend = dow === 0 || dow === 6;
    const isHoliday = holidays.has(dateStr);
    const abVal     = absenceMap.get(dateStr);

    let cls = 'cra-day';
    let lbl = '';
    if (isWeekend)          { cls += ' weekend'; }
    else if (isHoliday)     { cls += ' holiday'; lbl = 'Férié'; }
    else if (abVal === 1)   { cls += ' absent';  lbl = 'Abs.';  totalAbsent += 1; }
    else if (abVal === 0.5) { cls += ' half';    lbl = '½j';    totalAbsent += 0.5; }
    else                    { cls += ' full';    lbl = '1j'; }

    html += `<div class="${cls}">
      <span class="cra-day-num">${d}</span>
      <span class="cra-day-val">${lbl}</span>
    </div>`;
  }

  document.getElementById('abs-cal-grid').innerHTML = html;
  document.getElementById('abs-total-days').textContent =
    totalAbsent % 1 === 0 ? String(totalAbsent) : totalAbsent.toFixed(1);

  const periodEl = document.getElementById('abs-periods-list');
  if (!absenceMap.size) {
    periodEl.innerHTML = '<p class="muted-sm">Aucune absence enregistrée ce mois.</p>';
    return;
  }

  // Regrouper les jours consécutifs pour afficher les périodes.
  // Les demi-journées (0.5) ne sont jamais fusionnées — chacune a sa propre ligne.
  const sortedDays = [...absenceMap.keys()].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const periods = [];
  let from = sortedDays[0], to = sortedDays[0];
  for (let i = 1; i < sortedDays.length; i++) {
    const next       = new Date(new Date(to).getTime() + DAY_MS).toISOString().substring(0, 10);
    const toIsHalf   = absenceMap.get(to) === 0.5;
    const nextIsHalf = absenceMap.get(sortedDays[i]) === 0.5;
    if (next === sortedDays[i] && !toIsHalf && !nextIsHalf) {
      to = sortedDays[i];
    } else {
      periods.push({ from, to });
      from = sortedDays[i]; to = sortedDays[i];
    }
  }
  periods.push({ from, to });

  const fmtDate = d => `${d.getDate()} ${MONTHS_FR[d.getMonth()].toLowerCase()} ${d.getFullYear()}`;
  let periodHtml = '<div class="abs-periods">';
  periods.forEach(p => {
    const fromDate = new Date(p.from);
    const toDate   = new Date(p.to);
    const isHalf   = absenceMap.get(p.from) === 0.5 && p.from === p.to;
    const days     = isHalf ? 0.5 : Math.round((toDate - fromDate) / DAY_MS) + 1;
    const daysLabel = isHalf ? '½ jour' : `${days} jour${days > 1 ? 's' : ''}`;
    const rangeLabel = p.from === p.to
      ? fmtDate(fromDate)
      : `${fmtDate(fromDate)} → ${fmtDate(toDate)}`;
    periodHtml += `<div class="abs-period-row">
      <span class="abs-period-range">
        <svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
        ${rangeLabel}
      </span>
      <span class="abs-period-days">${daysLabel}</span>
    </div>`;
  });
  periodHtml += '</div>';
  periodEl.innerHTML = periodHtml;
}

// ============================================================
// NOTES DE FRAIS
// ============================================================

let notesAbsences = []; // [{ from: 'YYYY-MM-DD', to: 'YYYY-MM-DD' }]

function initNotes(user) {
  document.getElementById('notes-load-absences').addEventListener('click', () => loadAbsencesForNotes(user));
  document.getElementById('notes-add-absence').addEventListener('click',   addNotesAbsence);
  document.getElementById('notes-submit').addEventListener('click',        () => submitNotesSaisie(user));
  document.getElementById('notes-upload-btn').addEventListener('click',    () => uploadJustificatif(user));
  document.getElementById('notes-report-load').addEventListener('click',   () => loadNotesReport(user));
  document.getElementById('notes-report-pdf').addEventListener('click',    () => downloadNotesReport('pdf',   user));
  document.getElementById('notes-report-excel').addEventListener('click',  () => downloadNotesReport('excel', user));

  // Exemples express — pré-remplissent le textarea
  document.querySelectorAll('.btn-example').forEach(btn => {
    btn.addEventListener('click', () => {
      const textarea = document.getElementById('notes-text');
      const tpl = btn.dataset.exampleTpl;
      const currentMonth = new Date().toLocaleString('fr-FR', { month: 'long' });
      textarea.value = tpl
        ? tpl.replace('{month}', currentMonth).replace('{company}', tenantName())
        : btn.dataset.example;
      textarea.focus();
      textarea.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  });
}

async function loadNotesReport(user) {
  const monthStr = document.getElementById('notes-report-month').value;
  const statusEl = document.getElementById('notes-report-status');
  const listEl   = document.getElementById('notes-report-list');

  if (!monthStr) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Chargement…');
  listEl.style.display = 'none';

  try {
    const [y, mo] = monthStr.split('-').map(Number);
    const start   = `${y}-${pad(mo)}-01`;
    const end     = `${y}-${pad(mo)}-${new Date(y, mo, 0).getDate()}`;
    const p = new URLSearchParams({ start, end, consultantEmail: user.email });
    const res = await fetch(`${base()}/expenses/report?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data     = await res.json();
    const expenses = Array.isArray(data) ? data : (data.expenses || []);

    if (!expenses.length) {
      setStatus(statusEl, 'Aucune note de frais ce mois.', '');
      return;
    }

    setStatus(statusEl, `${expenses.length} dépense${expenses.length > 1 ? 's' : ''}.`, 'ok');
    listEl.innerHTML = renderConsultantExpenses(expenses);
    listEl.style.display = '';

    // PDF/Excel uniquement quand toutes les dépenses ont un statut final (APPROVED ou REFUSED)
    const allSettled = expenses.every(x => x.approvalStatus === 'APPROVED' || x.approvalStatus === 'REFUSED');
    document.getElementById('notes-report-pdf').style.display   = allSettled ? '' : 'none';
    document.getElementById('notes-report-excel').style.display = allSettled ? '' : 'none';
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function renderConsultantExpenses(expenses) {
  const APPROVAL_COLOR = { APPROVED: 'green', REFUSED: 'red', PENDING: 'orange' };
  const APPROVAL_LABEL = { APPROVED: 'Approuvé', REFUSED: 'Refusé', PENDING: 'En attente' };

  let html = `
    <div class="data-list">
      <div class="data-list-header" style="grid-template-columns:90px 100px 1fr 110px 110px">
        <span>Date</span><span>Type</span><span>Description</span><span>Montant</span><span>Statut</span>
      </div>`;
  for (const exp of expenses) {
    const color = APPROVAL_COLOR[exp.approvalStatus] || 'grey';
    const label = APPROVAL_LABEL[exp.approvalStatus] || '—';
    const amount = exp.amount != null
      ? Number(exp.amount).toFixed(2) + '\u00a0' + (exp.currency || 'EUR')
      : '—';
    const refusalNote = exp.approvalStatus === 'REFUSED' && exp.approvalNote
      ? `<span class="cell-sub refused-reason" style="font-style:italic">Motif : ${escapeHtml(exp.approvalNote)}</span>`
      : '';
    html += `
      <div class="data-list-row" style="grid-template-columns:90px 100px 1fr 110px 110px">
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(exp.date || '—')}</span>
        <span style="font-size:13px;font-weight:600">${escapeHtml(exp.type || '—')}</span>
        <div class="cell-primary">
          <span class="cell-name" style="font-size:13px;font-weight:500">${escapeHtml(exp.description || '')}</span>
          ${refusalNote}
        </div>
        <span class="cell-amount">${escapeHtml(amount)}</span>
        <span><span class="status-badge ${color}">${escapeHtml(label)}</span></span>
      </div>`;
  }
  html += '</div>';
  return html;
}

async function loadAbsencesForNotes(user) {
  const monthStr = document.getElementById('notes-km-month').value;
  const section  = document.getElementById('notes-absences-section');
  const statusEl = document.getElementById('notes-status');

  if (!monthStr) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Chargement des absences…');

  try {
    const p = new URLSearchParams({ month: monthStr, consultant: user.email });
    const res = await fetch(`${base()}/cra/absences?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    notesAbsences = await res.json();
    renderNotesAbsences();
    section.style.display = '';
    const n = notesAbsences.length;
    setStatus(statusEl, `${n} période${n !== 1 ? 's' : ''} d'absence chargée${n !== 1 ? 's' : ''}.`, n ? 'ok' : '');
  } catch (e) {
    notesAbsences = [];
    setStatus(statusEl, 'Erreur chargement absences : ' + e.message, 'err');
  }
}

function renderNotesAbsences() {
  const list = document.getElementById('notes-absences-list');
  if (!notesAbsences.length) {
    list.innerHTML = '<p class="muted-sm">Aucune absence. Ajoutez une période si besoin.</p>';
    return;
  }
  list.innerHTML = notesAbsences.map((p, i) => `
    <div class="absence-period-row" data-idx="${i}">
      <input type="date" class="abs-from" value="${p.from}" data-idx="${i}">
      <span class="abs-arrow">→</span>
      <input type="date" class="abs-to" value="${p.to}" data-idx="${i}">
      <button class="btn-remove-abs" data-idx="${i}" type="button">✕</button>
    </div>`).join('');

  list.querySelectorAll('.abs-from').forEach(inp => {
    inp.addEventListener('change', () => { notesAbsences[+inp.dataset.idx].from = inp.value; });
  });
  list.querySelectorAll('.abs-to').forEach(inp => {
    inp.addEventListener('change', () => { notesAbsences[+inp.dataset.idx].to = inp.value; });
  });
  list.querySelectorAll('.btn-remove-abs').forEach(btn => {
    btn.addEventListener('click', () => {
      notesAbsences.splice(+btn.dataset.idx, 1);
      renderNotesAbsences();
    });
  });
}

function addNotesAbsence() {
  const today = new Date().toISOString().substring(0, 10);
  notesAbsences.push({ from: today, to: today });
  renderNotesAbsences();
  document.getElementById('notes-absences-section').style.display = '';
}

function buildAbsenceInjection() {
  if (!notesAbsences.length) return '';
  const lines = notesAbsences.map(p =>
    p.from === p.to ? `absence le ${p.from}` : `absence du ${p.from} au ${p.to}`
  );
  return ' [absences: ' + lines.join(', ') + ']';
}

async function submitNotesSaisie(user) {
  const textEl   = document.getElementById('notes-text');
  const statusEl = document.getElementById('notes-status');
  const resultEl = document.getElementById('notes-result');
  const text     = textEl.value.trim();

  if (!text) { setStatus(statusEl, 'Saisissez un texte.', 'err'); return; }

  const fullText = text + buildAbsenceInjection();
  setStatus(statusEl, 'Analyse en cours…');
  resultEl.style.display = 'none';

  try {
    const res = await fetch(`${base()}/reasoning/analyze`, {
      method:  'POST',
      headers: authHeaders(),
      body:    JSON.stringify({ text: fullText, consultantEmail: user.email }),
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    await res.json();
    showToast('Note de frais enregistrée avec succès.', 'ok');
    setStatus(statusEl, '');
    textEl.value = '';
  } catch (e) {
    setStatus(statusEl, 'Erreur lors de l\'enregistrement. Réessayez ou contactez votre administrateur.', 'err');
  }
}

async function uploadJustificatif(user) {
  const fileInput = document.getElementById('notes-file');
  const modeEl    = document.getElementById('notes-payment-mode');
  const statusEl  = document.getElementById('notes-upload-status');

  if (!fileInput.files?.length) { setStatus(statusEl, 'Sélectionnez un fichier.', 'err'); return; }

  const fd = new FormData();
  fd.append('file', fileInput.files[0]);
  fd.append('paymentMode', modeEl.value);
  fd.append('consultantEmail', user.email);

  setStatus(statusEl, 'Upload en cours…');

  try {
    const token = _keycloak?.token;
    const res = await fetch(`${base()}/receipts/upload`, {
      method:  'POST',
      headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      body:    fd,
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    await res.json();
    showToast('Justificatif traité et note de frais créée.', 'ok');
    setStatus(statusEl, '');
    fileInput.value = '';
  } catch (e) {
    setStatus(statusEl, 'Erreur lors de l\'upload. Réessayez ou contactez votre administrateur.', 'err');
  }
}

async function downloadNotesReport(format, user) {
  const monthStr = document.getElementById('notes-report-month').value;
  const statusEl = document.getElementById('notes-report-status');

  if (!monthStr) { setStatus(statusEl, 'Sélectionnez un mois.', 'err'); return; }
  setStatus(statusEl, 'Téléchargement…');

  try {
    let url;
    if (format === 'pdf') {
      const p = new URLSearchParams({ month: monthStr, consultantEmail: user.email });
      url = `${base()}/expenses/report/pdf/month?${p}`;
    } else {
      const [y, mo] = monthStr.split('-').map(Number);
      const start   = `${y}-${pad(mo)}-01`;
      const end     = `${y}-${pad(mo)}-${new Date(y, mo, 0).getDate()}`;
      const p = new URLSearchParams({ start, end, consultantEmail: user.email });
      url = `${base()}/expenses/report/excel?${p}`;
    }

    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const link = document.createElement('a');
    link.href     = URL.createObjectURL(blob);
    link.download = format === 'pdf' ? `notes-${monthStr}.pdf` : `notes-${monthStr}.xlsx`;
    link.click();
    URL.revokeObjectURL(link.href);
    setStatus(statusEl, 'Téléchargement lancé.', 'ok');
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}
