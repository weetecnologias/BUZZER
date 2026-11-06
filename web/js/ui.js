// Shared UI components: chips, fields, tables, timelines, charts, modals, toasts.
import { ApiError, ago, cap, debounce, dt, esc, html, icon, initials, raw, statusInfo } from './core.js';

// ------------------------------------------------------------ small atoms
export const chip = (label, tone = 'neutral') => html`<span class="chip chip-${tone}">${label}</span>`;
export const statusChip = (key, label) => { const s = statusInfo(key); return chip(label || s.label, s.tone); };
export const avatar = (name, color, size = 40) => html`<span class="avatar" style="width:${size}px;height:${size}px;background:${color || '#BEEB5D'};font-size:${Math.round(size * 0.38)}px" aria-hidden="true">${initials(name)}</span>`;
export const verified = (status) => status === 'verified'
  ? html`<span class="vbadge" title="Identity and address documents reviewed by BUZZER">${icon('shield', 14)} Verified</span>`
  : html`<span class="chip chip-neutral">${status === 'pending' ? 'Verification pending' : 'Not verified'}</span>`;
export const stars = (n, size = 16) => html`<span class="stars" aria-label="${n || 0} out of 5">${[1, 2, 3, 4, 5].map((i) => html`<span class="${i <= Math.round(n || 0) ? 'on' : ''}">${icon('star', size)}</span>`)}</span>`;
export const starInput = (name, value = 0) => html`<div class="star-input" role="radiogroup" aria-label="Rating">${[1, 2, 3, 4, 5].map((i) => html`<label><input type="radio" name="${name}" value="${i}" ${value == i ? 'checked' : ''}><span>${icon('star', 30)}</span></label>`)}</div>`;
export const progress = (pct, label) => html`<div class="progress" role="progressbar" aria-valuenow="${pct}" aria-valuemin="0" aria-valuemax="100" aria-label="${label || 'Progress'}"><span style="width:${Math.max(2, Math.min(100, pct))}%"></span></div>`;
export const btn = (label, { href, act, cls = 'btn-primary', ic, attrs = '', type = 'button' } = {}) => href
  ? html`<a class="btn ${cls}" href="${href}" ${raw(attrs)}>${ic ? icon(ic, 18) : ''}${label}</a>`
  : html`<button type="${type}" class="btn ${cls}" ${act ? raw(`data-act="${esc(act)}"`) : ''} ${raw(attrs)}>${ic ? icon(ic, 18) : ''}${label}</button>`;
export const pageHeader = (title, sub, actions) => html`<header class="page-head"><div><h1 tabindex="-1">${title}</h1>${sub ? html`<p class="sub">${sub}</p>` : ''}</div>${actions ? html`<div class="head-actions">${actions}</div>` : ''}</header>`;
export const section = (title, body, action) => html`<section class="section"><div class="section-head"><h2>${title}</h2>${action || ''}</div>${body}</section>`;
export const stat = ({ label, value, delta, tone, ic, href }) => (href
  ? html`<a class="stat stat-link" href="${href}">${statInner({ label, value, delta, tone, ic })}</a>`
  : html`<div class="stat">${statInner({ label, value, delta, tone, ic })}</div>`);
const statInner = ({ label, value, delta, tone, ic }) => html`${ic ? html`<span class="stat-ic tone-${tone || 'neutral'}">${icon(ic, 18)}</span>` : ''}<span class="stat-v">${value}</span><span class="stat-l">${label}</span>${delta ? html`<span class="stat-d ${String(delta).startsWith('-') ? 'down' : 'up'}">${delta}</span>` : ''}`;
export const kv = (rows) => html`<dl class="kv">${rows.filter(Boolean).map(([k, v]) => html`<div><dt>${k}</dt><dd>${v ?? '—'}</dd></div>`)}</dl>`;
export const note = (text, tone = 'info') => html`<div class="note note-${tone}" role="note">${text}</div>`;

