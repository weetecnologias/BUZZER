// Partner portal: dashboard, leads, quote builder, bookings, job execution, earnings, KYC, hiring, catering/photography.
import { ApiError, ago, api, cap, dateOnly, dt, dtl, esc, go, html, icon, inDays, inr, istInput, meta, openFile, phone, qs, raw, setSession, upload, updateUser, getSession } from '../core.js';
import { avatar, btn, calendarMonth, chat, chip, composer, confirmDialog, emptyState, field, hbars, kv, modal, mountTable, note, opts, pageHeader, progress, rowBtn, section, stars, stat, statusChip, stepper, tabs, timeline, toast, verified, switchRow } from '../ui.js';

const S = (...p) => p.map(String).join('');
const QSTAT = { submitted: ['Checking with agent', 'info'], shared: ['Sent to customer', 'info'], approved: ['Approved', 'success'], rejected: ['Declined', 'danger'], clarification_requested: ['Customer asked a question', 'warn'], revised_pending_consent: ['Awaiting re-approval', 'warn'], not_selected: ['Not selected', 'neutral'], withdrawn: ['Withdrawn', 'neutral'], expired: ['Expired', 'neutral'] };
const BSTAT = { confirmed: ['Confirmed', 'info'], on_the_way: ['On the way', 'info'], in_progress: ['In progress', 'warn'], work_done: ['Awaiting customer', 'warn'], completed: ['Completed', 'success'], cancelled: ['Cancelled', 'neutral'], pending_reconsent: ['Re-approval pending', 'warn'], disputed: ['Disputed', 'danger'] };
const qChip = (s) => chip(...(QSTAT[s] || [cap(s), 'neutral']));
const bChip = (s) => chip(...(BSTAT[s] || [cap(s), 'neutral']));
const budget = (l) => (l.budget_min || l.budget_max ? `${l.budget_min ? inr(l.budget_min) : '—'}–${l.budget_max ? inr(l.budget_max) : '—'}` : '—');
const catIc = (c, size = 40) => html`<span class="cat-ic" style="background:${c.color || c.category_color || '#E6F4D7'};width:${size}px;height:${size}px;font-size:${Math.round(size * 0.5)}px" aria-hidden="true">${c.icon || c.category_icon || '✨'}</span>`;
const minutes = (m) => (m == null ? '' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} hr ago` : `${Math.round(m / 1440)} d ago`);
async function act(el, fn) { const l = el.innerHTML; el.disabled = true; el.innerHTML = '<span class="spin"></span>'; try { return await fn(); } catch (e) { toast(e.message || 'Something went wrong', 'error'); } finally { if (el.isConnected) { el.disabled = false; el.innerHTML = l; } } }

// ---------------------------------------------------------------- dashboard
async function dashboard(ctx) {
  const [d, leads, bookings] = await Promise.all([api('/partner/dashboard'), api('/leads?page_size=4'), api('/bookings?group=upcoming&page_size=3')]);
  const p = d.provider; const pending = d.verification_status !== 'verified';
  return html`${pageHeader(html`Good ${new Date().getHours() < 12 ? 'morning' : 'afternoon'}, ${p.business_name} 👋`, 'Here is how your business is doing.', html`<a class="btn btn-primary" href="#/p/leads">${icon('inbox', 18)} View new leads</a>`)}
    ${pending ? html`<div class="note note-warn mb"><b>${d.verification_status === 'pending' ? 'Verification in review.' : 'Get verified to win more work.'}</b> ${d.missing_docs.length ? 'Upload your ID and address proof so customers see the Verified badge.' : 'We are reviewing your documents (usually within 2 working days).'} <a href="#/p/documents"><b>Open KYC</b></a></div>` : ''}
    <div class="grid g4">
      <div class="stat card-dark" style="grid-column:span 2"><span class="stat-l" style="color:#A9B7AD">Net earnings · last 30 days</span><span class="stat-v" style="font-size:32px">${inr(d.month_earnings)}</span>${d.growth_pct != null ? html`<span class="stat-d ${d.growth_pct < 0 ? 'down' : 'up'}" style="color:${d.growth_pct < 0 ? '#F4A9A0' : 'var(--lime)'}">${d.growth_pct >= 0 ? '↑' : '↓'} ${Math.abs(d.growth_pct)}% vs previous 30 days</span>` : ''}<span class="stat-l" style="color:#A9B7AD">Available to withdraw: <b style="color:#fff">${inr(d.money.available)}</b></span></div>
      ${stat({ label: 'New leads', value: d.counters.new_leads, ic: 'inbox', tone: 'info', href: '#/p/leads' })}${stat({ label: 'Jobs in progress', value: d.counters.in_progress, ic: 'briefcase', tone: 'warn', href: '#/p/bookings' })}
      ${stat({ label: 'Completed jobs', value: d.counters.completed, ic: 'check', href: '#/p/bookings?tab=completed' })}${stat({ label: 'Quotes awaiting reply', value: d.counters.pending_quotes, ic: 'file', href: '#/p/quotes' })}
      ${stat({ label: `Rating (${p.rating_count})`, value: p.rating_avg ? p.rating_avg + ' ★' : '—', ic: 'star', href: '#/p/reviews' })}${stat({ label: 'Response rate', value: p.response_rate + '%', ic: 'trend' })}</div>
    <div class="split mt"><div>${section('New opportunities', leads.items.length ? html`<div class="card" style="padding:4px 16px">${leads.items.map((l) => html`<a class="list-item" href="#/p/lead/${l.request_id}">${catIc(l)}<div class="grow"><b>${l.title}</b> ${l.priority === 'urgent' ? chip('Urgent', 'danger') : ''}<div class="muted sm">${l.reference} · ${l.locality} · ${minutes(l.posted_minutes_ago)}</div></div>${l.quote_status ? qChip(l.quote_status) : chip('Quote needed', 'warn')}${icon('right', 18, 'chev')}</a>`)}</div>` : emptyState({ ic: 'inbox', title: 'No new leads right now', text: 'When your agent invites you to quote, it shows up here.' }), html`<a href="#/p/leads">View all</a>`)}
      ${section('Upcoming bookings', bookings.items.length ? html`<div class="card" style="padding:4px 16px">${bookings.items.map((b) => html`<a class="list-item" href="#/p/booking/${b.id}">${catIc({ icon: b.category_icon })}<div class="grow"><b>${b.title}</b><div class="muted sm">${b.reference} · ${b.scheduled_at ? dt(b.scheduled_at) : 'Time TBC'} · ${b.locality}</div></div>${bChip(b.status)}</a>`)}</div>` : emptyState({ ic: 'calendar', title: 'No upcoming jobs', text: 'Approved quotes become bookings here.' }), html`<a href="#/p/bookings">All bookings</a>`)}</div>
      <div>${section('Quick actions', html`<div class="card" style="padding:0 16px">${[['/p/leads', 'inbox', 'New leads'], ['/p/bookings', 'briefcase', 'My bookings'], ['/p/earnings', 'wallet', 'Earnings & payouts'], ['/p/profile', 'user', 'Update profile'], ['/p/profile#areas', 'pin', 'Service area'], ['/p/support', 'help', 'Support']].map(([h, i, l]) => html`<a class="drawer-link" href="#${h}">${icon(i, 20)} ${l} ${icon('right', 18, 'chev')}</a>`)}</div>`)}
      ${section('Your performance', html`<div class="card">${hbars([{ label: 'Response rate', value: p.response_rate }, { label: 'Rating (out of 5)', value: Math.round((p.rating_avg || 0) * 20) }, { label: 'Jobs completed', value: Math.min(100, p.jobs_completed * 5) }], { fmt: (v) => v })}<p class="muted xs" style="margin:10px 0 0">Reply quickly and keep your quotes itemised to win more jobs.</p></div>`)}</div></div>`;
}

// ---------------------------------------------------------------- business profile
async function profile(ctx) {
  const [p, m] = await Promise.all([api('/partner/profile'), meta()]);
  ctx.submit('#f-profile', async (d) => { await api('/partner/profile', { method: 'PATCH', body: { business_name: d.business_name, description: d.description, phone: d.phone, email: d.email || undefined, address: d.address, locality: d.locality, upi_id: d.upi_id, bank_account: d.bank_account || undefined } }); toast('Business profile saved.'); ctx.reload(); });
  ctx.submit('#f-areas', async (d) => { const r = await api('/partner/areas', { method: 'PUT', body: { areas: [].concat(d.areas || []) } }); toast(`Service area updated (${r.areas.length} localities).`); });
  ctx.after(() => { if (location.hash.includes('#areas')) document.getElementById('areas')?.scrollIntoView(); });
  return html`${pageHeader('Business profile', 'Control your public listing and payout details.')}
    <div class="card"><div class="row">${avatar(p.business_name, p.logo_color, 64)}<div class="grow"><h2 style="font-size:20px">${p.business_name}</h2><div class="row-gap sm" style="gap:8px">${verified(p.verification_status)}<span class="muted">${p.rating_avg ? html`${icon('star', 13)} ${p.rating_avg} (${p.rating_count})` : 'No reviews yet'}</span><span class="muted">${p.jobs_completed} jobs completed</span></div></div></div></div>
    <div class="split mt"><form id="f-profile" class="card" novalidate><h2 class="mb">Business information</h2>
      ${field({ name: 'business_name', label: 'Business name', value: p.business_name, required: true })}${field({ name: 'description', label: 'About your business', type: 'textarea', value: p.description || '', rows: 3, maxlength: 600 })}
      <div class="grid g2">${field({ name: 'phone', label: 'Contact mobile', type: 'tel', value: p.phone ? p.phone.replace('+91', '') : '', prefix: '+91' })}${field({ name: 'email', label: 'Email', type: 'email', value: p.email || '' })}</div>
      ${field({ name: 'address', label: 'Business address', value: p.address || '' })}${field({ name: 'locality', label: 'Main locality', type: 'select', value: p.locality || '', options: [['', 'Select'], ...m.localities] })}
      <h2 class="mb mt">Payout details</h2><div class="grid g2">${field({ name: 'upi_id', label: 'UPI ID', value: p.upi_id || '', placeholder: 'name@bank' })}${field({ name: 'bank_account', label: 'Bank account number', value: '', placeholder: p.bank_masked ? `Saved: ${p.bank_masked}` : 'Account number', hint: 'We only store the last 4 digits.', inputmode: 'numeric' })}</div>
      <div class="form-error" role="alert" hidden></div><button class="btn btn-primary" type="submit">Save changes</button></form>
    <div class="stack"><form id="f-areas" class="card" novalidate><h2 id="areas" class="mb">Service area</h2><p class="muted sm">Customers in these localities can be matched to you.</p><div class="chips-pick">${m.localities.map((l) => html`<label><input type="checkbox" name="areas" value="${l}" ${p.areas.includes(l) ? 'checked' : ''}><span>${l}</span></label>`)}</div><div class="form-error" role="alert" hidden></div><button class="btn btn-secondary mt" type="submit">Update service area</button></form>
      <div class="card"><h2 class="mb">Verification</h2>${kv([['Status', verified(p.verification_status)], ['Source', cap(p.source)], ['Member since', dateOnly(p.created_at)]])}<a class="btn btn-ghost btn-block mt" href="#/p/documents">Documents & KYC</a></div></div></div>`;
}

// ---------------------------------------------------------------- services
async function services(ctx) {
  const [p, cats] = await Promise.all([api('/partner/profile'), api('/categories')]);
  const catOpts = [['', 'Select category'], ...cats.items.filter((c) => c.is_active).map((c) => [c.id, c.name])];
  const form = (s = {}) => S(field({ name: 'category_id', label: 'Category', type: 'select', value: s.category_id || '', options: catOpts, required: true }), field({ name: 'name', label: 'Service name', value: s.name || '', required: true, placeholder: 'e.g. AC service & gas refill' }),
    html`<div class="grid g2">${field({ name: 'base_price', label: 'Starting price (₹)', type: 'number', value: s.base_price ?? '', min: 0, required: true })}${field({ name: 'unit', label: 'Per', value: s.unit || 'per job' })}</div>`, field({ name: 'description', label: 'Description', type: 'textarea', value: s.description || '', rows: 2 }));
  ctx.on('click', '[data-act=add]', () => modal({ title: 'Add service', body: form(), onSubmit: async (d) => { await api('/partner/services', { method: 'POST', body: d }); toast('Service added.'); ctx.reload(); } }));
  ctx.on('click', '[data-act=edit]', (e, b) => { const s = p.services.find((x) => x.id === b.dataset.id); modal({ title: 'Edit service', body: form(s), onSubmit: async (d) => { await api('/partner/services/' + s.id, { method: 'PATCH', body: { ...d, active: s.active } }); toast('Service updated.'); ctx.reload(); } }); });
  ctx.on('change', '[data-toggle]', async (e, el) => { const s = p.services.find((x) => x.id === el.dataset.toggle); try { await api('/partner/services/' + s.id, { method: 'PATCH', body: { name: s.name, base_price: s.base_price, unit: s.unit, description: s.description, active: el.checked } }); toast(el.checked ? 'Service is live.' : 'Service paused.'); } catch (err) { el.checked = !el.checked; toast(err.message, 'error'); } });
  ctx.on('click', '[data-act=del]', async (e, b) => { const r = await confirmDialog({ title: 'Remove this service?', message: 'It will no longer appear in your catalogue.', confirmLabel: 'Remove', danger: true }); if (r) { await api('/partner/services/' + b.dataset.id, { method: 'DELETE' }); toast('Service removed.'); ctx.reload(); } });
  return html`${pageHeader('Services & pricing', 'Your service catalogue helps agents match the right leads to you.', html`<button class="btn btn-primary" data-act="add">${icon('plus', 18)} Add service</button>`)}
    ${p.services.length ? html`<div class="table-wrap"><table class="table"><thead><tr><th>Service</th><th>Category</th><th class="num">Starting price</th><th>Live</th><th></th></tr></thead><tbody>${p.services.map((s) => html`<tr><td data-label="Service"><b>${s.name}</b><span class="sub">${s.description || ''}</span></td><td data-label="Category">${s.icon} ${s.category_name}</td><td class="num" data-label="Price">${inr(s.base_price)} <span class="muted xs">${s.unit}</span></td><td data-label="Live"><label class="switch-row" style="padding:0;border:0"><span class="visually-hidden">Live</span><input type="checkbox" role="switch" data-toggle="${s.id}" ${s.active ? 'checked' : ''}><i></i></label></td><td class="act"><button class="btn btn-ghost btn-sm" data-act="edit" data-id="${s.id}">Edit</button> <button class="icon-btn" data-act="del" data-id="${s.id}" aria-label="Remove ${s.name}">${icon('trash', 18)}</button></td></tr>`)}</tbody></table></div>`
      : emptyState({ ic: 'tool', title: 'No services yet', text: 'Add what you offer and your starting prices.', action: html`<button class="btn btn-primary" data-act="add">Add service</button>` })}`;
}

// ---------------------------------------------------------------- availability calendar
async function calendar(ctx) {
  let ym = ctx.query.m || new Date().toISOString().slice(0, 7);
  const a = await api('/partner/availability?month=' + ym);
  const marks = {}; a.bookings.forEach((b) => { const k = (b.scheduled_at || '').slice(0, 10); (marks[k] = marks[k] || []).push(b.title); });
  const blocked = Object.fromEntries(a.blocked.map((x) => [x.date, x.reason || 'Blocked']));
  const closed = a.hours.filter((h) => !h.active).map((h) => h.weekday);
  const shift = (n) => { const [y, m] = ym.split('-').map(Number); const d = new Date(Date.UTC(y, m - 1 + n, 1)); go('/p/calendar?m=' + d.toISOString().slice(0, 7)); };
  ctx.on('click', '[data-act=cal-prev]', () => shift(-1)); ctx.on('click', '[data-act=cal-next]', () => shift(1));
  ctx.on('click', '[data-date]', (e, b) => {
    const d = b.dataset.date;
    if (blocked[d]) { confirmDialog({ title: 'Unblock ' + d + '?', message: 'You will be available for bookings again.', confirmLabel: 'Unblock' }).then(async (r) => { if (r) { await api('/partner/availability/blocked/' + d, { method: 'DELETE' }); toast('Date unblocked.'); ctx.reload(); } }); return; }
    modal({ title: 'Block ' + d, submitLabel: 'Block date', body: S(marks[d] ? note(`${marks[d].length} booking(s) already on this day: ${marks[d].join(', ')}`, 'warn') : '', field({ name: 'reason', label: 'Reason (optional)', placeholder: 'e.g. Family function' })), onSubmit: async (x) => { await api('/partner/availability/blocked', { method: 'POST', body: { date: d, reason: x.reason } }); toast('Date blocked.'); ctx.reload(); } });
  });
  ctx.submit('#f-hours', async (d) => {
    const hours = a.hours.map((h) => ({ weekday: h.weekday, start_time: d['s' + h.weekday], end_time: d['e' + h.weekday], active: !!d['a' + h.weekday] }));
    await api('/partner/availability', { method: 'PUT', body: { hours, max_jobs_per_day: d.max_jobs_per_day } }); toast('Availability saved.'); ctx.reload();
  });
  const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  return html`${pageHeader('Availability calendar', 'Manage working hours, blocked dates and daily capacity. Tap a date to block or unblock it.')}
    <div class="split"><div>${calendarMonth(ym, { marks, blocked, closedWeekdays: closed })}<p class="muted xs mt">Dark chips are confirmed bookings. Red days are blocked — agents won't invite you for them.</p></div>
    <form id="f-hours" class="card" novalidate><h2 class="mb">Working hours</h2>${a.hours.map((h) => html`<div class="row" style="padding:6px 0"><label class="check grow" style="font-weight:600!important"><input type="checkbox" name="a${h.weekday}" ${h.active ? 'checked' : ''}><span>${days[h.weekday]}</span></label><input type="time" name="s${h.weekday}" value="${h.start_time}" style="width:110px;min-height:38px" aria-label="${days[h.weekday]} start"><span>–</span><input type="time" name="e${h.weekday}" value="${h.end_time}" style="width:110px;min-height:38px" aria-label="${days[h.weekday]} end"></div>`)}
      <hr class="divider">${field({ name: 'max_jobs_per_day', label: 'Maximum jobs per day', type: 'number', value: a.max_jobs_per_day, min: 1, max: 50, hint: 'Prevents double booking.' })}<div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block" type="submit">Save availability</button></form></div>`;
}

