"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Loading } from "@/components/ui";

/** First-response time per business vertical (pilot target 60-120 minutes). HIGH / URGENT requests use the shorter priority rule. */
export function VerticalSlaCard({ onSaved }: { onSaved: (msg: string) => void }) {
  const list = useApi<any[]>("admin/sla/verticals");
  const m = useMutation();
  const [edits, setEdits] = useState<Record<string, string>>({});
  return (
    <Card title="First response time per business area">
      <p className="muted small">How quickly an agent must first respond to a new request in each area (pilot target 60-120 minutes). Urgent and high-priority
        requests use the shorter of this and the priority rule below. Missed deadlines go to the customer&apos;s backup agent, then the best available agent, then the manager.</p>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : (
        <table>
          <thead><tr><th>Area</th><th>Minutes</th><th /></tr></thead>
          <tbody>{(list.data ?? []).map((v) => (
            <tr key={v.vertical}>
              <td><b>{v.name}</b>{!v.enabled ? <span className="muted small"> (switched off)</span> : null}</td>
              <td><input type="number" min={15} max={480} style={{ width: 90 }} value={edits[v.vertical] ?? String(v.first_response_minutes ?? "")}
                onChange={(e) => setEdits({ ...edits, [v.vertical]: e.target.value })} aria-label={`${v.name} minutes`} /></td>
              <td><button className="btn btn-primary btn-sm" disabled={m.busy || edits[v.vertical] === undefined} onClick={async () => {
                if (await m.run(() => api(`admin/sla/verticals/${v.vertical}`, { method: "PUT", body: { first_response_minutes: Number(edits[v.vertical]) } }))) {
                  onSaved(`${v.name}: first response ${edits[v.vertical]} minutes`);
                  const next = { ...edits };
                  delete next[v.vertical];
                  setEdits(next);
                  list.reload();
                }
              }}>Save</button></td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </Card>
  );
}
