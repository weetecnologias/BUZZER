// Customer app, part 2: messages, notifications, profile, support, membership, Quick Jobs, briefs.
import { ApiError, ago, api, cap, dt, dtl, dateOnly, debounce, esc, getSession, go, html, icon, inDays, inr, meta, num, openFile, phone, qs, raw, setSession, updateUser, upload } from '../core.js';
import { avatar, chat, chip, composer, confirmDialog, emptyState, field, kv, modal, note, opts, progress, section, starInput, stars, statusChip, switchRow, tabs, timeline, toast, verified } from '../ui.js';

const catIc = (c, size = 44) => html`<span class="cat-ic" style="background:${c.color || c.category_color || '#E6F4D7'};width:${size}px;height:${size}px;font-size:${Math.round(size * 0.5)}px" aria-hidden="true">${c.icon || c.category_icon || '✨'}</span>`;
async function act(el, fn) { const l = el.innerHTML; el.disabled = true; el.innerHTML = '<span class="spin"></span>'; try { return await fn(); } catch (e) { toast(e.message || 'Something went wrong', 'error'); } finally { if (el.isConnected) { el.disabled = false; el.innerHTML = l; } } }

// ---------------------------------------------------------------- messages
async function messages(ctx) {
  const list = await api('/conversations');
  const c = list.items[0];
  ctx.bar({ title: c ? c.title : 'Messages', back: '#/c/home' });
  if (!c) return emptyState({ ic: 'chat', title: 'No conversation yet', text: 'Your agent will message you here once your first request is created.', action: html`<a class="btn btn-primary" href="#/c/request/new">Request help</a>` });
  const me = ctx.user.id; const agent = c.agent;
  let pending = null;
  const load = async (scroll) => {
    const r = await api(`/conversations/${c.id}/messages`);
    const box = ctx.view.querySelector('#chat-box'); if (!box) return;
    const near = box.scrollHeight - box.scrollTop - box.clientHeight < 120;
    box.innerHTML = chat(r.items, me).toString();
    if (scroll || near) box.scrollTop = box.scrollHeight;
  };
  ctx.submit('[data-chat-form]', async (d, f) => {
    if (!d.body.trim() && !pending) return;
    await api(`/conversations/${c.id}/messages`, { method: 'POST', body: { body: d.body.trim(), attachment_id: pending ? pending.id : undefined } });
    pending = null; f.reset(); ctx.view.querySelector('#pending').textContent = ''; await load(true);
  });
  ctx.on('change', '[data-chat-file]', async (e, el) => {
    const f = el.files[0]; if (!f) return;
    try { pending = await upload(f); ctx.view.querySelector('#pending').textContent = '📎 ' + pending.filename + ' attached'; } catch (err) { toast(err.message, 'error'); }
    el.value = '';
  });
  ctx.on('click', '[data-act=open-file]', (e, b) => openFile(b.dataset.id));
  ctx.every(() => load(false).catch(() => {}), 6000);
  ctx.after(() => load(true));
  const wa = agent && agent.phone ? 'https://wa.me/' + agent.phone.replace(/\D/g, '') : null;
  return html`<div class="card"><div class="row">${avatar(agent ? agent.name : 'B', null, 46)}<div class="grow"><b>${agent ? agent.name : 'BUZZER support'}</b><div class="sm muted">Your personal agent</div></div>
      ${agent && agent.phone ? html`<a class="btn btn-ghost btn-sm" href="tel:${agent.phone}">${icon('phone', 16)} Call</a>` : ''}${wa ? html`<a class="btn btn-ghost btn-sm" href="${wa}" target="_blank" rel="noopener">WhatsApp</a>` : ''}</div></div>
    <div id="chat-box" style="max-height:52vh;overflow-y:auto;padding:12px 2px" tabindex="0" aria-label="Conversation"></div><div id="pending" class="sm muted"></div>${composer('Message your agent…')}
    <p class="center mt sm"><a href="#/c/support?tab=issue">${icon('alert', 14)} Raise an issue</a></p>`;
}

// ---------------------------------------------------------------- notifications
async function notifications(ctx) {
  ctx.bar({ title: 'Notifications', back: '#/c/home' });
  const [r, prefs] = await Promise.all([api('/notifications?page_size=30'), api('/me/preferences')]);
  const ic = { request: 'list', quote: 'file', booking: 'calendar', payment: 'wallet', message: 'chat', complaint: 'alert', job: 'briefcase', refund: 'wallet' };
  ctx.on('click', '[data-act=readall]', async () => { await api('/notifications/read', { method: 'POST', body: {} }); ctx.reload(); });
  ctx.on('click', '[data-nid]', (e, el) => { api('/notifications/read', { method: 'POST', body: { ids: [el.dataset.nid] } }).catch(() => {}); });
  ctx.on('change', '[data-pref] input', async (e, el) => {
    try { await api('/me/preferences', { method: 'PUT', body: { [el.name]: el.checked } }); toast('Preference saved.'); } catch (err) { el.checked = !el.checked; toast(err.message, 'error'); }
  });
  return html`<div class="row between mb"><span class="muted">${r.unread ? `${r.unread} unread` : 'All caught up'}</span>${r.unread ? html`<button class="btn btn-ghost btn-sm" data-act="readall">Mark all read</button>` : ''}</div>
    ${r.items.length ? html`<div class="card" style="padding:0 14px">${r.items.map((n) => html`<a class="list-item" href="${n.link || '#/c/notifications'}" data-nid="${n.id}" style="${n.is_read ? '' : 'font-weight:600'}"><span class="stat-ic">${icon(ic[n.type] || 'bell', 18)}</span><div class="grow"><b>${n.title}</b><div class="muted sm" style="font-weight:400">${n.body}</div></div><span class="muted xs">${ago(n.created_at)}</span>${n.is_read ? '' : html`<i style="width:9px;height:9px;border-radius:50%;background:var(--green)" aria-label="Unread"></i>`}</a>`)}</div>`
      : emptyState({ ic: 'bell', title: 'No notifications yet', text: 'Updates on your requests, quotes and payments appear here.' })}
    ${section('Notification preferences', html`<div class="card" data-pref>${prefs.items.map((p) => switchRow({ name: p.key, label: p.label, help: p.help, checked: p.enabled }))}</div>`)}`;
}

