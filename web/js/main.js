// App boot: router, portal shells, page context, session watch.
import {
  ApiError, PORTAL_KEY, PORTALS, api, currentPortal, debounce, esc, getSession, go, html, icon, inr, match, meta, parseHash, portalHome,
  raw, route, setExpiredHandler, setSession, ago, initials, updateUser,
} from './core.js';
import { avatar, emptyState, errorState, permState, skeleton, toast, onSubmit, modal } from './ui.js';
import * as auth from './pages/auth.js';
import * as customer from './pages/customer.js';
import * as partner from './pages/partner.js';
import * as agent from './pages/agent.js';
import * as admin from './pages/admin.js';

const app = document.getElementById('app');
let shell = null;          // current portal shell {portal, view, setBar, setActive}
let cleanup = [];          // listeners registered by the current page
let renderId = 0;

// ------------------------------------------------------------------ navigation definitions
const NAV = {
  partner: (u) => [
    ['/p/dashboard', 'Overview', 'home'], ['/p/leads', 'Leads', 'inbox'], ['/p/quotes', 'Quotes', 'file'], ['/p/bookings', 'Bookings', 'briefcase'],
    ['/p/calendar', 'Calendar', 'calendar'], ['/p/earnings', 'Earnings', 'wallet'], ['/p/profile', 'Business profile', 'user'],
    ['group', 'Manage'], ['/p/services', 'Services & pricing', 'tool'], ['/p/documents', 'Documents & KYC', 'shield'], ['/p/reviews', 'Ratings & reviews', 'star'],
    ['/p/messages', 'Messages', 'chat'], ['/p/support', 'Support & disputes', 'help'],
    ...(u.provider && u.provider.kind === 'catering' ? [['group', 'Specialised'], ['/p/catering', 'Catering packages', 'gift']] : []),
    ...(u.provider && u.provider.kind === 'photography' ? [['group', 'Specialised'], ['/p/photography', 'Portfolio & packages', 'camera']] : []),
    ...(u.roles.includes('employer') || true ? [['group', 'Hiring (Quick Jobs)'], ['/p/vacancies', 'Vacancies', 'briefcase'], ['/p/applications', 'Applications', 'users']] : []),
  ],
  agent: () => [
    ['/a/home', 'My day', 'home'], ['/a/customers', 'My customers', 'users'], ['/a/requests', 'Request inbox', 'inbox'], ['/a/providers', 'Provider directory', 'tool'],
    ['/a/quotes', 'Quote comparison', 'file'], ['/a/approvals', 'Approval queue', 'check'], ['/a/bookings', 'Booking coordination', 'briefcase'], ['/a/tracking', 'Live job tracking', 'truck'],
    ['group', 'Work'], ['/a/tasks', 'Follow-ups & tasks', 'clock'], ['/a/messages', 'Messages & calls', 'chat'], ['/a/outreach', 'Provider outreach log', 'phone'],
    ['/a/complaints', 'Complaints & escalation', 'alert'], ['group', 'Specialised'], ['/a/jobs', 'Quick Jobs ops', 'briefcase'], ['/a/events', 'Events & weddings', 'gift'], ['/a/performance', 'Performance', 'chart'],
  ],
  admin: (u) => {
    const p = new Set(u.permissions || []);
    const items = [
      ['/x/home', 'Overview', 'home', null],
      ['group', 'People'], ['/x/users', 'Users', 'users', 'users'], ['/x/agents', 'Agents', 'headset', 'agents'], ['/x/assignment', 'Assignment rules', 'refresh', 'assignment'],
      ['group', 'Network'], ['/x/partners', 'Partners', 'tool', 'partners'], ['/x/verification', 'Verification queue', 'shield', 'verification'],
      ['group', 'Catalogue'], ['/x/categories', 'Categories', 'list', 'catalogue'], ['/x/workflows', 'Workflow builder', 'settings', 'catalogue'],
      ['group', 'Operations'], ['/x/requests', 'Requests', 'inbox', 'requests'], ['/x/oversight', 'Quote & booking oversight', 'eye', 'oversight'], ['/x/jobs', 'Quick Jobs', 'briefcase', 'jobs'], ['/x/complaints', 'Complaints & disputes', 'alert', 'complaints'],
      ['group', 'Finance'], ['/x/finance', 'Payments & payouts', 'wallet', 'finance'], ['/x/plans', 'Membership & plans', 'card', 'plans'],
      ['group', 'Platform'], ['/x/templates', 'Message templates', 'chat', 'templates'], ['/x/reports', 'Reports & analytics', 'chart', 'reports'], ['/x/audit', 'Audit logs', 'lock', 'audit'], ['/x/settings', 'Settings & integrations', 'settings', 'settings'], ['/x/help', 'Content & help centre', 'help', 'content'],
    ];
    const filtered = items.filter((i) => i[0] === 'group' || !i[3] || p.has(i[3]));
    return filtered.filter((it, i, arr) => it[0] !== 'group' || (arr[i + 1] && arr[i + 1][0] !== 'group'));
  },
};
const PORTAL_TITLE = { partner: 'Partner portal', agent: 'Agent workspace', admin: 'Admin console' };
const TABBAR = { partner: [['/p/dashboard', 'Overview', 'home'], ['/p/leads', 'Leads', 'inbox'], ['/p/bookings', 'Bookings', 'briefcase'], ['/p/earnings', 'Earnings', 'wallet']] };