// ---------------------------------------------------------------- leads
async function leads(ctx) {
  const tab = ctx.query.tab || 'all';
  const counts = await api('/leads?page_size=1');
  ctx.after(() => mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/leads' + qs({ ...p, tab })), search: 'Search leads by title, ref or area', rowHref: (l) => '#/p/lead/' + l.request_id,
    columns: [
      { label: 'Request', render: (l) => html`<b>${l.title}</b> ${l.priority === 'urgent' ? chip('Urgent', 'danger') : ''}<span class="sub">${l.reference}</span>` },
      { label: 'Category', render: (l) => html`${l.category_icon} ${l.category_name}` }, { label: 'Area', render: (l) => html`${l.locality}${l.in_service_area ? '' : html`<span class="sub">Outside your area</span>`}` },
      { label: 'Preferred', render: (l) => (l.preferred_at ? dt(l.preferred_at) : 'Flexible') }, { label: 'Budget', render: budget }, { label: 'Posted', render: (l) => minutes(l.posted_minutes_ago) },
      { label: 'Status', render: (l) => (l.quote_status ? qChip(l.quote_status) : l.invite_status === 'declined' ? chip('Declined', 'neutral') : chip('Quote needed', 'warn')) },
    ], empty: { ic: 'inbox', title: tab === 'responded' ? 'No responses yet' : 'No leads right now', text: 'Your agent invites you when a request matches your services and area.' },
  }));
  return html`${pageHeader('Lead inbox', 'Respond quickly to relevant BUZZER enquiries.')}${tabs([['all', 'All', counts.counts.all], ['nearby', 'Nearby'], ['urgent', 'Urgent', counts.counts.urgent], ['responded', 'Responded']], tab, '#/p/leads')}<div id="tbl" class="mt"></div>`;
}

