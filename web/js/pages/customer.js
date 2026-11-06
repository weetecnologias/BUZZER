// Customer app (mobile-first): home, categories, request wizard, requests, quotes, approval, booking & payment.
import { ApiError, api, cap, dt, dtl, debounce, draft, esc, go, html, icon, inDays, inr, istInput, meta, num, openFile, phone, qs, raw, statusInfo, updateUser, upload, ago } from '../core.js';
import { avatar, btn, chip, confirmDialog, emptyState, field, kv, modal, note, opts, progress, requestTimeline, section, statusChip, stepper, tabs, timeline, toast, verified, stars } from '../ui.js';
import { routes as more } from './customer2.js';

export const catIc = (c, size = 44) => html`<span class="cat-ic" style="background:${c.color || c.category_color || '#E6F4D7'};width:${size}px;height:${size}px;font-size:${Math.round(size * 0.5)}px" aria-hidden="true">${c.icon || c.category_icon || '✨'}</span>`;
export const shortName = (n) => (n.length > 16 && n.includes(' & ') ? n.split(' & ')[0] : n);
const greet = () => { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'; };
export async function act(btnEl, fn) {
  const label = btnEl.innerHTML; btnEl.disabled = true; btnEl.innerHTML = '<span class="spin"></span>';
  try { return await fn(); } catch (e) { toast(e.message || 'Something went wrong', 'error'); } finally { if (btnEl.isConnected) { btnEl.disabled = false; btnEl.innerHTML = label; } }
}
const agentCard = (a, extra = '') => a ? html`<div class="card agent-card">${avatar(a.name, a.color, 48)}<div class="grow"><b>${a.name}</b> <span class="muted sm">· Your personal agent</span><div class="sm muted"><span class="online"></span>${a.availability === 'available' ? 'Online' : cap(a.availability || 'available')}${a.rating ? html` · ${icon('star', 13)} ${a.rating}` : ''}</div></div>${a.phone ? html`<a class="icon-btn btn-ghost" style="border:1.5px solid var(--line)" href="tel:${a.phone}" aria-label="Call ${a.name}">${icon('phone', 20)}</a>` : ''}<a class="icon-btn btn-ghost" style="border:1.5px solid var(--line)" href="#/c/messages" aria-label="Chat with ${a.name}">${icon('chat', 20)}</a></div>${extra}` : '';

// ---------------------------------------------------------------- home
async function home(ctx) {
  const [cats, reqs, me] = await Promise.all([api('/categories'), api('/requests?status_group=open&page_size=3'), api('/me')]);
  updateUser('customer', me);
  const by = Object.fromEntries(cats.items.map((c) => [c.slug, c]));
  const popular = [['home-repairs', 'Home repair'], ['travel-transport', 'Drivers'], ['events-celebrations', 'Events'], ['household-family', 'Cleaning']];
  ctx.on('input', '#q', debounce(async (e, el) => {
    const box = ctx.view.querySelector('#suggest'); const t = el.value.trim();
    if (t.length < 2) { box.innerHTML = ''; return; }
    const r = await api('/search?q=' + encodeURIComponent(t));
    box.innerHTML = (r.items.length ? r.items.map((x) => html`<a class="list-item" href="#/c/${x.category.is_active ? 'request/new?cat=' + x.category.slug + (x.subcategory ? '&sub=' + x.subcategory.id : '') : 'category/' + x.category.slug}">${catIc(x.category, 38)}<div class="grow"><b>${x.subcategory ? x.subcategory.name : x.category.name}</b><div class="muted sm">${x.subcategory ? x.category.name : 'Category'}</div></div>${icon('right', 18, 'chev')}</a>`).join('')
      : html`<div class="muted sm" style="padding:10px 4px">No match yet. <a href="#/c/request/new?cat=special-requests">Make a special request</a> and your agent will help.</div>`).toString();
  }, 250));
  const a = reqs.items.find((x) => x.quotes_shared) || reqs.items[0];
  return html`<div class="hello"><h1>${greet()}, ${me.name.split(' ')[0]} 👋</h1><p>What can we help you with?</p></div>
    <div class="card" style="padding:10px 12px"><label class="row" style="margin:0"><span class="visually-hidden">Search for a service</span>${icon('search', 20)}<input id="q" type="search" placeholder="Try “fix water leakage” or “driver for airport”" style="border:0;box-shadow:none;min-height:42px;padding:0" autocomplete="off"></label><div id="suggest"></div></div>
    <a class="btn btn-primary cta-big mt" href="#/c/request/new">${icon('plus', 22)} Request help</a>
    ${section('Popular services', html`<div class="tile-grid">${popular.map(([s, n]) => by[s] ? html`<a class="tile" href="#/c/category/${s}">${catIc(by[s], 56)}${n}</a>` : '')}</div>`, html`<a href="#/c/categories">Browse all ${cats.items.length}</a>`)}
    ${section('Active request', a ? html`<a class="card card-link req-card" href="#/c/request/${a.id}"><div class="row">${catIc(a)}<div class="grow"><b>${a.title}</b><div class="muted sm">${a.reference}</div></div>${statusChip(a.status)}</div>
        ${a.quotes_shared ? html`<div class="note note-warn mt">${a.quotes_shared} quote${a.quotes_shared > 1 ? 's' : ''} ready — compare and approve</div>` : html`<p class="muted sm mt" style="margin:8px 0 0">${statusInfo(a.status).next}</p>`}</a>`
      : html`<div class="card muted center">No open requests. Tap <b>Request help</b> to start.</div>`, reqs.total > 1 ? html`<a href="#/c/requests">View all (${reqs.total})</a>` : '')}
    <div class="mt">${me.agent ? agentCard(me.agent) : ''}</div>
    <div class="grid g2 mt"><a class="card card-link" href="#/c/jobs">${icon('briefcase', 22)}<h3 style="margin-top:8px">Quick Jobs</h3><p class="muted sm" style="margin:0">Find work near you</p></a><a class="card card-link" href="#/c/event-brief">${icon('gift', 22)}<h3 style="margin-top:8px">Plan an event</h3><p class="muted sm" style="margin:0">Weddings & photography</p></a></div>`;
}

// ---------------------------------------------------------------- categories
async function categories(ctx) {
  const r = await api('/categories');
  ctx.on('input', '#cq', debounce(async (e, el) => {
    const t = el.value.trim(); const box = ctx.view.querySelector('#cat-results'); const all = ctx.view.querySelector('#cat-all');
    if (t.length < 2) { box.innerHTML = ''; box.style.display = 'none'; all.hidden = false; return; }
    all.hidden = true; box.style.display = '';
    const s = await api('/search?q=' + encodeURIComponent(t));
    box.innerHTML = (s.items.length ? s.items.map((x) => html`<a class="list-item" href="#/c/${x.category.is_active ? 'category/' : 'category/'}${x.category.slug}">${catIc(x.category, 40)}<div class="grow"><b>${x.subcategory ? x.subcategory.name : x.category.name}</b><div class="muted sm">${x.subcategory ? x.category.name : ''}</div></div>${x.category.is_active ? '' : chip('Soon')}</a>`).join('') : emptyState({ ic: 'search', title: 'No matches', text: 'Try a simpler word, or send a special request.', action: html`<a class="btn btn-secondary" href="#/c/request/new?cat=special-requests">Special request</a>` }).toString()).toString();
  }, 250));
  ctx.on('click', '[data-soon]', () => toast('This category is coming soon. A special request lets your agent help right away.', 'error'));
  const live = r.items.filter((c) => c.is_active).length;
  return html`<div class="page-head"><div><h1>All services</h1><p class="sub">${live} live now · ${r.items.length - live} coming soon</p></div></div>
    <div class="card" style="padding:6px 12px"><label class="row" style="margin:0">${icon('search', 20)}<span class="visually-hidden">Search services</span><input id="cq" type="search" placeholder="Search services (e.g. plumber, wedding, driver)" style="border:0;box-shadow:none;min-height:42px;padding:0"></label></div>
    <div id="cat-results" class="card mt" style="padding:4px 12px;display:none"></div>
    <div id="cat-all">${r.modules.map((m) => html`${section(m.name, html`<div class="tile-grid">${m.categories.map((c) => c.is_active ? html`<a class="tile" href="#/c/category/${c.slug}">${catIc(c, 56)}${shortName(c.name)}</a>` : html`<button class="tile soon" data-soon style="border:0;background:none">${catIc(c, 56)}${shortName(c.name)}<span class="chip chip-neutral" style="font-size:10px">Soon</span></button>`)}</div>`)}`)}</div>`;
}

async function categoryDetail(ctx) {
  const c = await api('/categories/' + ctx.params.slug);
  ctx.bar({ title: c.name, back: '#/c/categories' });
  const special = { 'wedding-catering': ['/c/event-brief', 'Create wedding brief'], 'photography-videography': ['/c/photo-brief', 'Create photography brief'], 'quick-jobs-staffing': ['/c/jobs', 'Find a job'] }[c.slug];
  const cta = !c.is_active ? html`<button class="btn btn-secondary btn-block btn-lg" disabled>Coming soon</button><a class="btn btn-ghost btn-block mt" href="#/c/request/new?cat=special-requests">Ask for it as a special request</a>`
    : special ? html`<a class="btn btn-primary btn-block btn-lg" href="#${special[0]}">${special[1]}</a>${c.slug === 'quick-jobs-staffing' ? html`<a class="btn btn-ghost btn-block mt" href="#/c/request/new?cat=${c.slug}">Hire staff instead</a>` : ''}`
      : html`<a class="btn btn-primary btn-block btn-lg" href="#/c/request/new?cat=${c.slug}">Request help</a>`;
  return html`<div class="cover" style="background:${c.color};font-size:56px" aria-hidden="true">${c.icon}</div>
    <h1>${c.name}</h1><p class="muted">${c.description}</p>
    ${c.subcategories.length ? section('Popular requests', html`<div class="card" style="padding:0 14px">${c.subcategories.map((s) => html`<a class="list-item" href="#/c/${c.is_active && !special ? 'request/new?cat=' + c.slug + '&sub=' + s.id : 'category/' + c.slug}">${icon('tool', 20)}<span class="grow">${s.name}</span>${icon('right', 18, 'chev')}</a>`)}</div>`) : ''}
    ${section('What\'s included', html`<ul class="stack" style="padding-left:20px;margin:0">${c.includes.map((i) => html`<li>${i}</li>`)}</ul>`)}
    ${section('How BUZZER helps', timeline(c.how.map((t, i) => ({ title: t, state: 'done' }))))}
    ${section('Keep these handy', html`<ul style="padding-left:20px;margin:0;color:var(--muted)">${c.prepare.map((i) => html`<li>${i}</li>`)}</ul>`)}
    ${c.workflow ? note(html`Typical flow: ${c.workflow.stages.join(' → ')}`, 'info') : ''}
    <div class="sticky-cta">${cta}</div>`;
}

// ------------------------------------------------------------ request wizard
async function newRequest(ctx) {
  ctx.bar({ title: 'Create service request', back: '#/c/home' });
  const slug = ctx.query.cat;
  const all = (await api('/categories')).items.filter((c) => c.is_active);
  if (!slug) {
    return html`<h1>What do you need help with?</h1><p class="muted">Pick a service to get started.</p><div class="tile-grid mt">${all.map((c) => html`<a class="tile" href="#/c/${{ 'wedding-catering': 'event-brief', 'photography-videography': 'photo-brief' }[c.slug] || 'request/new?cat=' + c.slug}">${catIc(c, 56)}${shortName(c.name)}</a>`)}</div>`;
  }
  if (slug === 'wedding-catering') return go('/c/event-brief');
  if (slug === 'photography-videography') return go('/c/photo-brief');
  const [c, addr, m] = await Promise.all([api('/categories/' + slug), api('/addresses'), meta()]);
  if (!c.is_active) return html`${emptyState({ ic: 'sparkle', title: `${c.name} is coming soon`, text: 'Send a special request and your agent will arrange it manually.', action: html`<a class="btn btn-primary" href="#/c/request/new?cat=special-requests">Special request</a>` })}`;
  const key = 'request:' + slug; const saved = draft.load(key) || {};
  let step = 0; let files = saved.files || [];
  const $ = (s) => ctx.view.querySelector(s);
  const steps = ['Details', 'Location', 'Review'];
  const showStep = (n) => {
    step = n; ctx.view.querySelectorAll('[data-step]').forEach((s) => { s.hidden = Number(s.dataset.step) !== n; });
    $('#wiz-steps').innerHTML = stepper(steps, n).toString();
    $('[data-prev]').hidden = n === 0; $('[data-next]').hidden = n === 2; $('#submit-req').hidden = n !== 2;
    if (n === 2) $('#summary').innerHTML = summary().toString();
    window.scrollTo(0, 0);
  };
  const data = () => { const f = $('#f-req'); const d = {}; new FormData(f).forEach((v, k) => { d[k] = v; }); return d; };
  const fieldErr = (name, msg) => { const el = $(`[data-error-for="${CSS.escape(name)}"]`); if (el) el.textContent = msg; const i = $(`[name="${CSS.escape(name)}"]`); i && i.classList.toggle('invalid', !!msg); };
  const validate0 = () => {
    const d = data(); let ok = true;
    $('#f-req').querySelectorAll('[data-error-for]').forEach((e) => { e.textContent = ''; });
    if ((d.description || '').trim().length < 10) { fieldErr('description', 'Please describe what you need (at least 10 characters).'); ok = false; }
    c.schema.forEach((f) => { if (f.required && !(d['details.' + f.key] || '').trim()) { fieldErr('details.' + f.key, 'This field is required.'); ok = false; } });
    if (!ok) { const first = $('.invalid'); first && first.focus(); }
    return ok;
  };
  const validate1 = () => {
    const d = data(); if (d.address_id !== 'new') return true;
    let ok = true;
    if ((d['address.line1'] || '').trim().length < 5) { fieldErr('address.line1', 'Enter the full address.'); ok = false; }
    if (!d['address.locality']) { fieldErr('address.locality', 'Choose a locality.'); ok = false; }
    return ok;
  };
  const summary = () => {
    const d = data(); const sub = c.subcategories.find((s) => s.id === d.subcategory_id);
    const a = d.address_id === 'new' ? `${d['address.line1']}, ${d['address.locality']}` : (() => { const x = addr.items.find((i) => i.id === d.address_id); return x ? `${x.label}: ${x.line1}, ${x.locality}` : '—'; })();
    return html`<div class="card"><div class="row">${catIc(c)}<div class="grow"><b>${sub ? sub.name : c.name}</b><div class="muted sm">${c.name}</div></div></div><hr class="divider">${kv([
      ['What you need', d.description], ...c.schema.filter((f) => d['details.' + f.key]).map((f) => [f.label, d['details.' + f.key]]),
      ['Where', a], ['When', d.preferred_at ? dtl(new Date(d.preferred_at + ':00+05:30').toISOString()) : 'As soon as possible'],
      ['Budget', d.budget_min || d.budget_max ? `${d.budget_min ? inr(d.budget_min) : '—'} to ${d.budget_max ? inr(d.budget_max) : '—'}` : 'Not specified'], ['Photos', files.length ? files.length + ' attached' : 'None'], ['Priority', d.urgent ? 'Urgent' : 'Normal']])}</div>
      ${note('Nothing is booked yet. Your agent will collect quotes and you decide which one to approve.', 'info')}`;
  };
  ctx.on('click', '[data-next]', () => { if (step === 0 && !validate0()) return; if (step === 1 && !validate1()) return; showStep(step + 1); });
  ctx.on('click', '[data-prev]', () => showStep(step - 1));
  ctx.on('change', 'input[name=address_id]', (e, el) => { $('#new-addr').hidden = el.value !== 'new'; });
  ctx.on('input', '#f-req', () => { const d = data(); const c0 = $('#count'); c0 && (c0.textContent = (d.description || '').length + ' / 1000'); draft.save(key, { ...d, files }); });
  ctx.on('change', '#photos', async (e, el) => {
    const list = $('#photo-list');
    for (const f of [...el.files].slice(0, 4 - files.length)) {
      try { const r = await upload(f); files.push(r); } catch (err) { toast(err.message, 'error'); }
    }
    el.value = ''; list.innerHTML = files.map((f, i) => `<span class="chip chip-neutral">${esc(f.filename)} <button type="button" data-rm="${i}" aria-label="Remove ${esc(f.filename)}" style="border:0;background:none;padding:0 0 0 4px">✕</button></span>`).join(' ');
    draft.save(key, { ...data(), files });
  });
  ctx.on('click', '[data-rm]', (e, b) => { files.splice(Number(b.dataset.rm), 1); $('#photo-list').innerHTML = files.map((f, i) => `<span class="chip chip-neutral">${esc(f.filename)} <button type="button" data-rm="${i}" style="border:0;background:none;padding:0 0 0 4px">✕</button></span>`).join(' '); });
  ctx.submit('#f-req', async (d) => {
    if (step < 2) return;
    const details = {}; Object.entries(d).forEach(([k, v]) => { if (k.startsWith('details.') && v !== '') details[k.slice(8)] = v; });
    const body = { category_id: c.id, subcategory_id: d.subcategory_id || undefined, description: d.description, priority: d.urgent ? 'urgent' : 'normal', details, attachments: files.map((f) => f.id) };
    if (d.preferred_at) body.preferred_at = d.preferred_at;
    if (d.budget_min) body.budget_min = d.budget_min; if (d.budget_max) body.budget_max = d.budget_max;
    if (d.address_id === 'new') body.address = { label: d['address.label'] || 'Home', line1: d['address.line1'], locality: d['address.locality'], landmark: d['address.landmark'], pincode: d['address.pincode'] };
    else body.service_address_id = d.address_id;
    try {
      const r = await api('/requests', { method: 'POST', body });
      draft.clear(key); go(`/c/request/${r.id}/submitted`);
    } catch (e) {
      if (e instanceof ApiError && e.fields) { const bad = Object.keys(e.fields)[0] || ''; showStep(bad.startsWith('address') || ['service_address_id', 'preferred_at', 'budget_max', 'budget_min'].includes(bad) ? 1 : 0); }
      throw e;
    }
  });
  ctx.after(() => {
    showStep(0);
    if (files.length) $('#photo-list').innerHTML = files.map((f, i) => `<span class="chip chip-neutral">${esc(f.filename)} <button type="button" data-rm="${i}" style="border:0;background:none;padding:0 0 0 4px">✕</button></span>`).join(' ');
    Object.entries(saved).forEach(([k, v]) => { const el = $(`#f-req [name="${CSS.escape(k)}"]`); if (el && k !== 'files' && el.type !== 'radio' && el.type !== 'checkbox') el.value = v; });
    if (saved.address_id) { const r = $(`input[name=address_id][value="${CSS.escape(saved.address_id)}"]`); if (r) { r.checked = true; $('#new-addr').hidden = saved.address_id !== 'new'; } }
    if (saved.urgent) $('input[name=urgent]').checked = true;
    if (Object.keys(saved).length) toast('We restored your draft.');
  });
  const hasAddr = addr.items.length > 0;
  const minDt = istInput(new Date(Date.now() + 36e5).toISOString());
  return html`<div id="wiz-steps"></div>
    <form id="f-req" novalidate>
      <section data-step="0"><div class="row mb">${catIc(c)}<div><h1 style="font-size:20px">${c.name}</h1><span class="muted sm">${c.description}</span></div></div>
        ${c.subcategories.length ? field({ name: 'subcategory_id', label: 'Service type', type: 'select', options: [['', 'Select (optional)'], ...c.subcategories.map((s) => [s.id, s.name])], value: ctx.query.sub || '' }) : ''}
        <div class="field"><label for="f-description">Describe your requirement <span class="req">*</span></label><textarea id="f-description" name="description" rows="4" maxlength="1000" placeholder="e.g. The kitchen tap is leaking. Need a plumber tomorrow morning."></textarea><div class="row between"><div class="field-error" data-error-for="description" role="alert"></div><span class="hint" id="count">0 / 1000</span></div></div>
        ${c.schema.map((f) => field({ name: 'details.' + f.key, label: f.label, type: f.type === 'select' ? 'select' : f.type === 'textarea' ? 'textarea' : f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : 'text', required: f.required, placeholder: f.placeholder, options: f.type === 'select' ? [['', 'Select'], ...f.options] : undefined, min: f.type === 'number' ? 0 : undefined }))}
        <div class="field"><label>Photos (optional, up to 4)</label><label class="btn btn-ghost btn-block" style="cursor:pointer">${icon('camera', 20)} Add photos<input id="photos" type="file" accept="image/*" multiple hidden></label><div id="photo-list" class="row-gap mt"></div></div>
        <label class="switch-row"><span><b>Mark as urgent</b><small>Your agent will prioritise this request</small></span><input type="checkbox" role="switch" name="urgent"><i></i></label></section>
      <section data-step="1" hidden><h2 class="mb">Where do you need the service?</h2>
        <div class="radio-cards mb">${addr.items.map((a, i) => html`<label><input type="radio" name="address_id" value="${a.id}" ${i === 0 ? 'checked' : ''}><span><b>${a.label}</b><br><span class="muted sm">${a.line1}, ${a.locality}</span></span></label>`)}<label><input type="radio" name="address_id" value="new" ${hasAddr ? '' : 'checked'}><span><b>${icon('plus', 16)} Add a new address</b></span></label></div>
        <div id="new-addr" ${hasAddr ? 'hidden' : ''}>${field({ name: 'address.label', label: 'Label', value: 'Home' })}${field({ name: 'address.line1', label: 'Address', placeholder: 'Flat / house no., street', auto: 'street-address' })}${field({ name: 'address.locality', label: 'Locality', type: 'select', options: [['', 'Select locality'], ...m.localities] })}${field({ name: 'address.landmark', label: 'Landmark (optional)' })}${field({ name: 'address.pincode', label: 'Pincode (optional)', inputmode: 'numeric', maxlength: 6 })}</div>
        <div class="field-error" data-error-for="service_address_id" role="alert"></div>
        ${field({ name: 'preferred_at', label: 'Preferred date & time', type: 'datetime-local', min: minDt, hint: 'Optional. Times are in IST.' })}
        <div class="grid g2">${field({ name: 'budget_min', label: 'Budget from (₹)', type: 'number', min: 0, inputmode: 'numeric' })}${field({ name: 'budget_max', label: 'Budget to (₹)', type: 'number', min: 0, inputmode: 'numeric' })}</div></section>
      <section data-step="2" hidden><h2 class="mb">Review your request</h2><div id="summary"></div></section>
      <div class="form-error" role="alert" hidden></div>
      <div class="row between mt"><button type="button" class="btn btn-ghost" data-prev hidden>Back</button><button type="button" class="btn btn-primary grow" data-next>Next</button><button type="submit" class="btn btn-primary grow btn-lg" id="submit-req" hidden>Submit request</button></div>
    </form>`;
}

async function submitted(ctx) {
  ctx.bar({ title: '', back: '#/c/home' });
  const r = await api('/requests/' + ctx.params.id);
  return html`<div class="center" style="padding:20px 0"><div style="width:84px;height:84px;border-radius:50%;background:var(--ok);color:#fff;display:grid;place-items:center;margin:0 auto 14px">${icon('check', 44)}</div><h1>Request submitted!</h1><p class="muted">Your request has been created successfully.</p></div>
    <div class="card card-soft"><div class="row between"><span class="muted">Request ID</span><b style="font-size:18px">${r.reference}</b></div></div>
    ${r.agent ? html`<div class="mt">${agentCard(r.agent)}</div><p class="muted sm center">${r.agent.name} will review your request shortly.</p>` : html`<div class="card mt">An agent will be assigned shortly.</div>`}
    ${section('What\'s next?', timeline([{ title: 'Your agent reviews the request', state: 'current', note: 'Usually within 2 hours' }, { title: 'We contact verified providers', state: 'pending' }, { title: 'You compare quotes and approve one', state: 'pending', note: 'Nothing is booked without your approval' }]))}
    <div class="stack mt"><a class="btn btn-primary btn-block btn-lg" href="#/c/request/${r.id}">Track request</a><a class="btn btn-ghost btn-block" href="#/c/request/new">Create another request</a></div>`;
}

// ---------------------------------------------------------------- my requests
async function myRequests(ctx) {
  const tab = ctx.query.tab || 'open';
  const groups = { open: 'open', completed: 'completed', cancelled: 'cancelled' };
  const [r, counts] = await Promise.all([api('/requests' + qs({ status_group: groups[tab] || 'open', page_size: 30, q: ctx.query.q })),
    Promise.all(['open', 'completed', 'cancelled'].map((g) => api('/requests?page_size=1&status_group=' + g).then((x) => x.total)))]);
  ctx.on('input', '#rq', debounce((e, el) => go('/c/requests' + qs({ tab, q: el.value.trim() })), 500));
  ctx.after(() => { const i = ctx.view.querySelector('#rq'); if (i && ctx.query.q) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } });
  return html`<div class="page-head"><h1>My requests</h1><a class="btn btn-primary btn-sm" href="#/c/request/new">${icon('plus', 16)} New</a></div>
    ${tabs([['open', 'Active', counts[0]], ['completed', 'Completed', counts[1]], ['cancelled', 'Cancelled', counts[2]]], tab, '#/c/requests')}
    <label class="search mt" style="max-width:none">${icon('search', 18)}<input id="rq" type="search" placeholder="Search by reference or title" value="${ctx.query.q || ''}"></label>
    <div class="mt">${r.items.length ? r.items.map((x) => html`<a class="card card-link req-card" href="#/c/request/${x.id}"><div class="row">${catIc(x)}<div class="grow"><b>${x.title}</b><div class="muted sm">${x.reference} · ${x.agent_name ? 'Agent ' + x.agent_name : 'Assigning agent'}</div></div>${statusChip(x.status)}</div>
        <div class="row between sm muted" style="margin-top:10px"><span>${x.quotes_shared ? html`<b style="color:var(--green-d)">Quotes received (${x.quotes_shared})</b>` : x.quotes_total ? 'Quotes being checked' : 'Awaiting quotes'}</span><span>Updated ${ago(x.updated_at)}</span></div></a>`)
      : emptyState({ ic: 'list', title: tab === 'open' ? 'No active requests' : `No ${tab} requests`, text: 'When you create a request it will show up here with live status.', action: html`<a class="btn btn-primary" href="#/c/request/new">Create a request</a>` })}</div>`;
}