// ------------------------------------------------------------ states
export const skeleton = (rows = 3) => html`<div class="skeleton-wrap" aria-busy="true" aria-label="Loading">${Array.from({ length: rows }, (_, i) => html`<div class="sk sk-card" style="height:${i === 0 ? 92 : 68}px"></div>`)}</div>`;
export const emptyState = ({ ic = 'inbox', title, text, action }) => html`<div class="empty"><span class="empty-ic">${icon(ic, 28)}</span><h3>${title}</h3>${text ? html`<p>${text}</p>` : ''}${action || ''}</div>`;
export function errorState(err, retry = true) {
  if (err instanceof ApiError && err.status === 403) return permState(err.message);
  return html`<div class="empty empty-error" role="alert"><span class="empty-ic">${icon('alert', 28)}</span><h3>We couldn't load this</h3><p>${err.message || 'Something went wrong.'}</p>
    ${retry ? html`<div class="row-gap"><button class="btn btn-primary" data-act="retry">${icon('refresh', 16)} Try again</button><a class="btn btn-ghost" href="#/help-support" data-support>Contact support</a></div>` : ''}</div>`;
}
export const permState = (msg) => html`<div class="empty" role="alert"><span class="empty-ic">${icon('lock', 28)}</span><h3>You don't have access to this</h3><p>${msg || 'Your role does not include this page. Ask an administrator if you think this is a mistake.'}</p></div>`;

// ------------------------------------------------------------ forms
export function opts(list, value) {
  return list.map((o) => { const [v, l] = Array.isArray(o) ? o : [o, o]; return html`<option value="${v}" ${String(v) === String(value ?? '') ? 'selected' : ''}>${l}</option>`; });
}
export function field({ name, label, type = 'text', value = '', required = false, hint, placeholder, options, rows = 3, attrs = '', cls = '', min, max, step, inputmode, auto, maxlength, prefix }) {
  const id = 'f-' + name.replace(/\W/g, '_');
  let ctl;
  const common = raw(`id="${id}" name="${esc(name)}" ${required ? 'required' : ''} aria-describedby="${id}-e" ${attrs}`);
  if (type === 'textarea') ctl = html`<textarea ${common} rows="${rows}" placeholder="${placeholder || ''}" ${maxlength ? raw(`maxlength="${maxlength}"`) : ''}>${value}</textarea>`;
  else if (type === 'select') ctl = html`<select ${common}>${options ? opts(options, value) : ''}</select>`;
  else if (type === 'checkbox') return html`<div class="field ${cls}"><label class="check"><input type="checkbox" name="${name}" id="${id}" ${value ? 'checked' : ''} ${raw(attrs)}><span>${label}</span></label><div class="field-error" id="${id}-e" data-error-for="${name}" role="alert"></div></div>`;
  else ctl = html`<input ${common} type="${type}" value="${value ?? ''}" placeholder="${placeholder || ''}" ${min != null ? raw(`min="${min}"`) : ''} ${max != null ? raw(`max="${max}"`) : ''} ${step ? raw(`step="${step}"`) : ''} ${inputmode ? raw(`inputmode="${inputmode}"`) : ''} ${auto ? raw(`autocomplete="${auto}"`) : ''} ${maxlength ? raw(`maxlength="${maxlength}"`) : ''}>`;
  return html`<div class="field ${cls}"><label for="${id}">${label}${required ? html` <span class="req" aria-hidden="true">*</span>` : ''}</label>${prefix ? html`<div class="input-group"><span class="prefix">${prefix}</span>${ctl}</div>` : ctl}${hint ? html`<div class="hint">${hint}</div>` : ''}<div class="field-error" id="${id}-e" data-error-for="${name}" role="alert"></div></div>`;
}
export function switchRow({ name, label, help, checked }) {
  return html`<label class="switch-row"><span><b>${label}</b>${help ? html`<small>${help}</small>` : ''}</span><input type="checkbox" role="switch" name="${name}" ${checked ? 'checked' : ''}><i aria-hidden="true"></i></label>`;
}
export function formData(form) {
  const out = {}; const seen = {};
  for (const el of form.elements) {
    if (!el.name || el.disabled || el.type === 'file' || el.tagName === 'BUTTON') continue;
    if (el.type === 'checkbox') {
      const group = form.querySelectorAll(`input[type=checkbox][name="${CSS.escape(el.name)}"]`);
      if (group.length > 1) { (out[el.name] = out[el.name] || []); if (el.checked) out[el.name].push(el.value); } else out[el.name] = el.checked;
    } else if (el.type === 'radio') { if (el.checked) out[el.name] = el.value; else if (!(el.name in out)) out[el.name] = out[el.name] ?? ''; }
    else { seen[el.name] = 1; out[el.name] = el.value; }
  }
  return out;
}
export function clearErrors(form) {
  form.querySelectorAll('[data-error-for]').forEach((e) => { e.textContent = ''; });
  form.querySelectorAll('.invalid').forEach((e) => e.classList.remove('invalid'));
  const b = form.querySelector('.form-error'); if (b) { b.textContent = ''; b.hidden = true; }
}
export function showErrors(form, err) {
  const fields = err.fields || {}; let any = false;
  for (const [k, msg] of Object.entries(fields)) {
    const slot = form.querySelector(`[data-error-for="${CSS.escape(k)}"]`);
    if (slot) { slot.textContent = msg; any = true; const inp = form.querySelector(`[name="${CSS.escape(k)}"]`); inp && inp.classList.add('invalid'); }
  }
  const banner = form.querySelector('.form-error');
  if (banner) { banner.textContent = err.message; banner.hidden = false; banner.scrollIntoView({ block: 'nearest' }); }
  else if (!any) toast(err.message, 'error');
  const first = form.querySelector('.invalid'); if (first) first.focus();
}
/** Delegated, duplicate-safe form submit with inline field errors. */
export function onSubmit(root, selector, handler) {
  const fn = async (e) => {
    const form = e.target.closest(selector);
    if (!form || !root.contains(form)) return;
    e.preventDefault();
    if (form.dataset.busy) return;
    form.dataset.busy = '1'; clearErrors(form);
    const submit = e.submitter || form.querySelector('[type=submit]');
    const label = submit && submit.innerHTML;
    if (submit) { submit.disabled = true; submit.innerHTML = '<span class="spin"></span> Please wait'; }
    try { await handler(formData(form), form); }
    catch (err) { if (err instanceof ApiError) showErrors(form, err); else { console.error(err); toast('Something went wrong. Please try again.', 'error'); } }
    finally { delete form.dataset.busy; if (submit && submit.isConnected) { submit.disabled = false; submit.innerHTML = label; } }
  };
  root.addEventListener('submit', fn);
  return () => root.removeEventListener('submit', fn);
}

