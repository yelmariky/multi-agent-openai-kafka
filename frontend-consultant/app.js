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
const DEFAULT_BASE              = _cfg.apiBase          || 'http://localhost:8081';
const DEFAULT_NOTIFICATION_BASE = _cfg.notificationBase || 'http://localhost:8084';

function base()      { return DEFAULT_BASE.replace(/\/$/, ''); }
function notifBase() { return DEFAULT_NOTIFICATION_BASE.replace(/\/$/, ''); }

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
          transition:opacity .15s;
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
    if (btn.dataset.tab === 'conges') {
      const session = getSession();
      if (session) loadLeaveTab(session);
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

/** Retourne le numéro de semaine ISO 8601 (lundi = 1er jour, S01 = semaine avec le 1er jeudi). */
function getISOWeek(date) {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  d.setUTCDate(d.getUTCDate() + 4 - (d.getUTCDay() || 7));
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  return Math.ceil((((d - yearStart) / 86400000) + 1) / 7);
}

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
        const toastType = (n.type === 'CRA_VALIDATED' || n.type === 'EXPENSE_APPROVED' || n.type === 'LEAVE_APPROVED') ? 'ok'
                        : (n.type === 'CRA_REFUSED'   || n.type === 'EXPENSE_REFUSED'  || n.type === 'LEAVE_REFUSED')  ? 'err'
                        : 'info';
        showToast(n.message, toastType);
        const dropdown = document.getElementById('notif-dropdown');
        if (!dropdown.classList.contains('hidden')) loadNotifications(user);
        if (n.type === 'EXPENSE_APPROVED' || n.type === 'EXPENSE_REFUSED') {
          const notesTab = document.getElementById('tab-notes');
          if (notesTab && notesTab.style.display !== 'none') loadNotesReport(user);
        }
        if (n.type === 'LEAVE_APPROVED' || n.type === 'LEAVE_REFUSED') {
          const congesTab = document.getElementById('tab-conges');
          if (congesTab && congesTab.classList.contains('active')) loadLeaveTab(user);
        }
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
        <span style="font-size:16px">${['CRA_VALIDATED','EXPENSE_APPROVED','LEAVE_APPROVED'].includes(n.type) ? '✅' : ['CRA_REFUSED','EXPENSE_REFUSED','LEAVE_REFUSED'].includes(n.type) ? '❌' : n.type === 'LEAVE_REQUESTED' ? '🏖️' : 'ℹ️'}</span>
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
  document.getElementById('notes-km-month').value     = curMonth;
  document.getElementById('notes-report-month').value = curMonth;
  initCra(user);
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

  // Auto-charger quand le consultant change le mois (si les projets sont déjà chargés)
  document.getElementById('cra-month').addEventListener('change', () => {
    if (craProjects.length > 0) loadCra(user);
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

  // ── Règle mois courant ────────────────────────────────────────────────────
  // Si le CRA du mois précédent est clôturé (date > 5 du mois suivant),
  // le consultant ne peut travailler que sur le mois courant : forcer et verrouiller.
  const now          = new Date();
  const curMonthStr  = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const prevDate     = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  const prevMonthStr = `${prevDate.getFullYear()}-${String(prevDate.getMonth() + 1).padStart(2, '0')}`;
  const monthInput   = document.getElementById('cra-month');
  const locked       = isCloture(prevMonthStr);

  if (locked) {
    // Mois précédent clôturé → forcer le mois courant et verrouiller le sélecteur
    monthInput.value = curMonthStr;
    monthInput.classList.add('cra-month-locked');
    monthInput.readOnly = true;
    monthInput.title = `Le CRA de ${prevMonthStr} est clôturé — vous saisissez le mois en cours (${curMonthStr}).`;
    // <input type="month"> ignore readOnly — on bloque les événements
    monthInput.addEventListener('mousedown', e => e.preventDefault(), true);
    monthInput.addEventListener('keydown',   e => e.preventDefault(), true);
    // Badge d'info sous le champ
    const lockNotice = document.createElement('p');
    lockNotice.className = 'cra-month-lock-notice';
    lockNotice.textContent = `Mois de saisie verrouillé — ${prevMonthStr} clôturé.`;
    monthInput.closest('.inline')?.after(lockNotice);
  } else {
    // Restaurer le dernier mois mémorisé (rechargement de page)
    const lastMonth = sessionStorage.getItem('cra:lastMonth');
    if (lastMonth && !monthInput.value) monthInput.value = lastMonth;
  }

  // Auto-charger le CRA si un mois est déjà sélectionné (lock ou restore)
  if (monthInput.value && craProjects.length > 0) {
    await loadCra(user);
  }
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

    // Source de vérité : TRIOS (assignments) configurés par l'admin.
    // Si des assignments existent, on les utilise EXCLUSIVEMENT — les missions/projects
    // historiques ne sont PAS ajoutés pour éviter l'apparition de projets fantômes.
    let assignmentLoaded = false;
    if (currentProfile?.id) {
      try {
        const aRes = await fetch(`${base()}/consultants/${currentProfile.id}/assignments`, { headers: authHeaders() });
        if (aRes.ok) {
          const assignments = await aRes.json();
          assignments.forEach(a => {
            if (!a.project?.id) return;
            if (craProjects.some(cp => cp.id === a.project.id)) return;
            const opt = document.createElement('option');
            opt.value = a.project.id;
            opt.dataset.type = 'assignment';
            opt.dataset.client = a.client?.name || '';
            opt.textContent = a.project.name + (a.client?.name ? ' — ' + a.client.name : '');
            sel.appendChild(opt);
            craProjects.push({
              id:                 a.project.id,
              name:               a.project.name,
              clientName:         a.client?.name || '',
              clientContactEmail: '',
              tjm:                a.tjm || null,
            });
          });
          assignmentLoaded = assignments.length > 0;
        }
      } catch { /* assignments non disponibles */ }
    }

    // Fallback : si aucun assignment, utiliser missions + projects historiques
    if (!assignmentLoaded) {
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

function downloadCraPdf() {
  if (!craId) return;
  const month    = document.getElementById('cra-month').value;
  const statusEl = document.getElementById('cra-action-status');

  // Mono-projet ou aucun projet → téléchargement direct
  if (craProjects.length <= 1) {
    downloadCraPdfById(craId, month, statusEl, craProjects[0]?.id, craProjects[0]?.name);
    return;
  }

  // Multi-projets → mini-menu de sélection
  showCraPdfMenu(month, statusEl);
}

function showHistPdfMenu(btn) {
  document.getElementById('hist-pdf-menu')?.remove();
  const craId   = btn.dataset.id;
  const month   = btn.dataset.month;
  const projs   = JSON.parse(btn.dataset.projects || '[]');

  const menu = document.createElement('div');
  menu.id = 'hist-pdf-menu';
  menu.className = 'cra-pdf-menu';

  // Option "Tout le CRA"
  const allOpt = document.createElement('button');
  allOpt.textContent = 'Tout le CRA (tous clients)';
  allOpt.addEventListener('click', () => { menu.remove(); downloadCraPdfById(craId, month); });
  menu.appendChild(allOpt);

  projs.forEach(proj => {
    const opt = document.createElement('button');
    opt.textContent = proj.name;
    opt.addEventListener('click', () => { menu.remove(); downloadCraPdfById(craId, month, null, proj.id, proj.name); });
    menu.appendChild(opt);
  });

  // Use fixed positioning to escape overflow:hidden on .data-list
  const rect = btn.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top  = (rect.bottom + 4) + 'px';
  menu.style.left = (rect.right - 240) + 'px'; // right-align to button
  menu.style.right = 'auto';

  const close = (e) => { if (!menu.contains(e.target) && e.target !== btn) { menu.remove(); document.removeEventListener('click', close); } };
  document.addEventListener('click', close);
  document.body.appendChild(menu);
}

function showCraPdfMenu(month, statusEl) {
  document.getElementById('cra-pdf-menu')?.remove();

  const btn  = document.getElementById('cra-download-pdf');
  const menu = document.createElement('div');
  menu.id = 'cra-pdf-menu';
  menu.className = 'cra-pdf-menu';

  // Option "Tout le CRA"
  const allOpt = document.createElement('button');
  allOpt.textContent = 'Tout le CRA (tous clients)';
  allOpt.addEventListener('click', () => { menu.remove(); downloadCraPdfById(craId, month, statusEl); });
  menu.appendChild(allOpt);

  // Une option par projet
  craProjects.forEach(proj => {
    const opt = document.createElement('button');
    opt.textContent = proj.name + (proj.clientName ? ` — ${proj.clientName}` : '');
    opt.addEventListener('click', () => {
      menu.remove();
      downloadCraPdfById(craId, month, statusEl, proj.id, proj.name);
    });
    menu.appendChild(opt);
  });

  // Fermer au clic extérieur
  setTimeout(() => {
    const close = (e) => {
      if (!menu.contains(e.target) && e.target !== btn) {
        menu.remove();
        document.removeEventListener('click', close);
      }
    };
    document.addEventListener('click', close);
  }, 0);

  btn.insertAdjacentElement('afterend', menu);
}

async function downloadCraPdfById(id, month, statusEl, projectId, projectName) {
  if (!id) return;
  try {
    const url = projectId
      ? `${base()}/cra/pdf/${id}?projectId=${projectId}`
      : `${base()}/cra/pdf/${id}`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const blob = await res.blob();
    const objUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objUrl;
    const suffix = projectName ? `-${projectName.replace(/\s+/g, '_')}` : '';
    a.download = `CRA-${month || id}${suffix}.pdf`;
    a.click();
    URL.revokeObjectURL(objUrl);
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
          // Conserver la source pour distinguer congé approuvé vs en attente
          const src = e.projectId === '__LEAVE__'         ? 'LEAVE'
                    : e.projectId === '__LEAVE_PENDING__'  ? 'LEAVE_PENDING'
                    : null;
          allProjectEntries['__ABSENCE__'][e.date] = { value: e.value || 1, type: 'ABSENT', source: src };
        }
        // Vider la cellule projet si elle existait (rétrocompat : ancienne absence stockée par projet).
        // Ne pas cibler '__ABSENCE__' lui-même — sinon on efface ce qu'on vient de poser.
        if (e.projectId && e.projectId !== '__ABSENCE__' && allProjectEntries[e.projectId]?.[e.date]) {
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

  // ── Pré-calcul : total attendu (jours ouvrés hors week-ends et fériés) ──
  const DAY_ABBR = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];
  let nbAttendu = 0;
  for (let d = 1; d <= daysInMonth; d++) {
    const dow     = new Date(y, m - 1, d).getDay();
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    if (dow !== 0 && dow !== 6 && !holidays.has(dateStr)) nbAttendu++;
  }

  // ── Groupes de semaines ISO pour le colspan ────────────────────────────
  const weekGroups = [];
  let curWG = null;
  for (let d = 1; d <= daysInMonth; d++) {
    const wn = getISOWeek(new Date(y, m - 1, d));
    if (!curWG || curWG.week !== wn) { curWG = { week: wn, count: 1 }; weekGroups.push(curWG); }
    else curWG.count++;
  }

  // ── En-tête 3 lignes ──────────────────────────────────────────────────
  // Ligne 1 : numéros de semaine (ex. S23, S24…)
  let hdr = '<thead><tr>';
  hdr += '<th class="cra-g-label cra-g-label-top" rowspan="3">Projet</th>';
  weekGroups.forEach(wg => {
    hdr += `<th colspan="${wg.count}" class="cra-g-week-hdr">S${wg.week}</th>`;
  });
  hdr += '</tr>';

  // Ligne 2 : abréviations des jours (sam. + dim. fusionnés en "samdim.")
  hdr += '<tr>';
  let da = 1;
  while (da <= daysInMonth) {
    const dow = new Date(y, m - 1, da).getDay();
    if (dow === 6 && da + 1 <= daysInMonth && new Date(y, m - 1, da + 1).getDay() === 0) {
      hdr += `<th colspan="2" class="cra-g-wkd cra-g-day-abbr">samdim.</th>`;
      da += 2;
    } else {
      const cls = (dow === 0 || dow === 6) ? 'cra-g-wkd' : '';
      hdr += `<th class="${cls} cra-g-day-abbr">${DAY_ABBR[dow]}</th>`;
      da++;
    }
  }
  hdr += '</tr>';

  // Ligne 3 : numéros des jours
  hdr += '<tr>';
  for (let d = 1; d <= daysInMonth; d++) {
    const dow = new Date(y, m - 1, d).getDay();
    const isWkd = dow === 0 || dow === 6;
    const cls = isWkd ? 'cra-g-wkd' : (incompleteSet.has(d) ? 'cra-g-hdr-warn' : '');
    hdr += `<th class="${cls}">${d}</th>`;
  }
  hdr += '</tr></thead>';

  // ── Corps : total attendu EN PREMIÈRE LIGNE, puis projets / absences / total ──
  let body = '<tbody>';

  // Ligne total attendu — première position
  body += `<tr class="cra-g-attendu"><td class="cra-g-label">Total attendu — ${nbAttendu} j</td>`;
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${y}-${pad(m)}-${pad(d)}`;
    const dow     = new Date(y, m - 1, d).getDay();
    const isWkd   = dow === 0 || dow === 6;
    const isFerie = holidays.has(dateStr);
    const cls     = isWkd ? 'cra-g-wkd' : isFerie ? 'cra-g-ferie' : 'cra-g-attendu-val';
    const lbl     = (!isWkd && !isFerie) ? '1' : '';
    body += `<td class="${cls}">${lbl}</td>`;
  }
  body += '</tr>';
  craProjects.forEach(proj => {
    const entries    = allProjectEntries[proj.id] || {};
    const projTotal  = Object.values(entries).reduce((s, e) => s + (e?.type === 'TRAVAIL' ? e.value : 0), 0);
    const projTotStr = projTotal % 1 === 0 ? `${projTotal}j` : `${projTotal.toFixed(1)}j`;
    body += `<tr><td class="cra-g-label">${escHtml(proj.name)}<span class="cra-g-row-total">${projTotStr}</span></td>`;
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
  const absEntries = allProjectEntries['__ABSENCE__'] || {};
  const absTotal   = Object.values(absEntries).reduce((s, e) => s + (e?.type === 'ABSENT' ? e.value : 0), 0);
  const absTotStr  = absTotal % 1 === 0 ? `${absTotal}j` : `${absTotal.toFixed(1)}j`;
  body += `<tr class="cra-g-absence-row"><td class="cra-g-label">Absence<span class="cra-g-row-total">${absTotStr}</span></td>`;
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
    const isFromLeave    = ae.source === 'LEAVE';
    const isFromPending  = ae.source === 'LEAVE_PENDING';
    const isLeafLocked   = isFromLeave || isFromPending;
    // Les congés (approuvés ou en attente) ne sont jamais bloqués par le TRAVAIL existant
    // (cas de transition : l'ancien TRAVAIL n'a pas encore été nettoyé en base)
    const absDisabled    = !isLeafLocked && projSum >= 1;
    let cls = isWkd       ? 'cra-g-wkd'
            : isFerie     ? 'cra-g-ferie'
            : absDisabled ? 'cra-g-disabled'
            : ae.type === 'EMPTY' ? 'cra-g-empty'
            : ae.value === 0.5   ? 'cra-g-abs-half'
            :                      'cra-g-abs-full';
    if (isFromLeave   && ae.type !== 'EMPTY') cls += ' cra-g-leave-lock';
    if (isFromPending && ae.type !== 'EMPTY') cls += ' cra-g-leave-pending';
    const lbl = isWkd || isFerie || absDisabled || ae.type === 'EMPTY' ? ''
              : isFromLeave   ? '🏖️'
              : isFromPending ? '⏳'
              : ae.value === 0.5 ? '½A' : 'A';
    // Absences manuelles anciennes (sans congé = legacy) → retirables par clic uniquement
    const isManualLegacy = !isLeafLocked && !isWkd && !isFerie && !absDisabled && ae.type === 'ABSENT';
    if (!readonly && isManualLegacy) cls += ' clickable cra-g-abs-legacy';
    const title = isFromLeave   ? 'Congé approuvé'
                : isFromPending ? 'Congé en attente de validation RH'
                : isManualLegacy ? 'Absence sans congé associé — cliquez pour retirer puis créez une demande de congé'
                : '';
    body += `<td class="${cls}" data-pid="__ABSENCE__" data-date="${dateStr}" ${title ? `title="${title}"` : ''}>${lbl}</td>`;
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
          // Absence legacy (manuelle sans congé) : permettre le retrait uniquement
          const ae = allProjectEntries['__ABSENCE__'][date];
          if (ae && ae.type === 'ABSENT' && !ae.source) {
            allProjectEntries['__ABSENCE__'][date] = { value: 0, type: 'EMPTY' };
          }
        } else {
          // Cellule projet — cycle EMPTY → 1j → ½j → EMPTY
          // L'ajout manuel d'absences n'est plus possible : passer par une demande de congé
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

function updateCraTotal() { updateCraProgress(); }

function updateCraProgress() {
  // Jours ouvrés du mois sélectionné
  const monthStr = document.getElementById('cra-month')?.value;
  let ouvrés = 0;
  const workdays = new Set(); // dateStr des jours ouvrés
  if (monthStr) {
    const [y, m] = monthStr.split('-').map(Number);
    const daysInMonth = new Date(y, m, 0).getDate();
    const holidays = craHolidays(y);
    for (let d = 1; d <= daysInMonth; d++) {
      const dow     = new Date(y, m - 1, d).getDay();
      const dateStr = `${y}-${pad(m)}-${pad(d)}`;
      if (dow !== 0 && dow !== 6 && !holidays.has(dateStr)) { ouvrés++; workdays.add(dateStr); }
    }
  }

  // Pour chaque jour ouvré : couverts = TRAVAIL (projets) + ABSENT, plafonné à 1
  let saisi = 0;
  workdays.forEach(dateStr => {
    let travail = 0;
    craProjects.forEach(proj => {
      const e = (allProjectEntries[proj.id] || {})[dateStr];
      if (e?.type === 'TRAVAIL') travail += e.value;
    });
    const absent = allProjectEntries['__ABSENCE__']?.[dateStr]?.value || 0;
    saisi += Math.min(travail + absent, 1);
  });

  const pct = ouvrés > 0 ? (saisi / ouvrés) * 100 : 0;
  const pctStr = pct.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '\u00a0%';
  const saisiStr = saisi % 1 === 0 ? String(saisi) : saisi.toFixed(1);

  const pctEl   = document.getElementById('cra-progress-pct');
  const fillEl  = document.getElementById('cra-progress-bar-fill');
  const labelEl = document.getElementById('cra-progress-label');
  if (pctEl)   pctEl.textContent   = pctStr;
  if (fillEl)  fillEl.style.width  = Math.min(pct, 100) + '%';
  if (labelEl) labelEl.textContent = `${saisiStr}\u00a0/ ${ouvrés} jours ouvrés`;
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
  // Masquer barre de progression et légende quand CRA non modifiable (VALIDE / CLOTURE)
  const isReadonlyFinal = effectiveStatus === 'VALIDE' || effectiveStatus === 'CLOTURE';
  const progressWrap = document.getElementById('cra-progress-wrap');
  const legendEl     = document.getElementById('cra-legend');
  if (progressWrap) progressWrap.style.display = isReadonlyFinal ? 'none' : '';
  if (legendEl)     legendEl.style.display     = isReadonlyFinal ? 'none' : '';
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

  // Mémoriser le mois pour le restaurer après un rechargement de page
  sessionStorage.setItem('cra:lastMonth', monthStr);

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

      // Migration : si des entrées TRAVAIL référencent d'anciens projectIds (ex : EDF → expert kafka),
      // les remap vers l'assignment courant pour éviter des lignes fantômes dans la grille.
      // IMPORTANT : ne jamais remap les entrées ABSENT — leur projectId '__ABSENCE__' est intentionnel.
      if (craProjects.length > 0) {
        const validPids = new Set(craProjects.map(p => p.id));
        const mainPid   = craProjects[0].id;
        saved = saved.map(e => {
          if (e.type === 'ABSENT') return e;                           // absences → ne pas toucher
          return { ...e, projectId: validPids.has(e.projectId) ? e.projectId : mainPid };
        });
      }

      // Réinitialiser la sélection pour forcer le premier projet
      currentSelectedProjectId = null;
      initCraMonth(monthStr, saved);

      // Fallback : si loadMissions() n'a pas peuplé craProjects mais le CRA a des entrées
      // sauvegardées, on crée UN SEUL slot projet (pas un par projectId orphelin) et on
      // consolide toutes les entrées dedans.
      if (craProjects.length === 0) {
        const knownPids = Object.keys(allProjectEntries)
          .filter(k => k !== '__ABSENCE__' && k !== '__none__');
        if (knownPids.length > 0) {
          const mainPid = knownPids[0];

          // Fusionner les entrées des projectIds orphelins dans mainPid
          knownPids.slice(1).forEach(pid => {
            const src = allProjectEntries[pid] || {};
            Object.entries(src).forEach(([date, val]) => {
              if (val?.value > 0 && val.type !== 'EMPTY') {
                const cur = allProjectEntries[mainPid]?.[date];
                if (!cur || cur.type === 'EMPTY' || cur.value === 0) {
                  allProjectEntries[mainPid][date] = val;
                }
              }
            });
            delete allProjectEntries[pid];
          });

          // Un seul slot projet — le nom sera enrichi depuis les assignments
          craProjects.push({ id: mainPid, name: 'Projet',
            clientName: '', clientContactEmail: existing.clientContactEmail || '', tjm: null });

          // Enrichir depuis les assignments (source de vérité admin)
          if (currentProfile?.id) {
            try {
              const aRes = await fetch(`${base()}/consultants/${currentProfile.id}/assignments`, { headers: authHeaders() });
              if (aRes.ok) {
                const assignments = await aRes.json();
                const a = assignments[0]; // premier assignment = projet actif
                if (a?.project) {
                  craProjects[0].name      = a.project.name;
                  craProjects[0].clientName = a.client?.name || '';
                  // Remap les clés allProjectEntries vers le vrai projectId de l'assignment
                  if (a.project.id !== mainPid && allProjectEntries[mainPid]) {
                    allProjectEntries[a.project.id] = allProjectEntries[mainPid];
                    delete allProjectEntries[mainPid];
                    craProjects[0].id = a.project.id;
                  }
                }
              }
            } catch { /* ignore */ }
          }

          currentSelectedProjectId = craProjects[0].id;
          craEntries = allProjectEntries[currentSelectedProjectId];
        }
      }

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
      // Ne persister que les jours réellement saisis (TRAVAIL/ABSENT) — WEEKEND/FERIE sont recalculés côté client
      if (e.type !== 'TRAVAIL' && e.type !== 'ABSENT') return;
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
      btn.addEventListener('click', () => downloadCraPdfById(btn.dataset.id, btn.dataset.month, null, btn.dataset.pid || undefined, btn.dataset.pname || undefined));
    });
    resultEl.querySelectorAll('.hist-pdf-multi').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        showHistPdfMenu(btn);
      });
    });
    resultEl.style.display = 'block';
  } catch (e) { setStatus(statusEl, 'Erreur : ' + e.message, 'err'); }
}

function renderHistPdfBtn(cra) {
  const pdfIcon = `<svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><line x1="9" y1="15" x2="15" y2="15"/></svg>`;
  const projs = Array.isArray(cra.projects) ? cra.projects.filter(p => p.id) : [];
  if (projs.length <= 1) {
    // Mono-projet : bouton simple
    const pid = projs[0]?.id || '';
    return `<button class="btn-pdf hist-pdf-btn" data-id="${escapeHtml(cra.id)}" data-month="${escapeHtml(cra.billingMonth || '')}" data-pid="${escapeHtml(pid)}" data-pname="${escapeHtml(projs[0]?.name || '')}" title="Télécharger PDF">${pdfIcon} PDF</button>`;
  }
  // Multi-projets : bouton avec chevron + data JSON
  const projsJson = escapeHtml(JSON.stringify(projs));
  return `<button class="btn-pdf hist-pdf-multi" data-id="${escapeHtml(cra.id)}" data-month="${escapeHtml(cra.billingMonth || '')}" data-projects="${projsJson}" title="Choisir le PDF à télécharger">${pdfIcon} PDF ▾</button>`;
}

function renderHistoryList(items) {
  const STATUS_COLOR = { BROUILLON: 'grey', SOUMIS: 'orange', VALIDE: 'green', REFUSE: 'red', CLOTURE: 'purple' };
  const STATUS_LABEL = { BROUILLON: 'Brouillon', SOUMIS: 'Soumis', VALIDE: 'Validé', REFUSE: 'Refusé', CLOTURE: 'Clôturé' };
  const COLS = '1fr 1fr 80px 110px 1fr 100px';
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
        <span style="position:relative">${cra.id ? renderHistPdfBtn(cra) : ''}</span>
      </div>`;
  }
  html += '</div>';
  return html;
}

// ============================================================
// ABSENCES
// ============================================================
// ============================================================
// NOTES DE FRAIS
// ============================================================

let notesAbsences = []; // [{ from: 'YYYY-MM-DD', to: 'YYYY-MM-DD' }]

function initNotes(user) {
  document.getElementById('notes-load-absences').addEventListener('click', () => loadAbsencesForNotes(user));
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

    const kpisEl = document.getElementById('notes-report-kpis');
    if (!expenses.length) {
      setStatus(statusEl, 'Aucune note de frais ce mois.', '');
      if (kpisEl) kpisEl.style.display = 'none';
      return;
    }

    // --- KPIs ---
    if (kpisEl) {
      const totalPerso    = expenses.filter(x => (x.paymentMode || '').toLowerCase().includes('personnel'))
                                    .reduce((s, x) => s + (x.amount || 0), 0);
      const totalBusiness = expenses.filter(x => (x.paymentMode || '').toLowerCase().includes('business'))
                                    .reduce((s, x) => s + (x.amount || 0), 0);
      const approved = expenses.filter(x => x.approvalStatus === 'APPROVED').length;
      const pending  = expenses.filter(x => !x.approvalStatus || x.approvalStatus === 'PENDING').length;
      const fmt = v => v > 0 ? Number(v).toFixed(2) + ' €' : '0 €';
      document.getElementById('kpi-count').textContent    = expenses.length;
      document.getElementById('kpi-perso').textContent    = fmt(totalPerso);
      document.getElementById('kpi-business').textContent = fmt(totalBusiness);
      document.getElementById('kpi-approved').textContent = approved;
      document.getElementById('kpi-pending').textContent  = pending;
      kpisEl.style.display = '';
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

  const COLS = '90px 140px 1fr 120px 110px';
  let html = `
    <div class="data-list">
      <div class="data-list-header" style="grid-template-columns:${COLS}">
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
    const typeLabel = (exp.type || '—').replace(/_/g, ' ');
    html += `
      <div class="data-list-row" style="grid-template-columns:${COLS}">
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(exp.date || '—')}</span>
        <span class="expense-type-cell">${escapeHtml(typeLabel)}</span>
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
    // Source 1 : CRA chargé en mémoire
    const craMonth = document.getElementById('cra-month')?.value;
    if (craMonth === monthStr && craId && allProjectEntries['__ABSENCE__']) {
      let periods = Object.entries(allProjectEntries['__ABSENCE__'])
        .filter(([, ae]) => ae.type === 'ABSENT' && ae.value > 0)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([date]) => ({ from: date, to: date }));
      notesAbsences = await mergeApprovedLeaves(periods, user, monthStr);
      renderNotesAbsences(); section.style.display = '';
      const n = notesAbsences.length;
      setStatus(statusEl, `${n} absence${n !== 1 ? 's' : ''} chargée${n !== 1 ? 's' : ''} (CRA + congés).`, n ? 'ok' : '');
      return;
    }

    // Source 2 : CRA en base
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
          let periods = JSON.parse(myCra.entriesJson)
            .filter(e => e.type === 'ABSENT' && e.value > 0)
            .sort((a, b) => a.date.localeCompare(b.date))
            .map(e => ({ from: e.date, to: e.date }));
          notesAbsences = await mergeApprovedLeaves(periods, user, monthStr);
          renderNotesAbsences(); section.style.display = '';
          const n = notesAbsences.length;
          setStatus(statusEl, `${n} absence${n !== 1 ? 's' : ''} chargée${n !== 1 ? 's' : ''} (CRA + congés).`, n ? 'ok' : '');
          return;
        }
      }
    } catch { /* fallback */ }

    // Source 3 : congés approuvés seuls (pas de CRA ce mois)
    const leavePeriods = await fetchApprovedLeavePeriods(user, monthStr);
    if (leavePeriods.length) {
      notesAbsences = leavePeriods;
      renderNotesAbsences(); section.style.display = '';
      const n = notesAbsences.length;
      setStatus(statusEl, `${n} congé${n !== 1 ? 's' : ''} approuvé${n !== 1 ? 's' : ''} chargé${n !== 1 ? 's' : ''}.`, 'ok');
      return;
    }

    // Source 4 : km (dernier recours)
    const p = new URLSearchParams({ month: monthStr, consultant: user.email });
    const res = await fetch(`${base()}/cra/absences?${p}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    notesAbsences = await res.json();
    renderNotesAbsences(); section.style.display = '';
    const n = notesAbsences.length;
    setStatus(statusEl, `${n} période${n !== 1 ? 's' : ''} d'absence chargée${n !== 1 ? 's' : ''}.`, n ? 'ok' : '');
  } catch (e) {
    notesAbsences = [];
    setStatus(statusEl, 'Erreur chargement absences : ' + e.message, 'err');
  }
}

