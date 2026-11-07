"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Field, Loading, Modal, money, Notice, PageHeader, Status, when } from "@/components/ui";
import { ExportMenu } from "@/components/export";
import { ExcelImport } from "@/components/workflow";

const TABS = ["Commission rules", "Payouts", "Refunds", "Payments", "Reports"] as const;

export default function Finance() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Commission rules");
  return (
    <div className="stack">
      <PageHeader title="Finance" subtitle="Commission rules, ledger-backed payouts, refunds and reports" />
      <Notice tone="warning">Payments run through the TEST MODE gateway until real merchant credentials are configured. Tax (GST/TDS) treatment must be reviewed by qualified professionals.</Notice>
      <div className="row">{TABS.map((t) => <button key={t} className={`btn ${tab === t ? "btn-primary" : "btn-outline"} btn-sm`} onClick={() => setTab(t)}>{t}</button>)}</div>
      {tab === "Commission rules" && <Rules />}
      {tab === "Payouts" && <Payouts />}
      {tab === "Refunds" && <Refunds />}
      {tab === "Payments" && <Payments />}
      {tab === "Reports" && <Reports />}
    </div>
  );
}

function Rules() {
  const rules = useApi<any[]>("admin/finance/commission-rules");
  const tree = useApi<any[]>("admin/catalogue/tree");
  const m = useMutation();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ scope_type: "CATEGORY", scope_id: "", rate_percent: "5", effective_from: "", effective_to: "" });
  const [excel, setExcel] = useState(false);
  const create = async () => {
    const body = { scope_type: f.scope_type, scope_id: f.scope_type === "DEFAULT" ? null : f.scope_id || null, rate_percent: Number(f.rate_percent),
      effective_from: f.effective_from || null, effective_to: f.effective_to || null };
    if (await m.run(() => api("admin/finance/commission-rules", { method: "POST", body }))) {
      setOpen(false);
      rules.reload();
    }
  };
  return (
    <Card title="Commission rules" actions={<div className="row">
      <ExportMenu name="commission-rules" title="Commission rules" rows={rules.data} columns={[{ key: "scope_type", label: "Scope" }, { key: "scope_name", label: "Applies to" }, { key: "city_name", label: "City" }, { key: "rate_percent", label: "Rate %" }, { key: "effective_from", label: "From" }, { key: "effective_to", label: "To" }, { key: "active", label: "Active" }]} />
      <button className={`btn btn-sm ${excel ? "btn-secondary" : "btn-outline"}`} onClick={() => setExcel(!excel)}>📊 Excel upload</button>
      <button className="btn btn-primary btn-sm" onClick={() => setOpen(true)}>+ New rule</button></div>}>
      {excel ? (
        <div className="stack" style={{ marginBottom: 12 }}>
          <ExcelImport title="Commission rates for every category, subcategory and item"
            templates={[{ label: "Download rate sheet (.xlsx)", href: "/api/backend/admin/finance/commission/excel" }]}
            previewPath="admin/finance/commission/excel/preview" importPath="admin/finance/commission/excel/import"
            describe={(r: any) => `${r.scope_type} • ${r.name ?? ""}${r.new_rate_percent != null ? ` → ${r.new_rate_percent}%` : ""}`} onDone={rules.reload} />
          <p className="muted small">The sheet lists the default rate and every category, subcategory and menu item with its current effective rate. Type a new rate in <b>new_rate_percent</b> (and optionally <b>effective_from</b>, default today) only on the rows you want to change; leave the rest empty. Each change creates a new dated rule and closes the previous one, so history is kept for audits.</p>
        </div>
      ) : null}
      <p className="muted small">Precedence: Partner › Service › Subcategory › Category › Default. City-specific beats global; the latest effective date wins. Rates are examples, fully configurable.</p>
      {rules.loading && !rules.data ? <Loading /> : rules.error ? <ErrorBox message={rules.error} /> : (
        <table><thead><tr><th>Scope</th><th>Applies to</th><th>City</th><th>Rate</th><th>Effective</th><th>Status</th><th /></tr></thead>
          <tbody>{(rules.data ?? []).map((r) => (
            <tr key={r.id}><td>{r.scope_type}</td><td>{r.scope_name}</td><td>{r.city_name ?? "All"}</td><td><b>{r.rate_percent}%</b></td>
              <td>{r.effective_from}{r.effective_to ? ` → ${r.effective_to}` : ""}</td><td><Status value={r.active ? "ACTIVE" : "INACTIVE"} /></td>
              <td>{r.active ? <button className="btn btn-ghost btn-sm" onClick={async () => { if (await m.run(() => api(`admin/finance/commission-rules/${r.id}/deactivate`, { method: "POST", body: {} }))) rules.reload(); }}>Deactivate</button> : null}</td></tr>
          ))}</tbody></table>
      )}
      <Modal title="New commission rule" open={open} onClose={() => setOpen(false)} footer={<button className="btn btn-primary" onClick={create} disabled={m.busy}>Create</button>}>
        <Field label="Scope"><select value={f.scope_type} onChange={(e) => setF({ ...f, scope_type: e.target.value, scope_id: "" })}>
          {["CATEGORY", "SUBCATEGORY", "SERVICE", "PARTNER", "DEFAULT"].map((s) => <option key={s}>{s}</option>)}
        </select></Field>
        {f.scope_type === "CATEGORY" ? (
          <Field label="Category"><select value={f.scope_id} onChange={(e) => setF({ ...f, scope_id: e.target.value })}><option value="">Select…</option>{(tree.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
        ) : f.scope_type !== "DEFAULT" ? (
          <Field label={`${f.scope_type.toLowerCase()} id`}><input value={f.scope_id} onChange={(e) => setF({ ...f, scope_id: e.target.value })} placeholder="UUID (from catalogue / partner page)" /></Field>
        ) : null}
        <Field label="Rate %"><input value={f.rate_percent} onChange={(e) => setF({ ...f, rate_percent: e.target.value })} inputMode="decimal" /></Field>
        <div className="row"><Field label="Effective from"><input type="date" value={f.effective_from} onChange={(e) => setF({ ...f, effective_from: e.target.value })} /></Field>
          <Field label="Effective to"><input type="date" value={f.effective_to} onChange={(e) => setF({ ...f, effective_to: e.target.value })} /></Field></div>
        {m.error ? <ErrorBox message={m.error} /> : null}
      </Modal>
    </Card>
  );
}

function Payouts() {
  const list = useApi<any[]>("admin/finance/payouts");
  const m = useMutation();
  const [target, setTarget] = useState<any>(null);
  const [ref, setRef] = useState("");
  return (
    <Card title="Partner payouts (target T+1, subject to bank processing)">
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} /> : (
        <table><thead><tr><th>Payout</th><th>Partner</th><th>Request</th><th>Amount</th><th>Due</th><th>Status</th><th>Reference</th><th /></tr></thead>
          <tbody>{(list.data ?? []).map((p) => (
            <tr key={p.id}><td className="mono">{p.payout_number}</td><td>{p.partner_name}</td><td className="mono">{p.request_number}</td><td>{money(p.amount)}</td><td>{p.due_on}</td>
              <td><Status value={p.status} /></td><td>{p.reference ?? "—"}</td>
              <td>{p.status !== "PAID" ? <button className="btn btn-secondary btn-sm" onClick={() => { setTarget(p); setRef(""); }}>Mark paid</button> : when(p.paid_at)}</td></tr>
          ))}</tbody></table>
      )}
      <Modal title={`Mark ${target?.payout_number} paid`} open={!!target} onClose={() => setTarget(null)}
        footer={<button className="btn btn-primary" disabled={m.busy || ref.trim().length < 4} onClick={async () => { if (await m.run(() => api(`admin/finance/payouts/${target.id}/paid`, { method: "POST", body: { bank_reference: ref } }))) { setTarget(null); list.reload(); } }}>Confirm</button>}>
        <p>Record the bank transfer reference (UTR) for {money(target?.amount)} to {target?.partner_name}.</p>
        <Field label="Bank reference / UTR"><input value={ref} onChange={(e) => setRef(e.target.value)} /></Field>
        {m.error ? <ErrorBox message={m.error} /> : null}
      </Modal>
    </Card>
  );
}

