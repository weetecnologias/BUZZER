"use client";

import { ReactNode } from "react";

export type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const SUCCESS = ["COMPLETED", "SETTLEMENT_PENDING", "SETTLED", "PAYMENT_CONFIRMED", "APPROVED", "VERIFIED", "SUCCEEDED", "PAID", "AVAILABLE", "ACCEPTED", "CONFIRMED", "RESOLVED", "HEALTHY", "ACTIVE", "SENT"];
const DANGER = ["CANCELLED", "REJECTED", "EXPIRED", "NO_SHOW", "FAILED", "DISPUTED", "DECLINED", "SUSPENDED", "UNAVAILABLE", "ON_LEAVE", "URGENT", "DEAD", "REVOKED"];
const WARNING = ["PAYMENT_PENDING", "CUSTOMER_APPROVAL", "COMPLETION_PENDING", "REFUND_PENDING", "PARTNER_DECLINED", "PENDING", "SUBMITTED", "REQUESTED", "INVITED", "BUSY", "OPEN", "HIGH", "DEGRADED", "RESCHEDULING_REQUESTED", "COLLECT_ON_DELIVERY"];

export function toneOf(s: string): Tone {
  if (SUCCESS.includes(s)) return "success";
  if (DANGER.includes(s)) return "danger";
  if (WARNING.includes(s)) return "warning";
  if (["REQUEST_CREATED", "AGENT_REVIEW", "PARTNER_QUOTATION", "ACKNOWLEDGED", "NORMAL"].includes(s)) return "info";
  return "neutral";
}

export function Badge({ children, tone }: { children: ReactNode; tone?: Tone }) {
  return <span className={`badge tone-${tone ?? "neutral"}`}>{children}</span>;
}

export function Status({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>;
  return <Badge tone={toneOf(value)}>{value.replace(/_/g, " ")}</Badge>;
}

export function Card({ title, children, actions }: { title?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      {title || actions ? (
        <div className="spread" style={{ marginBottom: 12 }}>
          {title ? <h2 style={{ margin: 0 }}>{title}</h2> : <span />}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}

export function Stat({ label, value, alert }: { label: string; value: ReactNode; alert?: boolean }) {
  return (
    <div className={`card stat ${alert ? "alert" : ""}`}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="topbar">
      <div>
        <h1>{title}</h1>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
      {actions ? <div className="row">{actions}</div> : null}
    </div>
  );
}

export function Notice({ children, tone = "info" }: { children: ReactNode; tone?: Tone }) {
  return <div className={`notice tone-${tone}`} role={tone === "danger" ? "alert" : undefined}>{children}</div>;
}

export function Loading() {
  return <div className="muted" style={{ padding: 20 }}>Loading…</div>;
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <Notice tone="danger">
      <div className="spread">
        <span>{message}</span>
        {onRetry ? <button className="btn btn-outline btn-sm" onClick={onRetry}>Retry</button> : null}
      </div>
    </Notice>
  );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div style={{ textAlign: "center", padding: 28 }}>
      <div style={{ fontSize: 28 }}>🌿</div>
      <div style={{ fontWeight: 700 }}>{title}</div>
      {hint ? <div className="muted">{hint}</div> : null}
    </div>
  );
}

export function Modal({ title, open, onClose, children, footer }: { title: string; open: boolean; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  if (!open) return null;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={title} onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        {children}
        <div className="row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="btn btn-outline" onClick={onClose}>Cancel</button>
          {footer}
        </div>
      </div>
    </div>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

export function money(v: unknown) {
  const n = Number(v ?? 0);
  return "₹" + (Number.isNaN(n) ? 0 : n).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

export function when(v: unknown) {
  if (!v) return "—";
  const d = new Date(String(v));
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });
}

export function downloadCsv(filename: string, rows: Record<string, unknown>[]) {
  if (!rows.length) return;
  const cols = Object.keys(rows[0]);
  const esc = (v: unknown) => {
    let s = v == null ? "" : String(v);
    if (/^[=+\-@]/.test(s)) s = "'" + s; // avoid spreadsheet formula injection
    return `"${s.replace(/"/g, '""')}"`;
  };
  const csv = [cols.join(","), ...rows.map((r) => cols.map((c) => esc(r[c])).join(","))].join("\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
  a.download = filename;
  a.click();
}
