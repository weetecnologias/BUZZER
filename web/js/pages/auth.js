// Landing, customer welcome + OTP sign-in, workspace sign-in, partner onboarding.
import { ApiError, api, go, html, icon, meta, portalHome, raw, setSession } from '../core.js';
import { field, note, opts, stepper, toast } from '../ui.js';

const PORTAL_CARDS = [
  ['customer', '/c/welcome', 'phone', 'Customer app', 'Request help, compare quotes, approve and track - all from your phone.'],
  ['partner', '/p/login', 'tool', 'Partner portal', 'Receive leads, send itemised quotes, manage bookings and earnings.'],
  ['agent', '/a/login', 'headset', 'Agent workspace', 'Run your assigned customers, providers, quotes and follow-ups.'],
  ['admin', '/x/login', 'settings', 'Admin console', 'Users, partners, categories, workflows, finance and audit.'],
];

async function landing() {
  const m = await meta();
  return html`<div class="landing"><div class="landing-in">
    <span class="logo"><span class="logo-mark">B</span>BUZZER</span>
    <section class="hero"><h1>You buzz.<br><em>We do.</em></h1><p>One platform for everyday help in ${m.city}: a dedicated agent, verified providers, transparent quotes - and nothing booked without your approval.</p></section>
    <div class="portal-grid">${PORTAL_CARDS.map(([, href, ic, title, text]) => html`<a class="portal" href="#${href}"><span class="p-ic">${icon(ic, 24)}</span><h3>${title}</h3><p>${text}</p></a>`)}</div>
    <div class="landing-foot"><span>${m.env === 'development' ? 'Development build with seeded demo data' : 'BUZZER'}</span><a href="/api/v1/openapi.json" target="_blank" rel="noopener">API reference</a><a href="/api/v1/health" target="_blank" rel="noopener">Health</a></div>
  </div></div>`;
}

// ---------------------------------------------------------------- customer
const SLIDES = [
  ['🧑‍💼', 'Your personal assistant for everything', 'From daily needs to special events, we get it done for you.'],
  ['🎧', 'Save your time', 'A dedicated personal agent handles your requests from start to finish.'],
  ['📍', `Local support across Hyderabad`, 'Verified providers near you. Choose your language and continue.'],
];

async function welcome(ctx) {
  let i = 0;
  const show = () => {
    ctx.view.querySelectorAll('.slide').forEach((s, k) => s.classList.toggle('on', k === i));
    ctx.view.querySelectorAll('.dots i').forEach((d, k) => d.classList.toggle('on', k === i));
    const last = i === SLIDES.length - 1;
    ctx.view.querySelector('[data-next]').textContent = last ? 'Get started' : 'Next';
    ctx.view.querySelector('.lang').hidden = !last;
  };
  ctx.on('click', '[data-next]', () => { if (i < SLIDES.length - 1) { i++; show(); } else { try { localStorage.setItem('buzzer.lang', ctx.view.querySelector('#lang').value); } catch { /* ignore */ } go('/c/login'); } });
  ctx.on('click', '[data-skip]', () => go('/c/login'));
  ctx.after(show);
  return html`<div class="auth"><div class="auth-card"><span class="logo"><span class="logo-mark">B</span>BUZZER</span>
    <div class="slides" aria-live="polite">${SLIDES.map(([e, t, d]) => html`<div class="slide"><div class="art" aria-hidden="true">${e}</div><h1>${t}</h1><p class="muted">${d}</p></div>`)}</div>
    <div class="dots" aria-hidden="true">${SLIDES.map(() => html`<i></i>`)}</div>
    <div class="lang" hidden><div class="field"><label for="lang">Choose your language</label><select id="lang"><option value="en">English</option><option value="hi">हिन्दी (Hindi)</option><option value="te">తెలుగు (Telugu)</option><option value="ur">اردو (Urdu)</option></select></div></div>
    <div class="row between"><button class="btn btn-ghost" data-skip>Skip</button><button class="btn btn-primary" data-next>Next</button></div>
    <p class="muted xs center mt">Already have an account? <a href="#/c/login">Log in</a> · <a href="#/">All workspaces</a></p></div></div>`;
}