async function leadDetail(ctx) {
  const l = await api('/leads/' + ctx.params.id);
  ctx.on('click', '[data-act=decline]', () => modal({ title: 'Decline this lead', submitLabel: 'Decline', danger: true, body: field({ name: 'reason', label: 'Reason', type: 'select', options: ['Not available on that date', 'Outside my service area', 'Not my kind of work', 'Too busy right now', 'Budget too low'] }).toString(), onSubmit: async (d) => { await api(`/leads/${l.request_id}/decline`, { method: 'POST', body: d }); toast('Lead declined.'); go('/p/leads'); } }));
  ctx.on('click', '[data-act=open-file]', (e, b) => openFile(b.dataset.id));
  return html`<a href="#/p/leads" class="muted sm">← All leads</a>${pageHeader(html`${l.category.icon} ${l.title}`, `${l.reference} · ${l.locality}`, l.can_quote ? html`<a class="btn btn-primary" href="#/p/quote/${l.request_id}">${icon('file', 18)} Send quote</a><button class="btn btn-ghost" data-act="decline">Decline</button>` : '')}
    <div class="split"><div class="card"><h2 class="mb">What the customer needs</h2><p>${l.description}</p>${kv([...l.details.map((d) => [d.label, d.value]), ['Preferred time', l.preferred_at ? dtl(l.preferred_at) : 'Flexible'], ['Area', l.locality], ['Budget', budget(l)], ['Priority', cap(l.priority)]])}
      ${l.brief ? html`<hr class="divider"><h3 class="mb">Event brief</h3>${kv([['Event', l.brief.event_type], ['Date', l.brief.event_date], ['Guests', l.brief.guest_count], ['Services', l.brief.services], ['Coverage', l.brief.coverage_hours ? l.brief.coverage_hours + ' hrs · ' + (l.brief.coverage_style || '') : null], ['Deliverables', l.brief.deliverables], ['Notes', l.brief.notes]])}` : ''}
      ${l.attachments.length ? html`<div class="row-gap mt">${l.attachments.map((a) => html`<button class="attach" data-act="open-file" data-id="${a.id}">${icon('paperclip', 14)} ${a.filename}</button>`)}</div>` : ''}</div>
    <div class="stack"><div class="card"><h3 class="mb">Your quote</h3>${l.quote ? html`<div class="row between"><b class="price-big" style="font-size:22px">${inr(l.quote.total)}</b>${qChip(l.quote.status)}</div><ul class="sm">${l.quote.items.map((i) => html`<li>${i.description} · ${inr(i.amount)}</li>`)}</ul>${['submitted', 'shared', 'clarification_requested', 'approved', 'revised_pending_consent'].includes(l.quote.status) ? html`<a class="btn btn-secondary btn-block" href="#/p/quote/${l.request_id}">${l.quote.status === 'approved' ? 'Revise (needs re-approval)' : 'Edit quote'}</a>` : ''}` : html`<p class="muted">You haven't quoted yet. Itemised quotes win more jobs.</p>`}</div>
      ${note('Customer name and exact address are shared only after the customer approves your quote.', 'info')}</div></div>`;
}

// ---------------------------------------------------------------- quote builder
async function quoteBuilder(ctx) {
  const l = await api('/leads/' + ctx.params.id);
  const ex = l.quote && ['submitted', 'shared', 'clarification_requested', 'approved', 'revised_pending_consent'].includes(l.quote.status) ? await api('/quotes/' + l.quote.id) : null;
  if (!ex && !l.can_quote) return html`${emptyState({ ic: 'file', title: 'This request is not collecting quotes', text: 'It may have closed or already have a booking.', action: html`<a class="btn btn-primary" href="#/p/leads">Back to leads</a>` })}`;
  const items = ex ? ex.items : [{ description: '', qty: 1, unit_price: '' }];
  const row = (i = {}) => `<div class="item-row" style="display:grid;grid-template-columns:1fr 70px 110px 90px 36px;gap:8px;align-items:center;margin-bottom:8px"><input name="d" placeholder="Description (e.g. Labour)" value="${esc(i.description || '')}" aria-label="Item description"><input name="q" type="number" min="1" value="${i.qty || 1}" aria-label="Quantity"><input name="p" type="number" min="0" placeholder="₹ each" value="${i.unit_price ?? ''}" aria-label="Unit price"><b class="amt right">${esc(inr((i.qty || 1) * (i.unit_price || 0)))}</b><button type="button" class="icon-btn" data-rm aria-label="Remove item">✕</button></div>`;
  const recalc = () => { let t = 0; ctx.view.querySelectorAll('.item-row').forEach((r) => { const a = (Number(r.querySelector('[name=q]').value) || 0) * (Number(r.querySelector('[name=p]').value) || 0); t += a; r.querySelector('.amt').textContent = inr(a); }); ctx.view.querySelector('#total').textContent = inr(t); ctx.view.querySelector('#dep').textContent = inr(Math.round(t * (Number(ctx.view.querySelector('[name=deposit_pct]').value) || 0) / 100)); };
  ctx.on('click', '[data-add]', () => { ctx.view.querySelector('#items').insertAdjacentHTML('beforeend', row()); });
  ctx.on('click', '[data-rm]', (e, b) => { if (ctx.view.querySelectorAll('.item-row').length > 1) { b.closest('.item-row').remove(); recalc(); } });
  ctx.on('input', '#f-quote', recalc);
  ctx.after(recalc);
  ctx.submit('#f-quote', async (d) => {
    const its = [...ctx.view.querySelectorAll('.item-row')].map((r) => ({ description: r.querySelector('[name=d]').value, qty: r.querySelector('[name=q]').value, unit_price: r.querySelector('[name=p]').value }));
    const body = { scope: d.scope, exclusions: d.exclusions, terms: d.terms, warranty_days: d.warranty_days || 0, deposit_pct: d.deposit_pct || 0, valid_until: d.valid_until, est_start_at: d.est_start_at || undefined, est_duration: d.est_duration, items: its };
    if (ex) await api('/quotes/' + ex.id, { method: 'PATCH', body }); else await api(`/requests/${l.request_id}/quotes`, { method: 'POST', body });
    toast(ex ? 'Quote updated.' : 'Quote sent to your agent for checking.'); go('/p/quotes');
  });
  return html`<a href="#/p/lead/${l.request_id}" class="muted sm">← Lead details</a>${pageHeader(ex ? 'Edit quote' : 'Quote builder', `${l.title} · ${l.reference} · ${l.locality}`)}
    ${ex && ex.status === 'approved' ? note('This quote is already approved. Changing it puts the booking on hold until the customer approves again.', 'warn') : ''}
    <form id="f-quote" class="card" novalidate><h2 class="mb">Itemised quote</h2><div class="muted xs row" style="display:grid;grid-template-columns:1fr 70px 110px 90px 36px;gap:8px;padding:0 0 4px"><span>Item</span><span>Qty</span><span>Unit price</span><span class="right">Amount</span><span></span></div>
      <div id="items">${raw(items.map(row).join(''))}</div><div class="field-error" data-error-for="items" role="alert"></div><button type="button" class="btn btn-ghost btn-sm" data-add>${icon('plus', 16)} Add item</button>
      <div class="row between mt" style="font-size:18px"><b>Total</b><b id="total" class="price-big" style="font-size:24px"></b></div><hr class="divider">
      ${field({ name: 'scope', label: 'Scope of work', type: 'textarea', rows: 3, required: true, value: ex ? ex.scope : '', placeholder: 'What is included, how the job will be done' })}
      <div class="grid g2">${field({ name: 'exclusions', label: 'Not included', value: ex ? ex.exclusions || '' : '', placeholder: 'e.g. Spare parts beyond list' })}${field({ name: 'terms', label: 'Terms', value: ex ? ex.terms || '' : '' })}</div>
      <div class="grid g3">${field({ name: 'warranty_days', label: 'Warranty (days)', type: 'number', min: 0, value: ex ? ex.warranty_days : 30 })}${field({ name: 'deposit_pct', label: 'Deposit (%)', type: 'number', min: 0, max: 100, value: ex ? ex.deposit_pct : 20, hint: html`Deposit: <b id="dep"></b>` })}${field({ name: 'est_duration', label: 'Estimated duration', value: ex ? ex.est_duration || '' : '2-3 hours' })}</div>
      <div class="grid g2">${field({ name: 'valid_until', label: 'Quote valid until', type: 'datetime-local', required: true, value: ex ? istInput(ex.valid_until) : inDays(5, 18) })}${field({ name: 'est_start_at', label: 'Earliest start', type: 'datetime-local', value: ex && ex.est_start_at ? istInput(ex.est_start_at) : inDays(1, 10) })}</div>
      <div class="form-error" role="alert" hidden></div><div class="row-gap"><button class="btn btn-primary btn-lg" type="submit">${ex ? 'Save changes' : 'Submit quote'}</button><a class="btn btn-ghost" href="#/p/lead/${l.request_id}">Cancel</a></div></form>`;
}

async function myQuotes(ctx) {
  ctx.on('click', '[data-row-act]', () => {});
  ctx.after(() => mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/quotes' + qs(p)), search: false, filters: [{ key: 'status', label: 'All statuses', options: Object.entries(QSTAT).map(([k, v]) => [k, v[0]]) }],
    rowHref: (q) => '#/p/lead/' + q.request_id,
    columns: [{ label: 'Request', render: (q) => html`<b>${q.title}</b><span class="sub">${q.reference}</span>` }, { label: 'My quote', cls: 'num', render: (q) => inr(q.total) }, { label: 'Status', render: (q) => qChip(q.status) }, { label: 'Valid until', render: (q) => dt(q.valid_until) }, { label: 'Updated', render: (q) => ago(q.updated_at) },
      { label: '', cls: 'act', render: (q) => (['submitted', 'shared', 'clarification_requested'].includes(q.status) ? html`<a class="btn btn-ghost btn-sm" href="#/p/quote/${q.request_id}">Edit</a>` : '') }],
    empty: { ic: 'file', title: 'No quotes yet', text: 'Quotes you send appear here with their status.' },
  }));
  return html`${pageHeader('My quotes', 'Track every quote you have sent.')}<div id="tbl"></div>`;
}

// ---------------------------------------------------------------- bookings
async function bookings(ctx) {
  const tab = ctx.query.tab || 'upcoming';
  ctx.after(() => mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/bookings' + qs({ ...p, group: tab })), search: 'Search by booking or customer', rowHref: (b) => '#/p/booking/' + b.id,
    columns: [{ label: 'Booking', render: (b) => html`<b>${b.title}</b><span class="sub">${b.reference}</span>` }, { label: 'Customer', render: (b) => b.customer_name }, { label: 'When', render: (b) => (b.scheduled_at ? dt(b.scheduled_at) : 'TBC') }, { label: 'Area', render: (b) => b.locality },
      { label: 'Amount', cls: 'num', render: (b) => inr(b.amount) }, { label: 'Payment', render: (b) => chip(b.payment_status === 'paid' ? 'Paid' : b.payment_status === 'deposit_paid' ? 'Deposit paid' : 'Unpaid', b.payment_status === 'paid' ? 'success' : 'neutral') }, { label: 'Status', render: (b) => bChip(b.status) }],
    empty: { ic: 'briefcase', title: `No ${tab} bookings`, text: 'When a customer approves your quote it becomes a booking.' },
  }));
  return html`${pageHeader('Bookings & job details', 'See approved scope and confirmed schedule.')}${tabs([['upcoming', 'Upcoming'], ['completed', 'Completed'], ['cancelled', 'Cancelled']], tab, '#/p/bookings')}<div id="tbl" class="mt"></div>`;
}

const NEXT = { confirmed: [['on_the_way', 'On the way', 'truck'], ['in_progress', 'Start job', 'tool']], on_the_way: [['in_progress', 'Start job', 'tool']], in_progress: [['work_done', 'Mark work done', 'check']] };
async function bookingDetail(ctx) {
  const b = await api('/bookings/' + ctx.params.id);
  const move = async (status, el) => { if (status === 'work_done') { go('/p/job/' + b.id); return; } await act(el, async () => { await api(`/bookings/${b.id}/status`, { method: 'PATCH', body: { status } }); toast('Status updated.'); ctx.reload(); }); };
  ctx.on('click', '[data-move]', (e, el) => move(el.dataset.move, el));
  return html`<a href="#/p/bookings" class="muted sm">← Bookings</a>${pageHeader(b.title, `${b.reference} · request ${b.request_reference}`, html`${bChip(b.status)}`)}
    <div class="split"><div class="stack"><div class="card"><h2 class="mb">Job details</h2>${kv([['Customer', b.customer_name], ['Phone', b.customer_phone ? html`<a href="tel:${b.customer_phone}">${phone(b.customer_phone)}</a>` : '—'], ['Address', b.address ? `${b.address.line1}, ${b.address.locality}${b.address.landmark ? ' (' + b.address.landmark + ')' : ''}` : b.locality], ['Scheduled', b.scheduled_at ? dtl(b.scheduled_at) : 'To be confirmed'], ['Scope', b.quote.scope]])}
        <hr class="divider">${b.quote.items.map((i) => html`<div class="row between sm" style="padding:3px 0"><span>${i.description}</span><b>${inr(i.amount)}</b></div>`)}<div class="row between mt"><b>Total</b><b class="price-big" style="font-size:20px">${inr(b.amount)}</b></div></div>
      <div class="card"><h2 class="mb">Activity</h2>${timeline(b.events.map((e) => ({ title: cap(e.event.replace('_', ' ')), time: dt(e.created_at), note: e.detail })))}</div></div>
    <div class="stack"><div class="card"><h2 class="mb">Payment</h2>${kv([['Customer paid', inr(b.paid_amount)], ['Balance', inr(b.amount - b.paid_amount)], ['Status', b.payment_status === 'paid' ? 'Paid in full' : b.payment_status === 'deposit_paid' ? 'Deposit paid' : 'Unpaid']])}<p class="muted xs">Payouts are released after the customer confirms completion, net of BUZZER commission.</p></div>
      ${NEXT[b.status] ? html`<div class="card card-soft"><h3 class="mb">Next step</h3><div class="stack">${NEXT[b.status].map(([s, l, i], k) => html`<button class="btn ${k === NEXT[b.status].length - 1 ? 'btn-primary' : 'btn-secondary'} btn-block" data-move="${s}">${icon(i, 18)} ${l}</button>`)}</div></div>` : b.status === 'work_done' ? note('Waiting for the customer to confirm completion.', 'info') : b.status === 'pending_reconsent' ? note('The customer must re-approve your revised quote before work continues.', 'warn') : ''}
      <a class="btn btn-ghost btn-block" href="#/p/job/${b.id}">Open job execution</a><a class="btn btn-ghost btn-block" href="#/p/support?booking=${b.id}">Report an issue</a></div></div>`;
}

async function jobExecution(ctx) {
  const b = await api('/bookings/' + ctx.params.id);
  const steps = ['Confirmed', 'On the way', 'In progress', 'Work done', 'Completed']; const idx = { confirmed: 0, on_the_way: 1, in_progress: 2, work_done: 3, completed: 4 }[b.status] ?? 0;
  let evidence = null;
  ctx.on('change', '#evidence', async (e, el) => { const f = el.files[0]; if (!f) return; try { evidence = await upload(f, { request_id: b.request_id }); ctx.view.querySelector('#ev-name').textContent = '📎 ' + evidence.filename; } catch (err) { toast(err.message, 'error'); } });
  ctx.on('click', '[data-move]', (e, el) => act(el, async () => { await api(`/bookings/${b.id}/status`, { method: 'PATCH', body: { status: el.dataset.move } }); toast('Status updated.'); ctx.reload(); }));
  ctx.on('click', '[data-act=open-file]', (e, el) => openFile(el.dataset.id));
  ctx.submit('#f-done', async (d) => { await api(`/bookings/${b.id}/status`, { method: 'PATCH', body: { status: 'work_done', note: d.note, evidence_attachment_id: evidence ? evidence.id : undefined } }); toast('Marked as done. The customer has been asked to confirm.'); ctx.reload(); });
  return html`<a href="#/p/booking/${b.id}" class="muted sm">← Booking details</a>${pageHeader('Job execution', `${b.title} · ${b.reference}`, bChip(b.status))}
    <div class="card">${stepper(steps, Math.min(idx, 4))}<div class="split"><div>${kv([['Customer', b.customer_name], ['Where', b.address ? `${b.address.line1}, ${b.address.locality}` : b.locality], ['Scheduled', b.scheduled_at ? dtl(b.scheduled_at) : 'TBC'], ['Value', inr(b.amount)]])}</div>
      <div>${b.status === 'confirmed' ? html`<div class="stack"><button class="btn btn-secondary btn-block" data-move="on_the_way">${icon('truck', 18)} I'm on the way</button><button class="btn btn-primary btn-block" data-move="in_progress">${icon('tool', 18)} Start job</button></div>`
        : b.status === 'on_the_way' ? html`<button class="btn btn-primary btn-block btn-lg" data-move="in_progress">${icon('tool', 18)} Start job</button>`
        : b.status === 'in_progress' ? html`<form id="f-done" novalidate><h3 class="mb">Finish the job</h3>${field({ name: 'note', label: 'What was done?', type: 'textarea', rows: 3, required: true, placeholder: 'e.g. Replaced MCB and tested all points.' })}<div class="field"><label>Completion evidence (photo, optional)</label><label class="btn btn-ghost btn-block" style="cursor:pointer">${icon('camera', 18)} Add photo<input id="evidence" type="file" accept="image/*" hidden></label><div id="ev-name" class="sm muted mt"></div></div><div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Mark work done</button></form>`
        : b.status === 'work_done' ? html`${note('Waiting for the customer to confirm completion. Your payout is released after that.', 'info')}<p class="sm mt"><b>Your note:</b> ${b.completion_note}</p>${b.evidence_attachment_id ? html`<button class="attach" data-act="open-file" data-id="${b.evidence_attachment_id}">${icon('paperclip', 14)} View evidence</button>` : ''}`
        : b.status === 'completed' ? note(html`Job completed ${dt(b.completed_at)}. Net earnings are in your <a href="#/p/earnings">earnings</a>.`, 'success') : note(`This booking is ${cap(b.status)}.`, 'warn')}</div></div></div>
    <div class="card mt"><h2 class="mb">Activity</h2>${timeline(b.events.map((e) => ({ title: cap(e.event.replace('_', ' ')), time: dt(e.created_at), note: e.detail })))}</div>`;
}

// ---------------------------------------------------------------- messages
async function messages(ctx) {
  const list = await api('/conversations');
  const cur = ctx.query.c ? list.items.find((c) => c.id === ctx.query.c) : list.items[0];
  const me = ctx.user.id; let pending = null;
  if (cur) {
    const load = async () => { const r = await api(`/conversations/${cur.id}/messages`); const box = ctx.view.querySelector('#chat-box'); if (!box) return; const near = box.scrollHeight - box.scrollTop - box.clientHeight < 140; box.innerHTML = chat(r.items, me).toString(); if (near) box.scrollTop = box.scrollHeight; };
    ctx.after(async () => { await load(); const b = ctx.view.querySelector('#chat-box'); b && (b.scrollTop = b.scrollHeight); }); ctx.every(() => load().catch(() => {}), 6000);
    ctx.submit('[data-chat-form]', async (d, f) => { if (!d.body.trim() && !pending) return; await api(`/conversations/${cur.id}/messages`, { method: 'POST', body: { body: d.body.trim(), attachment_id: pending ? pending.id : undefined, request_id: cur.request_id || undefined } }); pending = null; f.reset(); await load(); });
    ctx.on('change', '[data-chat-file]', async (e, el) => { if (el.files[0]) { try { pending = await upload(el.files[0]); toast('Attachment ready — press send.'); } catch (err) { toast(err.message, 'error'); } el.value = ''; } });
    ctx.on('click', '[data-act=open-file]', (e, b) => openFile(b.dataset.id));
  }
  return html`${pageHeader('Messages', 'Coordinate with your BUZZER agent. Customer details are shared through your agent.')}
    ${list.items.length ? html`<div class="split" style="grid-template-columns:minmax(220px,.8fr) 2fr"><div class="card" style="padding:4px 12px">${list.items.map((c) => html`<a class="list-item" href="#/p/messages?c=${c.id}" style="${cur && c.id === cur.id ? 'background:var(--soft);margin:0 -12px;padding:12px' : ''}">${avatar(c.title, null, 38)}<div class="grow" style="min-width:0"><b>${c.title}</b><div class="muted xs truncate">${c.request ? c.request.reference + ' · ' : ''}${c.last ? c.last.body : 'No messages yet'}</div></div></a>`)}</div>
      <div class="card"><div class="row between mb"><b>${cur.title}</b><span class="muted sm">${cur.request ? cur.request.title : cur.subtitle}</span></div><div id="chat-box" style="max-height:50vh;overflow-y:auto"></div>${composer('Message your agent…')}</div></div>` : emptyState({ ic: 'chat', title: 'No conversations yet', text: 'When an agent invites you to a lead, you can chat with them here.' })}`;
}

// ---------------------------------------------------------------- earnings
async function earnings(ctx) {
  const first = await api('/partner/earnings?page_size=1');
  const m = first.money;
  ctx.on('click', '[data-act=payout]', () => {
    if (first.verification_status !== 'verified') { toast('Payouts unlock once your business is verified.', 'error'); return; }
    modal({ title: 'Request payout', submitLabel: 'Request payout', body: S(note(`Available: ${inr(m.available)}. Payouts are reviewed by BUZZER finance and sent to your saved account.`, 'info'), field({ name: 'amount', label: 'Amount (₹)', type: 'number', min: 100, max: m.available, value: m.available, required: true, inputmode: 'numeric' })), onSubmit: async (d) => { await api('/partner/payouts', { method: 'POST', body: { amount: d.amount } }); toast('Payout requested.'); ctx.reload(); } });
  });
  ctx.after(() => mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/partner/earnings' + qs(p)), search: false, pageSize: 10,
    columns: [{ label: 'Completed', render: (e) => dt(e.created_at) }, { label: 'Booking', render: (e) => html`<b>${e.title}</b><span class="sub">${e.booking}</span>` }, { label: 'Gross', cls: 'num', render: (e) => inr(e.gross) }, { label: 'Commission', cls: 'num', render: (e) => html`−${inr(e.commission)} <span class="muted xs">(${e.rate}%)</span>` }, { label: 'Net', cls: 'num', render: (e) => html`<b>${inr(e.net)}</b>` }],
    empty: { ic: 'wallet', title: 'No earnings yet', text: 'Completed jobs appear here with commission and net payout.' },
  }));
  return html`${pageHeader('Earnings & payouts', 'Reconcile gross value, commission and settlement.', html`<button class="btn btn-primary" data-act="payout">${icon('wallet', 18)} Request payout</button>`)}
    <div class="grid g4"><div class="stat card-dark"><span class="stat-l" style="color:#A9B7AD">Net earnings</span><span class="stat-v">${inr(m.net_earned)}</span></div>${stat({ label: 'Available to withdraw', value: inr(m.available), ic: 'wallet' })}${stat({ label: `Commission paid (${m.commission_rate}% avg)`, value: inr(m.commission), ic: 'receipt' })}${stat({ label: 'Paid out so far', value: inr(m.paid_out), ic: 'check', tone: 'info' })}</div>
    ${m.pending_payout ? html`<div class="note note-info mt">${inr(m.pending_payout)} payout is awaiting finance approval.</div>` : ''}
    <div class="split mt"><div>${section('Completed jobs', html`<div id="tbl"></div>`)}</div><div>${section('Payout history', first.payouts.length ? html`<div class="card" style="padding:0 16px">${first.payouts.map((y) => html`<div class="list-item"><div class="grow"><b>${inr(y.amount)}</b><div class="muted sm">${dt(y.requested_at)}${y.reference ? ' · ' + y.reference : ''}</div></div>${chip(cap(y.status), y.status === 'paid' ? 'success' : y.status === 'rejected' ? 'danger' : 'warn')}</div>`)}</div>` : html`<p class="muted">No payouts yet.</p>`)}</div></div>`;
}

// ---------------------------------------------------------------- reviews
async function reviews(ctx) {
  const r = await api('/partner/reviews');
  const total = r.count || 1;
  ctx.on('click', '[data-reply]', (e, b) => modal({ title: 'Reply to review', submitLabel: 'Post reply', body: field({ name: 'reply', label: 'Your reply (public)', type: 'textarea', required: true, rows: 3, placeholder: 'Thank you for your feedback…' }).toString(), onSubmit: async (d) => { await api(`/partner/reviews/${b.dataset.reply}/reply`, { method: 'POST', body: d }); toast('Reply posted.'); ctx.reload(); } }));
  return html`${pageHeader('Ratings & reviews', 'Monitor customer feedback and service quality.')}
    <div class="split"><div class="card center"><div style="font-size:54px;font-weight:800;letter-spacing:-.03em">${r.average || '—'}</div>${stars(r.average, 22)}<div class="muted">${r.count} review${r.count === 1 ? '' : 's'}</div></div>
    <div class="card">${hbars([5, 4, 3, 2, 1].map((n) => ({ label: `${n} ★`, value: Math.round((r.histogram[n] / total) * 100), color: n >= 4 ? 'var(--green)' : n === 3 ? '#F2B01E' : 'var(--danger)' })), { fmt: (v) => v + '%' })}</div></div>
    ${section('Customer reviews', r.items.length ? html`<div class="stack">${r.items.map((v) => html`<div class="card"><div class="row between"><div class="row">${avatar(v.reviewer, null, 38)}<div><b>${v.reviewer.split(' ')[0]}</b><div class="muted xs">${ago(v.created_at)}</div></div></div>${stars(v.rating)}</div><p style="margin:8px 0 0">${v.comment || ''}</p>${v.reply ? html`<div class="note note-info mt"><b>Your reply:</b> ${v.reply}</div>` : html`<button class="btn btn-ghost btn-sm mt" data-reply="${v.id}">Reply</button>`}</div>`)}</div>` : emptyState({ ic: 'star', title: 'No reviews yet', text: 'Complete jobs well and customers will rate you here.' }))}`;
}

// ---------------------------------------------------------------- documents & KYC
async function documents(ctx) {
  const r = await api('/partner/documents');
  const docChip = (d) => (d ? chip(cap(d.status), { verified: 'success', pending: 'warn', rejected: 'danger', expired: 'neutral' }[d.status]) : chip('Not uploaded', 'neutral'));
  ctx.on('click', '[data-up]', (e, b) => {
    const it = r.items.find((x) => x.doc_type === b.dataset.up); let att = null;
    modal({ title: 'Upload ' + it.label, submitLabel: 'Submit document', body: S(note('Files are stored privately and shown only to BUZZER verification staff.', 'info'), '<div class="field mt"><label>File (photo or PDF, max 5 MB) <span class="req">*</span></label><input type="file" id="docfile" accept="image/*,.pdf"><div class="field-error" data-error-for="attachment_id" role="alert"></div></div>', field({ name: 'doc_number', label: 'Document number (optional)', placeholder: 'Only the last 4 characters are stored' }), field({ name: 'expires_at', label: 'Expiry date (if any)', type: 'date' })),
      onSubmit: async (d) => { const f = document.getElementById('docfile').files[0]; if (!f) throw new ApiError(422, 'validation', 'Choose a file to upload.', { attachment_id: 'Choose a file to upload.' }); att = await upload(f); await api('/partner/documents', { method: 'POST', body: { doc_type: it.doc_type, doc_number: d.doc_number, expires_at: d.expires_at, attachment_id: att.id } }); toast('Document uploaded for review.'); ctx.reload(); } });
  });
  ctx.on('click', '[data-act=open-file]', (e, b) => openFile(b.dataset.id));
  ctx.on('click', '[data-act=submit]', (e, b) => act(b, async () => { await api('/partner/verification/submit', { method: 'POST' }); const s = getSession('partner'); toast('Submitted. We will review within 2 working days.'); ctx.reload(); }));
  const ready = r.items.filter((i) => i.required).every((i) => i.document && ['pending', 'verified'].includes(i.document.status));
  return html`${pageHeader('Documents & verification', 'Track KYC and service-specific document status.')}
    ${r.verification_status === 'verified' ? note('Your business is verified. Customers see the Verified badge next to your name.', 'success') : r.verification_status === 'pending' ? note('Your documents are in review. We will notify you as soon as there is an update.', 'info') : r.verification_status === 'rejected' ? note('Verification was not approved. Fix the flagged documents and submit again.', 'danger') : note('Upload your ID proof and business address proof, then submit for verification.', 'warn')}
    <div class="card mt">${r.items.map((i) => html`<div class="doc-row"><span class="stat-ic">${icon('file', 18)}</span><div class="grow"><b>${i.label}</b> ${i.required ? chip('Required', 'neutral') : ''}<div class="muted sm">${i.document ? `Uploaded ${ago(i.document.created_at)}${i.document.expires_at ? ' · expires ' + dateOnly(i.document.expires_at) : ''}` : 'Not uploaded yet'}${i.document && i.document.review_note ? html` · <span style="color:var(--danger)">${i.document.review_note}</span>` : ''}</div></div>${docChip(i.document)}
      ${i.document && i.document.attachment_id ? html`<button class="btn btn-ghost btn-sm" data-act="open-file" data-id="${i.document.attachment_id}">View</button>` : ''}${!i.document || ['rejected', 'expired'].includes(i.document.status) ? html`<button class="btn btn-secondary btn-sm" data-up="${i.doc_type}">${icon('upload', 16)} Upload</button>` : ''}</div>`)}</div>
    ${['unverified', 'rejected'].includes(r.verification_status) ? html`<div class="mt"><button class="btn btn-primary btn-lg" data-act="submit" ${ready ? '' : 'disabled'}>Submit for verification</button>${ready ? '' : html`<span class="muted sm"> Upload the required documents first.</span>`}</div>` : ''}`;
}

// ---------------------------------------------------------------- support
const PTOPICS = ['Payment issue', 'Booking issue', 'Customer conduct', 'Payout delay', 'App problem', 'Other'];
async function support(ctx) {
  const bk = await api('/bookings?page_size=50');
  ctx.submit('#f-issue', async (d) => { await api('/complaints', { method: 'POST', body: { topic: d.topic, description: d.description, booking_id: d.booking_id || undefined } }); toast('Ticket raised. Our team will respond within 24 hours.'); ctx.reload(); });
  ctx.after(() => mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/complaints' + qs(p)), search: false, pageSize: 8,
    columns: [{ label: 'Ticket', render: (t) => html`<b>${t.topic}</b><span class="sub">${t.reference}</span>` }, { label: 'Status', render: (t) => chip(cap(t.status), ['resolved', 'closed'].includes(t.status) ? 'success' : t.status === 'escalated' ? 'danger' : 'warn') }, { label: 'Opened', render: (t) => dt(t.created_at) }, { label: 'Resolution', render: (t) => t.resolution || '—' }],
    empty: { ic: 'help', title: 'No tickets', text: 'Raise booking, payment or conduct issues here.' },
  }));
  const [h] = await Promise.all([api('/help?audience=partner')]);
  return html`${pageHeader('Support & disputes', 'Raise booking, payment and conduct issues.')}
    <div class="split"><form id="f-issue" class="card" novalidate><h2 class="mb">Raise an issue</h2>${field({ name: 'topic', label: 'Topic', type: 'select', required: true, options: [['', 'Select a topic'], ...PTOPICS] })}${field({ name: 'booking_id', label: 'Related booking (optional)', type: 'select', options: [['', 'None'], ...bk.items.map((b) => [b.id, `${b.reference} · ${b.title}`])], value: ctx.query.booking || '' })}${field({ name: 'description', label: 'Describe the issue', type: 'textarea', rows: 4, required: true, maxlength: 1500 })}<div class="form-error" role="alert" hidden></div><button class="btn btn-primary" type="submit">Submit ticket</button></form>
    <div class="stack"><div class="card"><h2 class="mb">Help articles</h2>${h.items.slice(0, 5).map((a) => html`<details style="padding:6px 0"><summary><b>${a.title}</b></summary><p class="muted sm">${a.body}</p></details>`)}</div></div></div>
    ${section('My tickets', html`<div id="tbl"></div>`)}`;
}

// ---------------------------------------------------------------- hiring: vacancies & applications
const VSTAT = { pending_verification: ['Awaiting verification', 'warn'], published: ['Live', 'success'], paused: ['Paused', 'neutral'], closed: ['Closed', 'neutral'], rejected: ['Rejected', 'danger'], filled: ['Filled', 'success'] };
async function vacancies(ctx) {
  const [emp, m] = await Promise.all([api('/employer/profile'), meta()]);
  if (!emp.employer) {
    ctx.submit('#f-emp', async (d) => { await api('/employer', { method: 'POST', body: { company_name: d.company_name, industry: d.industry, locality: d.locality, contact_name: d.contact_name, phone: d.phone } }); toast('Employer profile created. It will be verified before vacancies go live.'); const me = await api('/me'); updateUser('partner', me); ctx.reload(); });
    return html`${pageHeader('Employer portal', 'Publish verified Quick Jobs vacancies and hire locally.')}<form id="f-emp" class="card" style="max-width:560px" novalidate><h2 class="mb">Create your employer profile</h2>${note('BUZZER verifies employers before vacancies are published. Candidates are never charged a recruitment fee.', 'info')}<div class="mt"></div>
      ${field({ name: 'company_name', label: 'Company name', required: true })}${field({ name: 'industry', label: 'Industry', required: true, placeholder: 'e.g. Retail' })}${field({ name: 'locality', label: 'Locality', type: 'select', required: true, options: [['', 'Select'], ...m.localities] })}${field({ name: 'contact_name', label: 'Hiring contact', required: true })}${field({ name: 'phone', label: 'Contact mobile', type: 'tel', required: true, prefix: '+91' })}<div class="form-error" role="alert" hidden></div><button class="btn btn-primary" type="submit">Create profile</button></form>`;
  }
  const e = emp.employer; const roles = ['Driver', 'Sales', 'Shopkeeper', 'Helper', 'Reception', 'Delivery', 'Cook', 'Security', 'Office assistant', 'Other'];
  const vForm = (v = {}) => S(field({ name: 'title', label: 'Job title', value: v.title || '', required: true }), html`<div class="grid g2">${field({ name: 'role_type', label: 'Role', type: 'select', value: v.role_type || '', options: [['', 'Select'], ...roles], required: true })}${field({ name: 'locality', label: 'Work location', type: 'select', value: v.locality || '', options: [['', 'Select'], ...m.localities], required: true })}${field({ name: 'salary_min', label: 'Salary from (₹)', type: 'number', value: v.salary_min || '', required: true })}${field({ name: 'salary_max', label: 'Salary to (₹)', type: 'number', value: v.salary_max || '', required: true })}${field({ name: 'pay_frequency', label: 'Paid per', type: 'select', value: v.pay_frequency || 'month', options: ['month', 'week', 'day', 'hour'] })}${field({ name: 'employment_type', label: 'Employment', type: 'select', value: v.employment_type || 'full_time', options: [['full_time', 'Full time'], ['part_time', 'Part time'], ['contract', 'Contract'], ['temporary', 'Temporary']] })}${field({ name: 'shift', label: 'Shift', value: v.shift || '', placeholder: 'Day (9am-6pm)' })}${field({ name: 'weekly_off', label: 'Weekly off', value: v.weekly_off || '' })}${field({ name: 'working_days', label: 'Working days', value: v.working_days || '' })}${field({ name: 'experience', label: 'Experience', value: v.experience || '' })}${field({ name: 'openings', label: 'Openings', type: 'number', value: v.openings || 1, min: 1 })}${field({ name: 'deadline', label: 'Apply by', type: 'date', value: v.deadline || '' })}</div>`,
    field({ name: 'skills', label: 'Skills', value: v.skills || '' }), field({ name: 'description', label: 'Description', type: 'textarea', rows: 4, value: v.description || '', required: true, hint: 'Be clear about salary, hours and duties. Misleading vacancies are removed.' }), field({ name: 'recruitment_fee_declared', type: 'checkbox', label: 'Candidates must pay a fee to apply (not allowed)', value: false }));
  ctx.on('click', '[data-act=add]', () => modal({ title: 'Post a vacancy', wide: true, submitLabel: 'Submit for verification', body: vForm(), onSubmit: async (d) => { await api('/vacancies', { method: 'POST', body: d }); toast('Submitted. We will verify it before it goes live.'); ctx.reload(); } }));
  ctx.after(() => { const t = mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/vacancies' + qs({ ...p, mine: 1 })), search: 'Search your vacancies', filters: [{ key: 'status', label: 'All statuses', options: Object.entries(VSTAT).map(([k, v]) => [k, v[0]]) }],
    columns: [{ label: 'Vacancy', render: (v) => html`<b>${v.title}</b><span class="sub">${v.reference} · ${v.locality}</span>` }, { label: 'Salary', render: (v) => `${inr(v.salary_min)}–${inr(v.salary_max)}/${v.pay_frequency}` }, { label: 'Applicants', cls: 'num', render: (v) => v.applicants }, { label: 'Status', render: (v) => html`${chip(...VSTAT[v.status])}${v.rejection_reason ? html`<span class="sub" style="color:var(--danger)">${v.rejection_reason}</span>` : ''}` },
      { label: '', cls: 'act', render: (v) => html`${v.status === 'published' ? rowBtn('pause', 'Pause') : v.status === 'paused' ? rowBtn('resume', 'Resume') : ''}${['published', 'paused', 'pending_verification'].includes(v.status) ? rowBtn('close', 'Close') : ''}${v.status === 'published' ? html`<a class="btn btn-ghost btn-sm" href="#/p/applications?vacancy_id=${v.id}">Applicants</a>` : ''}` }],
    actions: { pause: async (v) => { await api('/vacancies/' + v.id, { method: 'PATCH', body: { status: 'paused' } }); toast('Vacancy paused.'); t.reload(); }, resume: async (v) => { await api('/vacancies/' + v.id, { method: 'PATCH', body: { status: 'published' } }); toast('Vacancy is live again.'); t.reload(); },
      close: async (v) => { const r = await confirmDialog({ title: 'Close this vacancy?', message: 'Candidates will no longer be able to apply.', confirmLabel: 'Close vacancy', danger: true }); if (r) { await api('/vacancies/' + v.id, { method: 'PATCH', body: { status: 'closed' } }); toast('Vacancy closed.'); t.reload(); } } },
    empty: { ic: 'briefcase', title: 'No vacancies yet', text: 'Post a vacancy and we will verify it before it goes live.' },
  }); });
  return html`${pageHeader('Employer portal — vacancies', `${e.company_name} · ${e.verification_status === 'verified' ? 'Verified employer' : 'Employer verification ' + e.verification_status}`, html`<button class="btn btn-primary" data-act="add">${icon('plus', 18)} Post vacancy</button>`)}
    ${e.verification_status !== 'verified' ? note('Your employer profile is still being verified. Vacancies are published only after verification.', 'warn') : ''}<div id="tbl" class="mt"></div>`;
}

const ASTAT = { applied: ['New', 'info'], shortlisted: ['Shortlisted', 'info'], interview: ['Interview', 'warn'], hired: ['Hired', 'success'], rejected: ['Rejected', 'neutral'] };
async function applications(ctx) {
  ctx.after(() => { const t = mountTable(ctx.view.querySelector('#tbl'), {
    load: (p) => api('/employer/applications' + qs({ ...p, vacancy_id: ctx.query.vacancy_id })), search: false, filters: [{ key: 'status', label: 'All statuses', options: Object.entries(ASTAT).map(([k, v]) => [k, v[0]]) }],
    columns: [{ label: 'Candidate', render: (a) => html`<b>${a.candidate_name}</b><span class="sub">${a.headline || ''}</span>` }, { label: 'For', render: (a) => html`${a.title}<span class="sub">${a.reference}</span>` }, { label: 'Experience', render: (a) => `${a.experience_years} yr · ${a.skills || ''}` },
      { label: 'Contact', render: (a) => (a.candidate_mobile ? phone(a.candidate_mobile) : html`<span class="muted xs">Shown after shortlisting</span>`) }, { label: 'Status', render: (a) => html`${chip(...ASTAT[a.status])}${a.interview_at ? html`<span class="sub">${dt(a.interview_at)}</span>` : ''}` },
      { label: '', cls: 'act', render: (a) => html`${a.status === 'applied' ? rowBtn('short', 'Shortlist', 'btn-primary btn-sm') : ''}${['applied', 'shortlisted', 'interview'].includes(a.status) ? rowBtn('sched', 'Interview') : ''}${a.status === 'interview' ? rowBtn('hire', 'Hire', 'btn-primary btn-sm') : ''}${['applied', 'shortlisted', 'interview'].includes(a.status) ? rowBtn('rej', 'Reject') : ''}` }],
    actions: {
      short: async (a) => { await api('/applications/' + a.id, { method: 'PATCH', body: { status: 'shortlisted' } }); toast('Shortlisted.'); t.reload(); },
      hire: async (a) => { const r = await confirmDialog({ title: `Hire ${a.candidate_name}?`, message: 'This records the hiring outcome and notifies the candidate.', confirmLabel: 'Mark hired' }); if (r) { await api('/applications/' + a.id, { method: 'PATCH', body: { status: 'hired' } }); toast('Marked as hired.'); t.reload(); } },
      rej: async (a) => { const r = await confirmDialog({ title: 'Reject this application?', message: 'The candidate will be notified.', confirmLabel: 'Reject', danger: true }); if (r) { await api('/applications/' + a.id, { method: 'PATCH', body: { status: 'rejected' } }); toast('Rejected.'); t.reload(); } },
      sched: (a) => modal({ title: 'Schedule interview', submitLabel: 'Schedule', body: S(field({ name: 'scheduled_at', label: 'Date & time', type: 'datetime-local', required: true, value: inDays(2, 11), min: istInput(new Date().toISOString()) }), field({ name: 'mode', label: 'Mode', type: 'select', options: [['in_person', 'In person'], ['phone', 'Phone'], ['video', 'Video']] }), field({ name: 'location', label: 'Location / link' })), onSubmit: async (d) => { await api(`/applications/${a.id}/interview`, { method: 'POST', body: d }); toast('Interview scheduled.'); t.reload(); } }),
    }, empty: { ic: 'users', title: 'No applications yet', text: 'Candidates who apply to your live vacancies appear here.' },
  }); });
  return html`${pageHeader('Employer applications', 'Shortlist candidates and schedule interviews. Candidate contact details unlock after shortlisting.')}<div id="tbl"></div>`;
}

// ---------------------------------------------------------------- catering & photography
async function catering(ctx) {
  const [p, ld] = await Promise.all([api('/partner/profile'), api('/leads?page_size=30')]);
  const pk = p.packages.filter((k) => k.kind === 'catering');
  ctx.on('click', '[data-act=add]', () => modal({ title: 'Add catering package', body: S(field({ name: 'name', label: 'Package name', required: true, placeholder: 'e.g. Grand Wedding Spread' }), html`<div class="grid g2">${field({ name: 'price', label: 'Price per plate (₹)', type: 'number', required: true, min: 0 })}${field({ name: 'min_guests', label: 'Min guests', type: 'number', min: 1 })}${field({ name: 'max_guests', label: 'Max guests (capacity)', type: 'number', min: 1 })}</div>`, field({ name: 'menu', label: 'Menu', type: 'textarea', rows: 3 })),
    onSubmit: async (d) => { await api('/partner/packages', { method: 'POST', body: { name: d.name, price: d.price, kind: 'catering', details: { per_plate: inr(d.price), min_guests: d.min_guests, max_guests: d.max_guests, menu: d.menu } } }); toast('Package added.'); ctx.reload(); } }));
  ctx.on('click', '[data-del]', async (e, b) => { const r = await confirmDialog({ title: 'Remove package?', message: 'Customers will no longer see it.', confirmLabel: 'Remove', danger: true }); if (r) { await api('/partner/packages/' + b.dataset.del, { method: 'DELETE' }); ctx.reload(); } });
  const enq = ld.items.filter((l) => /wedding|event|cater/i.test(l.category_name));
  return html`${pageHeader('Wedding & catering', 'Manage event packages, menu and guest capacity.', html`<button class="btn btn-primary" data-act="add">${icon('plus', 18)} Add package</button>`)}
    <div class="auto-grid">${pk.length ? pk.map((k) => html`<div class="card"><div class="row between"><h2>${k.name}</h2><button class="icon-btn" data-del="${k.id}" aria-label="Remove ${k.name}">${icon('trash', 18)}</button></div><div class="price-big">${inr(k.price)}<span class="muted sm"> / plate</span></div>${kv([['Guests', `${k.details.min_guests || '—'} – ${k.details.max_guests || '—'}`], ['Menu', k.details.menu]])}</div>`) : html`<div style="grid-column:1/-1">${emptyState({ ic: 'gift', title: 'No packages yet', text: 'Add menus and per-plate pricing so agents can quote faster.' })}</div>`}</div>
    ${section('Wedding & event enquiries', enq.length ? html`<div class="card" style="padding:4px 16px">${enq.map((l) => html`<a class="list-item" href="#/p/lead/${l.request_id}">${catIc(l)}<div class="grow"><b>${l.title}</b><div class="muted sm">${l.reference} · ${l.locality}</div></div>${l.quote_status ? qChip(l.quote_status) : chip('Quote needed', 'warn')}</a>`)}</div>` : html`<p class="muted">No open enquiries.</p>`)}`;
}

async function photography(ctx) {
  const [p, ld] = await Promise.all([api('/partner/profile'), api('/leads?page_size=30')]);
  const pk = p.packages.filter((k) => k.kind === 'photography');
  ctx.on('click', '[data-act=add-port]', () => modal({ title: 'Add portfolio item', body: S(field({ name: 'title', label: 'Title', required: true, placeholder: 'e.g. Temple wedding - Banjara Hills' }), field({ name: 'caption', label: 'Caption' })), onSubmit: async (d) => { await api('/partner/portfolio', { method: 'POST', body: d }); toast('Added to portfolio.'); ctx.reload(); } }));
  ctx.on('click', '[data-act=add-pkg]', () => modal({ title: 'Add package', body: S(field({ name: 'name', label: 'Package name', required: true }), field({ name: 'price', label: 'Price (₹)', type: 'number', required: true, min: 0 }), field({ name: 'coverage', label: 'Coverage', placeholder: '6 hours' }), field({ name: 'team', label: 'Team', placeholder: '1 photographer + 1 assistant' }), field({ name: 'deliverables', label: 'Deliverables', placeholder: '400 edited photos, online gallery' })),
    onSubmit: async (d) => { await api('/partner/packages', { method: 'POST', body: { name: d.name, price: d.price, kind: 'photography', details: { coverage: d.coverage, team: d.team, deliverables: d.deliverables } } }); toast('Package added.'); ctx.reload(); } }));
  ctx.on('click', '[data-delp]', async (e, b) => { await api('/partner/portfolio/' + b.dataset.delp, { method: 'DELETE' }); ctx.reload(); });
  ctx.on('click', '[data-del]', async (e, b) => { const r = await confirmDialog({ title: 'Remove package?', message: 'Customers will no longer see it.', confirmLabel: 'Remove', danger: true }); if (r) { await api('/partner/packages/' + b.dataset.del, { method: 'DELETE' }); ctx.reload(); } });
  const enq = ld.items.filter((l) => /photo/i.test(l.category_name));
  return html`${pageHeader('Photography & videography', 'Portfolio-led profile: showcase your work and quote event coverage.', html`<button class="btn btn-primary" data-act="add-port">${icon('camera', 18)} Add portfolio item</button>`)}
    ${section('Portfolio', p.portfolio.length ? html`<div class="portfolio">${p.portfolio.map((x) => html`<figure><div class="ph" style="background:${x.color}">📷</div><figcaption>${x.title} <button class="icon-btn" data-delp="${x.id}" aria-label="Remove ${x.title}" style="width:28px;height:28px">${icon('x', 14)}</button></figcaption></figure>`)}</div>` : emptyState({ ic: 'camera', title: 'Your portfolio is empty', text: 'Customers compare portfolios before approving a quote.' }))}
    ${section('Packages', html`<div class="auto-grid">${pk.map((k) => html`<div class="card"><div class="row between"><h2>${k.name}</h2><button class="icon-btn" data-del="${k.id}" aria-label="Remove ${k.name}">${icon('trash', 18)}</button></div><div class="price-big">${inr(k.price)}</div>${kv([['Coverage', k.details.coverage], ['Team', k.details.team], ['Deliverables', k.details.deliverables]])}</div>`)}<button class="card card-link center" data-act="add-pkg" style="border-style:dashed;cursor:pointer">${icon('plus', 22)}<div><b>Add package</b></div></button></div>`)}
    ${section('Event coverage enquiries', enq.length ? html`<div class="card" style="padding:4px 16px">${enq.map((l) => html`<a class="list-item" href="#/p/lead/${l.request_id}">${catIc(l)}<div class="grow"><b>${l.title}</b><div class="muted sm">${l.reference} · ${l.locality}</div></div>${l.quote_status ? qChip(l.quote_status) : chip('Quote needed', 'warn')}</a>`)}</div>` : html`<p class="muted">No open enquiries.</p>`)}`;
}

export const routes = [
  ['/p/dashboard', dashboard], ['/p/profile', profile], ['/p/services', services], ['/p/calendar', calendar], ['/p/leads', leads], ['/p/lead/:id', leadDetail],
  ['/p/quote/:id', quoteBuilder], ['/p/quotes', myQuotes], ['/p/bookings', bookings], ['/p/booking/:id', bookingDetail], ['/p/job/:id', jobExecution],
  ['/p/messages', messages], ['/p/earnings', earnings], ['/p/reviews', reviews], ['/p/documents', documents], ['/p/support', support],
  ['/p/vacancies', vacancies], ['/p/applications', applications], ['/p/catering', catering], ['/p/photography', photography],
];