// ------------------------------------------------------------ tabs / stepper / timeline
export const tabs = (items, active, base) => html`<div class="tabs" role="tablist">${items.map(([k, l, n]) => html`<a role="tab" class="tab ${k === active ? 'active' : ''}" aria-selected="${k === active}" href="${base ? base + (base.includes('?') ? '&' : '?') + 'tab=' + k : '#'}" data-tab="${k}">${l}${n != null ? html`<em>${n}</em>` : ''}</a>`)}</div>`;
export const stepper = (steps, current) => html`<ol class="stepper">${steps.map((s, i) => html`<li class="${i < current ? 'done' : i === current ? 'cur' : ''}"><span>${i < current ? icon('check', 14) : i + 1}</span><b>${s}</b></li>`)}</ol>`;
export function timeline(items) {
  return html`<ol class="timeline">${items.map((it) => html`<li class="${it.state || 'done'}"><span class="dot">${it.state === 'pending' ? '' : it.state === 'current' ? '' : icon('check', 12)}</span><div><b>${it.title}</b>${it.time ? html`<time>${it.time}</time>` : ''}${it.note ? html`<p>${it.note}</p>` : ''}</div></li>`)}</ol>`;
}
export function requestTimeline(history, status, terminalStates = ['cancelled', 'rejected', 'expired', 'completed', 'disputed']) {
  const items = history.map((h, i) => ({ title: h.label, time: dt(h.created_at), note: h.reason && i > 0 ? `${h.reason}${h.actor_name ? ' — ' + h.actor_name : ''}` : null, state: i === history.length - 1 && !terminalStates.includes(status) ? 'current' : 'done' }));
  const order = ['submitted', 'under_review', 'finding_providers', 'quotes_received', 'awaiting_approval', 'confirmed', 'in_progress', 'completed'];
  if (!terminalStates.includes(status) || status === 'disputed') {
    const idx = Math.max(...history.map((h) => order.indexOf(h.to_status)));
    order.slice(idx + 1).forEach((s) => items.push({ title: statusInfo(s).label, state: 'pending' }));
  }
  return timeline(items);
}

