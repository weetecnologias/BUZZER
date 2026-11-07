"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Loading, PageHeader, Status, when } from "@/components/ui";

const STATUSES = ["", "REQUEST_CREATED", "AGENT_REVIEW", "PARTNER_QUOTATION", "CUSTOMER_APPROVAL", "PAYMENT_PENDING", "PARTNER_ASSIGNED",
  "PARTNER_CONFIRMED", "SERVICE_STARTED", "COMPLETION_PENDING", "SETTLEMENT_PENDING", "SETTLED", "PARTNER_DECLINED", "CANCELLED", "REFUND_PENDING"];

export default function Requests() {
  const router = useRouter();
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  if (q.trim()) params.set("q", q.trim());
  const { data, error, loading, reload } = useApi<any[]>(`admin/requests?${params}`, [status, q]);

  return (
    <div className="stack">
      <PageHeader title="Requests" subtitle="Monitor every request across the platform"
        actions={<ExportMenu name="requests" title="Requests" rows={data} columns={[{ key: "request_number", label: "Request" }, { key: "category_name", label: "Category" }, { key: "subcategory_name", label: "Subcategory" }, { key: "service_name", label: "Service" }, { key: "customer_name", label: "Customer" }, { key: "agent_name", label: "Agent" }, { key: "status", label: "Status" }, { key: "booking_mode", label: "Booking mode" }, { key: "handling_mode", label: "Handling" }, { key: "estimated_total", label: "Estimated total" }, { key: "quotation_count", label: "Quotes" }, { key: "created_at", label: "Created" }]} />} />
      <div className="row">
        <select value={status} onChange={(e) => setStatus(e.target.value)} style={{ width: 240 }} aria-label="Filter by status">
          {STATUSES.map((s) => <option key={s} value={s}>{s ? s.replace(/_/g, " ") : "All statuses"}</option>)}
        </select>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search request ID or customer" style={{ width: 300 }} aria-label="Search" />
      </div>
      {loading && !data ? <Loading /> : error ? <ErrorBox message={error} onRetry={reload} /> : !data?.length ? <Empty title="No requests" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Request</th><th>Service</th><th>Customer</th><th>Agent / handling</th><th>Status</th><th>Quotes</th><th>Created</th><th>SLA</th></tr></thead>
            <tbody>
              {data.map((r) => {
                const breached = !r.first_agent_response_at && r.sla_due_at && new Date(r.sla_due_at) < new Date() && r.status === "REQUEST_CREATED";
                return (
                  <tr key={r.id} className="clickable" onClick={() => router.push(`/requests/${r.id}`)}>
                    <td className="mono">{r.request_number}</td>
                    <td>{r.service_name || r.subcategory_name || r.category_name}<div className="muted small">{r.category_name}</div></td>
                    <td>{r.customer_name ?? "—"}<div className="muted small">{r.locality_name ?? ""}</div></td>
                    <td>{r.agent_name ?? <span className="muted">{r.handling_mode === "AUTOMATED" ? "No agent needed" : "Unassigned"}</span>}{r.handling_mode === "AUTOMATED" ? <div><span className="badge tone-info">🤖 Automated</span></div> : r.handed_to_agent_at ? <div className="muted small">handed over: {r.handover_reason}</div> : null}</td>
                    <td><Status value={r.status} />{r.escalated_at ? <div><Status value="ESCALATED" /></div> : null}</td>
                    <td>{r.quotation_count}</td>
                    <td>{when(r.created_at)}</td>
                    <td>{breached ? <Status value="BREACHED" /> : r.first_agent_response_at ? <span className="muted small">responded</span> : when(r.sla_due_at)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
