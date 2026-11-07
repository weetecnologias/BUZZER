"use client";

import Link from "next/link";
import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { TransferModal } from "@/components/workflow";
import { Empty, ErrorBox, Loading, PageHeader, Status, when } from "@/components/ui";

export default function Exceptions() {
  const [status, setStatus] = useState("OPEN");
  const list = useApi<any[]>(`admin/exceptions${status ? `?status=${status}` : ""}`, [status]);
  const m = useMutation();
  const [assigning, setAssigning] = useState<any>(null);
  const update = async (id: string, s: string) => {
    if (await m.run(() => api(`admin/exceptions/${id}`, { method: "PATCH", body: { status: s } }))) list.reload();
  };
  return (
    <div className="stack">
      <PageHeader title="Operational exceptions" subtitle="SLA breaches, escalations, unassigned work, repeated cancellations, payment issues"
        actions={<ExportMenu name="exceptions" title="Operational exceptions" rows={list.data} columns={[{ key: "priority", label: "Priority" }, { key: "exception_type", label: "Type" }, { key: "title", label: "Issue" }, { key: "next_action", label: "Next action" }, { key: "owner_name", label: "Owner" }, { key: "ageing_minutes", label: "Ageing (min)" }, { key: "status", label: "Status" }, { key: "created_at", label: "Created" }]} />} />
      <div className="row">{["OPEN", "ACKNOWLEDGED", "RESOLVED", ""].map((s) => <button key={s} className={`btn btn-sm ${status === s ? "btn-primary" : "btn-outline"}`} onClick={() => setStatus(s)}>{s || "All"}</button>)}</div>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !list.data?.length ? <Empty title="Nothing here" hint="No exceptions in this state." /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Priority</th><th>Type</th><th>Issue</th><th>Next action</th><th>Owner</th><th>Ageing</th><th>Status</th><th /></tr></thead>
            <tbody>{list.data.map((e) => (
              <tr key={e.id}>
                <td><Status value={e.priority} /></td>
                <td className="small">{e.exception_type.replace(/_/g, " ")}</td>
                <td>{e.title}{e.ref_type === "REQUEST" && e.ref_id ? <div><Link href={`/requests/${e.ref_id}`}>Open request</Link></div> : e.ref_type === "PARTNER" && e.ref_id ? <div><Link href={`/partners/${e.ref_id}`}>Open partner</Link></div> : null}</td>
                <td className="small">{e.next_action ?? "—"}</td>
                <td>{e.owner_name ?? <span className="muted">unassigned</span>}</td>
                <td>{e.ageing_minutes < 120 ? `${e.ageing_minutes} min` : `${Math.round(e.ageing_minutes / 60)} h`}<div className="muted small">{when(e.created_at)}</div></td>
                <td><Status value={e.status} /></td>
                <td>
                  <div className="row">
                    {["OPEN", "ACKNOWLEDGED"].includes(e.status) ? <button className="btn btn-primary btn-sm" onClick={() => setAssigning(e)}>↪ Assign / transfer</button> : null}
                    {e.status === "OPEN" ? <button className="btn btn-secondary btn-sm" onClick={() => update(e.id, "ACKNOWLEDGED")}>Take ownership</button> : null}
                    {["OPEN", "ACKNOWLEDGED"].includes(e.status) ? <button className="btn btn-outline btn-sm" onClick={() => update(e.id, "RESOLVED")}>Resolve</button> : null}
                  </div>
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      <TransferModal open={!!assigning} mode="exception" exceptionId={assigning?.id} canMoveRequest={assigning?.ref_type === "REQUEST"}
        onClose={() => setAssigning(null)} onDone={() => { setAssigning(null); list.reload(); }} />
    </div>
  );
}
