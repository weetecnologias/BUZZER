"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { ErrorBox, Field, Loading, Modal, PageHeader, Status } from "@/components/ui";

export default function Agents() {
  const list = useApi<any[]>("admin/agents");
  const cities = useApi<any[]>("locations/cities");
  const m = useMutation();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ phone: "", display_name: "", city_id: "", languages: "en,te,hi", max_customers: "300", is_manager: false, manager_id: "", wing: "General" });
  const [edit, setEdit] = useState<any>(null);
  const wings = useApi<any[]>("admin/wings");
  const saveEdit = async () => {
    const body = { wing: edit.wing, max_customers: Number(edit.max_customers), is_manager: edit.is_manager, manager_id: edit.manager_id || null, clear_manager: !edit.manager_id };
    if (await m.run(() => api(`admin/agents/${edit.id}`, { method: "PATCH", body }))) {
      setEdit(null);
      list.reload();
      wings.reload();
    }
  };

  const create = async () => {
    const body = { ...f, city_id: f.city_id || cities.data?.[0]?.id, max_customers: Number(f.max_customers), manager_id: f.manager_id || null };
    if (await m.run(() => api("admin/agents", { method: "POST", body }))) {
      setOpen(false);
      list.reload();
    }
  };

  return (
    <div className="stack">
      <PageHeader title="Agents" subtitle="Dedicated BUZZER agents, managers and workload" actions={<><ExportMenu name="agents" title="Agents" rows={list.data} columns={[{ key: "display_name", label: "Agent" }, { key: "phone", label: "Phone" }, { key: "wing", label: "Wing" }, { key: "city_name", label: "City" }, { key: "availability", label: "Availability" }, { key: "primary_customers", label: "Primary customers" }, { key: "open_requests", label: "Open requests" }, { key: "avg_first_response_minutes", label: "Avg first response (min)" }]} /><button className="btn btn-primary" onClick={() => setOpen(true)}>+ Add agent</button></>} />
      {(wings.data ?? []).length ? (
        <div className="row">{(wings.data ?? []).map((w) => <span key={w.wing} className="badge tone-info">{w.wing}: {w.agents} agents • {w.managers} managers</span>)}</div>
      ) : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Agent</th><th>Wing</th><th>City</th><th>Role</th><th>Availability</th><th>Shift</th><th>Languages</th><th>Primary customers</th><th>Open requests</th><th>Avg first response</th><th /></tr></thead>
            <tbody>{(list.data ?? []).map((a) => (
              <tr key={a.id}>
                <td><b>{a.display_name}</b><div className="muted small">{a.phone}</div></td>
                <td><b>{a.wing ?? "General"}</b></td>
                <td>{a.city_name}</td>
                <td>{a.is_manager ? "Manager" : `Agent${a.manager_name ? ` (mgr ${a.manager_name})` : ""}`}</td>
                <td><Status value={a.availability} /></td>
                <td>{String(a.shift_start).slice(0, 5)}–{String(a.shift_end).slice(0, 5)}</td>
                <td>{a.languages}</td>
                <td>{a.primary_customers} / {a.max_customers}</td>
                <td>{a.open_requests}</td>
                <td>{a.avg_first_response_minutes != null ? `${a.avg_first_response_minutes} min` : "—"}</td>
                <td><button className="btn btn-outline btn-sm" onClick={() => { m.setError(null); setEdit({ id: a.id, name: a.display_name, wing: a.wing ?? "General", max_customers: a.max_customers, is_manager: !!a.is_manager, manager_id: a.manager_id ?? "" }); }}>Edit</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      <Modal title="Add agent" open={open} onClose={() => setOpen(false)} footer={<button className="btn btn-primary" disabled={m.busy} onClick={create}>Create agent</button>}>
        <Field label="Mobile number"><input value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} /></Field>
        <Field label="Display name"><input value={f.display_name} onChange={(e) => setF({ ...f, display_name: e.target.value })} /></Field>
        <Field label="Wing (team)"><input value={f.wing} onChange={(e) => setF({ ...f, wing: e.target.value })} list="wing-list" placeholder="e.g. Home services, Travel, Events" /></Field>
        <Field label="City"><select value={f.city_id} onChange={(e) => setF({ ...f, city_id: e.target.value })}>{(cities.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
        <Field label="Languages (comma separated)"><input value={f.languages} onChange={(e) => setF({ ...f, languages: e.target.value })} /></Field>
        <Field label="Max customers"><input value={f.max_customers} onChange={(e) => setF({ ...f, max_customers: e.target.value })} inputMode="numeric" /></Field>
        <Field label="Manager"><select value={f.manager_id} onChange={(e) => setF({ ...f, manager_id: e.target.value })}>
          <option value="">None</option>{(list.data ?? []).filter((a) => a.is_manager).map((a) => <option key={a.id} value={a.id}>{a.display_name}</option>)}
        </select></Field>
        <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={f.is_manager} onChange={(e) => setF({ ...f, is_manager: e.target.checked })} /> This person is an agent manager</label>
        {m.error ? <ErrorBox message={m.error} /> : null}
      </Modal>
      <datalist id="wing-list">{(wings.data ?? []).map((w) => <option key={w.wing} value={w.wing} />)}</datalist>
      <Modal title={`Edit ${edit?.name ?? "agent"}`} open={!!edit} onClose={() => setEdit(null)} footer={<button className="btn btn-primary" disabled={m.busy} onClick={saveEdit}>Save</button>}>
        {edit ? (<>
          <Field label="Wing (team)"><input value={edit.wing} onChange={(e) => setEdit({ ...edit, wing: e.target.value })} list="wing-list" /></Field>
          <Field label="Manager"><select value={edit.manager_id} onChange={(e) => setEdit({ ...edit, manager_id: e.target.value })}>
            <option value="">None</option>{(list.data ?? []).filter((a) => a.is_manager && a.id !== edit.id).map((a) => <option key={a.id} value={a.id}>{a.display_name} ({a.wing ?? "General"})</option>)}
          </select></Field>
          <Field label="Max customers"><input value={edit.max_customers} onChange={(e) => setEdit({ ...edit, max_customers: e.target.value })} inputMode="numeric" /></Field>
          <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={edit.is_manager} onChange={(e) => setEdit({ ...edit, is_manager: e.target.checked })} /> Manager of this wing (receives escalations and transfers)</label>
          {m.error ? <ErrorBox message={m.error} /> : null}
        </>) : null}
      </Modal>
    </div>
  );
}
