"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Field, Loading, Modal, money, Notice, PageHeader, Status, when } from "@/components/ui";

/** Product catalogue moderation: catalogue products, shop-proposed products and shop listings waiting for review. */
export default function ProductsPage() {
  return <Suspense fallback={<Loading />}><Products /></Suspense>;
}

function Products() {
  const params = useSearchParams();
  const [tab, setTab] = useState<"listings" | "products">(params.get("tab") === "products" ? "products" : "listings");
  return (
    <div className="stack">
      <PageHeader title="Products & listings" subtitle="The shared product catalogue and each shop's price, stock and minimum order" />
      <div className="row">
        <button className={`btn btn-sm ${tab === "listings" ? "btn-primary" : "btn-outline"}`} onClick={() => setTab("listings")}>Shop listings</button>
        <button className={`btn btn-sm ${tab === "products" ? "btn-primary" : "btn-outline"}`} onClick={() => setTab("products")}>Catalogue products</button>
      </div>
      {tab === "listings" ? <Listings /> : <Catalogue />}
    </div>
  );
}

function Listings() {
  const [status, setStatus] = useState("PENDING");
  const list = useApi<any[]>(`admin/marketplace/listings${status ? `?status=${status}` : ""}`, [status]);
  const m = useMutation();
  const [note, setNote] = useState("");
  const review = async (id: string, decision: string) => {
    if (await m.run(() => api(`admin/marketplace/listings/${id}/review`, { method: "POST", body: { decision, note: note || null } }))) list.reload();
  };
  const rows = list.data ?? [];
  return (
    <>
      <Notice>New listings wait here before customers see them. Price and stock edits on a live listing apply at once (shops are responsible for their prices); pause a listing to hide it — the shop can&apos;t switch it back on.</Notice>
      <div className="row">
        {["PENDING", "ACTIVE", "PAUSED", "REJECTED", ""].map((s) => <button key={s} className={`btn btn-sm ${status === s ? "btn-primary" : "btn-outline"}`} onClick={() => setStatus(s)}>{s ? s.toLowerCase() : "All"}</button>)}
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Review note (shown to the shop)" style={{ flex: 1, minWidth: 220 }} />
        <ExportMenu name="listings" title="Shop listings" rows={rows} columns={[{ key: "shop_name", label: "Shop" }, { key: "product_name", label: "Product" }, { key: "sku", label: "SKU" }, { key: "price", label: "Price" }, { key: "mrp", label: "MRP" }, { key: "stock_status", label: "Stock" }, { key: "moq", label: "MOQ" }, { key: "status", label: "Status" }, { key: "updated_at", label: "Updated" }]} />
      </div>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !rows.length ? <Empty title="Nothing here" hint="Listings shops add appear here." /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Shop</th><th>Product</th><th>Price</th><th>Stock</th><th>Min. order</th><th>Status</th><th>Updated</th><th /></tr></thead>
            <tbody>{rows.map((l) => (
              <tr key={l.id}>
                <td>{l.shop_name}{!l.capability_approved ? <div className="small" style={{ color: "var(--danger)" }}>category not approved</div> : null}</td>
                <td>{l.product_name}{l.variant_name !== "Standard" ? ` (${l.variant_name})` : ""}<div className="muted small">{l.subcategory_name} · {l.sku}{l.product_status !== "ACTIVE" ? ` · product ${l.product_status.toLowerCase()}` : ""}</div></td>
                <td><b>{money(l.price)}</b>{l.mrp ? <div className="muted small">MRP {money(l.mrp)}</div> : null}{l.bulk_price ? <div className="muted small">{money(l.bulk_price)} from {l.bulk_min_qty}</div> : null}</td>
                <td><Status value={l.stock_status} /><div className="muted small">{when(l.stock_updated_at)}</div></td>
                <td>{l.moq}{l.lead_time_days ? <div className="muted small">{l.lead_time_days} day(s)</div> : null}</td>
                <td><Status value={l.status} />{l.review_note ? <div className="muted small">{l.review_note}</div> : null}</td>
                <td>{when(l.updated_at)}</td>
                <td className="row" style={{ gap: 4 }}>
                  {l.status !== "ACTIVE" ? <button className="btn btn-primary btn-sm" disabled={m.busy} onClick={() => review(l.id, "ACTIVE")}>Approve</button> : null}
                  {l.status === "ACTIVE" ? <button className="btn btn-outline btn-sm" disabled={m.busy} onClick={() => review(l.id, "PAUSED")}>Pause</button> : null}
                  {l.status === "PENDING" ? <button className="btn btn-outline btn-sm" disabled={m.busy} onClick={() => review(l.id, "REJECTED")}>Reject</button> : null}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </>
  );
}

const EMPTY = { name: "", brand: "", unit: "each", tax_rate_percent: "18", description: "", specs: "", subcategory_id: "", status: "ACTIVE" };

function Catalogue() {
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const list = useApi<any[]>(`admin/marketplace/products?${status ? `status=${status}&` : ""}${query ? `q=${encodeURIComponent(query)}` : ""}`, [status, query]);
  const cats = useApi<any[]>("admin/catalogue/tree");
  const m = useMutation();
  const [edit, setEdit] = useState<any>(null);
  const subs: { id: string; label: string }[] = [];
  (cats.data ?? []).filter((c: any) => c.vertical === "MARKETPLACE").forEach((c: any) => (c.subcategories_json ? JSON.parse(c.subcategories_json) : []).forEach((s: any) => subs.push({ id: s.id, label: `${c.name} › ${s.name}` })));
  const save = async () => {
    const specs: Record<string, string> = {};
    String(edit.specs ?? "").split("\n").forEach((line: string) => {
      const [k, ...v] = line.split(":");
      if (k?.trim() && v.join(":").trim()) specs[k.trim()] = v.join(":").trim();
    });
    const body = { subcategory_id: edit.subcategory_id, name: edit.name, brand: edit.brand || null, unit: edit.unit, tax_rate_percent: Number(edit.tax_rate_percent),
      description: edit.description || null, specs, status: edit.status };
    const ok = await m.run(() => api(edit.id ? `admin/marketplace/products/${edit.id}` : "admin/marketplace/products", { method: edit.id ? "PUT" : "POST", body }));
    if (ok) { setEdit(null); list.reload(); }
  };
  const review = async (id: string, decision: string) => {
    if (await m.run(() => api(`admin/marketplace/products/${id}/review`, { method: "POST", body: { decision } }))) list.reload();
  };
  const rows = list.data ?? [];
  return (
    <>
      <div className="row">
        {["", "PENDING", "ACTIVE", "INACTIVE", "REJECTED"].map((s) => <button key={s} className={`btn btn-sm ${status === s ? "btn-primary" : "btn-outline"}`} onClick={() => setStatus(s)}>{s ? s.toLowerCase() : "All"}</button>)}
        <form className="row" onSubmit={(e) => { e.preventDefault(); setQuery(q.trim()); }} style={{ flex: 1 }}>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, code or brand" style={{ flex: 1 }} />
          <button className="btn btn-outline btn-sm" type="submit">Search</button>
        </form>
        <button className="btn btn-primary btn-sm" onClick={() => setEdit({ ...EMPTY })}>＋ New product</button>
      </div>
      <Notice>Shops can propose products that aren&apos;t in the catalogue; approving one also puts that shop&apos;s listing live. Medical &amp; healthcare products stay off until licensing checks are in place.</Notice>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !rows.length ? <Empty title="No products" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Product</th><th>Category</th><th>GST</th><th>Shops</th><th>From</th><th>Status</th><th /></tr></thead>
            <tbody>{rows.map((p) => (
              <tr key={p.id}>
                <td><b>{p.name}</b><div className="muted small">{p.brand ? `${p.brand} · ` : ""}{p.code} · {p.unit}{p.proposed_by ? ` · proposed by ${p.proposed_by}` : ""}</div></td>
                <td className="small">{p.category_name} › {p.subcategory_name}</td>
                <td>{Number(p.tax_rate_percent)}%</td>
                <td>{p.active_listings}</td>
                <td>{p.min_price != null ? money(p.min_price) : "—"}</td>
                <td><Status value={p.status} /></td>
                <td className="row" style={{ gap: 4 }}>
                  {p.status === "PENDING" ? (
                    <>
                      <button className="btn btn-primary btn-sm" disabled={m.busy} onClick={() => review(p.id, "APPROVED")}>Approve</button>
                      <button className="btn btn-outline btn-sm" disabled={m.busy} onClick={() => review(p.id, "REJECTED")}>Reject</button>
                    </>
                  ) : null}
                  <button className="btn btn-outline btn-sm" onClick={() => setEdit({ ...p, brand: p.brand ?? "", tax_rate_percent: String(Number(p.tax_rate_percent)),
                    description: p.description ?? "", specs: Object.entries(p.specs ?? {}).map(([k, v]) => `${k}: ${v}`).join("\n") })}>Edit</button>
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      <Modal title={edit?.id ? "Edit product" : "New product"} open={!!edit} onClose={() => setEdit(null)}
        footer={<button className="btn btn-primary btn-sm" disabled={m.busy || !edit?.name || !edit?.subcategory_id} onClick={save}>Save</button>}>
        {edit ? (
          <div className="stack">
            <Field label="Subcategory">
              <select value={edit.subcategory_id} onChange={(e) => setEdit({ ...edit, subcategory_id: e.target.value })}>
                <option value="">Choose…</option>
                {subs.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
              </select>
            </Field>
            <Field label="Name"><input value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></Field>
            <div className="row">
              <Field label="Brand"><input value={edit.brand} onChange={(e) => setEdit({ ...edit, brand: e.target.value })} /></Field>
              <Field label="Unit"><input value={edit.unit} onChange={(e) => setEdit({ ...edit, unit: e.target.value })} /></Field>
              <Field label="GST %"><input value={edit.tax_rate_percent} onChange={(e) => setEdit({ ...edit, tax_rate_percent: e.target.value })} /></Field>
            </div>
            <Field label="Description"><textarea value={edit.description} onChange={(e) => setEdit({ ...edit, description: e.target.value })} /></Field>
            <Field label="Specifications (one per line, Name: value)"><textarea value={edit.specs} onChange={(e) => setEdit({ ...edit, specs: e.target.value })} rows={4} /></Field>
            <Field label="Status">
              <select value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })}>
                {["ACTIVE", "INACTIVE", "PENDING", "REJECTED"].map((s) => <option key={s}>{s}</option>)}
              </select>
            </Field>
          </div>
        ) : null}
      </Modal>
    </>
  );
}