function Refunds() {
  const list = useApi<any[]>("admin/finance/refunds");
  const m = useMutation();
  return (
    <Card title="Refunds (reviewed individually — never blanket auto-refunds)">
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : (
        <table><thead><tr><th>Refund</th><th>Request</th><th>Payment</th><th>Amount</th><th>Reason</th><th>Status</th><th /></tr></thead>
          <tbody>{(list.data ?? []).map((r) => (
            <tr key={r.id}><td className="mono">{r.refund_number}</td><td className="mono">{r.request_number}</td><td className="mono">{r.payment_number}{r.test_mode ? " (TEST)" : ""}</td>
              <td>{money(r.amount)}</td><td>{r.reason}</td><td><Status value={r.status} /></td>
              <td>{r.status === "PENDING" ? <button className="btn btn-secondary btn-sm" disabled={m.busy} onClick={async () => { if (await m.run(() => api(`admin/finance/refunds/${r.id}/approve`, { method: "POST", body: {} }))) list.reload(); }}>Approve & refund</button> : null}</td></tr>
          ))}</tbody></table>
      )}
    </Card>
  );
}

function Payments() {
  const list = useApi<any[]>("admin/finance/payments");
  return (
    <Card title="Payments">
      {list.loading && !list.data ? <Loading /> : (
        <table><thead><tr><th>Payment</th><th>Request</th><th>Payer</th><th>Method</th><th>Amount</th><th>Status</th><th>Created</th></tr></thead>
          <tbody>{(list.data ?? []).map((p) => (
            <tr key={p.id}><td className="mono">{p.payment_number}{p.test_mode ? <span className="badge tone-warning" style={{ marginLeft: 6 }}>TEST</span> : null}</td><td className="mono">{p.request_number}</td>
              <td>{p.payer_name}</td><td>{p.method}</td><td>{money(p.amount)}</td><td><Status value={p.status} />{p.failure_reason ? <div className="small muted">{p.failure_reason}</div> : null}</td><td>{when(p.created_at)}</td></tr>
          ))}</tbody></table>
      )}
    </Card>
  );
}

function Reports() {
  const r = useApi<any[]>("admin/finance/reports/category-performance");
  return (
    <Card title="Category performance" actions={<a className="btn btn-outline btn-sm" href="/api/backend/admin/finance/reports/category-performance.csv">Download CSV</a>}>
      {r.loading && !r.data ? <Loading /> : (
        <table><thead><tr><th>Category</th><th>Requests</th><th>Completed</th><th>Gross value</th><th>Commission</th></tr></thead>
          <tbody>{(r.data ?? []).map((x) => <tr key={x.category_name}><td>{x.category_name}</td><td>{x.requests}</td><td>{x.completed}</td><td>{money(x.gross_value)}</td><td>{money(x.commission)}</td></tr>)}</tbody></table>
      )}
    </Card>
  );
}