// ------------------------------------------------------------------ shells
function mountCustomer(user) {
  document.body.classList.add('cust-bg');
  app.innerHTML = `<div class="cust"><header class="appbar" id="appbar"></header><main class="cust-main" id="view" tabindex="-1"></main>
    <nav class="bottomnav" aria-label="Main">${[['/c/home', 'Home', 'home'], ['/c/categories', 'Explore', 'compass'], ['/c/requests', 'Requests', 'list'], ['/c/messages', 'Messages', 'chat'], ['/c/profile', 'Profile', 'user']]
      .map(([h, l, i]) => `<a href="#${h}" data-nav="${h}"><span class="ic-wrap">${icon(i, 22)}</span>${l}</a>`).join('')}</nav></div>`;
  const bar = document.getElementById('appbar');
  let unread = 0;
  const draw = (o = {}) => {
    bar.innerHTML = o.back
      ? `<a class="icon-btn" href="${esc(o.back)}" aria-label="Back">${icon('left', 22)}</a><h1>${esc(o.title || '')}</h1>${o.action || ''}`
      : `<span class="logo"><span class="logo-mark">B</span>BUZZER</span><span class="grow"></span><a class="icon-btn" href="#/c/notifications" aria-label="Notifications${unread ? ', ' + unread + ' unread' : ''}">${icon('bell', 22)}${unread ? `<span class="badge-dot">${unread > 9 ? '9+' : unread}</span>` : ''}</a>`;
  };
  let last = {};
  const api_ = {
    portal: 'customer', view: document.getElementById('view'),
    setBar(o) { last = o || {}; draw(last); },
    setActive(path) {
      const rules = { '/c/home': /^\/c\/home/, '/c/categories': /^\/c\/(categories|category|event-brief|photo-brief)/, '/c/requests': /^\/c\/(requests|request|quote|booking)/,
        '/c/messages': /^\/c\/messages/, '/c/profile': /^\/c\/(profile|support|membership|notifications|jobs|job)/ };
      document.querySelectorAll('.bottomnav a').forEach((a) => a.classList.toggle('active', rules[a.dataset.nav].test(path)));
    },
    async refreshBell() { try { const r = await api('/notifications?page_size=1&unread=1'); unread = r.unread; draw(last); } catch { /* ignore */ } },
  };
  draw({});
  return api_;
}