// ---------------------------------------------------------------- request detail
async function requestDetail(ctx) {
  const r = await api('/requests/' + ctx.params.id);
  ctx.bar({ title: r.reference, back: '#/c/requests' });
  ctx.on('click', '[data-act=cancel]', async () => {
    const res = await confirmDialog({ title: 'Cancel this request?', message: r.booking ? 'Your booking will be cancelled too. If you have paid, a refund request is raised automatically.' : 'You can always create a new request later.', confirmLabel: 'Cancel request', danger: true, reason: 'required', reasonLabel: 'Why are you cancelling?' });
    if (!res) return;
    try { await api(`/requests/${r.id}/cancel`, { method: 'POST', body: { reason: res.reason } }); toast('Request cancelled.'); ctx.reload(); } catch (e) { toast(e.message, 'error'); }
  });
  ctx.on('click', '[data-act=open-file]', (e, b) => openFile(b.dataset.id));
  const shared = r.quotes.filter((q) => ['shared', 'clarification_requested', 'revised_pending_consent'].includes(q.status));
  const reconsent = r.quotes.find((q) => q.status === 'revised_pending_consent');
  return html`<div class="card"><div class="row">${catIc(r.category)}<div class="grow"><h1 style="font-size:20px">${r.title}</h1><div class="muted sm">${r.reference} · ${dt(r.created_at)}</div></div></div><div class="mt">${statusChip(r.status)}</div>
      <p class="mt" style="margin-bottom:0">${r.status_next}</p>${r.cancel_reason ? html`<p class="muted sm">Reason: ${r.cancel_reason}</p>` : ''}</div>
    ${reconsent ? html`<a class="card card-link mt" style="border-color:var(--warn)" href="#/c/quote/${reconsent.id}"><b>${icon('alert', 18)} The price changed — please re-approve</b><p class="muted sm" style="margin:4px 0 0">Your booking is on hold until you approve the revised quote.</p></a>`
      : shared.length && r.status === 'awaiting_approval' ? html`<a class="card card-link card-soft mt" href="#/c/request/${r.id}/quotes"><div class="row"><div class="grow"><b>${shared.length} quote${shared.length > 1 ? 's' : ''} ready to compare</b><div class="muted sm">From ${r.quotes.map((q) => q.provider.business_name).slice(0, 2).join(' & ')} · from ${inr(Math.min(...shared.map((q) => q.total)))}</div></div><span class="btn btn-primary btn-sm">View quotes</span></div></a>` : ''}
    ${r.booking ? html`<a class="card card-link mt" href="#/c/booking/${r.booking.id}"><div class="row"><div class="grow"><b>${r.booking.provider.business_name}</b><div class="muted sm">${r.booking.reference} · ${r.booking.scheduled_at ? dtl(r.booking.scheduled_at) : 'Time to be confirmed'}</div></div>${chip(cap(r.booking.status), r.booking.status === 'completed' ? 'success' : 'info')}</div><div class="row between mt"><span class="muted sm">${inr(r.booking.amount)} · ${r.booking.payment_status === 'paid' ? 'Paid' : r.booking.payment_status === 'deposit_paid' ? 'Deposit paid' : 'Payment due'}</span><span class="sm" style="font-weight:650">Open ${icon('right', 14)}</span></div></a>` : ''}
    ${r.can_confirm_completion ? html`<div class="note note-warn mt"><b>Work marked as done.</b> Please confirm it is complete to release payment — or raise an issue.<div class="row-gap mt"><a class="btn btn-primary btn-sm" href="#/c/booking/${r.booking.id}">Review & confirm</a><a class="btn btn-ghost btn-sm" href="#/c/support?tab=issue&request=${r.id}">Raise an issue</a></div></div>` : ''}
    ${r.can_rate ? html`<a class="card card-link card-soft mt" href="#/c/support?tab=rate&request=${r.id}"><b>⭐ How was your service?</b><p class="muted sm" style="margin:2px 0 0">Rate your provider and agent in 10 seconds.</p></a>` : ''}
    ${section('Progress', html`<div class="card">${requestTimeline(r.history, r.status)}</div>`)}
    ${r.agent ? section('Your agent', agentCard(r.agent)) : ''}
    ${section('Request details', html`<div class="card">${kv([['Service', r.subcategory ? r.subcategory.name : r.category.name], ['What you asked', r.description], ...r.details.map((d) => [d.label, d.value]), ['Where', r.address ? `${r.address.line1}, ${r.address.locality}` : r.locality],
      ['When', r.preferred_at ? dtl(r.preferred_at) : 'As soon as possible'], ['Budget', r.budget_min || r.budget_max ? `${r.budget_min ? inr(r.budget_min) : '—'} to ${r.budget_max ? inr(r.budget_max) : '—'}` : null], ['Priority', cap(r.priority)]])}${r.attachments.length ? html`<div class="row-gap mt">${r.attachments.map((a) => html`<button class="attach" data-act="open-file" data-id="${a.id}">${icon('paperclip', 14)} ${a.filename}</button>`)}</div>` : ''}</div>`)}
    ${r.brief ? section(r.brief.kind === 'wedding' ? 'Event brief' : 'Coverage brief', html`<div class="card">${kv([['Event', r.brief.event_type], ['Date', r.brief.event_date ? dateFmt(r.brief.event_date) : null], ['Venue', r.brief.venue], ['Guests', r.brief.guest_count], ['Services', r.brief.services], ['Coverage', r.brief.coverage_hours ? r.brief.coverage_hours + ' hours · ' + (r.brief.coverage_style || '') : null], ['Deliverables', r.brief.deliverables], ['Notes', r.brief.notes]])}</div>`) : ''}
    ${r.milestones.length ? section('Milestones', html`<div class="card">${timeline(r.milestones.map((m) => ({ title: m.title, time: m.due_date ? dateFmt(m.due_date) : '', state: m.status === 'done' ? 'done' : m.status === 'in_progress' ? 'current' : 'pending', note: m.provider_name })))}</div>`) : ''}
    <div class="stack mt"><a class="btn btn-secondary btn-block" href="#/c/messages">${icon('chat', 18)} Message your agent</a>
      ${r.can_cancel ? html`<button class="btn btn-danger-o btn-block" data-act="cancel">Cancel request</button>` : ''}</div>`;
}
const dateFmt = (d) => new Date(d + 'T00:00:00+05:30').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'Asia/Kolkata' });

