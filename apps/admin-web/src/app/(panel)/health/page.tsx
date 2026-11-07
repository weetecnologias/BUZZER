"use client";

import { useEffect } from "react";
import { useApi } from "@/lib/api";
import { Card, ErrorBox, Loading, PageHeader, Status, when } from "@/components/ui";

const COLORS: Record<string, string> = { HEALTHY: "var(--secondary)", DEGRADED: "var(--warning)", UNAVAILABLE: "var(--danger)", UNKNOWN: "#9aa59e" };

export default function Health() {
  const h = useApi<any>("admin/system-health");
  useEffect(() => {
    const t = setInterval(h.reload, 30_000);
    return () => clearInterval(t);
  }, [h.reload]);
  if (h.loading && !h.data) return <Loading />;
  if (h.error || !h.data) return <ErrorBox message={h.error ?? "Health check failed"} onRetry={h.reload} />;
  const groups: Record<string, any[]> = {};
  for (const c of h.data.components) (groups[c.group] ??= []).push(c);
  return (
    <div className="stack">
      <PageHeader title="System health" subtitle={`Checked ${when(h.data.checked_at)} • refreshes every 30s`} actions={<button className="btn btn-outline" onClick={h.reload}>Refresh</button>} />
      <p className="muted small">UNKNOWN means the component is not provisioned or not observable from this environment — it is never shown as healthy by assumption.</p>
      <div className="grid grid-2">
        {Object.entries(groups).map(([g, items]) => (
          <Card key={g} title={g}>
            <table><tbody>{items.map((c) => (
              <tr key={c.name}>
                <td style={{ width: 18 }}><span className="health-dot" style={{ background: COLORS[c.status] }} aria-hidden /></td>
                <td><b>{c.name}</b><div className="muted small">{c.detail}</div></td>
                <td style={{ textAlign: "right" }}><Status value={c.status} /></td>
              </tr>
            ))}</tbody></table>
          </Card>
        ))}
      </div>
      <Card title="Recent incidents">
        {!h.data.incidents.length ? <div className="muted">No incidents recorded.</div> : (
          <table><thead><tr><th>When</th><th>Type</th><th>Priority</th><th>Issue</th><th>Status</th></tr></thead>
            <tbody>{h.data.incidents.map((i: any, n: number) => <tr key={n}><td>{when(i.created_at)}</td><td>{i.exception_type}</td><td><Status value={i.priority} /></td><td>{i.title}</td><td><Status value={i.status} /></td></tr>)}</tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
