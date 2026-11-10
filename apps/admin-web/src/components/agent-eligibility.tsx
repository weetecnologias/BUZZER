"use client";

import { useEffect, useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ErrorBox, Loading, Modal, when } from "@/components/ui";

const LABEL: Record<string, string> = { MARKETPLACE: "Shop & Buy", SERVICES: "Book Services", TASKS: "Tasks & Errands", ASSISTANCE: "Personal Assistance" };

/** Which verticals an agent is trained for, whether they take dedicated customers, and their recent activity. */
export function AgentEligibilityModal({ agent, onClose }: { agent: { id: string; name: string }; onClose: () => void }) {
  const elig = useApi<{ verticals: string[]; accepts_dedicated: boolean }>(`admin/agents/${agent.id}/eligibility`, [agent.id]);
  const log = useApi<any[]>(`admin/agents/${agent.id}/activity?limit=30`, [agent.id]);
  const m = useMutation();
  const [verticals, setVerticals] = useState<string[]>([]);
  const [dedicated, setDedicated] = useState(true);
  useEffect(() => {
    if (elig.data) {
      setVerticals(elig.data.verticals);
      setDedicated(elig.data.accepts_dedicated);
    }
  }, [elig.data]);
  const toggle = (v: string) => setVerticals((list) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]));
  const save = async () => {
    if (await m.run(() => api(`admin/agents/${agent.id}/eligibility`, { method: "PUT", body: { verticals, accepts_dedicated: dedicated } }))) onClose();
  };
  return (
    <Modal title={`${agent.name}: skills & activity`} open onClose={onClose}
      footer={<button className="btn btn-primary btn-sm" disabled={m.busy || !verticals.length} onClick={save}>Save</button>}>
      {!elig.data ? <Loading /> : (
        <div className="stack">
          <div>
            <div className="small muted">Trained for (used to match dedicated customers to the right agent)</div>
            <div className="row" style={{ flexWrap: "wrap", gap: 6, marginTop: 6 }}>
              {Object.keys(LABEL).map((v) => (
                <button key={v} className={`btn btn-sm ${verticals.includes(v) ? "btn-primary" : "btn-outline"}`} onClick={() => toggle(v)}>{LABEL[v]}</button>
              ))}
            </div>
          </div>
          <label className="row" style={{ gap: 8 }}>
            <input type="checkbox" style={{ width: "auto" }} checked={dedicated} onChange={(e) => setDedicated(e.target.checked)} />
            Takes dedicated customers (untick for trainees - they still get one-off and escalated requests)
          </label>
          {m.error ? <ErrorBox message={m.error} /> : null}
          <div>
            <div className="small muted">Recent activity</div>
            {(log.data ?? []).length === 0 ? <p className="small muted">No activity yet.</p> : (
              <table><tbody>{(log.data ?? []).map((x) => (
                <tr key={x.id}><td className="small muted" style={{ whiteSpace: "nowrap" }}>{when(x.created_at)}</td><td className="small">{x.kind.replace(/_/g, " ").toLowerCase()}</td><td className="small">{x.summary}</td></tr>
              ))}</tbody></table>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