async function customerLogin(ctx) {
  const m = await meta();
  const next = ctx.query.next || portalHome.customer;
  let mobile = ''; let terms = false; let timer;
  const $ = (s) => ctx.view.querySelector(s);
  const countdown = () => {
    let s = 30; const btn = $('[data-resend]'); btn.disabled = true; clearInterval(timer);
    timer = setInterval(() => { s--; btn.textContent = s > 0 ? `Resend OTP in ${s}s` : 'Resend OTP'; if (s <= 0) { btn.disabled = false; clearInterval(timer); } }, 1000);
    btn.textContent = 'Resend OTP in 30s';
  };
  const request = async () => {
    const r = await api('/auth/otp/request', { method: 'POST', body: { mobile }, portal: null });
    if (r.is_new && !terms) throw new ApiError(422, 'validation', 'Please accept the Terms and Privacy Policy to create your account.', { accept_terms: 'Please accept to continue.' });
    $('#step1').hidden = true; $('#step2').hidden = false; $('#num').textContent = '+91 ' + mobile.replace('+91', '');
    const dev = $('#dev'); if (r.dev_otp) { dev.hidden = false; dev.querySelector('b').textContent = r.dev_otp; dev.dataset.otp = r.dev_otp; } else dev.hidden = true;
    $('#otp').focus(); countdown();
  };
  ctx.submit('#f-mobile', async (d) => { mobile = d.mobile; terms = d.accept_terms; await request(); });
  ctx.submit('#f-otp', async (d) => {
    const r = await api('/auth/otp/verify', { method: 'POST', body: { mobile, otp: d.otp, accept_terms: terms }, portal: null });
    setSession('customer', { token: r.token, expires_at: r.expires_at, user: r.user });
    toast(r.is_new ? 'Welcome to BUZZER! Your agent is ready.' : 'Welcome back!');
    go(next);
  });
  ctx.on('click', '[data-edit]', () => { $('#step1').hidden = false; $('#step2').hidden = true; clearInterval(timer); $('#f-mobile input[name=mobile]').focus(); });
  ctx.on('click', '[data-resend]', async (e, b) => { b.disabled = true; try { await request(); toast('A new code was sent.'); } catch (err) { toast(err.message, 'error'); b.disabled = false; } });
  ctx.on('click', '[data-fill]', (e, b) => { $('#otp').value = $('#dev').dataset.otp; $('#otp').focus(); });
  ctx.on('click', '[data-demo]', () => { $('#f-mobile input[name=mobile]').value = '98765 43210'; $('#f-mobile input[name=accept_terms]').checked = true; });
  ctx.after(() => { const i = $('#f-mobile input[name=mobile]'); i && i.focus(); });
  return html`<div class="auth"><div class="auth-card">
    <div class="row between"><a class="icon-btn" href="#/c/welcome" aria-label="Back">${icon('left', 22)}</a><span class="logo"><span class="logo-mark">B</span>BUZZER</span><span style="width:40px"></span></div>
    <div id="step1"><h1>Sign up / Log in</h1><p class="muted">Enter your mobile number to continue.</p>
      <form id="f-mobile" novalidate>
        ${field({ name: 'mobile', label: 'Mobile number', type: 'tel', prefix: '+91', placeholder: '98765 43210', required: true, inputmode: 'numeric', auto: 'tel-national', maxlength: 14 })}
        ${field({ name: 'accept_terms', type: 'checkbox', label: raw('I agree to the <a href="#/c/legal" target="_blank">Terms</a> and <a href="#/c/legal" target="_blank">Privacy Policy</a>. New numbers create an account.') })}
        <div class="form-error" role="alert" hidden></div>
        <button class="btn btn-primary btn-block btn-lg" type="submit">Send OTP</button></form>
      ${m.env === 'development' ? html`<details class="demo"><summary>Demo access (development)</summary><button type="button" data-demo>Use demo customer — Rahul Sharma (98765 43210)</button><small>OTPs are shown on screen in development instead of being sent by SMS.</small></details>` : ''}</div>
    <div id="step2" hidden><h1>Enter the code</h1><p class="muted">We sent a 6-digit code to <b id="num"></b>. <button class="btn btn-ghost btn-sm" type="button" data-edit>Edit number</button></p>
      <div class="dev-otp" id="dev" hidden>Development mode - your OTP is <b></b> <button class="btn btn-sm btn-dark" type="button" data-fill>Autofill</button></div>
      <form id="f-otp" novalidate><div class="field"><label for="otp" class="visually-hidden">One-time code</label><input id="otp" name="otp" class="otp-input" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="••••••" aria-describedby="otp-e"><div class="field-error" id="otp-e" data-error-for="otp" role="alert"></div></div>
        <div class="form-error" role="alert" hidden></div><button class="btn btn-primary btn-block btn-lg" type="submit">Verify & continue</button></form>
      <p class="center mt"><button class="btn btn-ghost btn-sm" type="button" data-resend disabled>Resend OTP in 30s</button></p>
      <p class="center muted xs">Trouble signing in? <a href="#/c/support?tab=help">Get help</a></p></div>
  </div></div>`;
}