// ---------------------------------------------------------------- quotes comparison
async function quotes(ctx) {
  const r = await api('/requests/' + ctx.params.id);
  ctx.bar({ title: `Compare quotes (${r.quotes.filter((q) => q.status !== 'rejected').length})`, back: '#/c/request/' + r.id });
  const list = r.quotes.filter((q) => ['shared', 'clarification_requested', 'revised_pending_consent', 'approved'].includes(q.status)).sort((a, b) => a.total - b.total);
  const best = list.length ? Math.min(...list.map((q) => q.total)) : null;
  ctx.on('click', '[data-act=ask]', (e, b) => askAgent(b.dataset.id, ctx));
  ctx.on('click', '[data-act=reject]', async (e, b) => {
    const res = await confirmDialog({ title: 'Decline this quote?', message: 'Your agent will be told, and can find other options for you.', confirmLabel: 'Decline quote', danger: true, reason: true, reasonLabel: 'Anything we should know? (optional)' });
    if (res) { try { await api(`/quotes/${b.dataset.id}/decision`, { method: 'POST', body: { decision: 'reject', note: res.reason } }); toast('Quote declined.'); ctx.reload(); } catch (err) { toast(err.message, 'error'); } }
  });
  if (!list.length) return html`${emptyState({ ic: 'file', title: 'No quotes to compare yet', text: 'Your agent is checking quotes from providers. We will notify you as soon as they are ready.', action: html`<a class="btn btn-primary" href="#/c/request/${r.id}">Back to request</a>` })}`;
  return html`<p class="muted">Select a provider that works for you. Nothing is booked until you approve.</p>
    <div class="stack">${list.map((q) => html`<article class="card quote-card ${q.total === best ? 'best' : ''}">
      <div class="row">${avatar(q.provider.business_name, q.provider.logo_color, 44)}<div class="grow"><b>${q.provider.business_name}</b><div class="row-gap sm" style="gap:6px">${verified(q.provider.verification_status)}<span class="muted">${q.provider.rating_avg ? html`${icon('star', 13)} ${q.provider.rating_avg} (${q.provider.rating_count})` : 'New'}</span></div></div>${q.total === best && list.length > 1 ? chip('Best price', 'success') : ''}</div>
      <div class="row between mt"><span class="price-big">${inr(q.total)}</span><span class="muted sm">${q.deposit_pct ? `${inr(q.deposit_amount)} deposit` : 'Pay on completion'}</span></div>
      <ul class="sm" style="padding-left:18px;margin:8px 0">${q.items.map((i) => html`<li>${i.description} <span class="muted">· ${inr(i.amount)}</span></li>`)}</ul>
      <div class="sm muted">${icon('clock', 14)} ${q.est_start_at ? 'Available ' + dt(q.est_start_at) : 'Schedule to be agreed'}${q.est_duration ? ' · ' + q.est_duration : ''}${q.warranty_days ? ' · ' + q.warranty_days + '-day warranty' : ''}</div>
      ${q.status === 'clarification_requested' ? html`<div class="note note-info mt">You asked: “${q.clarification_note}”. Your agent will reply.</div>` : ''}
      ${q.status === 'revised_pending_consent' ? html`<div class="note note-warn mt">Price or scope changed — re-approval needed.</div>` : ''}
      <div class="row-gap mt">${q.status === 'approved' ? chip('Approved ✓', 'success') : html`<a class="btn btn-primary grow" href="#/c/quote/${q.id}">Review & approve</a><button class="btn btn-ghost" data-act="ask" data-id="${q.id}">Ask agent</button><button class="btn btn-ghost" data-act="reject" data-id="${q.id}" aria-label="Decline quote">${icon('x', 18)}</button>`}</div></article>`)}</div>`;
}
export function askAgent(qid, ctx) {
  modal({ title: 'Ask your agent', submitLabel: 'Send question', body: field({ name: 'note', label: 'What would you like to know?', type: 'textarea', required: true, placeholder: 'e.g. Does the price include spare parts?' }).toString(),
    onSubmit: async (d) => { await api(`/quotes/${qid}/decision`, { method: 'POST', body: { decision: 'clarify', note: d.note } }); toast('Your agent will get back to you shortly.'); ctx.reload(); } });
}

