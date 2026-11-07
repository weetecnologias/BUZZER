# BUZZER Admin Panel

Next.js 16 admin web panel. It is a backend-for-frontend: the browser talks only to `/api/*` route handlers here,
which hold the session in httpOnly cookies and proxy to the BUZZER backend (`BUZZER_BACKEND_URL`).

```bash
npm install
npm run dev     # http://localhost:3000 (backend must be running on :8080)
npm run build
```

Sign in with a staff phone number (dev super admin: 90000 00001; the OTP is shown in dev mode only).