// ---------------------------------------------------------------- profile
async function profile(ctx) {
  const [me, addr, cons, m] = await Promise.all([api('/me'), api('/addresses'), api('/me/consents'), meta()]);
  updateUser('customer', me);
  const addrForm = (a = {}) => field({ name: 'label', label: 'Label', value: a.label || 'Home', required: true }).toString() + field({ name: 'line1', label: 'Address', value: a.line1 || '', required: true }).toString()
    + field({ name: 'locality', label: 'Locality', type: 'select', value: a.locality || '', options: [['', 'Select'], ...m.localities], required: true }).toString() + field({ name: 'landmark', label: 'Landmark', value: a.landmark || '' }).toString()
    + field({ name: 'pincode', label: 'Pincode', value: a.pincode || '', inputmode: 'numeric', maxlength: 6 }).toString() + `<label class="check"><input type="checkbox" name="is_default" ${a.is_default ? 'checked' : ''}><span>Make this my default address</span></label>`;
  ctx.on('click', '[data-act=edit-name]', () => modal({ title: 'Edit profile', body: field({ name: 'name', label: 'Full name', value: me.name, required: true }).toString() + field({ name: 'email', label: 'Email (optional)', type: 'email', value: me.email || '' }).toString() + field({ name: 'language', label: 'Language', type: 'select', value: me.language, options: [['en', 'English'], ['hi', 'हिन्दी'], ['te', 'తెలుగు'], ['ur', 'اردو']] }).toString(),
    onSubmit: async (d) => { const u = await api('/me', { method: 'PATCH', body: { name: d.name, email: d.email || undefined, language: d.language } }); updateUser('customer', u); toast('Profile updated.'); ctx.reload(); } }));
  ctx.on('click', '[data-act=add-addr]', () => modal({ title: 'Add address', body: addrForm(), submitLabel: 'Save address', onSubmit: async (d) => { await api('/addresses', { method: 'POST', body: d }); toast('Address saved.'); ctx.reload(); } }));
  ctx.on('click', '[data-act=edit-addr]', (e, b) => { const a = addr.items.find((x) => x.id === b.dataset.id); modal({ title: 'Edit address', body: addrForm(a), onSubmit: async (d) => { await api('/addresses/' + a.id, { method: 'PATCH', body: d }); toast('Address updated.'); ctx.reload(); } }); });
  ctx.on('click', '[data-act=del-addr]', async (e, b) => { const r = await confirmDialog({ title: 'Delete this address?', message: 'This cannot be undone.', confirmLabel: 'Delete', danger: true }); if (r) { try { await api('/addresses/' + b.dataset.id, { method: 'DELETE' }); toast('Address deleted.'); ctx.reload(); } catch (err) { toast(err.message, 'error'); } } });
  ctx.on('change', '[data-consent] input', async (e, el) => { try { await api('/me/consents', { method: 'PUT', body: { [el.name]: el.checked } }); toast('Saved.'); } catch (err) { el.checked = !el.checked; toast(err.message, 'error'); } });
  ctx.on('click', '[data-act=export]', (e, b) => act(b, async () => {
    const res = await fetch('/api/v1/me/export', { headers: { Authorization: 'Bearer ' + getSession('customer').token } });
    if (!res.ok) throw new Error('Export failed'); const blob = await res.blob(); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'buzzer-my-data.json'; a.click(); URL.revokeObjectURL(a.href); toast('Your data was downloaded.');
  }));
  ctx.on('click', '[data-act=delete]', async () => {
    const r = await confirmDialog({ title: 'Request account deletion?', message: 'We will confirm by call within 7 days. Active requests must be closed first.', confirmLabel: 'Request deletion', danger: true, reason: true, reasonLabel: 'Tell us why (optional)' });
    if (r) { const x = await api('/me/delete-request', { method: 'POST', body: { reason: r.reason } }); toast(x.message); }
  });
  return html`<div class="card"><div class="row">${avatar(me.name, me.avatar_color, 60)}<div class="grow"><h1 style="font-size:20px">${me.name}</h1><div class="muted sm">${phone(me.mobile)}${me.email ? ' · ' + me.email : ''}</div>${me.membership ? chip(me.membership.plan_name, 'lime') : ''}</div><button class="btn btn-ghost btn-sm" data-act="edit-name">Edit</button></div></div>
    ${section('Saved addresses', html`<div class="card" style="padding:0 14px">${addr.items.length ? addr.items.map((a) => html`<div class="list-item">${icon('pin', 20)}<div class="grow"><b>${a.label}</b> ${a.is_default ? chip('Default', 'lime') : ''}<div class="muted sm">${a.line1}, ${a.locality}${a.landmark ? ' · ' + a.landmark : ''}</div></div><button class="icon-btn" data-act="edit-addr" data-id="${a.id}" aria-label="Edit ${a.label}">${icon('edit', 18)}</button><button class="icon-btn" data-act="del-addr" data-id="${a.id}" aria-label="Delete ${a.label}">${icon('trash', 18)}</button></div>`) : html`<p class="muted" style="padding:12px 0">No saved addresses yet.</p>`}</div><button class="btn btn-secondary btn-block mt" data-act="add-addr">${icon('plus', 18)} Add address</button>`)}
    ${section('Communication consent', html`<div class="card" data-consent>${cons.items.filter((c) => c.kind !== 'candidate_data_sharing').map((c) => switchRow({ name: c.kind, label: c.label, checked: c.granted }))}<p class="muted xs" style="margin:10px 0 0">Service updates about your requests are always sent in the app.</p></div>`)}
    ${section('More', html`<div class="card" style="padding:0 14px"><a class="drawer-link" href="#/c/membership">${icon('card', 20)} Membership & billing ${icon('right', 18, 'chev')}</a><a class="drawer-link" href="#/c/support">${icon('help', 20)} Ratings, complaints & support ${icon('right', 18, 'chev')}</a><a class="drawer-link" href="#/c/jobs">${icon('briefcase', 20)} Quick Jobs ${icon('right', 18, 'chev')}</a><a class="drawer-link" href="#/c/notifications">${icon('bell', 20)} Notifications ${icon('right', 18, 'chev')}</a></div>`)}
    ${section('Privacy & account', html`<div class="card"><p class="muted sm" style="margin-top:0">You control your data. Only the people who need it to handle your requests can see it.</p><div class="row-gap"><button class="btn btn-ghost btn-sm" data-act="export">${icon('download', 16)} Download my data</button><button class="btn btn-danger-o btn-sm" data-act="delete">Request account deletion</button></div></div>`)}
    <div class="mt"><button class="btn btn-secondary btn-block" data-signout>${icon('logout', 18)} Sign out</button><p class="center muted xs mt"><a href="#/c/legal">Terms & Privacy</a> · BUZZER v1.0</p></div>`;
}