function mountWork(portal, user) {
  document.body.classList.remove('cust-bg');
  const items = NAV[portal](user);
  const nav = items.map((i) => i[0] === 'group' ? `<div class="group">${esc(i[1])}</div>` : `<a href="#${i[0]}" data-nav="${i[0]}">${icon(i[2], 19)}<span>${esc(i[1])}</span></a>`).join('');
  const tab = (TABBAR[portal] || []).map(([h, l, i]) => `<a href="#${h}" data-nav="${h}"><span class="ic-wrap">${icon(i, 22)}</span>${l}</a>`).join('');
  app.innerHTML = `<div class="work"><aside class="side" aria-label="Main navigation"><span class="logo"><span class="logo-mark">B</span>BUZZER</span>${nav}
      <div class="me">${avatar(user.name, user.avatar_color, 36)}<div><b>${esc(user.name)}</b><small>${esc(PORTAL_TITLE[portal])}</small></div></div></aside><div class="side-bg" data-close-nav></div>
    <div class="work-col"><header class="topbar"><button class="icon-btn menu-btn" data-open-nav aria-label="Open menu">${icon('menu', 22)}</button>
      ${portal === 'partner' ? `<div class="row grow"><b class="truncate">${esc(user.provider ? user.provider.business_name : user.name)}</b>${user.provider && user.provider.verification_status === 'verified' ? '<span class="vbadge">' + icon('shield', 14).s + ' Verified</span>' : '<span class="chip chip-warn">' + esc(user.provider ? user.provider.verification_status : 'employer') + '</span>'}</div>`
        : `<label class="search grow" role="search"><span class="visually-hidden">Search</span>${icon('search', 18)}<input id="gsearch" type="search" placeholder="${portal === 'agent' ? 'Search requests, customers, providers…' : 'Search requests, customers, providers…'}" autocomplete="off"><div id="gpop" class="search-pop" hidden></div></label>`}
      <button class="icon-btn" id="help-btn" aria-label="Help">${icon('help', 22)}</button>
      <div style="position:relative"><button class="icon-btn" id="bell-btn" aria-label="Notifications">${icon('bell', 22)}<span class="badge-dot" id="bell-n" hidden></span></button><div class="menu-pop" id="bell-pop" hidden></div></div>
      <div style="position:relative"><button class="icon-btn" id="me-btn" aria-label="Account menu" style="width:auto;gap:8px;padding:0 4px">${avatar(user.name, user.avatar_color, 34).s}</button><div class="menu-pop" id="me-pop" hidden>
        <div style="padding:8px 10px"><b>${esc(user.name)}</b><div class="muted sm">${esc(user.email || user.mobile || '')}</div><div class="muted xs">${esc(user.roles.join(', ').replace(/_/g, ' '))}</div></div><hr class="divider" style="margin:6px 0">
        ${portal === 'partner' ? `<a href="#/p/profile">${icon('user', 18)} Business profile</a>` : ''}<button data-signout>${icon('logout', 18)} Sign out</button></div></div>
    </header><main class="work-main" id="view" tabindex="-1"></main></div>${tab ? `<nav class="tabbar" aria-label="Quick links">${tab}<button data-open-nav>${icon('menu', 22)}More</button></nav>` : ''}</div>`;
  const q = (s) => app.querySelector(s);
  const closeNav = () => document.body.classList.remove('nav-open');
  app.addEventListener('click', (e) => {
    if (e.target.closest('[data-open-nav]')) document.body.classList.add('nav-open');
    if (e.target.closest('[data-close-nav]') || e.target.closest('.side a')) closeNav();
  });
  // popovers
  const pops = [['bell-btn', 'bell-pop'], ['me-btn', 'me-pop']];
  const closePops = () => pops.forEach(([, p]) => { const el = q('#' + p); el && (el.hidden = true); });
  pops.forEach(([b, p]) => q('#' + b).addEventListener('click', async (e) => {
    e.stopPropagation(); const el = q('#' + p); const was = el.hidden; closePops(); el.hidden = !was;
    if (p === 'bell-pop' && was) { await drawBell(portal); }
  }));
  document.addEventListener('click', (e) => { if (!e.target.closest('.menu-pop') && !e.target.closest('.search')) { closePops(); const g = q('#gpop'); g && (g.hidden = true); } });
  q('#help-btn').addEventListener('click', () => openHelp(portal));
  async function badge() { try { const r = await api('/notifications?page_size=1&unread=1'); const n = q('#bell-n'); if (n) { n.hidden = !r.unread; n.textContent = r.unread > 9 ? '9+' : r.unread; } } catch { /* ignore */ } }
  badge();
  // global search for staff
  const gs = q('#gsearch');
  if (gs) {
    const pop = q('#gpop');
    gs.addEventListener('input', debounce(async () => {
      const t = gs.value.trim(); if (t.length < 2) { pop.hidden = true; return; }
      try {
        const r = await api('/staff/search?q=' + encodeURIComponent(t));
        const base = portal === 'agent' ? '#/a' : '#/x';
        const none = !r.requests.length && !r.customers.length && !r.providers.length;
        pop.innerHTML = none ? '<p class="muted" style="padding:12px">No matches.</p>' : [
          r.requests.length ? `<h4>Requests</h4>${r.requests.map((x) => `<a href="${portal === 'agent' ? base + '/request/' + x.id : base + '/requests?q=' + encodeURIComponent(x.reference)}"><b>${esc(x.reference)}</b> · ${esc(x.title)}<small>${esc(x.customer_name)} · ${esc(x.status_label)}</small></a>`).join('')}` : '',
          r.customers.length && portal === 'agent' ? `<h4>Customers</h4>${r.customers.map((x) => `<a href="#/a/customer/${x.id}">${esc(x.name)}<small>${esc(x.mobile || '')}</small></a>`).join('')}` : '',
          r.providers.length ? `<h4>Providers</h4>${r.providers.map((x) => `<a href="${portal === 'agent' ? '#/a/providers?open=' + x.id : '#/x/partners?open=' + x.id}">${esc(x.business_name)}<small>${esc(x.locality || '')} · ${esc(x.verification_status)}</small></a>`).join('')}` : '',
        ].join('');
        pop.hidden = false;
      } catch { pop.hidden = true; }
    }, 300));
    pop.addEventListener('click', () => { pop.hidden = true; gs.value = ''; });
  }
  const shell_ = {
    portal, view: q('#view'),
    setBar() {}, refreshBell: badge,
    setActive(path) {
      const links = [...app.querySelectorAll('.side a, .tabbar a')];
      links.forEach((a) => a.classList.toggle('active', path === a.dataset.nav || path.startsWith(a.dataset.nav + '/') || (a.dataset.nav === '/p/bookings' && path.startsWith('/p/job')) || (a.dataset.nav === '/p/leads' && path.startsWith('/p/lead')) || (a.dataset.nav === '/a/requests' && path.startsWith('/a/request/')) || (a.dataset.nav === '/p/quotes' && path.startsWith('/p/quote/'))));
    },
  };
  return shell_;
}

