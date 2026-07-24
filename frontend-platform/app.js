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

  // Billing SaaS
  document.getElementById('billing-refresh-btn').addEventListener('click', loadBilling);
  document.getElementById('sub-invoices-refresh-btn')?.addEventListener('click', loadSubscriptionInvoices);
  document.getElementById('billing-run-btn').addEventListener('click', runBillingNow);
  document.getElementById('billing-save').addEventListener('click', saveBillingModal);
  document.getElementById('billing-cancel').addEventListener('click', closeBillingModal);
  document.getElementById('billing-close').addEventListener('click', closeBillingModal);
  document.getElementById('billing-offer').addEventListener('change', () => {
    document.getElementById('billing-price-label').style.display =
      document.getElementById('billing-offer').value === 'ENTERPRISE' ? '' : 'none';
  });

  loadTenants();
  loadBilling();
  loadSubscriptionInvoices();
}

// ============================================================
// FACTURES D'ABONNEMENT — statut, encaissement, relances
// ============================================================
function invoiceBase() {
  return ((globalThis.APP_CONFIG || {}).invoiceBase || 'http://localhost:8083').replace(/\/$/, '');
}

const SUB_INV_STATUS = {
  EN_ATTENTE: { label: 'En attente', cls: 'starter' },
  ENVOYEE:    { label: 'Envoyée',    cls: 'enterprise' },
  PAYEE:      { label: 'Payée',      cls: 'pro' },
  EN_RETARD:  { label: 'En retard',  cls: 'inv-late' },
};
const DUN_LABEL = { 1: 'R1 rappel', 2: 'R2 relance', 3: 'R3 mise en demeure' };