// ---------------------------------------------------------------- quote approval
async function quoteApproval(ctx) {
  const q = await api('/quotes/' + ctx.params.id);
  ctx.bar({ title: 'Quote details', back: `#/c/request/${q.request_id}/quotes` });
  const re = q.status === 'revised_pending_consent';
  ctx.on('click', '[data-act=ask]', () => askAgent(q.id, ctx));
  ctx.on('click', '[data-act=reject]', async () => {
    const res = await confirmDialog({ title: 'Decline this quote?', message: 'Your agent will look for other options.', confirmLabel: 'Decline', danger: true, reason: true, reasonLabel: 'Reason (optional)' });
    if (res) { try { await api(`/quotes/${q.id}/decision`, { method: 'POST', body: { decision: 'reject', note: res.reason } }); go(`/c/request/${q.request_id}`); } catch (e) { toast(e.message, 'error'); } }
  });
  ctx.submit('#f-approve', async (d) => {
    const r = await api(`/quotes/${q.id}/decision`, { method: 'POST', body: { decision: 'approve', accept_terms: d.accept_terms, slot: d.slot || undefined } });
    toast('Quote approved — your booking is confirmed.'); go(`/c/booking/${r.id || ''}`);
  });
  if (!['shared', 'revised_pending_consent', 'clarification_requested'].includes(q.status)) return html`${emptyState({ ic: 'check', title: q.status === 'approved' ? 'You approved this quote' : 'This quote is no longer open', text: `Status: ${cap(q.status)}`, action: html`<a class="btn btn-primary" href="#/c/request/${q.request_id}">Back to request</a>` })}`;
  return html`<div class="card"><div class="row">${avatar(q.provider.business_name, q.provider.logo_color, 48)}<div class="grow"><b>${q.provider.business_name}</b><div class="row-gap sm" style="gap:6px">${verified(q.provider.verification_status)}<span class="muted">${q.provider.rating_avg ? html`${icon('star', 13)} ${q.provider.rating_avg} · ` : ''}${q.provider.jobs_completed} jobs</span></div></div></div></div>
    ${re ? html`<div class="note note-warn mt"><b>Price or scope changed.</b> Please review and approve again to keep your booking.</div>` : ''}
    ${section('Service breakdown', html`<div class="card"><p class="sm">${q.scope}</p><hr class="divider">${q.items.map((i) => html`<div class="row between sm" style="padding:4px 0"><span>${i.description}${i.qty > 1 ? ' × ' + i.qty : ''}</span><b>${inr(i.amount)}</b></div>`)}<hr class="divider"><div class="row between"><b>Total</b><span class="price-big" style="font-size:22px">${inr(q.total)}</span></div></div>`)}
    ${section('Terms & conditions', html`<div class="card">${kv([['Valid until', dt(q.valid_until)], ['Warranty', q.warranty_days ? q.warranty_days + ' days' : 'None'], ['Deposit', q.deposit_pct ? `${q.deposit_pct}% (${inr(q.deposit_amount)}) to confirm` : 'None - pay after work'], ['Expected', q.est_duration]])}${q.exclusions ? html`<p class="sm mt"><b>Not included:</b> ${q.exclusions}</p>` : ''}${q.terms ? html`<p class="sm muted">${q.terms}</p>` : ''}<p class="sm muted" style="margin:0">Free cancellation before the provider starts the job.</p></div>`)}
    <form id="f-approve" class="mt" novalidate>${field({ name: 'slot', label: 'Confirm your preferred slot', type: 'datetime-local', value: q.est_start_at ? istInput(q.est_start_at) : '', min: istInput(new Date().toISOString()), hint: 'Optional. We will confirm the exact time with the provider.' })}
      ${field({ name: 'accept_terms', type: 'checkbox', label: `I approve this quote of ${inr(q.total)} from ${q.provider.business_name} and its terms. A booking will be created.` })}
      <div class="form-error" role="alert" hidden></div>
      <div class="sticky-cta"><button class="btn btn-primary btn-block btn-lg" type="submit">${re ? 'Approve revised quote' : 'Approve quote'}</button><div class="grid g2 mt"><button class="btn btn-secondary" type="button" data-act="ask">Ask agent</button><button class="btn btn-danger-o" type="button" data-act="reject">Decline</button></div></div></form>`;
}

