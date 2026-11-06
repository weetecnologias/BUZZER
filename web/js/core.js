// Core runtime: safe templating, API client, sessions, router, formatting, icons.
// All dynamic values interpolated through `html` are escaped unless wrapped with raw().

class Safe { constructor(s) { this.s = s; } toString() { return this.s; } }
export const raw = (s) => new Safe(String(s ?? ''));
const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);
function part(v) {
  if (v == null || v === false || v === true) return '';
  if (v instanceof Safe) return v.s;
  if (Array.isArray(v)) return v.map(part).join('');
  return esc(v);
}
export function html(strings, ...vals) {
  let out = '';
  strings.forEach((s, i) => { out += s; if (i < vals.length) out += part(vals[i]); });
  return new Safe(out);
}

// ----------------------------------------------------------------- formatting
const TZ = 'Asia/Kolkata';
export const inr = (n) => (n == null || n === '' ? '—' : '₹' + Number(n).toLocaleString('en-IN'));
export const num = (n) => Number(n || 0).toLocaleString('en-IN');
const asDate = (iso) => (iso ? new Date(String(iso).length === 10 ? iso + 'T00:00:00+05:30' : iso) : null);
export const dt = (iso) => { const d = asDate(iso); return d ? d.toLocaleString('en-IN', { timeZone: TZ, day: '2-digit', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true }) : '—'; };
export const dtl = (iso) => { const d = asDate(iso); return d ? d.toLocaleString('en-IN', { timeZone: TZ, day: '2-digit', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true }) : '—'; };
export const dateOnly = (iso) => { const d = asDate(iso); return d ? d.toLocaleDateString('en-IN', { timeZone: TZ, day: '2-digit', month: 'short', year: 'numeric' }) : '—'; };
export const timeOnly = (iso) => { const d = asDate(iso); return d ? d.toLocaleTimeString('en-IN', { timeZone: TZ, hour: 'numeric', minute: '2-digit', hour12: true }) : '—'; };
export function ago(iso) {
  const d = asDate(iso); if (!d) return '—';
  const s = Math.round((Date.now() - d.getTime()) / 1000);
  if (s < 0) return 'in ' + shortSpan(-s);
  if (s < 45) return 'just now';
  return shortSpan(s) + ' ago';
}
function shortSpan(s) {
  if (s < 3600) return Math.max(1, Math.round(s / 60)) + ' min';
  if (s < 86400) return Math.round(s / 3600) + ' hr';
  if (s < 86400 * 30) return Math.round(s / 86400) + ' d';
  return Math.round(s / 86400 / 30) + ' mo';
}
export const hoursText = (h) => (h == null ? '—' : h < 1 ? Math.round(h * 60) + ' min' : h < 48 ? Math.round(h) + ' hr' : Math.round(h / 24) + ' d');
export const phone = (m) => (m ? String(m).replace(/^\+91(\d{5})(\d{5})$/, '+91 $1 $2') : '');
export const initials = (n) => String(n || '?').split(/\s+/).slice(0, 2).map((x) => x[0]).join('').toUpperCase();
export const istInput = (iso) => { const d = asDate(iso); if (!d) return ''; const p = new Date(d.getTime() + 5.5 * 3600e3); return p.toISOString().slice(0, 16); };
export const inDays = (n, hour = 10) => { const d = new Date(Date.now() + n * 864e5 + 5.5 * 3600e3); d.setUTCHours(hour, 0, 0, 0); return d.toISOString().slice(0, 16); };
export const cap = (s) => String(s || '').replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