async function loadSubscriptionInvoices() {
  const statusEl = document.getElementById('sub-invoices-status');
  const listEl   = document.getElementById('sub-invoices-list');
  if (!listEl) return;
  setStatus(statusEl, 'Chargement...');
  try {
    const res = await fetch(`${invoiceBase()}/invoices/platform/subscription-invoices`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const invoices = await res.json();

    if (!invoices.length) {
      setStatus(statusEl, '');
      listEl.innerHTML = `<p style="color:var(--muted);font-size:13px">Aucune facture d'abonnement émise pour l'instant — affectez une offre puis « Facturer maintenant ».</p>`;
      return;
    }

    const late = invoices.filter(i => i.paymentStatus === 'EN_RETARD');
    const lateSum = late.reduce((s, i) => s + (i.totalTtc || 0), 0);
    setStatus(statusEl, late.length
      ? `⚠ ${late.length} facture(s) en retard — ${fmtEur(lateSum)} TTC à recouvrer`
      : `${invoices.length} facture(s) — encaissements à jour`, late.length ? 'err' : 'ok');

    listEl.innerHTML = `<div class="tenant-grid">` + invoices.map(inv => {
      const st = SUB_INV_STATUS[inv.paymentStatus] || SUB_INV_STATUS.EN_ATTENTE;
      const dun = inv.lastDunningStage
        ? `<span style="color:#f59e0b">Relancé ${DUN_LABEL[inv.lastDunningStage] || 'R' + inv.lastDunningStage}${inv.lastDunningDate ? ' le ' + String(inv.lastDunningDate).slice(0, 10).split('-').reverse().join('/') : ''}</span>` : '';
      const canSend = inv.paymentStatus === 'EN_ATTENTE';
      const canPayOrDun = inv.paymentStatus === 'ENVOYEE' || inv.paymentStatus === 'EN_RETARD';
      return `
      <div class="tenant-row">
        <div class="status-dot ${inv.paymentStatus === 'PAYEE' ? 'active' : 'inactive'}"></div>
        <div class="tenant-info">
          <p class="tenant-name">${escapeHtml(inv.invoiceName)} — ${escapeHtml(inv.clientCompanyName || '—')}</p>
          <div class="tenant-meta">
            <span>${fmtEur(inv.totalTtc)} TTC</span>
            ${inv.paymentDueDate ? `<span>Échéance : ${escapeHtml(inv.paymentDueDate)}</span>` : ''}
            ${dun ? `<span>${dun}</span>` : ''}
          </div>
        </div>
        <span class="plan-badge ${st.cls}">${st.label}</span>
        <div class="tenant-actions">
          ${canSend ? `<button class="btn-edit" data-sub-inv-action="sent" data-id="${inv.id}">Envoyée</button>` : ''}
          ${canPayOrDun ? `<button class="btn-edit" data-sub-inv-action="dun" data-id="${inv.id}" style="color:#f59e0b;border-color:rgba(245,158,11,.4)">Relancer</button>` : ''}
          ${canPayOrDun ? `<button class="btn-edit" data-sub-inv-action="paid" data-id="${inv.id}" style="color:var(--accent);border-color:rgba(var(--accent-rgb),.4)">Payée ✓</button>` : ''}
        </div>
      </div>`;
    }).join('') + `</div>`;

    listEl.querySelectorAll('[data-sub-inv-action]').forEach(btn =>
      btn.addEventListener('click', () => subInvoiceAction(btn.dataset.subInvAction, btn.dataset.id, btn)));
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

async function subInvoiceAction(action, id, btn) {
  const statusEl = document.getElementById('sub-invoices-status');
  const conf = {
    sent: { method: 'PUT',  path: 'mark-sent', confirm: null,                                        ok: 'Facture marquée envoyée.' },
    paid: { method: 'PUT',  path: 'mark-paid', confirm: null,                                        ok: 'Facture marquée payée ✓' },
    dun:  { method: 'POST', path: 'dunning',   confirm: 'Envoyer une relance de paiement au client ?', ok: 'Relance envoyée.' },
  }[action];
  if (!conf) return;
  if (conf.confirm && !confirm(conf.confirm)) return;
  btn.disabled = true;
  try {
    const res = await fetch(`${invoiceBase()}/invoices/platform/${id}/${conf.path}`, {
      method: conf.method,
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: conf.method === 'PUT' && conf.path === 'mark-paid' ? JSON.stringify({}) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    if (action === 'dun') {
      if (data.status === 'SENT') setStatus(statusEl, `Relance envoyée (${data.recipient}).`, 'ok');
      else if (data.status === 'NO_EMAIL') setStatus(statusEl, `Aucun email de contact pour « ${data.client} » — renseignez la fiche client du tenant vendeur.`, 'err');
      else if (data.status === 'ERROR') setStatus(statusEl, `Échec d'envoi : ${data.error || 'serveur mail indisponible'}`, 'err');
      else setStatus(statusEl, conf.ok, 'ok');
    } else {
      setStatus(statusEl, conf.ok, 'ok');
    }
    loadSubscriptionInvoices();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
    btn.disabled = false;
  }
}

// ============================================================
// BILLING — abonnements SaaS & MRR
// ============================================================
const OFFER_MONTHLY = { ESSENTIEL: 29, CROISSANCE: 49 };
const fmtEur = v => v == null ? '—'
  : new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v);

async function loadBilling() {
  const statusEl = document.getElementById('billing-status');
  const kpisEl   = document.getElementById('billing-kpis');
  const listEl   = document.getElementById('billing-list');
  setStatus(statusEl, 'Chargement...');
  try {
    const res = await fetch(`${base()}/platform/billing/overview`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const d = await res.json();

    kpisEl.innerHTML = `
      <div class="billing-kpi accent"><span class="bk-label">MRR (HT)</span><span class="bk-value">${fmtEur(d.totalMrrHt)}</span></div>
      <div class="billing-kpi"><span class="bk-label">ARR (HT)</span><span class="bk-value">${fmtEur(d.totalArrHt)}</span></div>
      <div class="billing-kpi"><span class="bk-label">Organisations abonnées</span><span class="bk-value">${d.organizations.filter(o => o.sub_active).length} / ${d.organizations.length}</span></div>`;

    listEl.innerHTML = d.organizations.map(o => {
      const subscribed = o.sub_active === true;
      return `
      <div class="tenant-row">
        <div class="status-dot ${subscribed ? 'active' : 'inactive'}" title="${subscribed ? 'Abonné' : 'Sans abonnement actif'}"></div>
        <div class="tenant-info">
          <p class="tenant-name">${escapeHtml(o.name)}</p>
          <div class="tenant-meta">
            <span>${o.consultant_count} consultant(s) facturable(s)</span>
            ${subscribed ? `<span>Offre <code>${escapeHtml(o.offer)}</code> · ${escapeHtml((o.billing_period || '').toLowerCase())}</span>` : '<span>Aucune offre affectée</span>'}
            ${subscribed && o.next_invoice_date ? `<span>Prochaine facture : ${escapeHtml(o.next_invoice_date)}</span>` : ''}
          </div>
        </div>
        <span class="plan-badge ${subscribed ? 'pro' : 'starter'}">${subscribed ? 'MRR ' + fmtEur(o.mrrHt) : '—'}</span>
        <div class="tenant-actions">
          <button class="btn-edit" data-action="billing-edit" data-org='${escapeHtml(JSON.stringify(o))}'>${subscribed ? 'Modifier l\'offre' : 'Affecter une offre'}</button>
          ${subscribed ? `<button class="btn-danger" data-action="billing-suspend" data-id="${o.id}" data-name="${escapeHtml(o.name)}">Suspendre</button>` : ''}
        </div>
      </div>`;
    }).join('');

    document.querySelectorAll('[data-action="billing-edit"]').forEach(btn =>
      btn.addEventListener('click', () => openBillingModal(JSON.parse(btn.dataset.org))));
    document.querySelectorAll('[data-action="billing-suspend"]').forEach(btn =>
      btn.addEventListener('click', () => suspendBilling(btn.dataset.id, btn.dataset.name)));

    setStatus(statusEl, '', '');
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

function openBillingModal(org) {
  document.getElementById('billing-org-id').value    = org.id;
  document.getElementById('billing-org-name').value  = org.name;
  document.getElementById('billing-offer').value     = org.offer || 'CROISSANCE';
  document.getElementById('billing-period').value    = org.billing_period || 'ANNUEL';
  document.getElementById('billing-price').value     = org.negotiated_monthly_price_ht || '';
  document.getElementById('billing-address').value   = '';
  document.getElementById('billing-rcs').value       = '';
  document.getElementById('billing-start').value     = org.next_invoice_date || new Date().toISOString().slice(0, 10);
  document.getElementById('billing-price-label').style.display =
    document.getElementById('billing-offer').value === 'ENTERPRISE' ? '' : 'none';
  setStatus(document.getElementById('billing-modal-status'), '');
  document.getElementById('billing-modal').style.display = '';
}

function closeBillingModal() {
  document.getElementById('billing-modal').style.display = 'none';
}

async function saveBillingModal() {
  const statusEl = document.getElementById('billing-modal-status');
  const offer = document.getElementById('billing-offer').value;
  const body = {
    organizationId: document.getElementById('billing-org-id').value,
    offer,
    billingPeriod: document.getElementById('billing-period').value,
    negotiatedMonthlyPriceHt: offer === 'ENTERPRISE'
      ? parseFloat(document.getElementById('billing-price').value) || null : null,
    clientAddress: document.getElementById('billing-address').value.trim() || null,
    clientRcs: document.getElementById('billing-rcs').value.trim() || null,
    startDate: document.getElementById('billing-start').value
  };
  if (!body.startDate) { setStatus(statusEl, 'Date de début obligatoire.', 'err'); return; }
  setStatus(statusEl, 'Enregistrement...');
  try {
    const res = await fetch(`${base()}/platform/billing/subscription`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body)
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    closeBillingModal();
    loadBilling();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

async function suspendBilling(orgId, name) {
  if (!confirm(`Suspendre la facturation automatique de « ${name} » ?\n(L'accès au service n'est pas coupé.)`)) return;
  const statusEl = document.getElementById('billing-status');
  try {
    const res = await fetch(`${base()}/platform/billing/subscription/${orgId}/suspend`, {
      method: 'PUT', headers: authHeaders()
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    loadBilling();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
}

async function runBillingNow() {
  if (!confirm('Générer maintenant les factures des abonnements arrivés à échéance ?')) return;
  const statusEl = document.getElementById('billing-status');
  const invoiceBase = ((globalThis.APP_CONFIG || {}).invoiceBase || 'http://localhost:8083').replace(/\/$/, '');
  setStatus(statusEl, 'Facturation en cours...');
  try {
    const res = await fetch(`${invoiceBase}/invoices/subscription/run-billing`, {
      method: 'POST', headers: authHeaders()
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const results = await res.json();
    const invoiced = results.filter(r => r.status === 'INVOICED').length;
    const skipped  = results.filter(r => r.status !== 'INVOICED').length;
    setStatus(statusEl, `${invoiced} facture(s) générée(s)${skipped ? `, ${skipped} ignorée(s)/en erreur` : ''}.`, 'ok');
    loadBilling();
  } catch (e) {
    setStatus(statusEl, 'Erreur : ' + e.message, 'err');
  }
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
        name, slug,
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
  document.getElementById('edit-id').value            = tenant.id;
  document.getElementById('edit-name').value          = tenant.name || '';
  document.getElementById('edit-slug').value          = tenant.slug || '';
  document.getElementById('edit-plan-current').value  = tenant.plan || 'STARTER';
  document.getElementById('edit-active').checked      = tenant.active !== false;
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
  const plan   = document.getElementById('edit-plan-current').value;
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