/** Congés APPROUVÉS du consultant pour le mois, sous forme {from, to, type}. */
async function fetchApprovedLeavePeriods(user, monthStr) {
  try {
    const res = await fetch(
      `${base()}/leaves/mine?consultantEmail=${encodeURIComponent(user.email)}&status=APPROUVEE`,
      { headers: authHeaders() }
    );
    if (!res.ok) return [];
    const leaves = await res.json();
    const [y, m] = monthStr.split('-').map(Number);
    const monthStart = `${y}-${String(m).padStart(2,'0')}-01`;
    const monthEnd   = `${y}-${String(m).padStart(2,'0')}-${new Date(y, m, 0).getDate()}`;
    return leaves
      .filter(l => l.startDate <= monthEnd && l.endDate >= monthStart)
      .map(l => ({ from: l.startDate, to: l.endDate, type: l.type }));
  } catch { return []; }
}

/** Fusionne absences CRA avec congés approuvés (déduplique par date). */
async function mergeApprovedLeaves(existing, user, monthStr) {
  const leavePeriods = await fetchApprovedLeavePeriods(user, monthStr);
  if (!leavePeriods.length) return existing;
  const existingDates = new Set(existing.map(p => p.from));
  const extra = [];
  for (const lp of leavePeriods) {
    let d = new Date(lp.from);
    const end = new Date(lp.to);
    while (d <= end) {
      const ds = d.toISOString().substring(0, 10);
      if (!existingDates.has(ds)) extra.push({ from: ds, to: ds, type: lp.type });
      d = new Date(d.getTime() + DAY_MS);
    }
  }
  return [...existing, ...extra].sort((a, b) => a.from.localeCompare(b.from));
}