async function drawBell(portal) {
  const pop = document.getElementById('bell-pop');
  pop.innerHTML = '<p class="muted" style="padding:12px">Loading…</p>';
  try {
    const r = await api('/notifications?page_size=8');
    pop.style.width = '320px';
    pop.innerHTML = `<div class="row between" style="padding:6px 10px"><b>Notifications</b><button data-mark-read style="width:auto;padding:4px 8px;font-size:13px">Mark all read</button></div>` +
      (r.items.length ? r.items.map((n) => `<a href="${esc(n.link || '#')}" style="${n.is_read ? 'opacity:.7' : ''}"><div><b>${esc(n.title)}</b><div class="muted sm">${esc(n.body || '')}</div><div class="muted xs">${esc(ago(n.created_at))}</div></div></a>`).join('') : '<p class="muted" style="padding:12px">You\'re all caught up.</p>');
    pop.querySelector('[data-mark-read]')?.addEventListener('click', async () => { await api('/notifications/read', { method: 'POST', body: {} }); document.getElementById('bell-n').hidden = true; pop.hidden = true; });
    pop.addEventListener('click', (e) => { if (e.target.closest('a')) pop.hidden = true; });
  } catch (e) { pop.innerHTML = `<p class="muted" style="padding:12px">${esc(e.message)}</p>`; }
}

async function openHelp(portal) {
  const aud = portal === 'partner' ? 'partner' : 'customer';
  modal({
    title: 'Help centre', footer: false, wide: true,
    body: '<div class="field"><input type="search" id="help-q" placeholder="Search help articles…"></div><div id="help-list" class="stack"></div>',
    onMount: async (m) => {
      const list = m.querySelector('#help-list'); const input = m.querySelector('#help-q');
      const load = async () => { const r = await api(`/help?audience=${aud}&q=${encodeURIComponent(input.value)}`, { portal: null }); list.innerHTML = r.items.map((a) => `<details class="card card-flat"><summary><b>${esc(a.title)}</b> <span class="chip chip-neutral">${esc(a.topic)}</span></summary><p style="margin-top:8px">${esc(a.body)}</p></details>`).join('') || '<p class="muted">No articles found.</p>'; };
      input.addEventListener('input', debounce(load, 250)); load();
    },
  });
}

// ------------------------------------------------------------------ sign-out & session expiry
export async function signOut(portal) {
  try { await api('/auth/logout', { method: 'POST', portal, quiet: true }); } catch { /* ignore */ }
  setSession(portal, null);
  shell = null;
  go(`/${PORTAL_KEY[portal]}/login`);
}
setExpiredHandler((portal) => {
  setSession(portal, null);
  const here = parseHash();
  toast('Your session has expired. Please sign in again — your draft is saved.', 'error');
  shell = null;
  go(`/${PORTAL_KEY[portal]}/login?next=${encodeURIComponent(here.path)}`);
});
let warned = false;
setInterval(() => {
  const s = getSession(); if (!s || !s.expires_at) return;
  const left = new Date(s.expires_at).getTime() - Date.now();
  if (left < 5 * 60e3 && left > 0 && !warned) {
    warned = true;
    toast('Your session expires in a few minutes.', 'error', { label: 'Stay signed in', fn: async () => { try { const r = await api('/auth/refresh', { method: 'POST' }); setSession(currentPortal(), { token: r.token, expires_at: r.expires_at, user: r.user }); warned = false; toast('Session extended.'); } catch (e) { toast(e.message, 'error'); } } });
  }
}, 30000);

