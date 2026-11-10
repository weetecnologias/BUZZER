"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Loading, Notice, PageHeader } from "@/components/ui";
import { RfqRulesCard } from "@/components/rfq-rules";
import { VerticalSlaCard } from "@/components/sla-verticals";

export default function Settings() {
  const settings = useApi<any[]>("admin/settings");
  const sla = useApi<any[]>("admin/sla-rules");
  const cities = useApi<any[]>("admin/locations/cities");
  const verticals = useApi<any[]>("admin/platform/verticals");
  const m = useMutation();
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [slaEdits, setSlaEdits] = useState<Record<string, { first_response_minutes: number; resolution_minutes: number }>>({});
  const [saved, setSaved] = useState<string | null>(null);

  return (
    <div className="stack">
      <PageHeader title="Settings & SLA" subtitle="Thresholds and policies are configuration, not code" />
      {m.error ? <ErrorBox message={m.error} /> : null}
      {saved ? <Notice tone="success">{saved}</Notice> : null}
      <Card title="Business verticals">
        <p className="muted small">Switch a whole vertical on or off. When off, its categories disappear from the customer app, search and checkout immediately; nothing is deleted.</p>
        {verticals.loading && !verticals.data ? <Loading /> : (
          <table><thead><tr><th>Vertical</th><th>What customers see</th><th>Categories</th><th>Status</th><th /></tr></thead>
            <tbody>{(verticals.data ?? []).map((v) => (
              <tr key={v.code}><td><b>{v.name}</b><div className="muted small mono">{v.code}</div></td><td className="small">{v.tagline}</td><td>{v.category_count}</td>
                <td><span className={`badge ${v.enabled ? "tone-success" : "tone-danger"}`}>{v.enabled ? "ON" : "OFF"}</span></td>
                <td><button className={`btn btn-sm ${v.enabled ? "btn-outline" : "btn-primary"}`} disabled={m.busy} onClick={async () => {
                  if (await m.run(() => api(`admin/platform/verticals/${v.code}`, { method: "PUT", body: { enabled: !v.enabled } }))) { setSaved(`${v.name} switched ${v.enabled ? "off" : "on"}`); verticals.reload(); }
                }}>{v.enabled ? "Switch off" : "Switch on"}</button></td></tr>
            ))}</tbody></table>
        )}
      </Card>
      <VerticalSlaCard onSaved={setSaved} />
      <RfqRulesCard onSaved={setSaved} />
      <Card title="Platform settings">
        {settings.loading && !settings.data ? <Loading /> : (
          <table><thead><tr><th>Setting</th><th>Description</th><th style={{ width: 180 }}>Value</th><th /></tr></thead>
            <tbody>{(settings.data ?? []).map((s) => (
              <tr key={s.key}><td className="mono">{s.key}</td><td className="small">{s.description}</td>
                <td><input value={edits[s.key] ?? s.value} onChange={(e) => setEdits({ ...edits, [s.key]: e.target.value })} aria-label={s.key} /></td>
                <td><button className="btn btn-secondary btn-sm" disabled={edits[s.key] === undefined || m.busy} onClick={async () => {
                  if (await m.run(() => api(`admin/settings/${s.key}`, { method: "PUT", body: { value: edits[s.key] } }))) { setSaved(`Saved ${s.key}`); settings.reload(); }
                }}>Save</button></td></tr>
            ))}</tbody></table>
        )}
      </Card>
      <Card title="SLA rules (first response is tracked separately from resolution)">
        {sla.loading && !sla.data ? <Loading /> : (
          <table><thead><tr><th>Priority</th><th>First response (min)</th><th>Resolution (min)</th><th /></tr></thead>
            <tbody>{(sla.data ?? []).map((r) => {
              const v = slaEdits[r.priority] ?? { first_response_minutes: r.first_response_minutes, resolution_minutes: r.resolution_minutes };
              return (
                <tr key={r.id}><td><b>{r.priority}</b></td>
                  <td><input value={v.first_response_minutes} inputMode="numeric" onChange={(e) => setSlaEdits({ ...slaEdits, [r.priority]: { ...v, first_response_minutes: Number(e.target.value) } })} /></td>
                  <td><input value={v.resolution_minutes} inputMode="numeric" onChange={(e) => setSlaEdits({ ...slaEdits, [r.priority]: { ...v, resolution_minutes: Number(e.target.value) } })} /></td>
                  <td><button className="btn btn-secondary btn-sm" disabled={!slaEdits[r.priority]} onClick={async () => {
                    if (await m.run(() => api(`admin/sla-rules/${r.priority}`, { method: "PUT", body: v }))) { setSaved(`SLA for ${r.priority} saved`); sla.reload(); }
                  }}>Save</button></td></tr>
              );
            })}</tbody></table>
        )}
      </Card>
      <Card title="Cities">
        {cities.loading && !cities.data ? <Loading /> : (
          <table><thead><tr><th>City</th><th>State</th><th>Localities</th><th>Status</th></tr></thead>
            <tbody>{(cities.data ?? []).map((c) => (
              <tr key={c.id}><td>{c.name} <span className="muted small">{c.code}</span></td><td>{c.state}</td><td>{c.locality_count}</td>
                <td><button className={`btn btn-sm ${c.active ? "btn-secondary" : "btn-outline"}`} onClick={async () => {
                  if (await m.run(() => api(`admin/locations/cities/${c.id}`, { method: "PATCH", body: { active: !c.active } }))) cities.reload();
                }}>{c.active ? "Live — click to pause" : "Inactive — click to launch"}</button></td></tr>
            ))}</tbody></table>
        )}
      </Card>
    </div>
  );
}
