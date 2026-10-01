'use strict';

/* User Lab dashboard. Plain JavaScript, no framework.
 * Every HTTP call goes through api(), which also writes the request log. */

const LANGS = ['en', 'ta', 'hi', 'ml', 'kn', 'te'];
const ROLES = ['ADMIN', 'SUPPORT', 'USER'];
const CHANNELS = ['EMAIL', 'SMS', 'PUSH'];
const STATUSES = ['PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DELETED'];
const ADDRESS_TYPES = ['HOME', 'WORK', 'BILLING'];
const SORTS = { 'id,asc': 'Id (oldest first)', 'username,asc': 'Username A-Z', 'username,desc': 'Username Z-A',
  'lastName,asc': 'Last name A-Z', 'createdAt,desc': 'Newest first', 'status,asc': 'Status' };
/* Status changes the API allows through PATCH. Keep in sync with UserService.TRANSITIONS. */
const NEXT_STATUS = { PENDING_VERIFICATION: [['ACTIVE', 'Activate']], ACTIVE: [['SUSPENDED', 'Suspend']],
  SUSPENDED: [['ACTIVE', 'Reactivate']], DELETED: [] };
const LOG_HEADERS = ['etag', 'location', 'link', 'x-total-count', 'content-type', 'cache-control'];

const state = {
  filters: { q: '', status: '', role: '', city: '', sort: 'id,asc', size: 10, page: 0 },
  current: null,   // { user, etag } of the user open in detail or edit view
  log: [],
  logSeq: 0,
};

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const esc = (v) => String(v ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const main = () => $('#main');

/* ---------------- HTTP ---------------- */

async function api(method, path, { body, rawBody, headers = {}, contentType } = {}) {
  const reqHeaders = { ...headers };
  let payload;
  if (rawBody !== undefined) {
    // Sent exactly as typed, so the reference page can also send broken JSON on purpose.
    payload = rawBody;
  } else if (body !== undefined) {
    reqHeaders['Content-Type'] = contentType || 'application/json';
    payload = JSON.stringify(body);
  }
  const started = performance.now();
  let res = null;
  let text = '';
  let data = null;
  let error = null;
  try {
    // no-store: the browser must not answer from its own cache. The log should show the real exchange.
    res = await fetch(path, { method, headers: reqHeaders, body: payload, cache: 'no-store' });
    text = await res.text();
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  } catch (e) {
    error = e;
  }
  const out = {
    status: res ? res.status : 0,
    ok: res ? res.ok : false,
    headers: res ? res.headers : new Headers(),
    data,
    text,
    ms: Math.round(performance.now() - started),
  };
  addLog({ method, path, reqHeaders, payload, out, error });
  return out;
}

/* ---------------- request log ---------------- */

function addLog({ method, path, reqHeaders, payload, out, error }) {
  const respHeaders = [];
  out.headers.forEach((v, k) => respHeaders.push([k, v]));
  state.log.unshift({ id: ++state.logSeq, method, path, reqHeaders, payload, status: out.status,
    ms: out.ms, respHeaders, text: out.text, error: error ? String(error) : null });
  state.log = state.log.slice(0, 40);
  renderLog();
}

function pretty(text) {
  if (!text) return '(empty)';
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
}

function curlFor(entry) {
  const parts = [`curl -i -X ${entry.method} '${location.origin}${entry.path}'`];
  Object.entries(entry.reqHeaders).forEach(([k, v]) => parts.push(`-H '${k}: ${v.replace(/'/g, "'\\''")}'`));
  if (entry.payload) parts.push(`--data '${entry.payload.replace(/'/g, "'\\''")}'`);
  return parts.join(' \\\n  ');
}

function renderLog() {
  $('#log-list').innerHTML = state.log.map((e) => {
    const cls = `s${String(e.status)[0] || 0}`;
    const reqH = Object.entries(e.reqHeaders).map(([k, v]) => `${esc(k)}: ${esc(v)}`).join('\n') || '(none)';
    const resH = e.respHeaders.map(([k, v]) => {
      const line = `${esc(k)}: ${esc(v)}`;
      return LOG_HEADERS.includes(k) ? `<span class="hl">${line}</span>` : line;
    }).join('\n') || '(none)';
    return `<li class="log-item ${cls}">
      <details>
        <summary><span class="m">${esc(e.method)}</span><span class="p" title="${esc(e.path)}">${esc(e.path)}</span>
          <span class="s">${e.status || 'ERR'} in ${e.ms} ms</span></summary>
        <div class="log-body">
          ${e.error ? `<h4>Network error</h4><pre>${esc(e.error)}</pre>` : ''}
          <h4>Request headers</h4><pre>${reqH}</pre>
          ${e.payload ? `<h4>Request body</h4><pre>${esc(pretty(e.payload))}</pre>` : ''}
          <h4>Response headers</h4><pre>${resH}</pre>
          <h4>Response body</h4><pre>${esc(pretty(e.text))}</pre>
          <p><button class="link-btn" data-curl="${e.id}">Copy as curl</button></p>
        </div>
      </details></li>`;
  }).join('');
}

document.addEventListener('click', async (ev) => {
  const btn = ev.target.closest('[data-curl]');
  if (!btn) return;
  const entry = state.log.find((e) => e.id === Number(btn.dataset.curl));
  try {
    await navigator.clipboard.writeText(curlFor(entry));
    toast('Copied the curl command');
  } catch {
    toast('Copy failed. Your browser blocked clipboard access.');
  }
});

/* ---------------- shared UI ---------------- */

let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

function banner(kind, title, lines = []) {
  return `<div class="banner ${kind}" role="alert"><strong>${esc(title)}</strong>
    ${lines.map((l) => `<div class="code-line">${esc(l)}</div>`).join('')}</div>`;
}

/** Turns a problem+json body into a banner. Keeps the API's own words. */
function problemBanner(res) {
  const p = res.data || {};
  const lines = [];
  if (p.code) lines.push(`code: ${p.code}`);
  if (p.field) lines.push(`field: ${p.field}`);
  if (p.constraint) lines.push(`constraint: ${p.constraint} (SQLState ${p.sqlState})`);
  if (p.currentETag) lines.push(`current ETag: ${p.currentETag}`);
  const kind = res.status >= 500 || res.status === 0 ? 'err' : 'warn';
  return banner(kind, `${res.status} ${p.title || ''}: ${p.detail || res.text || 'no response'}`, lines);
}

function statusBadge(s) { return `<span class="status ${esc(s)}">${esc(s)}</span>`; }

function setNav(view) {
  $$('.nav-item').forEach((b) => {
    if (b.dataset.view === view) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  });
}

$$('.nav-item').forEach((b) => b.addEventListener('click', () => {
  const v = b.dataset.view;
  if (v === 'list') showList();
  if (v === 'create') showForm(null);
  if (v === 'controls') showControls();
  if (v === 'reference') showReference();
}));

/* ---------------- environment strip ---------------- */

async function refreshEnv() {
  const res = await api('GET', '/test/state');
  const env = $('#env');
  if (res.status === 404) {
    env.innerHTML = '<span>Test profile is off. Seed, clock and bug switches are not available.</span>';
    return;
  }
  if (!res.ok) { env.textContent = `Could not read /test/state (${res.status})`; return; }
  const s = res.data;
  const bugs = Object.entries(s.bugs).filter(([, on]) => on).map(([k]) => k);
  env.innerHTML = `<span>Today: <strong>${esc(s.today)}</strong>${s.clockPinned ? ' (pinned)' : ''}</span>
    <span>${bugs.length ? `<span class="bug-on">Bugs on: ${esc(bugs.join(', '))}</span>` : 'Bugs: off'}</span>`;
}

/* ---------------- list view ---------------- */

async function showList() {
  setNav('list');
  const f = state.filters;
  const opt = (vals, cur, empty) => `<option value="">${empty}</option>` +
    vals.map((v) => `<option ${v === cur ? 'selected' : ''}>${esc(v)}</option>`).join('');
  main().innerHTML = `
    <h2>Users</h2>
    <p class="lede">Search, filter and sort. Deleted users are hidden unless you pick the DELETED status.</p>
    <form class="filters" id="filters">
      <div><label for="f-q">Search name, username or email</label><input id="f-q" name="q" value="${esc(f.q)}"></div>
      <div><label for="f-status">Status</label><select id="f-status" name="status">${opt(STATUSES, f.status, 'Any (not deleted)')}</select></div>
      <div><label for="f-role">Role</label><select id="f-role" name="role">${opt(ROLES, f.role, 'Any')}</select></div>
      <div><label for="f-city">Primary city</label><input id="f-city" name="city" value="${esc(f.city)}"></div>
      <div><label for="f-sort">Sort</label><select id="f-sort" name="sort">${Object.entries(SORTS)
        .map(([v, t]) => `<option value="${v}" ${v === f.sort ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
      <div><button class="btn" type="submit">Search</button></div>
    </form>
    <div id="list-area"></div>`;
  $('#filters').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const d = new FormData(ev.target);
    Object.assign(state.filters, { q: d.get('q'), status: d.get('status'), role: d.get('role'),
      city: d.get('city'), sort: d.get('sort'), page: 0 });
    loadList();
  });
  await loadList();
}

async function loadList() {
  const f = state.filters;
  const params = new URLSearchParams();
  ['q', 'status', 'role', 'city'].forEach((k) => { if (f[k]) params.set(k, f[k]); });
  params.set('sort', f.sort);
  params.set('page', f.page);
  params.set('size', f.size);
  const res = await api('GET', `/users?${params}`);
  const area = $('#list-area');
  if (!area) return;
  if (!res.ok) { area.innerHTML = problemBanner(res); return; }
  const p = res.data;
  if (p.content.length === 0) {
    area.innerHTML = `<div class="panel empty">No users match these filters. Clear a filter, or seed data from Test controls.</div>`;
    return;
  }
  area.innerHTML = `<div class="panel table-wrap"><table>
      <thead><tr><th>Code</th><th>Username</th><th>Name</th><th>Email</th><th>Status</th><th>Roles</th><th>City</th></tr></thead>
      <tbody>${p.content.map((u) => `<tr tabindex="0" data-id="${u.id}">
        <td class="code">${esc(u.userCode)}</td><td>${esc(u.username)}</td><td>${esc(u.fullName)}</td>
        <td>${esc(u.email)}</td><td>${statusBadge(u.status)}</td>
        <td>${u.roles.map((r) => `<span class="chip">${esc(r)}</span>`).join('')}</td>
        <td>${esc(u.primaryCity || '')}</td></tr>`).join('')}</tbody></table>
    <div class="pager">
      <button class="btn secondary" id="prev" ${p.page === 0 ? 'disabled' : ''}>Previous</button>
      <span>Page ${p.page + 1} of ${Math.max(p.totalPages, 1)}. X-Total-Count: ${esc(res.headers.get('x-total-count'))}</span>
      <button class="btn secondary" id="next" ${p.page >= p.totalPages - 1 ? 'disabled' : ''}>Next</button>
    </div></div>`;
  $$('tbody tr', area).forEach((tr) => {
    tr.addEventListener('click', () => showDetail(tr.dataset.id));
    tr.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') showDetail(tr.dataset.id); });
  });
  $('#prev').addEventListener('click', () => { state.filters.page -= 1; loadList(); });
  $('#next').addEventListener('click', () => { state.filters.page += 1; loadList(); });
}

/* ---------------- detail view ---------------- */

async function showDetail(id, notice = '') {
  setNav('list');
  const res = await api('GET', `/users/${id}`);
  if (!res.ok) {
    main().innerHTML = `<h2>User ${esc(id)}</h2>${problemBanner(res)}
      <button class="btn secondary" id="back">Back to users</button>`;
    $('#back').addEventListener('click', showList);
    return;
  }
  state.current = { user: res.data, etag: res.headers.get('etag') };
  renderDetail(notice);
}

function renderDetail(notice = '') {
  const { user: u, etag } = state.current;
  const p = u.profile;
  const n = u.preferences.notifications;
  main().innerHTML = `
    ${notice}
    <div class="detail-head">
      <div>
        <h2>${esc(p.firstName)} ${esc(p.lastName)}</h2>
        <p class="lede"><span class="code">${esc(u.userCode)}</span> ${esc(u.username)} ${statusBadge(u.status)}</p>
      </div>
      <div class="actions">
        <span>ETag <span class="etag" id="etag">${esc(etag)}</span></span>
        <button class="btn secondary" id="check">Check for changes</button>
      </div>
    </div>
    <div class="actions" style="margin-bottom:20px">
      <button class="btn" id="edit">Edit user</button>
      ${NEXT_STATUS[u.status].map(([to, label]) => `<button class="btn secondary" data-status="${to}">${label}</button>`).join('')}
      <button class="btn danger" id="delete">Delete user</button>
      <button class="link-btn" id="back">Back to users</button>
    </div>
    <div class="grid2">
      <section class="panel"><h3>Profile</h3><dl class="kv">
        <dt>Email</dt><dd>${esc(u.email)}</dd>
        <dt>Date of birth</dt><dd>${esc(p.dateOfBirth)}</dd>
        <dt>Phone</dt><dd>${p.phone ? esc(p.phone) : 'Not set'}</dd>
        <dt>Roles</dt><dd>${u.roles.map((r) => `<span class="chip">${esc(r)}</span>`).join('')}</dd>
        <dt>Version</dt><dd>${u.version}</dd></dl></section>
      <section class="panel"><h3>Preferences</h3><dl class="kv">
        <dt>Language</dt><dd>${esc(u.preferences.language)}</dd>
        <dt>Time zone</dt><dd>${esc(u.preferences.timezone)}</dd>
        <dt>Email alerts</dt><dd>${n.email ? 'On' : 'Off'}</dd>
        <dt>SMS alerts</dt><dd>${n.sms ? 'On' : 'Off'}</dd>
        <dt>Channels</dt><dd>${n.channels.map((c) => `<span class="chip">${esc(c)}</span>`).join('') || 'None'}</dd></dl></section>
      <section class="panel"><h3>Addresses</h3>${u.addresses.length === 0 ? '<p class="lede">No addresses.</p>' :
        u.addresses.map((a) => `<div class="addr"><strong>${esc(a.type)}${a.primary ? ' (primary)' : ''}</strong>
          <div>${esc(a.line1)}${a.line2 ? `, ${esc(a.line2)}` : ''}</div>
          <div>${esc(a.city)}, ${esc(a.state)} ${esc(a.pincode)}</div>
          <div class="code">${a.geo ? `${esc(a.geo.lat)}, ${esc(a.geo.lon)}` : 'No coordinates'}, address id ${a.id}</div></div>`).join('')}</section>
      <section class="panel"><h3>Audit</h3><dl class="kv">
        <dt>Created</dt><dd>${esc(u.audit.createdAt)}</dd>
        <dt>Updated</dt><dd>${esc(u.audit.updatedAt)}</dd>
        <dt>Last login</dt><dd>${esc(u.audit.lastLoginAt || 'Never')}</dd></dl></section>
    </div>`;

  $('#back').addEventListener('click', showList);
  $('#edit').addEventListener('click', () => showForm(state.current));
  $('#check').addEventListener('click', checkForChanges);
  $('#delete').addEventListener('click', deleteCurrent);
  $$('[data-status]').forEach((b) => b.addEventListener('click', () => changeStatus(b.dataset.status)));
}

/** Conditional GET: 304 means our copy is still current. */
async function checkForChanges() {
  const { user, etag } = state.current;
  const res = await api('GET', `/users/${user.id}`, { headers: { 'If-None-Match': etag } });
  if (res.status === 304) { toast(`304 Not Modified: ETag ${etag} is still current`); return; }
  if (res.ok) {
    state.current = { user: res.data, etag: res.headers.get('etag') };
    renderDetail(banner('ok', `The user changed. New ETag ${state.current.etag}.`));
    return;
  }
  main().insertAdjacentHTML('afterbegin', problemBanner(res));
}

async function changeStatus(to) {
  const { user, etag } = state.current;
  const res = await api('PATCH', `/users/${user.id}`, {
    body: { status: to }, contentType: 'application/merge-patch+json', headers: { 'If-Match': etag } });
  if (res.ok) {
    state.current = { user: res.data, etag: res.headers.get('etag') };
    renderDetail();
    toast(`Status is now ${to}`);
    return;
  }
  renderDetail(problemBanner(res) + (res.status === 412 ? reloadHint() : ''));
  bindReload();
}

async function deleteCurrent() {
  const { user } = state.current;
  if (!window.confirm(`Delete ${user.username}? This is a soft delete. The row stays with status DELETED.`)) return;
  const res = await api('DELETE', `/users/${user.id}`);
  if (res.status === 204) { toast(`Deleted ${user.username}`); showList(); return; }
  main().insertAdjacentHTML('afterbegin', problemBanner(res));
}

function reloadHint() {
  return '<p><button class="btn secondary" id="reload">Load the latest version</button></p>';
}
function bindReload() {
  const b = $('#reload');
  if (b) b.addEventListener('click', () => showDetail(state.current.user.id));
}

/* ---------------- create / edit form ---------------- */

const EMPTY_USER = {
  username: '', email: '',
  profile: { firstName: '', lastName: '', dateOfBirth: '', phone: null },
  preferences: { language: 'ta', timezone: 'Asia/Kolkata', notifications: { email: true, sms: false, channels: ['EMAIL'] } },
  addresses: [], roles: ['USER'],
};

function field(path, label, value, type = 'text') {
  const id = `fld-${path.replace(/[^a-z0-9]/gi, '-')}`;
  return `<div class="field" data-field="${esc(path)}"><label for="${id}">${esc(label)}</label>
    <input id="${id}" type="${type}" name="${esc(path)}" value="${esc(value ?? '')}"></div>`;
}

function addressRow(a, i) {
  const p = `addresses[${i}]`;
  return `<div class="addr-row" data-index="${i}">
    <div class="actions">
      <label class="checks"><input type="radio" name="primary" value="${i}" ${a.primary ? 'checked' : ''}> Primary address</label>
      <button type="button" class="link-btn" data-remove="${i}">Remove address</button>
    </div>
    <div class="form-grid">
      <div class="field" data-field="${p}.type"><label for="a${i}-type">Type</label>
        <select id="a${i}-type" name="${p}.type">${ADDRESS_TYPES.map((t) => `<option ${t === a.type ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
      ${field(`${p}.line1`, 'Line 1', a.line1)}
      ${field(`${p}.line2`, 'Line 2 (optional)', a.line2)}
      ${field(`${p}.city`, 'City', a.city)}
      ${field(`${p}.state`, 'State', a.state)}
      ${field(`${p}.pincode`, 'Pincode', a.pincode)}
      ${field(`${p}.geo.lat`, 'Latitude (optional)', a.geo ? a.geo.lat : '')}
      ${field(`${p}.geo.lon`, 'Longitude (optional)', a.geo ? a.geo.lon : '')}
    </div></div>`;
}

function showForm(current) {
  setNav(current ? 'list' : 'create');
  const editing = Boolean(current);
  const u = editing ? current.user : structuredClone(EMPTY_USER);
  const addresses = u.addresses.map((a) => ({ ...a }));
  const p = u.profile;
  const n = u.preferences.notifications;

  main().innerHTML = `
    <h2>${editing ? `Edit ${esc(u.username)}` : 'New user'}</h2>
    <p class="lede">${editing
      ? `Saving sends PUT with If-Match ${esc(current.etag)}. If someone saved a newer version first, you get 412.`
      : 'Saving sends POST /users. Field errors from the API appear next to the field they belong to.'}</p>
    <div id="form-msg"></div>
    <form id="user-form" novalidate>
      <section class="panel"><h3>Account</h3><div class="form-grid">
        ${field('username', 'Username', u.username)}
        ${field('email', 'Email', u.email, 'email')}
      </div>
      <div class="field" data-field="roles" style="margin-top:14px"><label>Roles</label><div class="checks">
        ${ROLES.map((r) => `<label><input type="checkbox" name="roles" value="${r}" ${u.roles.includes(r) ? 'checked' : ''}> ${r}</label>`).join('')}
      </div></div></section>

      <section class="panel"><h3>Profile</h3><div class="form-grid">
        ${field('profile.firstName', 'First name', p.firstName)}
        ${field('profile.lastName', 'Last name', p.lastName)}
        ${field('profile.dateOfBirth', 'Date of birth', p.dateOfBirth, 'date')}
        ${field('profile.phone', 'Phone (optional)', p.phone)}
      </div></section>

      <section class="panel"><h3>Preferences</h3><div class="form-grid">
        <div class="field" data-field="preferences.language"><label for="pref-lang">Language</label>
          <select id="pref-lang" name="preferences.language">${LANGS.map((l) => `<option ${l === u.preferences.language ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        ${field('preferences.timezone', 'Time zone', u.preferences.timezone)}
      </div>
      <div class="field" data-field="preferences.notifications.sms" style="margin-top:14px"><label>Alerts</label><div class="checks">
        <label><input type="checkbox" name="notif.email" ${n.email ? 'checked' : ''}> Email alerts</label>
        <label><input type="checkbox" name="notif.sms" ${n.sms ? 'checked' : ''}> SMS alerts</label>
      </div></div>
      <div class="field" data-field="preferences.notifications.channels" style="margin-top:10px"><label>Channels</label><div class="checks">
        ${CHANNELS.map((c) => `<label><input type="checkbox" name="channels" value="${c}" ${n.channels.includes(c) ? 'checked' : ''}> ${c}</label>`).join('')}
      </div></div></section>

      <section class="panel"><h3>Addresses</h3>
        <div class="field" data-field="addresses"><div id="addr-list"></div></div>
        <button type="button" class="btn secondary" id="add-addr">Add address</button></section>

      <div class="actions">
        <button class="btn" type="submit">${editing ? 'Save changes' : 'Create user'}</button>
        <button type="button" class="link-btn" id="cancel">Cancel</button>
      </div>
    </form>`;

  const renderAddresses = () => {
    $('#addr-list').innerHTML = addresses.map(addressRow).join('') || '<p class="lede">No addresses yet.</p>';
    $$('[data-remove]').forEach((b) => b.addEventListener('click', () => {
      syncAddresses(addresses);
      addresses.splice(Number(b.dataset.remove), 1);
      renderAddresses();
    }));
  };
  renderAddresses();

  $('#add-addr').addEventListener('click', () => {
    syncAddresses(addresses);
    addresses.push({ type: 'HOME', primary: addresses.length === 0, line1: '', line2: '', city: '', state: '', pincode: '', geo: null });
    renderAddresses();
  });
  $('#cancel').addEventListener('click', () => (editing ? showDetail(u.id) : showList()));
  $('#user-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    syncAddresses(addresses);
    await submitForm(editing, current, buildBody(addresses));
  });
}

/** Copies what the user typed in the address rows back into the array, so add/remove keeps it. */
function syncAddresses(addresses) {
  const form = $('#user-form');
  const primary = form.querySelector('input[name="primary"]:checked');
  addresses.forEach((a, i) => {
    const v = (k) => form.elements[`addresses[${i}].${k}`].value.trim();
    a.type = v('type');
    a.primary = primary ? Number(primary.value) === i : false;
    a.line1 = v('line1');
    a.line2 = v('line2');
    a.city = v('city');
    a.state = v('state');
    a.pincode = v('pincode');
    const lat = v('geo.lat');
    const lon = v('geo.lon');
    a.geo = lat === '' && lon === '' ? null : { lat: toNumber(lat), lon: toNumber(lon) };
  });
}

function toNumber(s) {
  const n = Number(s);
  return s === '' || Number.isNaN(n) ? null : n;
}

function buildBody(addresses) {
  const form = $('#user-form');
  const val = (name) => form.elements[name].value.trim();
  const checked = (name) => $$(`input[name="${name}"]:checked`, form).map((i) => i.value);
  return {
    username: val('username'),
    email: val('email'),
    profile: { firstName: val('profile.firstName'), lastName: val('profile.lastName'),
      dateOfBirth: val('profile.dateOfBirth') || null, phone: val('profile.phone') || null },
    preferences: { language: val('preferences.language'), timezone: val('preferences.timezone'),
      notifications: { email: form.elements['notif.email'].checked, sms: form.elements['notif.sms'].checked,
        channels: checked('channels') } },
    addresses: addresses.map((a) => ({ type: a.type, primary: a.primary, line1: a.line1, line2: a.line2 || null,
      city: a.city, state: a.state, pincode: a.pincode, geo: a.geo })),
    roles: checked('roles'),
  };
}

async function submitForm(editing, current, body) {
  clearFieldErrors();
  const res = editing
    ? await api('PUT', `/users/${current.user.id}`, { body, headers: { 'If-Match': current.etag } })
    : await api('POST', '/users', { body });

  if (res.ok) {
    toast(editing ? `Saved. New ETag ${res.headers.get('etag')}` : `Created ${res.data.userCode}`);
    showDetail(res.data.id);
    return;
  }
  const msg = $('#form-msg');
  if (res.status === 400 && res.data && res.data.errors) {
    const unplaced = res.data.errors.filter((e) => !markField(e.field, e.message));
    msg.innerHTML = banner('err', `400: ${res.data.errors.length} field(s) need fixing.`,
      unplaced.map((e) => `${e.field}: ${e.message}`));
  } else if (res.status === 422 && res.data) {
    markField(res.data.field, res.data.detail);
    msg.innerHTML = problemBanner(res);
  } else if (res.status === 412) {
    msg.innerHTML = problemBanner(res) + banner('warn', 'Someone saved a newer version after you opened this form.',
      ['Your changes are still in the form. Open the latest version and apply them again.']) + reloadHint();
    bindReload();
  } else {
    msg.innerHTML = problemBanner(res);
  }
  msg.scrollIntoView({ block: 'start' });
}

/** Finds the input whose data-field equals the API's field path, e.g. addresses[0].pincode. */
function markField(path, message) {
  if (!path) return false;
  const box = $(`[data-field="${CSS.escape(path)}"]`);
  if (!box) return false;
  box.classList.add('invalid');
  box.insertAdjacentHTML('beforeend', `<div class="msg">${esc(message)} <span class="path">${esc(path)}</span></div>`);
  return true;
}

function clearFieldErrors() {
  $$('.field.invalid').forEach((f) => f.classList.remove('invalid'));
  $$('.field .msg').forEach((m) => m.remove());
}

/* ---------------- test controls ---------------- */

async function showControls() {
  setNav('controls');
  const res = await api('GET', '/test/state');
  if (res.status === 404) {
    main().innerHTML = `<h2>Test controls</h2>${banner('warn', 'The service is not running with the test profile.',
      ['Start it with --spring.profiles.active=test to use seed, clock and bug switches.'])}`;
    return;
  }
  const s = res.data || { today: '', bugs: {} };
  main().innerHTML = `
    <h2>Test controls</h2>
    <p class="lede">The same /test endpoints your RestAssured tests call in their setup.</p>
    <section class="panel"><h3>Data</h3>
      <p class="lede">Seed replaces all data with the 12 known users. Reset leaves the tables empty. Both restart ids at 1.</p>
      <div class="actions"><button class="btn" id="seed">Seed data</button><button class="btn danger" id="reset">Reset to empty</button></div></section>
    <section class="panel"><h3>Clock</h3>
      <p class="lede">Pin "today" so age rules give the same answer every run. The seed data expects 2026-10-15.</p>
      <div class="actions"><div style="max-width:200px"><label for="pin-date">Date</label>
        <input id="pin-date" type="date" value="${esc(s.clockPinned ? s.today : '2026-10-15')}"></div>
        <button class="btn" id="pin">Pin date</button><button class="btn secondary" id="unpin">Use real date</button></div></section>
    <section class="panel"><h3>Bug switches</h3>
      <p class="lede">Turn a known bug on. A good test fails while its bug is on.</p>
      <div class="checks" style="flex-direction:column;gap:10px">
        <label><input type="checkbox" id="bug-ifmatch" ${s.bugs.ignoreIfMatch ? 'checked' : ''}>
          Ignore If-Match: a stale PUT or PATCH overwrites newer data</label>
        <label><input type="checkbox" id="bug-null" ${s.bugs.mergePatchNullIgnored ? 'checked' : ''}>
          Ignore null in merge patch: "phone": null does not clear the phone</label>
      </div>
      <p><button class="btn" id="save-bugs">Save bug switches</button></p></section>`;

  const after = async (r, okMsg) => {
    if (r.ok) toast(okMsg); else main().insertAdjacentHTML('afterbegin', problemBanner(r));
    await refreshEnv();
  };
  $('#seed').addEventListener('click', async () => {
    const r = await api('POST', '/test/seed');
    await after(r, r.ok ? `Seeded ${r.data.users} users` : '');
  });
  $('#reset').addEventListener('click', async () => {
    if (!window.confirm('Delete all users and addresses?')) return;
    await after(await api('POST', '/test/reset'), 'All data removed');
  });
  $('#pin').addEventListener('click', async () => {
    const d = $('#pin-date').value;
    await after(await api('PUT', '/test/clock', { body: { today: d || null } }), `Today is pinned to ${d}`);
  });
  $('#unpin').addEventListener('click', async () => {
    await after(await api('PUT', '/test/clock', { body: { today: null } }), 'Using the real date');
  });
  $('#save-bugs').addEventListener('click', async () => {
    await after(await api('PUT', '/test/bugs', { body: {
      ignoreIfMatch: $('#bug-ifmatch').checked, mergePatchNullIgnored: $('#bug-null').checked } }), 'Bug switches saved');
  });
}

/* ---------------- API reference ---------------- */

const EX_NEW_USER = {
  username: 'kavin.s', email: 'kavin@example.in',
  profile: { firstName: 'Kavin', lastName: 'S', dateOfBirth: '1997-01-20', phone: '9500012345' },
  preferences: { language: 'ta', timezone: 'Asia/Kolkata',
    notifications: { email: true, sms: true, channels: ['EMAIL', 'SMS'] } },
  addresses: [{ type: 'HOME', primary: true, line1: '3 Gandhi Road', line2: null, city: 'Chennai',
    state: 'Tamil Nadu', pincode: '600116', geo: { lat: 13.0354, lon: 80.158 } }],
  roles: ['USER'],
};
const EX_PUT_USER3 = {
  username: 'selvi.r', email: 'selvi@example.in',
  profile: { firstName: 'Selvi', lastName: 'Rajan', dateOfBirth: '1994-06-12', phone: '9876543210' },
  preferences: { language: 'ta', timezone: 'Asia/Kolkata',
    notifications: { email: true, sms: false, channels: ['EMAIL', 'PUSH'] } },
  addresses: [{ type: 'HOME', primary: true, line1: '12 Anna Nagar', line2: null, city: 'Chennai',
    state: 'Tamil Nadu', pincode: '600040', geo: { lat: 13.085, lon: 80.21 } }],
  roles: ['USER'],
};
const J = (o) => JSON.stringify(o, null, 2);

/* Each entry: what the endpoint does, what it needs, what it returns, and a request that works on seeded data. */
const ENDPOINTS = [
  { group: 'Users', method: 'POST', path: '/users', summary: 'Create a user with profile, preferences, addresses and roles.',
    headers: [['Content-Type', 'application/json', 'required']],
    success: '201 Created. Headers: Location (/users/{id}), ETag "1". Body: the new user. New users start as PENDING_VERIFICATION.',
    errors: [['400', 'A field is invalid. errors[] lists every field with its full path.'],
      ['409', 'Username taken (uq_users_username) or email taken, ignoring case (uq_users_email_lower).'],
      ['422', 'UNDERAGE, MULTIPLE_PRIMARY_ADDRESSES, PRIMARY_ADDRESS_REQUIRED, SMS_NEEDS_PHONE, SMS_CHANNEL_DISABLED.']],
    example: { path: '/users', headers: 'Content-Type: application/json', body: J(EX_NEW_USER) } },

  { group: 'Users', method: 'GET', path: '/users/{id}', summary: 'Read one user. Supports conditional GET with If-None-Match.',
    headers: [['If-None-Match', 'An ETag, for example "1". If it is current, the answer is 304 with no body.', 'optional']],
    success: '200 OK with ETag and Cache-Control: no-cache. 304 Not Modified when If-None-Match matches.',
    errors: [['404', 'The user never existed.'], ['410', 'The user was deleted.']],
    example: { path: '/users/3', headers: 'If-None-Match: "1"', body: '' } },

  { group: 'Users', method: 'PUT', path: '/users/{id}', summary: 'Replace the whole user. Addresses and roles are replaced as complete lists, so address ids change.',
    headers: [['Content-Type', 'application/json', 'required'], ['If-Match', 'The ETag from your last GET, quotes included. * means any version.', 'required']],
    success: '200 OK with the new ETag. version goes up by 1.',
    errors: [['400', 'A field is invalid.'], ['404', 'Unknown user.'], ['409', 'Username or email taken.'], ['410', 'The user was deleted.'],
      ['412', 'If-Match is stale or malformed. Body has currentVersion and currentETag.'], ['422', 'A business rule failed.'],
      ['428', 'If-Match header is missing.']],
    example: { path: '/users/3', headers: 'Content-Type: application/json\nIf-Match: "1"', body: J(EX_PUT_USER3) } },

  { group: 'Users', method: 'PATCH', path: '/users/{id}', summary: 'Change part of a user with JSON Merge Patch (RFC 7396). Also used to change status.',
    headers: [['Content-Type', 'application/merge-patch+json (application/json gets 415)', 'required'],
      ['If-Match', 'Checked when sent. Without it, the service still refuses to overwrite a change made during the request.', 'optional']],
    success: '200 OK with the new ETag.',
    errors: [['400', 'The merged result is invalid, for example "firstName": null.'], ['404', 'Unknown user.'],
      ['409', 'INVALID_STATUS_TRANSITION, or a unique constraint.'], ['410', 'The user was deleted.'], ['412', 'If-Match is stale.'],
      ['415', 'Wrong Content-Type.'], ['422', 'A business rule failed after the merge, for example clearing phone while sms is true.']],
    notes: ['Objects merge key by key. null removes a key. Arrays and plain values replace the old value.',
      'Status changes: PENDING_VERIFICATION to ACTIVE, ACTIVE to SUSPENDED, SUSPENDED to ACTIVE. DELETED only through DELETE.'],
    example: { path: '/users/10', headers: 'Content-Type: application/merge-patch+json', body: J({ profile: { phone: null } }) } },

  { group: 'Users', method: 'DELETE', path: '/users/{id}', summary: 'Soft delete. The row stays with status DELETED. Calling it again is still 204.',
    headers: [], success: '204 No Content.', errors: [['404', 'The user never existed.']],
    example: { path: '/users/12', headers: '', body: '' } },

  { group: 'Users', method: 'GET', path: '/users', summary: 'Search with filters, sorting and offset paging.',
    headers: [],
    params: [['q', 'Text in username, email or full name. Case-insensitive. % and _ match literally.'],
      ['status', 'PENDING_VERIFICATION, ACTIVE, SUSPENDED or DELETED. Without it, DELETED users are hidden.'],
      ['role', 'ADMIN, SUPPORT or USER.'], ['city', 'City of the primary address. Case-insensitive.'],
      ['sort', 'field,asc or field,desc. Fields: id, username, email, lastName, createdAt, status. Default id,asc.'],
      ['page', 'Starts at 0. Default 0.'], ['size', '1 to 100. Default 20.']],
    success: '200 OK. Body: content, page, size, totalElements, totalPages. Headers: X-Total-Count and Link (first, prev, next, last).',
    errors: [['400', 'Bad sort, page or size, or an unknown status or role value.']],
    example: { path: '/users?role=USER&city=Chennai&sort=lastName,asc&page=0&size=5', headers: '', body: '' } },

  { group: 'Test controls (test profile only)', method: 'POST', path: '/test/seed', summary: 'Reset, then load the 12 seed users. Ids always start at 1.',
    headers: [], success: '200 OK with counts and current state.', errors: [['404', 'The service runs without the test profile.']],
    example: { path: '/test/seed', headers: '', body: '' } },
  { group: 'Test controls (test profile only)', method: 'POST', path: '/test/reset', summary: 'Empty all data tables. Clock unpinned, bugs off.',
    headers: [], success: '200 OK with current state.', errors: [['404', 'No test profile.']],
    example: { path: '/test/reset', headers: '', body: '' } },
  { group: 'Test controls (test profile only)', method: 'PUT', path: '/test/clock', summary: 'Pin "today". Send {"today": null} to use the real date.',
    headers: [['Content-Type', 'application/json', 'required']], success: '200 OK with current state.', errors: [['404', 'No test profile.']],
    example: { path: '/test/clock', headers: 'Content-Type: application/json', body: J({ today: '2026-10-15' }) } },
  { group: 'Test controls (test profile only)', method: 'PUT', path: '/test/bugs', summary: 'Switch known bugs on or off.',
    headers: [['Content-Type', 'application/json', 'required']], success: '200 OK with current state.', errors: [['404', 'No test profile.']],
    notes: ['ignoreIfMatch: PUT and PATCH skip the version check (lost update).', 'mergePatchNullIgnored: PATCH ignores null instead of removing the key.'],
    example: { path: '/test/bugs', headers: 'Content-Type: application/json', body: J({ ignoreIfMatch: false, mergePatchNullIgnored: false }) } },
  { group: 'Test controls (test profile only)', method: 'GET', path: '/test/state', summary: 'Current date, whether it is pinned, and bug switches.',
    headers: [], success: '200 OK.', errors: [['404', 'No test profile.']], example: { path: '/test/state', headers: '', body: '' } },

  { group: 'Health', method: 'GET', path: '/actuator/health', summary: 'Is the service up? Poll this before your tests start.',
    headers: [], success: '200 OK with {"status":"UP"}.', errors: [['503', 'Up but not healthy, for example the database is down.']],
    example: { path: '/actuator/health', headers: '', body: '' } },
];

/* Field rules, taken from Dtos.java and UserService.checkRules. */
const FIELD_RULES = [
  ['username', 'string', 'Required. 3 to 30 characters: lowercase letters, digits, dot, underscore. Starts with a letter. Unique.'],
  ['email', 'string', 'Required. Valid email, max 120. Unique, ignoring upper/lower case.'],
  ['profile.firstName, profile.lastName', 'string', 'Required, not blank, max 60.'],
  ['profile.dateOfBirth', 'date (yyyy-mm-dd)', 'Required, in the past. At least 18 years before "today", or 422 UNDERAGE.'],
  ['profile.phone', 'string or null', 'Optional. 10 digits, starting with 6, 7, 8 or 9.'],
  ['preferences.language', 'string', 'Required. One of en, ta, hi, ml, kn, te.'],
  ['preferences.timezone', 'string', 'Required. A valid zone id like Asia/Kolkata, or 400.'],
  ['preferences.notifications.email', 'boolean', 'Required.'],
  ['preferences.notifications.sms', 'boolean', 'Required. true needs profile.phone, or 422 SMS_NEEDS_PHONE.'],
  ['preferences.notifications.channels', 'array', 'Required, max 3, values EMAIL, SMS, PUSH. SMS listed with sms false is 422 SMS_CHANNEL_DISABLED.'],
  ['addresses', 'array', 'Required (may be empty), max 5. If not empty, exactly one must be primary, or 422.'],
  ['addresses[].type', 'string', 'Required. HOME, WORK or BILLING.'],
  ['addresses[].primary', 'boolean', 'Optional. Missing means false.'],
  ['addresses[].line1, city, state', 'string', 'Required, not blank. line1 max 120, city and state max 60.'],
  ['addresses[].line2', 'string or null', 'Optional, max 120.'],
  ['addresses[].pincode', 'string', 'Required. Exactly 6 digits.'],
  ['addresses[].geo', 'object or null', 'Optional. When sent, lat (-90 to 90) and lon (-180 to 180) are both required.'],
  ['roles', 'array', 'Required, at least one. ADMIN, SUPPORT, USER. Duplicates are ignored.'],
  ['status', 'string', 'PATCH only. See the allowed status changes under PATCH.'],
  ['id, userCode, version, addresses[].id, audit.*', 'response only', 'Set by the service. Ignored if sent in a request.'],
];

function parseHeaderLines(text) {
  const out = {};
  text.split('\n').map((l) => l.trim()).filter(Boolean).forEach((line) => {
    const i = line.indexOf(':');
    if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  });
  return out;
}

function showReference() {
  setNav('reference');
  let group = '';
  const blocks = ENDPOINTS.map((e, i) => {
    const heading = e.group !== group ? `<h3 class="ref-group">${esc(e.group)}</h3>` : '';
    group = e.group;
    return `${heading}
    <details class="ref" id="ref-${i}">
      <summary><span class="verb ${e.method}">${e.method}</span><span class="ref-path">${esc(e.path)}</span>
        <span class="ref-sum">${esc(e.summary)}</span></summary>
      <div class="ref-body">
        ${e.headers.length ? `<h4>Headers</h4><table><tbody>${e.headers.map(([n, d, r]) =>
          `<tr><td class="code">${esc(n)}</td><td>${esc(r)}</td><td>${esc(d)}</td></tr>`).join('')}</tbody></table>` : ''}
        ${e.params ? `<h4>Query parameters</h4><table><tbody>${e.params.map(([n, d]) =>
          `<tr><td class="code">${esc(n)}</td><td>${esc(d)}</td></tr>`).join('')}</tbody></table>` : ''}
        <h4>Success</h4><p>${esc(e.success)}</p>
        <h4>Errors</h4><table><tbody>${e.errors.map(([c, d]) =>
          `<tr><td class="code">${esc(c)}</td><td>${esc(d)}</td></tr>`).join('')}</tbody></table>
        ${e.notes ? `<h4>Rules</h4><ul>${e.notes.map((n) => `<li>${esc(n)}</li>`).join('')}</ul>` : ''}
        <h4>Try it</h4>
        <form class="try" data-i="${i}">
          <div class="try-line"><span class="verb ${e.method}">${e.method}</span>
            <input name="path" aria-label="Path" value="${esc(e.example.path)}"></div>
          <label for="h-${i}">Headers, one per line</label>
          <textarea id="h-${i}" name="headers" rows="2">${esc(e.example.headers)}</textarea>
          ${['POST', 'PUT', 'PATCH'].includes(e.method) && e.example.body !== '' ? `<label for="b-${i}">Body (sent exactly as typed)</label>
          <textarea id="b-${i}" name="body" rows="${Math.min(e.example.body.split('\n').length + 1, 22)}">${esc(e.example.body)}</textarea>` : ''}
          <div class="actions"><button class="btn" type="submit">Send</button><span class="try-out" aria-live="polite"></span></div>
          <pre class="try-res" hidden></pre>
        </form>
      </div>
    </details>`;
  }).join('');

  main().innerHTML = `
    <h2>API reference</h2>
    <p class="lede">Every endpoint, what it needs, and what it returns. Each one has a request that works on freshly
      seeded data with the date pinned to 2026-10-15. Send puts the full exchange in the request log.</p>
    ${blocks}
    <h3 class="ref-group">User fields</h3>
    <div class="panel table-wrap"><table>
      <thead><tr><th>Field</th><th>Type</th><th>Rules</th></tr></thead>
      <tbody>${FIELD_RULES.map(([f, t, r]) => `<tr><td class="code">${esc(f)}</td><td>${esc(t)}</td><td>${esc(r)}</td></tr>`).join('')}</tbody>
    </table></div>`;

  $$('form.try').forEach((form) => form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const e = ENDPOINTS[Number(form.dataset.i)];
    const opts = { headers: parseHeaderLines(form.elements.headers.value) };
    if (form.elements.body) opts.rawBody = form.elements.body.value;
    const res = await api(e.method, form.elements.path.value.trim(), opts);
    const etag = res.headers.get('etag');
    form.querySelector('.try-out').textContent = `${res.status || 'Network error'}${etag ? `, ETag ${etag}` : ''}`;
    const pre = form.querySelector('.try-res');
    pre.hidden = false;
    pre.textContent = res.status === 304 ? '(304: no body)' : pretty(res.text);
  }));
}

/* ---------------- start ---------------- */

$('#clear-log').addEventListener('click', () => { state.log = []; renderLog(); });
refreshEnv();
showList();
