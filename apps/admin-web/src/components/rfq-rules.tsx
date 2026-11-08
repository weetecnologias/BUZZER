"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Loading } from "@/components/ui";

/** How many shops a quotation request (RFQ) is sent to: rules by category / city / locality; the rest use 'rfq.invite_count'. */
export function RfqRulesCard({ onSaved }: { onSaved: (msg: string) => void }) {
  const rules = useApi<any[]>("admin/marketplace/rfq-rules");
  const cats = useApi<any[]>("admin/catalogue/tree");
  const cities = useApi<any[]>("admin/locations/cities");
  const m = useMutation();
  const [form, setForm] = useState({ category_id: "", city_id: "", invite_count: "7" });
  const marketplace = (cats.data ?? []).filter((c) => c.vertical === "MARKETPLACE");
  const save = async () => {
    const body = { category_id: form.category_id || null, city_id: form.city_id || null, invite_count: Number(form.invite_count) };
    if (await m.run(() => api("admin/marketplace/rfq-rules", { method: "POST", body }))) {
      onSaved("Invite rule saved");
      rules.reload();
    }
  };
  return (
    <Card title="Quotation requests: how many shops are invited">
      <p className="muted small">
        Each quotation request goes to the best-matching approved shops (category approved, open, within their delivery radius; wholesalers first for bulk).
        The most specific rule wins: category + locality, category + city, category, city, then the default setting <span className="mono">rfq.invite_count</span>.
        Pilot guidance: 5-10.
      </p>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {rules.loading && !rules.data ? <Loading /> : (
        <table>
          <thead><tr><th>Category</th><th>City</th><th>Locality</th><th>Shops invited</th><th /></tr></thead>
          <tbody>
            {(rules.data ?? []).map((r) => (
              <tr key={r.id}>
                <td>{r.category_name ?? <span className="muted">any</span>}</td>
                <td>{r.city_name ?? <span className="muted">any</span>}</td>
                <td>{r.locality_name ?? <span className="muted">any</span>}</td>
                <td><b>{r.invite_count}</b></td>
                <td><button className="btn btn-outline btn-sm" disabled={m.busy} onClick={async () => {
                  if (await m.run(() => api(`admin/marketplace/rfq-rules/${r.id}`, { method: "DELETE" }))) { onSaved("Invite rule removed"); rules.reload(); }
                }}>Remove</button></td>
              </tr>
            ))}
            <tr>
              <td><select value={form.category_id} onChange={(e) => setForm({ ...form, category_id: e.target.value })} aria-label="Category">
                <option value="">Any category</option>{marketplace.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select></td>
              <td><select value={form.city_id} onChange={(e) => setForm({ ...form, city_id: e.target.value })} aria-label="City">
                <option value="">Any city</option>{(cities.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select></td>
              <td className="muted small">—</td>
              <td><input type="number" min={1} max={20} value={form.invite_count} onChange={(e) => setForm({ ...form, invite_count: e.target.value })} style={{ width: 80 }} aria-label="Shops invited" /></td>
              <td><button className="btn btn-primary btn-sm" disabled={m.busy || (!form.category_id && !form.city_id)} onClick={save}>Add rule</button></td>
            </tr>
          </tbody>
        </table>
      )}
    </Card>
  );
}