// ---------------------------------------------------------------- booking & payment
async function booking(ctx) {
  const b = await api('/bookings/' + ctx.params.id);
  ctx.bar({ title: 'Booking & payment', back: '#/c/request/' + b.request_id });
  const m = await meta();
  const due = b.amount - b.paid_amount; const depDue = Math.max(0, b.deposit_amount - b.paid_amount);
  const pay = async (kind, el) => act(el, async () => {
    const method = ctx.view.querySelector('input[name=method]:checked')?.value || 'upi';
    await api(`/bookings/${b.id}/pay`, { method: 'POST', body: { kind, method, simulate: el.dataset.fail ? 'failure' : undefined } });
    toast('Payment successful.'); ctx.reload();
  });
  ctx.on('click', '[data-pay]', (e, el) => pay(el.dataset.pay, el));
  ctx.on('click', '[data-act=confirm]', (e, el) => act(el, async () => {
    if (due > 0) { const method = ctx.view.querySelector('input[name=method]:checked')?.value || 'upi'; await api(`/bookings/${b.id}/pay`, { method: 'POST', body: { kind: 'balance', method } }); }
    await api(`/requests/${b.request_id}/complete`, { method: 'POST' }); toast('Thanks! Marked as complete.'); go(`/c/support?tab=rate&request=${b.request_id}`);
  }));
  ctx.on('click', '[data-act=cancel]', async () => {
    const res = await confirmDialog({ title: 'Cancel this booking?', message: b.paid_amount ? `You paid ${inr(b.paid_amount)}. A refund request will be raised for finance review (3-5 working days).` : 'Free cancellation before the job starts.', confirmLabel: 'Cancel booking', danger: true, reason: 'required', reasonLabel: 'Reason for cancelling' });
    if (res) { try { await api(`/bookings/${b.id}/cancel`, { method: 'POST', body: { reason: res.reason } }); toast('Booking cancelled.'); go('/c/request/' + b.request_id); } catch (e) { toast(e.message, 'error'); } }
  });
  ctx.on('click', '[data-act=receipt]', () => {
    modal({ title: 'Receipt', footer: false, body: `<div class="receipt"><div class="row between"><b class="logo">BUZZER</b><span class="muted sm">RCPT-${esc(b.reference.split('-').slice(1).join('-'))}</span></div><hr class="divider"><p class="sm" style="margin:0">${esc(b.provider_name)} · ${esc(b.title)}<br><span class="muted">Request ${esc(b.request_reference)} · Booking ${esc(b.reference)}</span></p><hr class="divider">${b.payments.filter((p) => p.status === 'success').map((p) => `<div class="row between sm"><span>${esc(p.reference)} · ${esc(p.method || '')}<br><span class="muted xs">${esc(dt(p.created_at))}</span></span><b>${esc(inr(p.amount))}</b></div>`).join('') || '<p class="muted sm">No payments yet.</p>'}<hr class="divider"><div class="row between"><b>Paid</b><b>${esc(inr(b.paid_amount))}</b></div><div class="row between muted"><span>Service total</span><span>${esc(inr(b.amount))}</span></div><p class="xs muted" style="margin-top:12px">Sandbox payments - no real money moved.</p></div><div class="row-gap mt"><button class="btn btn-primary" data-print>Print / save PDF</button></div>`, onMount: (m) => m.querySelector('[data-print]').addEventListener('click', () => window.print()) });
  });
  const live = !['cancelled', 'completed'].includes(b.status);
  return html`<div class="card"><div class="row"><div class="grow"><b>${b.title}</b><div class="muted sm">${b.reference} · ${b.provider_name}</div></div>${chip(cap(b.status), b.status === 'completed' ? 'success' : b.status === 'cancelled' ? 'neutral' : b.status === 'pending_reconsent' ? 'warn' : 'info')}</div>
      <hr class="divider">${kv([['Scheduled', b.scheduled_at ? dtl(b.scheduled_at) : 'To be confirmed'], ['Provider', html`${b.provider_name} ${verified(b.provider_verification)}`], ['Where', b.address ? `${b.address.line1}, ${b.address.locality}` : b.locality]])}</div>
    ${b.status === 'work_done' ? html`<div class="note note-warn mt"><b>${b.provider_name} says the work is done.</b> ${b.completion_note || ''}<br>Confirm if you're happy — payment of any balance is released then.</div>` : ''}
    ${b.status === 'pending_reconsent' ? html`<a class="card card-link mt" style="border-color:var(--warn)" href="#/c/quote/${b.quotation_id}"><b>Re-approval needed</b><p class="muted sm" style="margin:2px 0 0">The quote changed after you approved it. Review and approve again.</p></a>` : ''}
    ${section('Payment', html`<div class="card pay-row">${kv([['Service total', inr(b.amount)], b.deposit_amount ? ['Deposit to confirm', inr(b.deposit_amount)] : null, ['Paid so far', inr(b.paid_amount)], ['Balance due', html`<b>${inr(due)}</b>`]])}
      ${live && due > 0 && b.status !== 'pending_reconsent' ? html`<hr class="divider"><div class="radio-cards" role="radiogroup" aria-label="Payment method">${[['upi', 'UPI (Google Pay / PhonePe / Paytm)'], ['card', 'Debit / Credit card'], ['netbanking', 'Net banking'], ['wallet', 'BUZZER wallet']].map(([v, l], i) => html`<label><input type="radio" name="method" value="${v}" ${i === 0 ? 'checked' : ''}><span>${l}</span></label>`)}</div>
        <div class="stack mt">${depDue > 0 ? html`<button class="btn btn-primary btn-block" data-pay="deposit">Pay deposit ${inr(depDue)}</button>` : ''}<button class="btn ${depDue > 0 ? 'btn-secondary' : 'btn-primary'} btn-block" data-pay="${b.paid_amount ? 'balance' : 'full'}">${b.paid_amount ? 'Pay balance ' : 'Pay in full '}${inr(due)}</button>
        ${m.env === 'development' ? html`<button class="btn btn-ghost btn-sm" data-pay="full" data-fail="1">Simulate a declined payment (sandbox)</button>` : ''}</div><p class="xs muted mt">Sandbox payments: no real card details are collected or stored.</p>` : ''}
      ${b.payment_status === 'paid' ? html`<div class="note note-success mt">${icon('check', 16)} Fully paid</div>` : ''}${b.refund ? html`<div class="note note-info mt">Refund ${b.refund.status}: ${inr(b.refund.amount)}</div>` : ''}</div>`)}
    ${b.status === 'work_done' ? html`<div class="sticky-cta"><button class="btn btn-primary btn-block btn-lg" data-act="confirm">${due > 0 ? `Pay ${inr(due)} & confirm completion` : 'Confirm completion'}</button><a class="btn btn-ghost btn-block mt" href="#/c/support?tab=issue&request=${b.request_id}">Something's wrong — raise an issue</a></div>` : ''}
    ${section('Booking activity', html`<div class="card">${timeline(b.events.map((e) => ({ title: cap(e.event.replace('_', ' ')), time: dt(e.created_at), note: e.detail })))}</div>`)}
    ${section('Cancellation & refunds', html`<p class="muted sm">${b.cancellation_terms}</p>`)}
    <div class="stack mt"><button class="btn btn-secondary btn-block" data-act="receipt">${icon('receipt', 18)} View receipt</button>${['confirmed', 'pending_reconsent'].includes(b.status) ? html`<button class="btn btn-danger-o btn-block" data-act="cancel">Cancel booking</button>` : ''}</div>`;
}

export const routes = [
  ['/c/home', home], ['/c/categories', categories], ['/c/category/:slug', categoryDetail], ['/c/request/new', newRequest],
  ['/c/request/:id/submitted', submitted], ['/c/requests', myRequests], ['/c/request/:id', requestDetail], ['/c/request/:id/quotes', quotes],
  ['/c/quote/:id', quoteApproval], ['/c/booking/:id', booking], ...more,
];