// ------------------------------------------------------------ charts (inline SVG)
export function barChart({ labels, series, height = 190 }) {
  const W = 640; const pad = { l: 34, r: 8, t: 10, b: 26 };
  const max = Math.max(1, ...series.flatMap((s) => s.values));
  const top = Math.ceil(max / 4) * 4 || 4;
  const iw = W - pad.l - pad.r; const ih = height - pad.t - pad.b;
  const gw = iw / labels.length; const bw = Math.min(16, (gw - 4) / series.length);
  const y = (v) => pad.t + ih - (v / top) * ih;
  const grid = [0, 1, 2, 3, 4].map((i) => { const v = (top / 4) * i; return `<line x1="${pad.l}" x2="${W - pad.r}" y1="${y(v)}" y2="${y(v)}" class="grid"/><text x="${pad.l - 6}" y="${y(v) + 4}" class="axis" text-anchor="end">${Math.round(v)}</text>`; }).join('');
  const bars = labels.map((l, i) => series.map((s, j) => { const x = pad.l + i * gw + (gw - bw * series.length) / 2 + j * bw; const v = s.values[i]; return `<rect x="${x}" y="${y(v)}" width="${bw - 2}" height="${Math.max(0, pad.t + ih - y(v))}" rx="3" fill="${s.color}"><title>${esc(l)} · ${esc(s.name)}: ${v}</title></rect>`; }).join('')).join('');
  const step = Math.ceil(labels.length / 10);
  const xl = labels.map((l, i) => (i % step === 0 ? `<text x="${pad.l + i * gw + gw / 2}" y="${height - 8}" class="axis" text-anchor="middle">${esc(l)}</text>` : '')).join('');
  const legend = series.map((s) => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join('');
  return raw(`<figure class="chart"><svg viewBox="0 0 ${W} ${height}" role="img" aria-label="${esc(series.map((s) => s.name).join(' and '))} chart">${grid}${bars}${xl}</svg><figcaption class="legend">${legend}</figcaption></figure>`);
}
export function hbars(items, { fmt = (v) => v, color = 'var(--lime)' } = {}) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return html`<ul class="hbars">${items.map((i) => html`<li><span class="hb-l">${i.label}</span><span class="hb-bar"><i style="width:${Math.max(3, (i.value / max) * 100)}%;background:${i.color || color}"></i></span><b>${fmt(i.value)}</b></li>`)}</ul>`;
}

// ------------------------------------------------------------ chat
export function chat(items, meId, { empty = 'No messages yet. Say hello!' } = {}) {
  if (!items.length) return html`<div class="chat-empty">${empty}</div>`;
  return html`<div class="chat" role="log" aria-live="polite">${items.map((m) => html`<div class="msg ${m.sender_id === meId ? 'mine' : 'theirs'}">${m.sender_id === meId ? '' : html`<small>${m.sender_name}</small>`}<div class="bubble">${m.body === '(attachment)' ? '' : m.body}${m.attachment_id ? html`<button class="attach" data-act="open-file" data-id="${m.attachment_id}">${icon('paperclip', 14)} ${m.attachment_name || 'Attachment'}</button>` : ''}</div><time>${ago(m.created_at)}</time></div>`)}</div>`;
}
export const composer = (placeholder = 'Type a message…') => html`<form class="composer" data-chat-form autocomplete="off"><label class="icon-btn" title="Attach a photo or document" tabindex="0">${icon('paperclip', 20)}<input type="file" data-chat-file accept="image/*,.pdf,.txt" hidden></label><input name="body" placeholder="${placeholder}" aria-label="Message" maxlength="2000"><button class="btn btn-primary btn-icon" type="submit" aria-label="Send">${icon('send', 18)}</button></form>`;