// ------------------------------------------------------------- workspace sign-in
const DEMO = {
  partner: [['arun@abchome.in', 'ABC Home Services (verified partner + employer)'], ['nisha@lensandlight.in', 'Lens & Light Studios (photography)'], ['mahesh@royalfeast.in', 'Royal Feast Caterers (catering)'], ['rizwan@metrointeriors.in', 'Metro Interiors (verification pending)']],
  agent: [['priya@buzzer.in', 'Priya - agent (Rahul\'s dedicated agent)'], ['ravi@buzzer.in', 'Ravi - agent'], ['neha@buzzer.in', 'Neha - team lead']],
  admin: [['super@buzzer.in', 'Anita - super admin (everything)'], ['ops@buzzer.in', 'Vikram - operations admin'], ['finance@buzzer.in', 'Meghna - finance admin'], ['verify@buzzer.in', 'Imran - verification reviewer']],
};
const TITLE = { partner: ['Partner sign in', 'Secure partner access.'], agent: ['Agent sign in', 'Secure access for assigned customer operations.'], admin: ['Admin sign in', 'Privileged access. Sign-ins and sensitive actions are audited.'] };

const workLogin = (portal) => async (ctx) => {
  const m = await meta();
  const next = ctx.query.next || portalHome[portal];
  ctx.submit('#f-login', async (d) => {
    const r = await api('/auth/login', { method: 'POST', body: { identifier: d.identifier, password: d.password, portal }, portal: null });
    setSession(portal, { token: r.token, expires_at: r.expires_at, user: r.user });
    toast(`Signed in as ${r.user.name}`);
    go(next);
  });
  ctx.on('click', '[data-use]', (e, b) => { ctx.view.querySelector('[name=identifier]').value = b.dataset.use; ctx.view.querySelector('[name=password]').value = 'Buzzer@123'; ctx.view.querySelector('[name=password]').focus(); });
  const [t, sub] = TITLE[portal];
  return html`<div class="auth"><div class="auth-card"><div class="row between"><a class="icon-btn" href="#/" aria-label="All workspaces">${icon('left', 22)}</a><span class="logo"><span class="logo-mark">B</span>BUZZER</span><span style="width:40px"></span></div>
    <h1>${t}</h1><p class="muted">${sub}</p>
    <form id="f-login" novalidate>
      ${field({ name: 'identifier', label: portal === 'partner' ? 'Mobile number or email' : 'Work email', required: true, auto: 'username' })}
      ${field({ name: 'password', label: 'Password', type: 'password', required: true, auto: 'current-password' })}
      <div class="form-error" role="alert" hidden></div>
      <button class="btn btn-primary btn-block btn-lg" type="submit">Sign in</button></form>
    <p class="muted xs center mt">Forgot your password? ${portal === 'partner' ? 'Contact your BUZZER agent.' : 'Ask a super admin to reset it.'}</p>
    ${portal === 'partner' ? html`<p class="center mt">New to BUZZER? <a href="#/p/onboarding"><b>Create a partner account</b></a></p>` : ''}
    ${m.env === 'development' ? html`<details class="demo" open><summary>Demo accounts (development seed data)</summary>${DEMO[portal].map(([e, l]) => html`<button type="button" data-use="${e}">${l}<br><small>${e}</small></button>`)}<small>Password for all demo accounts: <b>Buzzer@123</b></small></details>` : ''}
  </div></div>`;
};

