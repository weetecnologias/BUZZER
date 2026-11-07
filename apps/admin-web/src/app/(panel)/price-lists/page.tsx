"use client";

import Link from "next/link";
import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Loading, money, Notice, PageHeader, Status, when } from "@/components/ui";

/** Partner price lists: partners submit their own price per approved service; admin approves before agents can use them for instant quotes. */
export default function PriceLists() {
  const [status, setStatus] = useState("PENDING");
  const list = useApi<any[]>(`admin/price-lists${status ? `?status=${status}` : ""}`, [status]);
  const m = useMutation();
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [note, setNote] = useState("");
  const rows = list.data ?? [];
  const pending = rows.filter((r) => r.status === "PENDING");
  const toggle = (id: string) => {
    const n = new Set(picked);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    setPicked(n);
  };
  const review = async (approve: boolean) => {
    if (await m.run(() => api("admin/price-lists/review", { method: "POST", body: { ids: [...picked], approve, note: note || null } }))) {
      setPicked(new Set());
      setNote("");
      list.reload();
    }
  };
  const diff = (r: any) => {
    if (r.catalogue_price == null || r.price == null) return null;
    const pct = Math.round(((Number(r.price) - Number(r.catalogue_price)) / Number(r.catalogue_price)) * 100);
    return <span className="small" style={{ color: pct > 15 ? "var(--danger)" : pct < -15 ? "var(--warning, #b7791f)" : "var(--muted)" }}>{pct > 0 ? `+${pct}` : pct}% vs listed</span>;
  };

  return (
    <div className="stack">
      <PageHeader title="Partner price lists" subtitle="Partners publish their own prices for approved services. Approved prices power instant quotations for agents."
        actions={<ExportMenu name="partner-prices" title="Partner price lists" rows={list.data} columns={[{ key: "partner_name", label: "Partner" }, { key: "category_name", label: "Category" }, { key: "subcategory_name", label: "Subcategory" }, { key: "service_name", label: "Service" }, { key: "catalogue_price", label: "Listed price" }, { key: "price", label: "Partner price" }, { key: "approved_price", label: "Approved price" }, { key: "unit", label: "Unit" }, { key: "status", label: "Status" }, { key: "notes", label: "Partner notes" }, { key: "updated_at", label: "Updated" }]} />} />
      <Notice>Flow: partner app → <b>Services &amp; prices</b> (or Excel upload) → this page → approved prices appear for agents as <b>Instant quotes</b> on every matching request, so the customer can get a quotation in one tap.</Notice>
      <div className="row">{["PENDING", "APPROVED", "REJECTED", ""].map((s) => <button key={s} className={`btn btn-sm ${status === s ? "btn-primary" : "btn-outline"}`} onClick={() => { setStatus(s); setPicked(new Set()); }}>{s || "All"}</button>)}</div>
      {pending.length ? (
        <div className="card row" style={{ alignItems: "center" }}>
          <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={picked.size === pending.length && pending.length > 0}
            onChange={(e) => setPicked(e.target.checked ? new Set(pending.map((r) => r.id)) : new Set())} /> Select all pending ({pending.length})</label>
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Review note (shown to the partner)" style={{ flex: 1, minWidth: 200 }} />
          <button className="btn btn-primary btn-sm" disabled={!picked.size || m.busy} onClick={() => review(true)}>✓ Approve {picked.size || ""}</button>
          <button className="btn btn-outline btn-sm" disabled={!picked.size || m.busy} onClick={() => review(false)}>✕ Reject</button>
        </div>
      ) : null}
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !rows.length ? <Empty title="Nothing to review" hint="New partner prices will appear here." /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th /><th>Partner</th><th>Service</th><th>Listed price</th><th>Partner price</th><th>Notes</th><th>Status</th><th>Updated</th></tr></thead>
            <tbody>{rows.map((r) => (
              <tr key={r.id}>
                <td>{r.status === "PENDING" ? <input type="checkbox" style={{ width: "auto" }} checked={picked.has(r.id)} onChange={() => toggle(r.id)} aria-label="Select" /> : null}</td>
                <td><Link href={`/partners/${r.partner_id}`}>{r.partner_name}</Link></td>
                <td>{r.service_name}<div className="muted small">{r.category_name} › {r.subcategory_name}</div></td>
                <td>{r.catalogue_price != null ? money(r.catalogue_price) : <span className="muted">quote</span>}{r.unit ? <div className="muted small">{r.unit}</div> : null}</td>
                <td><b>{money(r.price)}</b><div>{diff(r)}</div>{r.approved_price != null && r.approved_price !== r.price ? <div className="muted small">approved {money(r.approved_price)}</div> : null}</td>
                <td className="small">{r.notes ?? "—"}</td>
                <td><Status value={r.status} /></td>
                <td>{when(r.updated_at)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