// ----------------------------------------------------------------- icons
const I = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M9.5 21v-6h5v6"/>',
  compass: '<circle cx="12" cy="12" r="9"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
  list: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4h6v3H9zM9 12h6M9 16h4"/>',
  chat: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/>',
  bell: '<path d="M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 21h4"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>', check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>', x: '<path d="M6 6l12 12M18 6 6 18"/>',
  right: '<path d="m9 6 6 6-6 6"/>', left: '<path d="m15 6-6 6 6 6"/>', down: '<path d="m6 9 6 6 6-6"/>',
  phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/>',
  star: '<path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
  shield: '<path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.2 7.5 9.5 4.4-1.3 7.5-4.9 7.5-9.5V6z"/><path d="m9 12 2.2 2.2L15.5 10"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
  pin: '<path d="M12 21s7-6.2 7-11.5A7 7 0 0 0 5 9.5C5 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>',
  wallet: '<path d="M4 7.5A2.5 2.5 0 0 1 6.5 5H18v3"/><rect x="3.5" y="7.5" width="17" height="12" rx="2.5"/><circle cx="16.5" cy="13.5" r="1.2"/>',
  briefcase: '<rect x="3.5" y="7.5" width="17" height="12" rx="2.5"/><path d="M9 7.5V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1.5M3.5 13h17"/>',
  settings: '<path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/>',
  logout: '<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4"/><path d="m15 8 4 4-4 4M19 12H9"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>', filter: '<path d="M4 5h16l-6 8v5l-4 2v-7z"/>',
  upload: '<path d="M12 16V4m-4.5 4.5L12 4l4.5 4.5"/><path d="M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3"/>',
  send: '<path d="m21 3-9.5 18-2.2-7.8L2 11z"/><path d="m21 3-11.7 10.2"/>',
  alert: '<path d="M12 4 2.5 20h19z"/><path d="M12 10v4.5M12 17.5v.01"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/><path d="M16 5a3.5 3.5 0 0 1 0 7M21.5 20c0-2.6-1.4-4.6-3.5-5.5"/>',
  chart: '<path d="M5 20V11M11 20V4M17 20v-6M3 20h18"/>',
  file: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5"/>',
  tool: '<path d="M14.5 6a4 4 0 0 0 4.8 4.8L9 21.2a2.1 2.1 0 0 1-3-3z"/><path d="m14.5 6 3-3 3 3-3 3"/>',
  trend: '<path d="m3 17 6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
  inbox: '<path d="M3 13h5l1 3h6l1-3h5"/><path d="M5 5h14l2 8v6H3v-6z"/>',
  card: '<rect x="3" y="5.5" width="18" height="13" rx="2.5"/><path d="M3 10h18"/>',
  lock: '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.8M12 17v.01"/>',
  download: '<path d="M12 4v12m-4.5-4.5L12 16l4.5-4.5"/><path d="M5 18v1a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-1"/>',
  eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>', edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m14 6 4 4"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.5-4M4 4v4h4M4 13a8 8 0 0 0 14.5 4M20 20v-4h-4"/>',
  truck: '<path d="M2 6h11v10H2zM13 9h4l3 3v4h-7"/><circle cx="6.5" cy="17.5" r="1.8"/><circle cx="16.5" cy="17.5" r="1.8"/>',
  camera: '<path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/>',
  heart: '<path d="M12 20s-8-5-8-11a4.5 4.5 0 0 1 8-2.5A4.5 4.5 0 0 1 20 9c0 6-8 11-8 11z"/>',
  bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>', headset: '<path d="M4 14v-2a8 8 0 0 1 16 0v2"/><rect x="3" y="14" width="4" height="6" rx="1.5"/><rect x="17" y="14" width="4" height="6" rx="1.5"/><path d="M19 20a4 4 0 0 1-4 2h-2"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>',
  sparkle: '<path d="M12 3v5M12 16v5M3 12h5M16 12h5M6 6l3 3M15 15l3 3M18 6l-3 3M9 15l-3 3"/>',
  dot: '<circle cx="12" cy="12" r="4" fill="currentColor"/>', mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
  paperclip: '<path d="m20 11-8.5 8.5a5 5 0 0 1-7-7L13 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L14 7"/>',
  receipt: '<path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/><path d="M9 8h6M9 12h6"/>', gift: '<rect x="4" y="9" width="16" height="11" rx="2"/><path d="M12 9v11M3 9h18M12 9c-3 0-5-4-2-5 2-.5 2 2 2 5zm0 0c3 0 5-4 2-5-2-.5-2 2-2 5z"/>',
};
export function icon(name, size = 20, cls = '') {
  const filled = name === 'star' ? 'fill="currentColor"' : 'fill="none"';
  return raw(`<svg class="ic ${cls}" width="${size}" height="${size}" viewBox="0 0 24 24" ${filled} stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${I[name] || I.dot}</svg>`);
}