// ------------------------------------------------------------------ page context + rendering
function makeCtx(m, view, user, portal) {
  const ctx = {
    params: m.params, query: m.query, path: m.path, portal, user, view,
    _after: [], _bar: null,
    on(evt, selector, fn) { const h = (e) => { const t = e.target.closest(selector); if (t && view.contains(t)) fn(e, t); }; view.addEventListener(evt, h); cleanup.push(() => view.removeEventListener(evt, h)); },
    submit(selector, fn) { cleanup.push(onSubmit(view, selector, fn)); },
    after(fn) { ctx._after.push(fn); },
    every(fn, ms) { const t = setInterval(fn, ms); cleanup.push(() => clearInterval(t)); },
    bar(o) { ctx._bar = o; },
    reload() { render(); },
    go,
  };
  return ctx;
}

async function render() {
  const id = ++renderId;
  cleanup.forEach((f) => f()); cleanup = [];
  document.body.classList.remove('nav-open');
  const m = match();
  const r = m.route;
  if (!r) {
    app.innerHTML = `<div class="auth"><div class="auth-card center">${emptyState({ ic: 'compass', title: 'Page not found', text: "We couldn't find that page.", action: html`<a class="btn btn-primary" href="#/">Go to BUZZER home</a>` })}</div></div>`;
    shell = null; return;
  }
  const portal = r.portal || r.opts.portal || null;
  const session = portal ? getSession(portal) : null;
  if (r.opts.bare) { // landing / auth screens render without a shell
    if (r.opts.redirectIfAuthed && session) { go(m.query.next ? '#' + m.query.next : portalHome[portal]); return; }
    shell = null; document.body.classList.remove('cust-bg');
    app.innerHTML = ''; const ctx = makeCtx(m, app, null, portal);
    try { const out = await r.handler(ctx); if (id !== renderId) return; app.innerHTML = out ? out.toString() : ''; ctx._after.forEach((f) => f()); document.title = (ctx.title || 'BUZZER') + ' · BUZZER'; } catch (e) { app.innerHTML = errorState(e).toString(); }
    return;
  }
  if (!session) { go(`/${PORTAL_KEY[portal]}/login?next=${encodeURIComponent(m.path + (location.hash.includes('?') ? '?' + location.hash.split('?')[1] : ''))}`); return; }
  const user = session.user;
  if (!shell || shell.portal !== portal) { shell = portal === 'customer' ? mountCustomer(user) : mountWork(portal, user); shell.refreshBell && shell.refreshBell(); }
  const view = shell.view;
  const ctx = makeCtx(m, view, user, portal);
  shell.setActive(m.path);
  view.innerHTML = skeleton(3).toString();
  try {
    const out = await r.handler(ctx);
    if (id !== renderId) return;
    view.innerHTML = out ? out.toString() : '';
    if (portal === 'customer') shell.setBar(ctx._bar);
    ctx._after.forEach((f) => f());
    const h = view.querySelector('h1'); document.title = (h ? h.textContent : 'BUZZER') + ' · BUZZER';
    window.scrollTo(0, 0);
  } catch (e) {
    if (id !== renderId) return;
    if (e instanceof ApiError && e.status === 401) return; // handled by the expiry hook
    if (portal === 'customer') shell.setBar({ back: '#/c/home', title: 'Something went wrong' });
    view.innerHTML = (e instanceof ApiError && e.status === 404 ? emptyState({ ic: 'compass', title: 'Not found', text: e.message }) : errorState(e)).toString();
    if (!(e instanceof ApiError)) console.error(e);
    ctx.on('click', '[data-act=retry]', () => render());
  }
}

// global handlers
document.addEventListener('click', (e) => {
  const so = e.target.closest('[data-signout]'); if (so && currentPortal()) { e.preventDefault(); signOut(currentPortal()); return; }
  const h = e.target.closest('[data-go]'); if (h) { e.preventDefault(); go(h.dataset.go); }
  const back = e.target.closest('[data-back]'); if (back) { e.preventDefault(); history.back(); }
});

// register routes
const reg = (mod, portal) => (mod.routes || []).forEach(([pattern, handler, opts]) => route(pattern, portal, handler, opts || {}));
reg(auth, null); reg(customer, 'customer'); reg(partner, 'partner'); reg(agent, 'agent'); reg(admin, 'admin');
// auth routes are tagged with their portal for redirect logic
window.addEventListener('hashchange', render);
meta().catch(() => {}).finally(() => {
  if (!location.hash || location.hash === '#') location.hash = '#/';
  render();
});
export { updateUser };
