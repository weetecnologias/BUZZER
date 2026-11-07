"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { TransferModal, previewSrc } from "@/components/workflow";
import { Card, ErrorBox, Loading, money, PageHeader, Status, when } from "@/components/ui";

export default function RequestDetail() {
  const { id } = useParams<{ id: string }>();
  const { data: d, error, loading, reload } = useApi<any>(`admin/requests/${id}`, [id]);
  const [transfer, setTransfer] = useState(false);
  if (loading && !d) return <Loading />;
  if (error || !d) return <ErrorBox message={error ?? "Not found"} onRetry={reload} />;
  return (
    <div className="stack">
      <PageHeader title={`Request ${d.request_number}`} subtitle={`${d.category_name}${d.subcategory_name ? " › " + d.subcategory_name : ""}${d.service_name ? " › " + d.service_name : ""}`}
        actions={<><Status value={d.status} />{!["SETTLED", "CANCELLED"].includes(d.status) ? <button className="btn btn-primary" onClick={() => setTransfer(true)}>↪ Transfer to agent</button> : null}<Link className="btn btn-outline" href="/requests">Back</Link></>} />
      <TransferModal open={transfer} mode="request" requestId={id} onClose={() => setTransfer(false)} onDone={() => { setTransfer(false); reload(); }} />
      <div className="grid grid-2">
        <Card title="Summary">
          <table><tbody>
            <tr><td className="muted">Type</td><td><b>{d.request_type_label}</b> <span className="muted small">({d.vertical} • lifecycle {d.lifecycle})</span></td></tr>
            <tr><td className="muted">Customer</td><td>{d.customer_name} ({d.customer_phone})</td></tr>
            <tr><td className="muted">Agent</td><td>{d.agent_name ?? "—"}</td></tr>
            <tr><td className="muted">Created</td><td>{when(d.created_at)} via {d.created_via}</td></tr>
            <tr><td className="muted">Consent</td><td>{d.consent_method ? `${d.consent_method} at ${when(d.consent_at)}` : "Customer submitted"}</td></tr>
            <tr><td className="muted">First response</td><td>{d.first_agent_response_at ? when(d.first_agent_response_at) : `due ${when(d.sla_due_at)}`}</td></tr>
            <tr><td className="muted">Escalated</td><td>{d.escalated_at ? when(d.escalated_at) : "No"}</td></tr>
            <tr><td className="muted">Address</td><td>{d.address_snapshot ? [d.address_snapshot.line1, d.address_snapshot.locality_name, d.address_snapshot.pincode].filter(Boolean).join(", ") : "—"}</td></tr>
            {d.cancel_reason ? <tr><td className="muted">Cancel reason</td><td>{d.cancel_reason}</td></tr> : null}
          </tbody></table>
        </Card>
        <Card title="Requirement (dynamic form answers)">
          <table><tbody>
            {Object.entries(d.answers ?? {}).map(([k, v]) => <tr key={k}><td className="muted">{k.replace(/_/g, " ")}</td><td>{Array.isArray(v) ? v.join(", ") : String(v)}</td></tr>)}
          </tbody></table>
        </Card>
      </div>
      <Card title="Quotations">
        <div className="table-wrap"><table>
          <thead><tr><th>Quotation</th><th>Partner</th><th>Scope</th><th>Total</th><th>Status</th><th>Valid until</th></tr></thead>
          <tbody>{(d.quotations ?? []).map((q: any) => (
            <tr key={q.id}><td className="mono">{q.quotation_number}</td><td>{q.partner_name}</td><td>{q.scope_of_work}</td><td>{money(q.total_amount)}</td><td><Status value={q.status} /></td><td>{when(q.valid_until)}</td></tr>
          ))}</tbody>
        </table></div>
      </Card>
      <div className="grid grid-2">
        <Card title="Assignments (one live at a time)">
          <table><tbody>{(d.assignments ?? []).map((a: any) => (
            <tr key={a.id}><td className="mono">{a.assignment_number}</td><td>{a.partner_name}</td><td><Status value={a.status} /></td><td className="small muted">{a.cancel_reason ?? ""}</td></tr>
          ))}</tbody></table>
        </Card>
        <Card title="Payments & ledger">
          <table><tbody>
            {(d.payments ?? []).map((p: any) => <tr key={p.id}><td className="mono">{p.payment_number}</td><td>{p.method}</td><td>{money(p.amount)}</td><td><Status value={p.status} />{p.test_mode ? <span className="badge tone-warning">TEST</span> : null}</td></tr>)}
            {(d.ledger ?? []).map((l: any, i: number) => <tr key={i}><td colSpan={2}>{l.account.replace(/_/g, " ")}</td><td>{l.direction === "DEBIT" ? "−" : "+"}{money(l.amount)}</td><td className="small muted">{l.memo}</td></tr>)}
          </tbody></table>
        </Card>
      </div>
      <Card title={`Attachments (${(d.attachments ?? []).length})`}>
        {(d.attachments ?? []).length ? (
          <table><thead><tr><th>File</th><th>Type</th><th>Visibility</th><th>Uploaded by</th><th>When</th></tr></thead>
            <tbody>{d.attachments.map((a: any) => (
              <tr key={a.id}><td>{a.content_type.startsWith("image/") ? <img className="thumb" src={previewSrc(a.url)} alt="" style={{ marginRight: 8, verticalAlign: "middle" }} /> : "📄 "}<a href={previewSrc(a.url)} target="_blank" rel="noreferrer">{a.file_name}</a></td>
                <td className="small">{a.content_type}</td><td>{a.visibility === "PARTNERS" ? "Shared with quoting partners" : "Private"}</td>
                <td>{a.uploaded_by_name} <span className="muted small">({a.uploader_role})</span></td><td>{when(a.created_at)}</td></tr>
            ))}</tbody></table>
        ) : <span className="muted">No attachments.</span>}
        <p className="muted small">Links are signed and expire after 15 minutes — reload the page for fresh links.</p>
      </Card>
      {d.parent || (d.children ?? []).length ? (
        <Card title="Related requests">
          {d.parent ? <div>↑ Part of <Link href={`/requests/${d.parent.id}`}>{d.parent.request_number}</Link> ({d.parent.request_type})</div> : null}
          {(d.children ?? []).map((ch: any) => <div key={ch.id}>↳ <Link href={`/requests/${ch.id}`}>{ch.request_number}</Link> — {ch.subcategory_name ?? ch.category_name} • <Status value={ch.status} /></div>)}
        </Card>
      ) : null}
      <Card title="Lifecycle & audit trail">
        <table><thead><tr><th>When</th><th>Transition</th><th>Actor</th><th>Reason</th></tr></thead>
          <tbody>{(d.history ?? []).map((h: any, i: number) => (
            <tr key={i}><td>{when(h.created_at)}</td><td>{h.from_status ? `${h.from_status} → ` : ""}<b>{h.to_status}</b></td><td>{h.actor_name ?? h.actor_role}</td><td>{h.reason}</td></tr>
          ))}</tbody>
        </table>
      </Card>
    </div>
  );
}