// ------------------------------------------------------------ partner onboarding
async function partnerOnboarding(ctx) {
  const cats = (await api('/categories', { portal: null })).items.filter((c) => c.is_active);
  const m = await meta();
  let step = 0;
  const $ = (s) => ctx.view.querySelector(s);
  const show = () => {
    ctx.view.querySelectorAll('[data-step]').forEach((s) => { s.hidden = Number(s.dataset.step) !== step; });
    $('#stepper').innerHTML = stepper(['Business', 'Details', 'Verify'], step).toString();
    $('[data-prev]').hidden = step === 0; $('[data-next]').hidden = step === 2; $('[type=submit]').hidden = step !== 2;
  };
  ctx.on('click', '[data-next]', () => {
    const inputs = [...ctx.view.querySelectorAll(`[data-step="${step}"] input,[data-step="${step}"] select`)];
    const bad = inputs.find((i) => !i.checkValidity());
    if (bad) { bad.reportValidity(); return; }
    step++; show();
  });
  ctx.on('click', '[data-prev]', () => { step--; show(); });
  ctx.submit('#f-reg', async (d) => {
    const r = await api('/auth/register', { method: 'POST', body: { ...d, consent: d.consent }, portal: null });
    setSession('partner', { token: r.token, expires_at: r.expires_at, user: r.user });
    toast('Account created. Upload your documents to get verified.');
    go('/p/documents');
  });
  ctx.after(show);
  return html`<div class="auth"><div class="auth-card"><div class="row between"><a class="icon-btn" href="#/p/login" aria-label="Back">${icon('left', 22)}</a><span class="logo"><span class="logo-mark">B</span>BUZZER</span><span style="width:40px"></span></div>
    <h1>Create partner account</h1><p class="muted">Grow your business with BUZZER leads in Hyderabad.</p><div id="stepper"></div>
    <form id="f-reg" novalidate>
      <div data-step="0"><div class="field"><label>Business type</label><div class="radio-cards"><label><input type="radio" name="type" value="individual"> <span><b>Individual / freelancer</b><br><small class="muted">Plumber, electrician, driver…</small></span></label><label><input type="radio" name="type" value="business" checked> <span><b>Business / company</b><br><small class="muted">Registered firm or agency</small></span></label></div></div>
        ${field({ name: 'business_name', label: 'Business name', required: true, placeholder: 'e.g. ABC Home Services' })}
        ${field({ name: 'category_id', label: 'Main service category', type: 'select', required: true, options: [['', 'Select a category'], ...cats.map((c) => [c.id, c.name])] })}</div>
      <div data-step="1" hidden>${field({ name: 'name', label: 'Your name', required: true, auto: 'name' })}${field({ name: 'mobile', label: 'Mobile number', type: 'tel', prefix: '+91', required: true, inputmode: 'numeric' })}
        ${field({ name: 'email', label: 'Email (optional)', type: 'email', auto: 'email' })}${field({ name: 'locality', label: 'Main locality', type: 'select', options: [['', 'Select'], ...m.localities] })}
        ${field({ name: 'password', label: 'Create a password', type: 'password', required: true, hint: 'At least 8 characters with letters and numbers.', auto: 'new-password' })}</div>
      <div data-step="2" hidden>${note('Next you will upload ID proof and business address proof. Until your business is verified you will not be shown as verified to customers.')}<div class="mt">${field({ name: 'consent', type: 'checkbox', label: 'I consent to BUZZER verifying my business and documents, and to the Terms and Privacy Policy.' })}</div></div>
      <div class="form-error" role="alert" hidden></div>
      <div class="row between mt"><button class="btn btn-ghost" type="button" data-prev>Back</button><button class="btn btn-primary" type="button" data-next>Continue</button><button class="btn btn-primary" type="submit" hidden>Create account</button></div>
    </form></div></div>`;
}

export const routes = [
  ['/', landing, { bare: true }],
  ['/c/welcome', welcome, { bare: true, portal: 'customer', redirectIfAuthed: true }],
  ['/c/login', customerLogin, { bare: true, portal: 'customer', redirectIfAuthed: true }],
  ['/p/login', workLogin('partner'), { bare: true, portal: 'partner', redirectIfAuthed: true }],
  ['/a/login', workLogin('agent'), { bare: true, portal: 'agent', redirectIfAuthed: true }],
  ['/x/login', workLogin('admin'), { bare: true, portal: 'admin', redirectIfAuthed: true }],
  ['/p/onboarding', partnerOnboarding, { bare: true, portal: 'partner', redirectIfAuthed: true }],
];
