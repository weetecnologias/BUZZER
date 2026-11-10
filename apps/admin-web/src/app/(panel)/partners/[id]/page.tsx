"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Field, Loading, Modal, Notice, PageHeader, Status, when } from "@/components/ui";

type Decision = { kind: "registration" | "kyc" | "capability"; id: string; decision: string; label: string };

export default function PartnerDetail() {
  const { id } = useParams<{ id: string }>();
  const { data: d, error, loading, reload } = useApi<any>(`admin/partners/${id}`, [id]);
  const schedule = useApi<any>(`admin/partners/${id}/schedule`, [id]);
  const m = useMutation();
  const [pending, setPending] = useState<Decision | null>(null);
  const [note, setNote] = useState("");

  const decide = async () => {
    if (!pending) return;
    const path = pending.kind === "capability" ? `admin/partner-capabilities/${pending.id}` : `admin/partners/${id}/${pending.kind}`;
    if (await m.run(() => api(path, { method: "POST", body: { decision: pending.decision, note: note || null } }))) {
      setPending(null);
      setNote("");
      reload();
    }
  };

  if (loading && !d) return <Loading />;
  if (error || !d) return <ErrorBox message={error ?? "Not found"} onRetry={reload} />;
  const p = d.partner;
  return (
    <div className="stack">
      <PageHeader title={p.display_name} subtitle={`${p.partner_type}${p.business_name ? " • " + p.business_name : ""} • ${p.city_name}`}
        actions={<Link className="btn btn-outline" href="/partners">Back</Link>} />
      {m.error ? <ErrorBox message={m.error} /> : null}
      <div className="grid grid-2">
        <Card title="Registration">
          <div className="spread"><Status value={p.registration_status} /><span className="muted small">since {when(p.created_at)}</span></div>
          <table style={{ marginTop: 10 }}><tbody>
            <tr><td className="muted">Phone</td><td>{p.contact_phone}</td></tr>
            <tr><td className="muted">Email</td><td>{p.email ?? "—"}</td></tr>
            <tr><td className="muted">Address</td><td>{p.address ?? "—"}</td></tr>
            <tr><td className="muted">Service areas</td><td>{d.service_areas.length ? d.service_areas.map((a: any) => a.name).join(", ") : "Whole city"}</td></tr>
            <tr><td className="muted">Rating</td><td>{Number(p.rating_count) ? `★ ${p.rating_avg} (${p.rating_count})` : "—"}</td></tr>
          </tbody></table>
          <div className="row" style={{ marginTop: 12 }}>
            <button className="btn btn-primary btn-sm" onClick={() => setPending({ kind: "registration", id, decision: "APPROVED", label: "Approve registration" })}>Approve</button>
            <button className="btn btn-outline btn-sm" onClick={() => setPending({ kind: "registration", id, decision: "REJECTED", label: "Reject registration" })}>Reject</button>
            <button className="btn btn-danger btn-sm" onClick={() => setPending({ kind: "registration", id, decision: "SUSPENDED", label: "Suspend partner" })}>Suspend</button>
          </div>
        </Card>
        <Card title="KYC verification">
          <Status value={p.kyc_status} />
          <table style={{ marginTop: 10 }}><thead><tr><th>Document</th><th>Number</th><th>Status</th><th>Submitted</th></tr></thead>
            <tbody>{d.kyc_documents.map((k: any) => <tr key={k.id}><td>{k.doc_type}</td><td className="mono">{k.doc_number_masked}</td><td><Status value={k.status} /></td><td>{when(k.created_at)}</td></tr>)}</tbody>
          </table>
          {!d.kyc_documents.length ? <Notice tone="warning">No KYC documents submitted yet.</Notice> : null}
          <div className="row" style={{ marginTop: 12 }}>
            <button className="btn btn-primary btn-sm" disabled={!d.kyc_documents.length} onClick={() => setPending({ kind: "kyc", id, decision: "VERIFIED", label: "Mark KYC verified" })}>Verify KYC</button>
            <button className="btn btn-outline btn-sm" onClick={() => setPending({ kind: "kyc", id, decision: "REJECTED", label: "Reject KYC" })}>Reject KYC</button>
          </div>
        </Card>
      </div>
      <Card title="Working hours & upcoming jobs (instant booking)">
        {!schedule.data ? <Loading /> : (
          <div className="grid grid-2">
            <div>
              {schedule.data.using_defaults ? <p className="muted small">Not set by the partner - Mon-Sat 09:00-19:00 assumed.</p> : null}
              <table><tbody>{["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((n, i) => {
                const w = (schedule.data.weekly ?? []).filter((x: any) => x.weekday === i + 1);
                return <tr key={n}><td className="muted">{n}</td><td>{w.length ? w.map((x: any) => `${x.start_time}-${x.end_time}`).join(", ") : "Off"}</td></tr>;
              })}</tbody></table>
              {(schedule.data.time_off ?? []).length ? <p className="small">Days off: {schedule.data.time_off.map((t: any) => t.starts_on === t.ends_on ? t.starts_on : `${t.starts_on} to ${t.ends_on}`).join("; ")}</p> : null}
            </div>
            <div>
              {(schedule.data.upcoming_jobs ?? []).length ? (
                <table><thead><tr><th>When</th><th>Request</th><th>Status</th></tr></thead><tbody>{schedule.data.upcoming_jobs.map((j: any) => (
                  <tr key={j.assignment_id}><td>{j.starts_at ? when(j.starts_at) : "—"}</td><td><Link href={`/requests/${j.request_id}`}>{j.request_number}</Link></td><td><Status value={j.status} /></td></tr>
                ))}</tbody></table>
              ) : <p className="muted small">No upcoming jobs.</p>}
            </div>
          </div>
        )}
      </Card>
      <Card title="Service capabilities (each requires approval)">
        <table><thead><tr><th>Category</th><th>Subcategory</th><th>Service</th><th>Status</th><th>Requested</th><th /></tr></thead>
          <tbody>{d.capabilities.map((c: any) => (
            <tr key={c.id}><td>{c.category_name}</td><td>{c.subcategory_name}</td><td>{c.service_name ?? "All in subcategory"}</td><td><Status value={c.status} /></td><td>{when(c.created_at)}</td>
              <td className="row">
                {c.status !== "APPROVED" ? <button className="btn btn-secondary btn-sm" onClick={() => setPending({ kind: "capability", id: c.id, decision: "APPROVED", label: `Approve ${c.subcategory_name}` })}>Approve</button> : null}
                {c.status === "REQUESTED" ? <button className="btn btn-ghost btn-sm" onClick={() => setPending({ kind: "capability", id: c.id, decision: "REJECTED", label: `Reject ${c.subcategory_name}` })}>Reject</button> : null}
                {c.status === "APPROVED" ? <button className="btn btn-ghost btn-sm" onClick={() => setPending({ kind: "capability", id: c.id, decision: "REVOKED", label: `Revoke ${c.subcategory_name}` })}>Revoke</button> : null}
              </td></tr>
          ))}</tbody>
        </table>
      </Card>
      <Modal title={pending?.label ?? ""} open={!!pending} onClose={() => setPending(null)}
        footer={<button className={`btn ${pending?.decision === "APPROVED" || pending?.decision === "VERIFIED" ? "btn-primary" : "btn-danger"}`} disabled={m.busy} onClick={decide}>Confirm</button>}>
        <Field label="Note to partner (recorded in audit log)"><textarea value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      </Modal>
    </div>
  );
}