// ----------------------------------------------------------------- API client
export class ApiError extends Error {
  constructor(status, code, message, fields) { super(message); this.status = status; this.code = code; this.fields = fields || {}; }
}
export const PORTALS = { c: 'customer', p: 'partner', a: 'agent', x: 'admin' };
export const PORTAL_KEY = { customer: 'c', partner: 'p', agent: 'a', admin: 'x' };
export const portalHome = { customer: '/c/home', partner: '/p/dashboard', agent: '/a/home', admin: '/x/home' };
export function currentPortal() {
  const m = (location.hash || '').match(/^#\/([cpax])(\/|\?|$)/);
  return m ? PORTALS[m[1]] : null;
}
const KEY = (p) => 'buzzer.session.' + p;
export function getSession(portal = currentPortal()) {
  if (!portal) return null;
  try { return JSON.parse(localStorage.getItem(KEY(portal)) || 'null'); } catch { return null; }
}
export function setSession(portal, s) { try { if (s) localStorage.setItem(KEY(portal), JSON.stringify(s)); else localStorage.removeItem(KEY(portal)); } catch { /* storage unavailable */ } }
export function updateUser(portal, user) { const s = getSession(portal); if (s) setSession(portal, { ...s, user }); }

let onExpired = () => {};
export const setExpiredHandler = (fn) => { onExpired = fn; };

export async function api(path, { method = 'GET', body, form, portal = currentPortal(), quiet = false } = {}) {
  const headers = {};
  const s = getSession(portal);
  if (s && s.token) headers.Authorization = 'Bearer ' + s.token;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  let res;
  try { res = await fetch('/api/v1' + path, { method, headers, body: payload }); }
  catch { throw new ApiError(0, 'network', "You seem to be offline. Check your connection and try again."); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const e = data.error || {};
    if (res.status === 401 && s && !quiet) onExpired(portal);
    throw new ApiError(res.status, e.code || 'error', e.message || 'Something went wrong. Please try again.', e.fields);
  }
  return data;
}
export async function upload(file, extra = {}) {
  const f = new FormData();
  f.append('file', file);
  Object.entries(extra).forEach(([k, v]) => v && f.append(k, v));
  return api('/files', { method: 'POST', form: f });
}
export async function openFile(id) {
  const r = await api(`/files/${id}/url`);
  window.open(r.url, '_blank', 'noopener');
}

// ----------------------------------------------------------------- meta cache
let _meta;
export async function meta() {
  if (!_meta) _meta = await api('/meta', { portal: null });
  return _meta;
}
export const statusInfo = (key) => (_meta?.statuses || []).find((s) => s.key === key) || { label: cap(key), tone: 'neutral', next: '' };

// ----------------------------------------------------------------- router
const routes = [];
export function route(pattern, portal, handler, opts = {}) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:([a-z_]+)/gi, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
  routes.push({ re, keys, portal, handler, opts });
}
export function parseHash() {
  const h = (location.hash || '#/').slice(1) || '/';
  const [path, qs] = h.split('?');
  return { path, query: Object.fromEntries(new URLSearchParams(qs || '')) };
}
export function match() {
  const { path, query } = parseHash();
  for (const r of routes) {
    const m = path.match(r.re);
    if (m) return { route: r, params: Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])])), query, path };
  }
  return { route: null, params: {}, query, path };
}
export const go = (href) => { const t = href.replace(/^#/, ''); if (location.hash === '#' + t) window.dispatchEvent(new HashChangeEvent('hashchange')); else location.hash = t; };
export const qs = (obj) => { const p = new URLSearchParams(); Object.entries(obj).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') p.set(k, v); }); const s = p.toString(); return s ? '?' + s : ''; };

// ----------------------------------------------------------------- drafts (survive session expiry)
export const draft = {
  save(key, data) { try { sessionStorage.setItem('buzzer.draft.' + key, JSON.stringify(data)); } catch { /* ignore */ } },
  load(key) { try { return JSON.parse(sessionStorage.getItem('buzzer.draft.' + key) || 'null'); } catch { return null; } },
  clear(key) { try { sessionStorage.removeItem('buzzer.draft.' + key); } catch { /* ignore */ } },
};

// ----------------------------------------------------------------- debounce
export function debounce(fn, ms = 300) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
