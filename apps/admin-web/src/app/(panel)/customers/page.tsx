"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Field, Loading, Modal, PageHeader, Status, when } from "@/components/ui";

const LANGS: Record<string, string> = { te: "Telugu", en: "English", hi: "Hindi", ur: "Urdu", ta: "Tamil", kn: "Kannada", ml: "Malayalam", mr: "Marathi", bn: "Bengali" };

export default function Customers() {
  const [q, setQ] = useState("");
  const list = useApi<any[]>(`admin/customers${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`, [q]);
  const agents = useApi<any[]>("admin/agents");
  const m = useMutation();
  const [target, setTarget] = useState<any>(null);
  const [form, setForm] = useState({ agent_id: "", link_role: "PRIMARY", reason: "" });

  const save = async () => {
    if (await m.run(() => api(`admin/customers/${target.id}/agent`, { method: "POST", body: form }))) {
      setTarget(null);
      list.reload();
    }
  };

  return (
    <div className="stack">
      <PageHeader title="Customers" subtitle="Dedicated agent allocation with manager override"
        actions={<ExportMenu name="customers" title="Customers" rows={list.data} columns={[{ key: "full_name", label: "Name" }, { key: "phone", label: "Phone" }, { key: "city_name", label: "City" }, { key: "agent_plan", label: "Plan" }, { key: "languages", label: "Languages" }, { key: "contact_method", label: "Contact via" }, { key: "primary_agent", label: "Primary agent" }, { key: "backup_agent", label: "Backup agent" }, { key: "request_count", label: "Requests" }, { key: "status", label: "Status" }, { key: "created_at", label: "Joined" }]} />} />
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search name or phone" style={{ maxWidth: 360 }} aria-label="Search customers" />
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !list.data?.length ? <Empty title="No customers" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Customer</th><th>City</th><th>Plan</th><th>Primary agent</th><th>Backup agent</th><th>Requests</th><th>Status</th><th>Joined</th><th /></tr></thead>
            <tbody>{list.data.map((c) => (
              <tr key={c.id}>
                <td><b>{c.full_name ?? "—"}</b><div className="muted small">{c.phone}</div></td>
                <td>{c.city_name ?? "—"}</td><td>{c.agent_plan === "DEDICATED" ? <Status value="SUBSCRIBED" /> : c.agent_plan === "EXPLORE" ? <span className="badge tone-info">Exploring</span> : <span className="muted">not chosen</span>}{c.languages ? <div className="muted small">{c.languages.split(", ").map((l: string) => LANGS[l] ?? l).join(", ")}</div> : null}</td><td>{c.primary_agent ?? <span className="muted">none</span>}</td><td>{c.backup_agent ?? <span className="muted">none</span>}</td>
                <td>{c.request_count}</td><td><Status value={c.status} /></td><td>{when(c.created_at)}</td>
                <td><button className="btn btn-secondary btn-sm" onClick={() => { setTarget(c); setForm({ agent_id: "", link_role: "PRIMARY", reason: "" }); m.setError(null); }}>Reassign agent</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      <Modal title={`Assign agent for ${target?.full_name ?? target?.phone ?? ""}`} open={!!target} onClose={() => setTarget(null)}
        footer={<button className="btn btn-primary" onClick={save} disabled={m.busy || !form.agent_id || form.reason.trim().length < 3}>Save</button>}>
        <Field label="Agent">
          <select value={form.agent_id} onChange={(e) => setForm({ ...form, agent_id: e.target.value })}>
            <option value="">Select…</option>
            {(agents.data ?? []).filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.display_name} — {a.availability} ({a.primary_customers} customers)</option>)}
          </select>
        </Field>
        <Field label="Role">
          <select value={form.link_role} onChange={(e) => setForm({ ...form, link_role: e.target.value })}>
            <option value="PRIMARY">Primary</option><option value="BACKUP">Backup</option><option value="TEMPORARY">Temporary replacement</option>
          </select>
        </Field>
        <Field label="Reason (audited)"><input value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field>
        {m.error ? <ErrorBox message={m.error} /> : null}
      </Modal>
    </div>
  );
}