/** Fusionne des entrées jour-par-jour en plages continues ({from, to}[]). */
function mergeConsecutiveDays(periods) {
  if (!periods.length) return [];
  const sorted = [...periods].sort((a, b) => a.from.localeCompare(b.from));
  const ranges = [];
  let cur = { from: sorted[0].from, to: sorted[0].to };
  for (let i = 1; i < sorted.length; i++) {
    const prev = new Date(cur.to);
    const next = new Date(sorted[i].from);
    prev.setDate(prev.getDate() + 1);
    if (prev >= next) {
      if (sorted[i].to > cur.to) cur.to = sorted[i].to;
    } else {
      ranges.push(cur);
      cur = { from: sorted[i].from, to: sorted[i].to };
    }
  }
  ranges.push(cur);
  return ranges;
}

function fmtDate(iso) {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

function renderNotesAbsences() {
  const summary = document.getElementById('notes-absences-summary');
  const list    = document.getElementById('notes-absences-list');

  if (!notesAbsences.length) {
    summary.innerHTML = '<p class="muted-sm">Aucune absence trouvée pour ce mois.</p>';
    list.style.display = 'none';
    return;
  }

  const n      = notesAbsences.length;
  const ranges = mergeConsecutiveDays(notesAbsences);
  const label  = ranges.length === 1 && ranges[0].from === ranges[0].to
    ? fmtDate(ranges[0].from)
    : ranges.map(r => r.from === r.to ? fmtDate(r.from) : `${fmtDate(r.from)} – ${fmtDate(r.to)}`).join(', ');

  summary.innerHTML = `
    <div class="absence-summary-pill">
      <span class="absence-summary-check">✓</span>
      <span class="absence-summary-text">${n} jour${n > 1 ? 's' : ''} d'absence — ${label}</span>
      <button class="absence-summary-toggle" type="button">Modifier ▾</button>
    </div>`;

  summary.querySelector('.absence-summary-toggle').addEventListener('click', function () {
    const open = list.style.display !== 'none';
    list.style.display = open ? 'none' : '';
    this.textContent = open ? 'Modifier ▾' : 'Réduire ▴';
    if (!open) renderAbsenceList(list);
  });
}

function renderAbsenceList(list) {
  list.innerHTML = notesAbsences.map((p, i) => `
    <div class="absence-period-row" data-idx="${i}">
      <input type="date" class="abs-from" value="${p.from}" data-idx="${i}">
      <span class="abs-arrow">→</span>
      <input type="date" class="abs-to" value="${p.to}" data-idx="${i}">
      <button class="btn-remove-abs" data-idx="${i}" type="button">✕</button>
    </div>`).join('');

  list.querySelectorAll('.abs-from').forEach(inp => {
    inp.addEventListener('change', () => { notesAbsences[+inp.dataset.idx].from = inp.value; renderNotesAbsences(); });
  });
  list.querySelectorAll('.abs-to').forEach(inp => {
    inp.addEventListener('change', () => { notesAbsences[+inp.dataset.idx].to = inp.value; renderNotesAbsences(); });
  });
  list.querySelectorAll('.btn-remove-abs').forEach(btn => {
    btn.addEventListener('click', () => {
      notesAbsences.splice(+btn.dataset.idx, 1);
      renderNotesAbsences();
      if (notesAbsences.length) renderAbsenceList(list);
    });
  });
}

function buildAbsenceInjection() {
  if (!notesAbsences.length) return '';
  const lines = notesAbsences.map(p =>
    p.from === p.to ? `absence le ${p.from}` : `absence du ${p.from} au ${p.to}`
  );
  return ' [absences: ' + lines.join(', ') + ']';
}

// Mots-clés bloqués côté client avant même d'appeler le serveur.
// Double protection : le serveur bloque aussi, mais on évite l'appel réseau inutile.
// Note : "facture" seul n'est PAS bloqué car un consultant peut écrire
// "j'ai payé une facture de taxi/restaurant" — c'est une note de frais légitime.
const EXPENSE_BLOCKED_PATTERNS = [
  // Verbes destructifs FR
  /\b(supprim|effac|enlev|retir|vider|détruir|nettoy|écras)/i,
  // Verbes destructifs EN
  /\b(delete|remove|erase|wipe|destroy|drop|truncate|purge)\b/i,
  // Gestion de factures en masse (pas "une facture de restaurant")
  /\bfactures\b.{0,40}(du mois|de mai|de juin|de janvier|de f[eé]vrier|de mars|d'avril|de juillet|d'ao[uû]t|de septembre|d'octobre|de novembre|de d[eé]cembre|\d{4})/i,
  /\b(toutes?|tous?)\s+(les|des)\s+(factures?|invoices?|clients?|utilisateurs?|comptes?|consultants?)\b/i,
];

function isBlockedExpenseInput(text) {
  return EXPENSE_BLOCKED_PATTERNS.some(p => p.test(text));
}

async function submitNotesSaisie(user) {
  const textEl   = document.getElementById('notes-text');
  const statusEl = document.getElementById('notes-status');
  const resultEl = document.getElementById('notes-result');
  const text     = textEl.value.trim();

  if (!text) { setStatus(statusEl, 'Saisissez un texte.', 'err'); return; }

  // ── Garde client : rejeter les saisies hors périmètre sans appel serveur ──
  if (isBlockedExpenseInput(text)) {
    setStatus(statusEl,
      'Saisie non autorisée. Ce champ accepte uniquement des notes de frais (repas, transport, hébergement…). ' +
      'Les suppressions et les opérations sur les factures ne sont pas acceptées ici.', 'err');
    return;
  }

  const fullText = text + buildAbsenceInjection();
  setStatus(statusEl, 'Analyse en cours…');
  resultEl.style.display = 'none';

  try {
    const res = await fetch(`${base()}/reasoning/analyze`, {
      method:  'POST',
      headers: { ...authHeaders(), 'X-Source': 'consultant-frontend' },
      body:    JSON.stringify({ text: fullText, consultantEmail: user.email }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const msg  = body.error || ('Erreur serveur (' + res.status + ')');
      setStatus(statusEl, msg, 'err');
      return;
    }
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

  setStatus(statusEl, 'Analyse du justificatif en cours… (peut prendre quelques secondes)');

  try {
    const token = _keycloak?.token;
    const res = await fetch(`${base()}/receipts/upload`, {
      method:  'POST',
      headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      body:    fd,
    });
    if (!res.ok) throw new Error('Erreur serveur (' + res.status + ')');
    const data = await res.json();

    // Déterminer le mois de la dépense créée (date extraite ou mois courant)
    let expenseMonth = null;
    try {
      const expJson = data?.extractedExpenseJson ? JSON.parse(data.extractedExpenseJson) : null;
      if (expJson?.date) expenseMonth = expJson.date.substring(0, 7); // YYYY-MM
    } catch { /* ignore */ }
    if (!expenseMonth) expenseMonth = new Date().toISOString().substring(0, 7);

    showToast('Justificatif traité — note de frais créée pour ' + expenseMonth + '.', 'ok');
    setStatus(statusEl, '');
    fileInput.value = '';

    // Auto-charger le rapport du mois de la dépense créée
    const monthInput = document.getElementById('notes-report-month');
    if (monthInput) {
      monthInput.value = expenseMonth;
      // Scroll vers le rapport et charger
      monthInput.closest('.card')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      await loadNotesReport(user);
    }
  } catch (e) {
    setStatus(statusEl, 'Erreur lors de l\'upload : ' + e.message + '. Réessayez.', 'err');
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

// ============================================================
// CONGÉS CP/RTT
// ============================================================

async function loadLeaveTab(user) {
  await Promise.all([
    loadLeaveBalance(user),
    loadLeaveHistory(user),
  ]);
  // Wiring bouton de soumission
  const btn = document.getElementById('leave-submit-btn');
  if (btn && !btn._wired) {
    btn._wired = true;
    btn.addEventListener('click', () => submitLeaveRequest(user));
  }
}

async function loadLeaveBalance(user) {
  const year = new Date().getFullYear();
  const el   = document.getElementById('leave-balance-cards');
  if (!el) return;
  try {
    const res = await fetch(`${base()}/leaves/balance?consultantEmail=${encodeURIComponent(user.email)}&year=${year}`, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const b = await res.json();
    el.innerHTML = `
      <div class="leave-balance-card">
        <span class="leave-balance-label">CP restants</span>
        <span class="leave-balance-value accent">${Number(b.cpRemaining).toFixed(1)} j</span>
        <span class="leave-balance-sub">${Number(b.cpTaken).toFixed(1)} pris / ${Number(b.cpInitial).toFixed(1)} initial</span>
      </div>
      <div class="leave-balance-card">
        <span class="leave-balance-label">RTT restants</span>
        <span class="leave-balance-value" style="color:#7ad7ff">${Number(b.rttRemaining).toFixed(1)} j</span>
        <span class="leave-balance-sub">${Number(b.rttTaken).toFixed(1)} pris / ${Number(b.rttInitial).toFixed(1)} initial</span>
      </div>`;
  } catch (e) {
    el.innerHTML = `<span style="color:var(--muted);font-size:12px">Solde indisponible</span>`;
  }
}

async function loadLeaveHistory(user) {
  const statusEl = document.getElementById('leave-history-status');
  const listEl   = document.getElementById('leave-history-list');
  setStatus(statusEl, 'Chargement…');
  try {
    const res = await fetch(`${base()}/leaves/mine?consultantEmail=${encodeURIComponent(user.email)}`, { headers: authHeaders() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const items = await res.json();
    setStatus(statusEl, '');
    if (!items.length) { listEl.innerHTML = '<p class="muted-sm">Aucune demande de congé.</p>'; return; }
    const COLS = '90px 90px 90px 80px 100px 1fr';
    const STATUS_COLOR = { DEMANDEE: 'orange', APPROUVEE: 'green', REFUSEE: 'red' };
    const STATUS_LABEL = { DEMANDEE: 'En attente', APPROUVEE: 'Approuvée', REFUSEE: 'Refusée' };
    let html = `<div class="data-list">
      <div class="data-list-header" style="grid-template-columns:${COLS}">
        <span>Du</span><span>Au</span><span>Jours</span><span>Type</span><span>Statut</span><span>Motif refus</span>
      </div>`;
    items.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || '')).forEach(l => {
      const color  = STATUS_COLOR[l.status] || 'grey';
      const label  = STATUS_LABEL[l.status] || l.status;
      html += `<div class="data-list-row" style="grid-template-columns:${COLS}">
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(l.startDate || '')}</span>
        <span style="font-size:12px;font-weight:600;color:var(--accent-2)">${escapeHtml(l.endDate || '')}</span>
        <span style="font-weight:600">${l.daysCount}j</span>
        <span class="expense-type-cell">${escapeHtml(l.type || '')}</span>
        <span><span class="status-badge ${color}">${escapeHtml(label)}</span></span>
        <span class="cell-muted" style="font-size:12px;font-style:italic">${escapeHtml(l.refusedReason || l.reason || '')}</span>
      </div>`;
    });
    html += '</div>';
    listEl.innerHTML = html;
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

async function submitLeaveRequest(user) {
  const type   = document.getElementById('leave-type').value;
  const start  = document.getElementById('leave-start').value;
  const end    = document.getElementById('leave-end').value;
  const reason = document.getElementById('leave-reason').value.trim();
  const statusEl = document.getElementById('leave-submit-status');

  if (!start || !end) { setStatus(statusEl, 'Veuillez sélectionner les dates.', 'err'); return; }
  setStatus(statusEl, 'Envoi en cours…');
  try {
    const res = await fetch(`${base()}/leaves/request`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ consultantEmail: user.email, type, startDate: start, endDate: end, reason }),
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    setStatus(statusEl, 'Demande envoyée avec succès.', 'ok');
    document.getElementById('leave-start').value = '';
    document.getElementById('leave-end').value   = '';
    document.getElementById('leave-reason').value = '';
    document.getElementById('leave-request-details').removeAttribute('open');
    await loadLeaveHistory(user);
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}
