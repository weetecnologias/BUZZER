"use client";

import Link from "next/link";
import { useApi } from "@/lib/api";
import { Card, ErrorBox, Loading, money, PageHeader, Stat, Status } from "@/components/ui";

type Dash = {
  totals: Record<string, number>;
  operations: Record<string, number>;
  finance: Record<string, number>;
  partners: Record<string, number>;
  exceptions: Record<string, number>;
  status_breakdown: { status: string; count: number }[];
  requests_trend: { day: string; count: number }[];
  agent_workload: { id: string; display_name: string; availability: string; portfolio: number; open_requests: number; max_customers: number }[];
};

export default function Dashboard() {
  const { data: d, error, loading, reload } = useApi<Dash>("admin/dashboard");
  if (loading && !d) return <Loading />;
  if (error || !d) return <ErrorBox message={error ?? "Failed"} onRetry={reload} />;
  const max = Math.max(1, ...d.requests_trend.map((t) => Number(t.count)));
  return (
    <div className="stack">
      <PageHeader title="Platform overview" subtitle="Live operational data" actions={<button className="btn btn-outline" onClick={reload}>Refresh</button>} />
      <div className="grid grid-4">
        <Stat label="Customers" value={d.totals.customers} />
        <Stat label="Partners (verified)" value={`${d.totals.partners} (${d.totals.verified_partners})`} />
        <Stat label="Agents" value={d.totals.agents} />
        <Stat label="Requests" value={d.totals.requests} />
        <Stat label="Gross collected" value={money(d.totals.gross_collected)} />
        <Stat label="Commission earned" value={money(d.totals.commission_earned)} />
      </div>
      <h2 style={{ fontSize: 15 }}>Needs attention</h2>
      <div className="grid grid-4">
        <Stat label="Pending requests" value={d.operations.pending_requests} />
        <Stat label="SLA breaches" value={d.operations.sla_breaches} alert={d.operations.sla_breaches > 0} />
        <Stat label="Delayed quotations (>4h)" value={d.operations.delayed_quotations} alert={d.operations.delayed_quotations > 0} />
        <Stat label="Unaccepted assignments (>1h)" value={d.operations.unaccepted_assignments} alert={d.operations.unaccepted_assignments > 0} />
        <Stat label="Need reassignment" value={d.operations.needing_reassignment} alert={d.operations.needing_reassignment > 0} />
        <Stat label="Open exceptions" value={d.exceptions.open_total} alert={d.exceptions.open_total > 0} />
        <Stat label="Failed payments (7d)" value={d.finance.failed_payments_7d} alert={d.finance.failed_payments_7d > 0} />
        <Stat label="Pending refunds" value={d.finance.pending_refunds} />
        <Stat label="Pending payouts" value={`${d.finance.pending_payouts} • ${money(d.finance.pending_payout_amount)}`} />
        <Stat label="Pending partner registrations" value={d.partners.pending_registrations} />
        <Stat label="Pending KYC" value={d.partners.pending_kyc} />
        <Stat label="Pending service approvals" value={d.partners.pending_capabilities} />
      </div>
      <div className="grid grid-2">
        <Card title="Requests — last 30 days">
          <div className="bars" aria-label="Requests per day chart">
            {d.requests_trend.map((t) => <div key={t.day} title={`${t.day}: ${t.count}`} style={{ height: `${(Number(t.count) / max) * 100}%` }} />)}
          </div>
          <div className="spread small muted" style={{ marginTop: 6 }}><span>{d.requests_trend[0]?.day}</span><span>{d.requests_trend.at(-1)?.day}</span></div>
        </Card>
        <Card title="Requests by status">
          <table><tbody>
            {d.status_breakdown.map((s) => (
              <tr key={s.status}><td><Status value={s.status} /></td><td style={{ textAlign: "right", fontWeight: 700 }}>{s.count}</td></tr>
            ))}
          </tbody></table>
        </Card>
      </div>
      <Card title="Agent workload" actions={<Link href="/agents">Manage agents →</Link>}>
        <div className="table-wrap"><table>
          <thead><tr><th>Agent</th><th>Availability</th><th>Portfolio</th><th>Open requests</th><th>Capacity</th></tr></thead>
          <tbody>
            {d.agent_workload.map((a) => (
              <tr key={a.id}><td>{a.display_name}</td><td><Status value={a.availability} /></td><td>{a.portfolio}</td><td>{a.open_requests}</td>
                <td>{Math.round((a.portfolio / a.max_customers) * 100)}%</td></tr>
            ))}
          </tbody>
        </table></div>
      </Card>
    </div>
  );
}