// ------------------------------------------------------------ calendar
export function calendarMonth(ym, { marks = {}, blocked = {}, closedWeekdays = [] } = {}) {
  const [y, m] = ym.split('-').map(Number);
  const first = new Date(Date.UTC(y, m - 1, 1)); const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const offset = (first.getUTCDay() + 6) % 7; const today = new Date().toISOString().slice(0, 10);
  const cells = [];
  for (let i = 0; i < offset; i++) cells.push(html`<div class="cal-cell empty"></div>`);
  for (let d = 1; d <= days; d++) {
    const key = `${ym}-${String(d).padStart(2, '0')}`; const wd = (offset + d - 1) % 7;
    const bk = marks[key] || [];
    cells.push(html`<button type="button" class="cal-cell ${blocked[key] ? 'blocked' : ''} ${closedWeekdays.includes(wd) ? 'off' : ''} ${key === today ? 'today' : ''}" data-date="${key}" aria-label="${key}${blocked[key] ? ', blocked' : ''}${bk.length ? ', ' + bk.length + ' booking(s)' : ''}"><b>${d}</b>${bk.slice(0, 2).map((b) => html`<span class="cal-ev">${b}</span>`)}${bk.length > 2 ? html`<span class="cal-more">+${bk.length - 2}</span>` : ''}${blocked[key] ? html`<span class="cal-ev block">Blocked</span>` : ''}</button>`);
  }
  const name = first.toLocaleString('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  return html`<div class="cal"><div class="cal-head"><button class="icon-btn" data-act="cal-prev" aria-label="Previous month">${icon('left', 18)}</button><b>${name}</b><button class="icon-btn" data-act="cal-next" aria-label="Next month">${icon('right', 18)}</button></div><div class="cal-grid">${['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => html`<div class="cal-dow">${d}</div>`)}${cells}</div></div>`;
}

// ------------------------------------------------------------ toast / modal / confirm
let toastRoot;
function root(id, cls) { let r = document.getElementById(id); if (!r) { r = document.createElement('div'); r.id = id; r.className = cls; document.body.appendChild(r); } return r; }
export function toast(message, tone = 'success', action) {
  toastRoot = toastRoot || root('toasts', 'toasts'); toastRoot.setAttribute('aria-live', 'polite');
  const el = document.createElement('div'); el.className = 'toast toast-' + tone; el.setAttribute('role', tone === 'error' ? 'alert' : 'status');
  el.innerHTML = `<span>${esc(message)}</span>`;
  if (action) { const b = document.createElement('button'); b.textContent = action.label; b.onclick = () => { action.fn(); el.remove(); }; el.appendChild(b); }
  toastRoot.appendChild(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 250); }, action ? 12000 : tone === 'error' ? 6000 : 3500);
}
export function modal({ title, body, submitLabel = 'Save', cancelLabel = 'Cancel', onSubmit: submit, wide = false, danger = false, footer = true, onMount }) {
  const r = root('modal-root', ''); const prev = document.activeElement;
  const wrap = document.createElement('div'); wrap.className = 'modal-wrap';
  const form = submit ? 'form' : 'div';
  wrap.innerHTML = `<div class="modal-bg" data-close></div><${form} class="modal ${wide ? 'wide' : ''}" role="dialog" aria-modal="true" aria-label="${esc(title)}" ${submit ? 'novalidate' : ''}><div class="modal-head"><h2>${esc(title)}</h2><button type="button" class="icon-btn" data-close aria-label="Close">${icon('x', 20)}</button></div><div class="modal-body">${body}<div class="form-error" role="alert" hidden></div></div>${footer ? `<div class="modal-foot"><button type="button" class="btn btn-ghost" data-close>${esc(cancelLabel)}</button>${submit ? `<button type="submit" class="btn ${danger ? 'btn-danger' : 'btn-primary'}">${esc(submitLabel)}</button>` : ''}</div>` : ''}</${form}>`;
  r.appendChild(wrap); document.body.classList.add('modal-open');
  const close = () => { wrap.remove(); document.body.classList.remove('modal-open'); prev && prev.focus && prev.focus(); document.removeEventListener('keydown', esc_); };
  const esc_ = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', esc_);
  wrap.addEventListener('click', (e) => { if (e.target.closest('[data-close]')) close(); });
  const m = wrap.querySelector('.modal');
  if (submit) onSubmit(wrap, '.modal', async (data, f) => { const res = await submit(data, f, close); if (res !== false) close(); });
  (m.querySelector('input:not([type=hidden]),select,textarea,button') || m).focus();
  onMount && onMount(m, close);
  return { close, el: m };
}
export function confirmDialog({ title, message, confirmLabel = 'Confirm', danger = false, reason, reasonLabel = 'Reason' }) {
  return new Promise((resolve) => {
    let done = false;
    const m = modal({
      title, danger, submitLabel: confirmLabel,
      body: `<p class="modal-msg">${esc(message)}</p>${reason ? `<div class="field"><label for="cf-reason">${esc(reasonLabel)}${reason === 'required' ? ' <span class="req">*</span>' : ''}</label><textarea id="cf-reason" name="reason" rows="3" ${reason === 'required' ? 'required' : ''}></textarea><div class="field-error" data-error-for="reason" role="alert"></div></div>` : ''}`,
      onSubmit: async (d) => {
        if (reason === 'required' && !(d.reason || '').trim()) throw new ApiError(422, 'validation', 'Please add a reason.', { reason: 'A reason is required.' });
        done = true; resolve({ reason: (d.reason || '').trim() });
      },
    });
    const obs = new MutationObserver(() => { if (!document.body.contains(m.el)) { obs.disconnect(); if (!done) resolve(null); } });
    obs.observe(document.getElementById('modal-root'), { childList: true });
  });
}

// ------------------------------------------------------------ data table
/**
 * mountTable(el, { load, columns, filters, search, pageSize, empty, actions, rowHref, extra, onLoaded })
 * columns: [{ key, label, render(row) -> html|string, cls }]
 * Rows collapse into stacked cards on small screens. Search/filter/page state is local to the table.
 */
export function mountTable(el, cfg) {
  const state = { page: 1, q: '', ...(cfg.initial || {}) };
  let rows = []; let lastRes = null;
  const filters = cfg.filters || [];
  el.classList.add('tbl');
  el.innerHTML = '';
  const bar = document.createElement('div'); bar.className = 'tbl-bar';
  bar.innerHTML = `${cfg.search === false ? '' : `<label class="search"><span class="visually-hidden">Search</span>${icon('search', 18)}<input type="search" placeholder="${esc(cfg.search || 'Search…')}" value="${esc(state.q)}"></label>`}${filters.map((f) => `<select data-f="${esc(f.key)}" aria-label="${esc(f.label)}"><option value="">${esc(f.label)}</option>${f.options.map(([v, l]) => `<option value="${esc(v)}" ${String(state[f.key] ?? '') === String(v) ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>`).join('')}${cfg.extra ? `<div class="tbl-extra">${cfg.extra}</div>` : ''}`;
  const body = document.createElement('div'); body.className = 'tbl-body';
  const pager = document.createElement('div'); pager.className = 'tbl-pager';
  el.append(bar, body, pager);

  async function load() {
    body.innerHTML = skeleton(3).toString(); body.setAttribute('aria-busy', 'true');
    const params = { page: state.page, page_size: cfg.pageSize || 12 };
    if (state.q) params.q = state.q;
    filters.forEach((f) => { if (state[f.key]) params[f.key] = state[f.key]; });
    Object.keys(cfg.initial || {}).forEach((k) => { if (!filters.find((f) => f.key === k) && state[k] !== undefined && k !== 'page' && k !== 'q') params[k] = state[k]; });
    try {
      lastRes = await cfg.load(params); rows = lastRes.items || [];
      draw(); cfg.onLoaded && cfg.onLoaded(lastRes);
    } catch (e) { body.innerHTML = errorState(e).toString(); }
    body.removeAttribute('aria-busy');
  }
  function draw() {
    if (!rows.length) {
      const e = cfg.empty || { title: 'Nothing here yet', text: 'Records will appear here.' };
      body.innerHTML = emptyState({ ic: e.ic || 'inbox', title: state.q ? 'No matches' : e.title, text: state.q ? 'Try a different search or clear the filters.' : e.text, action: e.action ? raw(e.action) : '' }).toString(); pager.innerHTML = ''; return;
    }
    const head = cfg.columns.map((c) => `<th class="${c.cls || ''}">${esc(c.label)}</th>`).join('');
    const trs = rows.map((r, i) => `<tr data-i="${i}" ${cfg.rowHref ? `class="clickable" tabindex="0" data-href="${esc(cfg.rowHref(r))}"` : ''}>${cfg.columns.map((c) => `<td class="${c.cls || ''}" data-label="${esc(c.label)}">${part(c.render ? c.render(r, i) : r[c.key])}</td>`).join('')}</tr>`).join('');
    body.innerHTML = `<div class="table-wrap"><table class="table"><thead><tr>${head}</tr></thead><tbody>${trs}</tbody></table></div>`;
    const total = lastRes.total ?? rows.length; const size = lastRes.page_size || 12; const pages = Math.max(1, Math.ceil(total / size));
    pager.innerHTML = pages > 1 || total > 0 ? `<span>${total} result${total === 1 ? '' : 's'}</span><div><button class="btn btn-ghost btn-sm" data-page="${state.page - 1}" ${state.page <= 1 ? 'disabled' : ''}>${icon('left', 16)} Prev</button><b>Page ${state.page} of ${pages}</b><button class="btn btn-ghost btn-sm" data-page="${state.page + 1}" ${state.page >= pages ? 'disabled' : ''}>Next ${icon('right', 16)}</button></div>` : '';
  }
  const part = (v) => (v == null ? '' : v && v.s !== undefined ? v.s : Array.isArray(v) ? v.map(part).join('') : esc(v));
  const search = bar.querySelector('input[type=search]');
  if (search) search.addEventListener('input', debounce(() => { state.q = search.value.trim(); state.page = 1; load(); }, 350));
  bar.addEventListener('change', (e) => { const f = e.target.closest('[data-f]'); if (f) { state[f.dataset.f] = f.value; state.page = 1; load(); } });
  el.addEventListener('click', (e) => {
    const p = e.target.closest('[data-page]'); if (p && !p.disabled) { state.page = Number(p.dataset.page); load(); el.scrollIntoView({ block: 'nearest' }); return; }
    const a = e.target.closest('[data-row-act]');
    if (a) { e.stopPropagation(); const row = rows[Number(a.closest('tr').dataset.i)]; cfg.actions && cfg.actions[a.dataset.rowAct] && cfg.actions[a.dataset.rowAct](row, a, ctrl); return; }
    const tr = e.target.closest('tr[data-href]'); if (tr && !e.target.closest('a,button,input,select')) location.hash = tr.dataset.href.replace(/^#/, '');
  });
  el.addEventListener('keydown', (e) => { if (e.key === 'Enter') { const tr = e.target.closest('tr[data-href]'); if (tr && e.target === tr) location.hash = tr.dataset.href.replace(/^#/, ''); } });
  el.addEventListener('click', (e) => { if (e.target.closest('[data-act=retry]')) load(); });
  const ctrl = { reload: load, state, get rows() { return rows; } };
  load();
  return ctrl;
}
export const rowBtn = (act, label, cls = 'btn-ghost btn-sm') => html`<button class="btn ${cls}" data-row-act="${act}">${label}</button>`;

// Re-export helpers pages use constantly
export { cap, dt, html, icon, raw };