// ---------------------------------------------------------------- support
const TOPICS = ['Quality of work', 'Late or no-show', 'Overcharged', 'Behaviour', 'Safety', 'Billing / refund', 'Other'];
async function support(ctx) {
  const tab = ctx.query.tab || 'rate';
  ctx.bar({ title: 'Ratings & support', back: '#/c/profile' });
  const head = tabs([['rate', 'Rate service'], ['issue', 'Raise issue'], ['track', 'Track'], ['help', 'Help']], tab, '#/c/support');
  let body = '';
  if (tab === 'rate') {
    if (ctx.query.request) {
      const r = await api('/requests/' + ctx.query.request);
      if (!r.can_rate) body = emptyState({ ic: 'star', title: r.review ? 'Thanks — you already rated this' : 'Not ready to rate yet', text: r.review ? `You gave ${r.review.rating} stars.` : 'You can rate once the service is completed.', action: html`<a class="btn btn-primary" href="#/c/request/${r.id}">Back to request</a>` });
      else {
        ctx.submit('#f-rate', async (d) => { await api(`/requests/${r.id}/reviews`, { method: 'POST', body: { provider_rating: d.provider_rating, provider_comment: d.provider_comment, agent_rating: d.agent_rating, agent_comment: d.agent_comment } }); toast('Thank you for your feedback!'); go('/c/requests?tab=completed'); });
        body = html`<div class="card"><b>${r.title}</b><div class="muted sm">${r.reference}</div></div><form id="f-rate" class="mt" novalidate>
          <h2>Rate ${r.booking ? r.booking.provider.business_name : 'your provider'}</h2>${starInput('provider_rating', 5)}<div class="field-error" data-error-for="provider_rating" role="alert"></div>${field({ name: 'provider_comment', label: 'Share your feedback (optional)', type: 'textarea', rows: 3, maxlength: 500, placeholder: 'Great service, on time and professional.' })}
          <h2>Rate your agent ${r.agent ? r.agent.name : ''}</h2>${starInput('agent_rating', 5)}<div class="field-error" data-error-for="agent_rating" role="alert"></div>${field({ name: 'agent_comment', label: 'Comments (optional)', type: 'textarea', rows: 2, maxlength: 500 })}
          <div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Submit rating</button></form>`;
      }
    } else {
      const done = await api('/requests?status_group=completed&page_size=8');
      const details = await Promise.all(done.items.map((x) => api('/requests/' + x.id).catch(() => null)));
      const todo = details.filter((d) => d && d.can_rate);
      body = todo.length ? html`<p class="muted">Rate your recent services.</p>${todo.map((d) => html`<a class="card card-link req-card" href="#/c/support?tab=rate&request=${d.id}"><div class="row">${catIc(d.category)}<div class="grow"><b>${d.title}</b><div class="muted sm">${d.booking ? d.booking.provider.business_name : ''} · ${d.reference}</div></div><span class="btn btn-primary btn-sm">Rate</span></div></a>`)}`
        : emptyState({ ic: 'star', title: 'Nothing to rate right now', text: 'Completed services you have not rated yet will appear here.' });
    }
  } else if (tab === 'issue') {
    const reqs = await api('/requests?page_size=50');
    ctx.submit('#f-issue', async (d, f) => {
      const sel = reqs.items.find((x) => x.id === d.request_id);
      const body_ = { topic: d.topic, description: d.description, kind: d.kind, request_id: d.request_id || undefined, booking_id: sel && sel.booking_id ? sel.booking_id : undefined, attachments: evidence.map((e) => e.id) };
      await api('/complaints', { method: 'POST', body: body_ }); toast('Issue raised. We will update you here.'); go('/c/support?tab=track');
    });
    const evidence = [];
    ctx.on('change', '#evidence', async (e, el) => { for (const f of [...el.files].slice(0, 3)) { try { evidence.push(await upload(f)); } catch (err) { toast(err.message, 'error'); } } ctx.view.querySelector('#ev-list').textContent = evidence.map((x) => '📎 ' + x.filename).join('  '); el.value = ''; });
    body = html`<form id="f-issue" novalidate><p class="muted">Tell us what went wrong. A BUZZER specialist will review it and update you.</p>
      ${field({ name: 'request_id', label: 'Which request?', type: 'select', value: ctx.query.request || '', options: [['', 'General (no specific request)'], ...reqs.items.map((r) => [r.id, `${r.reference} · ${r.title}`])] })}
      ${field({ name: 'topic', label: 'What is the issue about?', type: 'select', required: true, options: [['', 'Select a topic'], ...TOPICS] })}
      <div class="field"><label>Type</label><div class="radio-cards"><label><input type="radio" name="kind" value="complaint" checked><span><b>Complaint</b><br><small class="muted">Share feedback and get it fixed</small></span></label><label><input type="radio" name="kind" value="dispute"><span><b>Dispute</b><br><small class="muted">Pauses the booking while we investigate</small></span></label></div></div>
      ${field({ name: 'description', label: 'Describe the issue', type: 'textarea', rows: 4, required: true, maxlength: 1500, placeholder: 'What happened? When? What would resolve it?' })}
      <div class="field"><label>Evidence (optional)</label><label class="btn btn-ghost btn-block" style="cursor:pointer">${icon('camera', 18)} Attach photos<input id="evidence" type="file" accept="image/*" multiple hidden></label><div id="ev-list" class="sm muted mt"></div></div>
      <div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Submit</button></form>`;
  } else if (tab === 'track') {
    const r = await api('/complaints?page_size=30');
    ctx.on('click', '[data-act=ticket]', async (e, b) => {
      const t = await api('/complaints/' + b.dataset.id);
      modal({ title: t.reference, footer: false, wide: true, body: `<div class="row between"><b>${esc(t.topic)}</b>${statusChip === null ? '' : `<span class="chip chip-${t.status === 'resolved' || t.status === 'closed' ? 'success' : 'warn'}">${esc(cap(t.status))}</span>`}</div><p class="sm">${esc(t.description)}</p>${t.resolution ? `<div class="note note-success">${esc(t.resolution)}</div>` : ''}<h3 style="margin:14px 0 8px">Progress</h3>${timeline(t.events.map((x) => ({ title: cap(x.event.replace('_', ' ')), time: dt(x.created_at), note: x.note }))).toString()}
        ${['resolved', 'closed'].includes(t.status) ? '' : `<form id="f-note" class="mt"><div class="field"><label for="n">Add a note</label><textarea id="n" name="note" rows="2" required></textarea><div class="field-error" data-error-for="note"></div></div><button class="btn btn-primary btn-sm" type="submit">Send</button></form>`}` ,
        onMount: (m) => { const f = m.querySelector('#f-note'); f && f.addEventListener('submit', async (ev) => { ev.preventDefault(); try { await api(`/complaints/${t.id}/notes`, { method: 'POST', body: { note: f.note.value } }); toast('Note added.'); m.querySelector('[data-close]').click(); ctx.reload(); } catch (err) { toast(err.message, 'error'); } }); } });
    });
    body = r.items.length ? r.items.map((t) => html`<button class="card card-link req-card" data-act="ticket" data-id="${t.id}" style="width:100%;text-align:left"><div class="row"><div class="grow"><b>${t.topic}</b><div class="muted sm">${t.reference} · ${dt(t.created_at)}</div></div>${chip(cap(t.status), ['resolved', 'closed'].includes(t.status) ? 'success' : t.status === 'escalated' ? 'danger' : 'warn')}</div>${t.overdue ? html`<div class="note note-warn mt">We're taking longer than usual — your agent has been alerted.</div>` : ''}</button>`)
      : emptyState({ ic: 'shield', title: 'No issues raised', text: 'If something goes wrong with a service, raise it and track the resolution here.' });
  } else {
    const [h, me] = await Promise.all([api('/help?audience=customer'), api('/me')]);
    body = html`${h.items.map((a) => html`<details class="card card-flat mb"><summary><b>${a.title}</b></summary><p class="muted" style="margin-top:8px">${a.body}</p></details>`)}
      ${me.agent ? html`<div class="card card-soft mt"><b>Still stuck?</b><p class="muted sm">Your agent ${me.agent.name} is happy to help.</p><div class="row-gap"><a class="btn btn-primary btn-sm" href="#/c/messages">${icon('chat', 16)} Chat</a>${me.agent.phone ? html`<a class="btn btn-ghost btn-sm" href="tel:${me.agent.phone}">${icon('phone', 16)} Call</a>` : ''}</div></div>` : ''}`;
  }
  return html`${head}<div class="mt">${body}</div>`;
}

// ---------------------------------------------------------------- membership
async function membership(ctx) {
  ctx.bar({ title: 'Membership & billing', back: '#/c/profile' });
  const [plans, me] = await Promise.all([api('/plans'), api('/me/membership')]);
  const cur = me.membership; let interval = 'month';
  const paid = plans.items.filter((p) => p.price_month > 0);
  const draw = () => {
    ctx.view.querySelectorAll('[data-price]').forEach((el) => { const p = plans.items.find((x) => x.id === el.dataset.price); el.textContent = inr(interval === 'year' ? p.price_year : p.price_month) + (interval === 'year' ? ' / year' : ' / month'); });
    ctx.view.querySelectorAll('[data-int]').forEach((b) => b.classList.toggle('active', b.dataset.int === interval));
  };
  ctx.on('click', '[data-int]', (e, b) => { interval = b.dataset.int; draw(); });
  ctx.after(draw);
  ctx.on('click', '[data-act=sub]', (e, b) => modal({ title: 'Subscribe', submitLabel: 'Pay & subscribe', body: `<p>${esc(b.dataset.name)} — <b>${esc(inr(interval === 'year' ? b.dataset.year : b.dataset.month))}</b> per ${interval}.</p><div class="radio-cards">${[['upi', 'UPI'], ['card', 'Card'], ['netbanking', 'Net banking']].map(([v, l], i) => `<label><input type="radio" name="method" value="${v}" ${i === 0 ? 'checked' : ''}><span>${l}</span></label>`).join('')}</div><p class="xs muted mt">Sandbox payment — no real money moves. Cancel anytime.</p>`,
    onSubmit: async (d) => { await api('/me/membership', { method: 'POST', body: { plan_id: b.dataset.id, interval, method: d.method } }); toast('Welcome to ' + b.dataset.name + '!'); ctx.reload(); } }));
  ctx.on('click', '[data-mact]', async (e, b) => {
    const a = b.dataset.mact; const r = await confirmDialog({ title: { pause: 'Pause your plan?', resume: 'Resume your plan?', cancel: 'Cancel your plan?' }[a], message: a === 'cancel' ? 'You will keep benefits until the end of the current period. No further charges.' : 'You can change this anytime.', confirmLabel: cap(a), danger: a === 'cancel' });
    if (r) { try { await api('/me/membership/' + a, { method: 'POST' }); toast('Plan updated.'); ctx.reload(); } catch (err) { toast(err.message, 'error'); } }
  });
  return html`${cur ? html`<div class="hero-card"><div class="row between"><div><span class="chip chip-lime">${cur.status === 'paused' ? 'Paused' : 'Active'}</span><h1 style="margin-top:8px">${cur.plan_name}</h1></div><span style="font-size:34px">👑</span></div>
      <div class="row between mt"><div><div class="muted sm">Next renewal</div><b>${dateOnly(cur.renewal_at)}</b></div><div class="right"><div class="muted sm">${cur.interval === 'year' ? 'Yearly' : 'Monthly'}</div><b>${inr(cur.interval === 'year' ? cur.price_year : cur.price_month)}</b></div></div></div>
      <ul class="mt" style="padding-left:18px">${cur.benefits.map((b) => html`<li>${b}</li>`)}</ul>
      <div class="row-gap mt">${cur.status === 'paused' ? html`<button class="btn btn-primary btn-sm" data-mact="resume">Resume plan</button>` : html`<button class="btn btn-ghost btn-sm" data-mact="pause">Pause</button>`}<button class="btn btn-danger-o btn-sm" data-mact="cancel">Cancel plan</button></div>`
    : html`<h1>Choose your plan</h1><p class="muted">Get extra benefits and priority support.</p>`}
    ${section(cur ? 'Other plans' : 'Plans', html`<div class="row-gap mb"><div class="tabs"><button class="tab" data-int="month">Monthly</button><button class="tab" data-int="year">Yearly <em>Save 20%</em></button></div></div>
      <div class="stack">${paid.filter((p) => !cur || p.id !== cur.plan_id).map((p) => html`<div class="card"><div class="row between"><h2>${icon('star', 18)} ${p.name}</h2><span class="price-big" data-price="${p.id}"></span></div><ul style="padding-left:18px">${p.benefits.map((b) => html`<li>${b}</li>`)}</ul><button class="btn btn-primary btn-block" data-act="sub" data-id="${p.id}" data-name="${p.name}" data-month="${p.price_month}" data-year="${p.price_year}">Subscribe</button></div>`)}</div>`)}
    ${section('Payment history', me.payments.length ? html`<div class="card" style="padding:0 14px">${me.payments.map((p) => html`<div class="list-item"><div class="grow"><b>${p.memo || cap(p.kind.replace('_', ' '))}</b><div class="muted sm">${p.reference} · ${dt(p.created_at)}</div></div><b>${p.kind === 'refund' ? '+' : ''}${inr(p.amount)}</b></div>`)}</div>` : html`<p class="muted">No payments yet.</p>`)}
    ${section('Terms & refund policy', html`<p class="muted sm">Plans renew automatically until cancelled. Cancel anytime from this page; benefits continue until the period ends. Refunds on subscription fees are reviewed case by case within 5 working days. Payments are in sandbox mode in this build.</p>`)}`;
}

// ---------------------------------------------------------------- Quick Jobs
const salary = (v) => `${inr(v.salary_min)}–${inr(v.salary_max)}/${v.pay_frequency}`;
const jobCard = (v) => html`<article class="card job-card"><div class="row"><div class="grow"><a href="#/c/job/${v.id}"><b>${v.title}</b></a><div class="muted sm">${v.company_name} ${verified(v.employer_verification)}</div></div><button class="icon-btn" data-act="save" data-id="${v.id}" data-saved="${v.saved ? 1 : 0}" aria-label="${v.saved ? 'Unsave' : 'Save'} ${v.title}" aria-pressed="${!!v.saved}" style="${v.saved ? 'color:var(--green-d)' : ''}">${icon('bookmark', 22)}</button></div>
  <div class="tags">${chip(salary(v), 'lime')}${chip(cap(v.employment_type), 'neutral')}${v.shift ? chip(v.shift, 'neutral') : ''}</div>
  <div class="row between sm muted"><span>${icon('pin', 14)} ${v.locality}</span><span>${v.application_status ? chip(cap(v.application_status), 'info') : html`<a class="btn btn-primary btn-sm" href="#/c/job/${v.id}">View & apply</a>`}</span></div></article>`;

async function jobs(ctx) {
  ctx.bar({ title: 'Quick Jobs', back: '#/c/home' });
  const tab = ctx.query.tab || 'find';
  const cp = await api('/candidate/profile');
  const filtersQ = { q: ctx.query.q, role_type: ctx.query.role_type, locality: ctx.query.locality, shift: ctx.query.shift, min_salary: ctx.query.min_salary };
  const m = await meta();
  ctx.on('click', '[data-act=save]', async (e, b) => {
    try { await api(`/vacancies/${b.dataset.id}/save`, { method: b.dataset.saved === '1' ? 'DELETE' : 'PUT' }); ctx.reload(); } catch (err) { if (err.code === 'no_profile') { toast('Create your candidate profile to save jobs.', 'error'); profileModal(ctx, cp); } else toast(err.message, 'error'); }
  });
  ctx.on('change', '[data-filter]', () => { const q_ = {}; ctx.view.querySelectorAll('[data-filter]').forEach((i) => { q_[i.name] = i.value; }); go('/c/jobs' + qs({ tab: 'find', ...q_ })); });
  ctx.on('input', '#jq', debounce((e, el) => { const q_ = { tab: 'find' }; ctx.view.querySelectorAll('[data-filter]').forEach((i) => { q_[i.name] = i.value; }); q_.q = el.value; go('/c/jobs' + qs(q_)); }, 600));
  ctx.on('click', '[data-act=profile]', () => profileModal(ctx, cp));
  ctx.on('click', '[data-act=withdraw]', async (e, b) => { const r = await confirmDialog({ title: 'Withdraw application?', message: 'The employer will no longer see your profile for this job.', confirmLabel: 'Withdraw', danger: true }); if (r) { await api(`/applications/${b.dataset.id}/withdraw`, { method: 'POST' }); toast('Application withdrawn.'); ctx.reload(); } });
  let body = '';
  if (tab === 'find') {
    const r = await api('/vacancies' + qs({ ...filtersQ, page_size: 30 }));
    body = html`<label class="search" style="max-width:none">${icon('search', 18)}<input id="jq" type="search" placeholder="Search jobs, skills or company" value="${ctx.query.q || ''}"></label>
      <div class="grid g2k mt"><div class="field" style="margin:0"><select name="role_type" data-filter aria-label="Role">${opts([['', 'All roles'], ...r.role_types], ctx.query.role_type)}</select></div><div class="field" style="margin:0"><select name="locality" data-filter aria-label="Locality">${opts([['', 'Anywhere'], ...m.localities], ctx.query.locality)}</select></div>
        <div class="field" style="margin:0"><select name="shift" data-filter aria-label="Shift">${opts([['', 'Any shift'], 'Day', 'Night', 'Rotational', 'Flexible'], ctx.query.shift)}</select></div><div class="field" style="margin:0"><select name="min_salary" data-filter aria-label="Minimum salary">${opts([['', 'Any salary'], ['10000', '₹10,000+'], ['15000', '₹15,000+'], ['20000', '₹20,000+']], ctx.query.min_salary)}</select></div></div>
      <div class="stack mt">${r.items.length ? r.items.map(jobCard) : emptyState({ ic: 'briefcase', title: 'No jobs match', text: 'Try removing a filter or searching a different role.' })}</div>`;
  } else if (tab === 'saved') {
    const r = await api('/vacancies?saved=1&page_size=30');
    body = html`<div class="stack">${r.items.length ? r.items.map(jobCard) : emptyState({ ic: 'bookmark', title: 'No saved jobs', text: 'Tap the bookmark on a job to keep it here.' })}</div>`;
  } else {
    const r = await api('/candidate/applications');
    body = html`<div class="stack">${r.items.length ? r.items.map((a) => html`<div class="card"><div class="row"><div class="grow"><a href="#/c/job/${a.vacancy_id}"><b>${a.title}</b></a><div class="muted sm">${a.company_name} · ${a.locality}</div></div>${chip(cap(a.status), { hired: 'success', rejected: 'danger', interview: 'warn', shortlisted: 'info' }[a.status] || 'neutral')}</div>
      ${a.interview_at ? html`<div class="note note-warn mt">${icon('calendar', 16)} Interview ${dtl(a.interview_at)}${a.interview_location ? ' · ' + a.interview_location : ''}</div>` : ''}
      <div class="row between sm muted mt"><span>Applied ${ago(a.applied_at)}</span>${['applied', 'shortlisted', 'interview'].includes(a.status) ? html`<button class="btn btn-ghost btn-sm" data-act="withdraw" data-id="${a.id}">Withdraw</button>` : ''}</div></div>`) : emptyState({ ic: 'file', title: 'No applications yet', text: 'Apply to a job and track its progress here.' })}</div>`;
  }
  return html`<div class="card ${cp.completion >= 100 ? 'card-soft' : ''}"><div class="row between"><b>${cp.completion >= 100 ? 'Profile complete ✓' : 'Complete your candidate profile'}</b><span class="muted sm">${cp.completion}%</span></div>${progress(cp.completion, 'Profile completion')}
      <div class="row between mt"><span class="muted sm">${cp.profile ? cp.profile.headline : 'Employers see this only after you apply.'}</span><button class="btn ${cp.completion >= 100 ? 'btn-ghost' : 'btn-primary'} btn-sm" data-act="profile">${cp.profile ? 'Edit' : 'Create'} profile</button></div></div>
    <div class="mt">${tabs([['find', 'Find jobs'], ['saved', 'Saved'], ['apps', 'Applications']], tab, '#/c/jobs')}</div><div class="mt">${body}</div>`;
}
function profileModal(ctx, cp) {
  const p = cp.profile || {};
  modal({ title: 'Candidate profile', submitLabel: 'Save profile', body: field({ name: 'headline', label: 'Headline', value: p.headline || '', required: true, placeholder: 'e.g. Experienced personal driver' }).toString() + field({ name: 'skills', label: 'Skills', value: p.skills || '', required: true }).toString()
    + `<div class="grid g2">${field({ name: 'experience_years', label: 'Experience (years)', type: 'number', value: p.experience_years ?? 0, min: 0, max: 50 }).toString()}${field({ name: 'expected_salary', label: 'Expected salary (₹/month)', type: 'number', value: p.expected_salary || '', min: 0 }).toString()}</div>`
    + field({ name: 'preferred_localities', label: 'Preferred localities', value: p.preferred_localities || '', required: true, placeholder: 'Kondapur, Gachibowli' }).toString() + field({ name: 'shift_pref', label: 'Shift preference', type: 'select', value: p.shift_pref || '', options: [['', 'Select'], 'Day', 'Night', 'Rotational', 'Flexible'] }).toString()
    + `<label class="check"><input type="checkbox" name="data_sharing_consent" ${p.data_sharing_consent ? 'checked' : ''}><span>I agree to share my profile with verified employers <b>only when I apply</b>.</span></label>`,
  onSubmit: async (d) => { await api('/candidate/profile', { method: 'PUT', body: d }); toast('Profile saved.'); ctx.reload(); } });
}

async function jobDetail(ctx) {
  const v = await api('/vacancies/' + ctx.params.id);
  ctx.bar({ title: 'Job details', back: '#/c/jobs' });
  const cp = await api('/candidate/profile');
  ctx.on('click', '[data-act=apply]', () => {
    if (!cp.profile) { toast('Create your candidate profile first.', 'error'); profileModal(ctx, cp); return; }
    modal({ title: 'Apply for ' + v.title, submitLabel: 'Submit application', body: `<p class="sm">Your profile — <b>${esc(cp.profile.headline)}</b>, ${esc(String(cp.profile.experience_years))} yrs — will be shared with <b>${esc(v.company_name)}</b>.</p><label class="check"><input type="checkbox" name="consent_shared"><span>I consent to share my candidate profile and contact details with this employer for this job.</span></label><div class="field-error" data-error-for="consent_shared" role="alert"></div>`,
      onSubmit: async (d) => { await api(`/vacancies/${v.id}/applications`, { method: 'POST', body: { consent_shared: d.consent_shared } }); toast('Application sent!'); ctx.reload(); } });
  });
  ctx.on('click', '[data-act=withdraw]', async () => { const r = await confirmDialog({ title: 'Withdraw application?', message: 'You can apply again later.', confirmLabel: 'Withdraw', danger: true }); if (r) { await api(`/applications/${v.my_application.id}/withdraw`, { method: 'POST' }); toast('Application withdrawn.'); ctx.reload(); } });
  ctx.on('click', '[data-act=save]', async () => { try { await api(`/vacancies/${v.id}/save`, { method: v.saved ? 'DELETE' : 'PUT' }); ctx.reload(); } catch (e) { toast('Create your candidate profile to save jobs.', 'error'); } });
  ctx.on('click', '[data-act=report]', () => modal({ title: 'Report this vacancy', submitLabel: 'Send report', danger: true, body: field({ name: 'reason', label: 'What is wrong?', type: 'textarea', required: true, placeholder: 'e.g. They asked me to pay a fee.' }).toString(), onSubmit: async (d) => { const r = await api(`/vacancies/${v.id}/report`, { method: 'POST', body: d }); toast(r.message); } }));
  const app = v.my_application && v.my_application.status !== 'withdrawn' ? v.my_application : null;
  return html`<div class="card"><div class="row"><div class="grow"><h1 style="font-size:21px">${v.title}</h1><div class="muted">${v.employer.company_name} ${verified(v.employer.verification_status)}</div></div><button class="icon-btn" data-act="save" aria-label="${v.saved ? 'Unsave' : 'Save'} job" style="${v.saved ? 'color:var(--green-d)' : ''}">${icon('bookmark', 24)}</button></div>
      <div class="price-big mt">${salary(v)}</div></div>
    ${section('Job conditions', html`<div class="card">${kv([['Role', v.role_type], ['Location', v.locality], ['Employment', cap(v.employment_type)], ['Shift', v.shift], ['Working days', v.working_days], ['Weekly off', v.weekly_off], ['Experience', v.experience], ['Skills', v.skills], ['Openings', v.openings], ['Apply by', v.deadline ? dateOnly(v.deadline) : 'Open']])}</div>`)}
    ${section('About the role', html`<p>${v.description}</p>`)}
    ${note(v.safety_notice, 'info')}
    <div class="sticky-cta">${app ? html`<div class="card card-soft"><div class="row between"><b>Application: ${cap(app.status)}</b><button class="btn btn-ghost btn-sm" data-act="withdraw">Withdraw</button></div></div>` : v.status === 'published' ? html`<button class="btn btn-primary btn-block btn-lg" data-act="apply">Apply now</button>` : html`<button class="btn btn-secondary btn-block" disabled>Not accepting applications</button>`}<p class="center mt"><button class="btn btn-ghost btn-sm" data-act="report">${icon('alert', 14)} Report this vacancy</button></p></div>`;
}

// ---------------------------------------------------------------- event & photography briefs
const chips = (name, list, checked = []) => html`<div class="chips-pick">${list.map((x) => html`<label><input type="checkbox" name="${name}" value="${x}" ${checked.includes(x) ? 'checked' : ''}><span>${x}</span></label>`)}</div>`;

async function eventBrief(ctx) {
  ctx.bar({ title: 'Wedding & event brief', back: '#/c/category/wedding-catering' });
  const m = await meta(); const minDate = inDays(1).slice(0, 10);
  ctx.submit('#f-brief', async (d) => {
    const services = [].concat(d.services || []);
    const milestones = [1, 2, 3].map((i) => ({ title: d['ms' + i], due_date: d['md' + i] })).filter((x) => x.title);
    const r = await api('/requests', { method: 'POST', body: { category_id: d.category, priority: 'normal', locality: d.locality,
      description: `${d.event_type} for ${d.guest_count} guests on ${d.event_date} at ${d.venue || d.locality}. Services needed: ${services.join(', ') || 'to be discussed'}. ${d.notes || ''}`.trim(),
      budget_min: d.budget_min || undefined, budget_max: d.budget_max || undefined,
      brief: { kind: 'wedding', event_type: d.event_type, event_date: d.event_date, venue: d.venue, guest_count: d.guest_count, services, milestones, notes: d.notes } } });
    toast('Brief submitted. Your agent will source vendors.'); go(`/c/request/${r.id}/submitted`);
  });
  return html`<p class="muted">One brief for planning, catering, decor and more. Your agent coordinates all vendors and you approve every quote.</p>
    <form id="f-brief" novalidate><input type="hidden" name="category" value="wedding-catering">
      <div class="grid g2">${field({ name: 'event_type', label: 'Event type', type: 'select', required: true, options: [['', 'Select'], 'Wedding', 'Engagement', 'Reception', 'Sangeet / Mehendi', 'Baby shower', 'Anniversary', 'Corporate event'] })}${field({ name: 'event_date', label: 'Event date', type: 'date', required: true, min: minDate })}</div>
      ${field({ name: 'venue', label: 'Venue (or "need help finding one")', placeholder: 'e.g. Function hall, Banjara Hills' })}${field({ name: 'locality', label: 'Locality', type: 'select', required: true, options: [['', 'Select'], ...m.localities] })}
      <div class="grid g2">${field({ name: 'guest_count', label: 'Guest count', type: 'number', required: true, min: 1, inputmode: 'numeric' })}<div></div>${field({ name: 'budget_min', label: 'Budget from (₹)', type: 'number', min: 0 })}${field({ name: 'budget_max', label: 'Budget to (₹)', type: 'number', min: 0 })}</div>
      <div class="field"><label>Services you need</label>${chips('services', ['Planning', 'Venue', 'Catering', 'Decor & mandap', 'Photo & video', 'Entertainment', 'Invitations'], ['Planning', 'Catering'])}</div>
      <div class="field"><label>Milestones (optional)</label>${[1, 2, 3].map((i) => html`<div class="grid g2"><input name="ms${i}" placeholder="e.g. Finalise venue" aria-label="Milestone ${i}"><input type="date" name="md${i}" aria-label="Milestone ${i} date"></div><div style="height:6px"></div>`)}</div>
      ${field({ name: 'notes', label: 'Anything else?', type: 'textarea', rows: 3, placeholder: 'Menu preferences, traditions, accessibility needs…' })}
      <div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Submit event brief</button></form>`;
}

async function photoBrief(ctx) {
  ctx.bar({ title: 'Photography brief', back: '#/c/category/photography-videography' });
  const [m, show] = await Promise.all([meta(), api('/showcase?category=photography-videography')]);
  const minDate = inDays(1).slice(0, 10);
  ctx.submit('#f-photo', async (d) => {
    const needs = [].concat(d.needs || []); const deliver = [].concat(d.deliverables || []);
    const r = await api('/requests', { method: 'POST', body: { category_id: 'photography-videography', locality: d.locality, priority: 'normal', budget_min: d.budget_min || undefined, budget_max: d.budget_max || undefined,
      description: `${d.event_type} coverage on ${d.event_date} in ${d.locality}. ${d.coverage_hours} hours, ${d.coverage_style} style. Needs: ${needs.join(', ') || 'photos'}. Deliverables: ${deliver.join(', ') || 'edited photos'}. ${d.notes || ''}`.trim(),
      brief: { kind: 'photography', event_type: d.event_type, event_date: d.event_date, venue: d.venue, guest_count: d.guests || undefined, coverage_hours: d.coverage_hours, coverage_style: d.coverage_style, services: needs, deliverables: deliver, package_pref: d.package_pref, notes: d.notes } } });
    toast('Brief submitted. Your agent will share portfolios and quotes.'); go(`/c/request/${r.id}/submitted`);
  });
  return html`<p class="muted">Tell us about the coverage you need. Compare portfolios below, and approve the quote you like.</p>
    <form id="f-photo" novalidate>
      <div class="grid g2">${field({ name: 'event_type', label: 'Event', type: 'select', required: true, options: [['', 'Select'], 'Wedding', 'Pre-wedding shoot', 'Engagement', 'Reception', 'Birthday', 'Corporate event', 'Baby shower'] })}${field({ name: 'event_date', label: 'Date', type: 'date', required: true, min: minDate })}</div>
      <div class="grid g2">${field({ name: 'locality', label: 'Locality', type: 'select', required: true, options: [['', 'Select'], ...m.localities] })}${field({ name: 'venue', label: 'Venue (optional)' })}</div>
      <div class="grid g2">${field({ name: 'coverage_hours', label: 'Hours of coverage', type: 'number', required: true, min: 1, max: 48, value: 6 })}${field({ name: 'coverage_style', label: 'Style', type: 'select', required: true, options: ['Candid', 'Traditional', 'Candid + traditional', 'Cinematic'] })}</div>
      <div class="field"><label>What do you need?</label>${chips('needs', ['Photos', 'Video', 'Pre-wedding', 'Drone', 'Candid coverage'], ['Photos'])}</div>
      <div class="field"><label>Deliverables</label>${chips('deliverables', ['Edited photos', 'Album', 'Raw files', 'Highlight film', 'Online gallery'], ['Edited photos'])}</div>
      ${field({ name: 'package_pref', label: 'Package preference', type: 'select', options: [['', 'No preference'], 'Essentials', 'Signature', 'Custom'] })}
      <div class="grid g2">${field({ name: 'budget_min', label: 'Budget from (₹)', type: 'number', min: 0 })}${field({ name: 'budget_max', label: 'Budget to (₹)', type: 'number', min: 0 })}</div>
      ${field({ name: 'notes', label: 'Notes', type: 'textarea', rows: 3 })}
      <div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Submit photography brief</button></form>
    ${section('Compare portfolios & packages', show.items.length ? html`<div class="stack">${show.items.map((p) => html`<div class="card"><div class="row">${avatar(p.business_name, p.logo_color, 44)}<div class="grow"><b>${p.business_name}</b><div class="sm muted">${verified('verified')} ${p.rating_avg ? html`${icon('star', 13)} ${p.rating_avg}` : ''} · ${p.jobs_completed} jobs · ${p.locality}</div></div></div>
      ${p.portfolio.length ? html`<div class="row-gap mt">${p.portfolio.slice(0, 4).map((x) => html`<span class="chip" style="background:${x.color};color:var(--ink)">📷 ${x.title}</span>`)}</div>` : ''}
      ${p.packages.length ? html`<div class="mt">${p.packages.map((k) => html`<div class="row between sm" style="padding:4px 0"><span><b>${k.name}</b> <span class="muted">${k.details.coverage || ''}</span></span><b>${inr(k.price)}</b></div>`)}</div>` : html`<p class="muted sm">Packages shared on request.</p>`}</div>`)}</div>` : html`<p class="muted">Your agent will share options after you submit.</p>`)}`;
}

async function legal() {
  return html`<div class="auth"><div class="auth-card"><a class="icon-btn" href="#/c/login" aria-label="Back">${icon('left', 22)}</a><h1>Terms & Privacy</h1>
    <h3 style="margin-top:12px">Terms of use (summary)</h3><p class="muted sm">BUZZER connects you with a dedicated agent and independent, reviewed service providers. Nothing is booked without your explicit approval of a quote. BUZZER does not provide legal, medical or financial advice. Providers shown as “Verified” have had their identity and address documents reviewed.</p>
    <h3>Privacy (summary)</h3><p class="muted sm">We collect only what is needed to handle your requests: your mobile number, name, addresses and request details. They are shared with your agent and, where needed, with the provider doing your job. We never sell your data. You can download your data or request deletion from Profile › Privacy at any time. Communication consents (WhatsApp, offers) are optional and can be changed anytime.</p>
    <p class="muted xs">This is a development summary; final legal text requires legal review before launch.</p></div></div>`;
}

export const routes = [
  ['/c/messages', messages], ['/c/notifications', notifications], ['/c/profile', profile], ['/c/support', support], ['/c/membership', membership],
  ['/c/jobs', jobs], ['/c/job/:id', jobDetail], ['/c/event-brief', eventBrief], ['/c/photo-brief', photoBrief], ['/c/legal', legal, { bare: true, portal: 'customer' }],
];
