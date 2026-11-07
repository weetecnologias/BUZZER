"use client";

import { useState } from "react";

export default function LoginPage() {
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"phone" | "code">("phone");
  const [dev, setDev] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const call = async (url: string, body: object) => {
    const res = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const json = await res.json();
    if (!res.ok) throw new Error(json.error?.message ?? "Request failed");
    return json.data;
  };

  const request = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const d = await call("/api/auth/otp", { phone });
      setDev(d.dev_code ?? null);
      setStep("code");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const verify = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await call("/api/auth/verify", { phone, code });
      window.location.href = "/";
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <div className="card" style={{ width: "min(420px, 100%)", padding: 28 }}>
        <div className="brand" style={{ padding: 0, marginBottom: 18 }}>
          <div className="brand-logo">B</div>
          <div>
            <div className="brand-name" style={{ color: "var(--forest)" }}>BUZZER Admin</div>
            <div className="muted small">Platform operations & control</div>
          </div>
        </div>
        {step === "phone" ? (
          <form onSubmit={request}>
            <label className="field"><span>Registered staff mobile number</span>
              <input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" autoComplete="tel" placeholder="98765 43210" required />
            </label>
            {error ? <div className="notice tone-danger" style={{ marginBottom: 10 }}>{error}</div> : null}
            <button className="btn btn-primary" style={{ width: "100%", justifyContent: "center" }} disabled={busy}>{busy ? "Sending…" : "Send OTP"}</button>
            <p className="muted small" style={{ marginTop: 12 }}>Only staff numbers with an admin, finance, support or manager role can sign in.</p>
          </form>
        ) : (
          <form onSubmit={verify}>
            {dev ? <div className="notice tone-warning" style={{ marginBottom: 10 }}>DEV MODE — OTP {dev} (never shown in production)</div> : null}
            <label className="field"><span>6-digit OTP</span>
              <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} required />
            </label>
            {error ? <div className="notice tone-danger" style={{ marginBottom: 10 }}>{error}</div> : null}
            <button className="btn btn-primary" style={{ width: "100%", justifyContent: "center" }} disabled={busy}>{busy ? "Verifying…" : "Verify & sign in"}</button>
            <button type="button" className="btn btn-ghost" onClick={() => setStep("phone")}>Change number</button>
          </form>
        )}
      </div>
    </div>
  );
}
