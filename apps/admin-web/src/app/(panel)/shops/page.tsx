"use client";

import Link from "next/link";
import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Badge, Empty, ErrorBox, Loading, Notice, PageHeader, Status, toneOf } from "@/components/ui";

const ROLE: Record<string, string> = { RETAIL_SHOP: "Retail shop", WHOLESALER: "Wholesaler", DISTRIBUTOR: "Distributor", SERVICE_PROVIDER: "Service provider", TASK_PARTNER: "Task partner" };

/** Vendor management: shops, wholesalers and distributors, their partner types, listings and orders. */
export default function Shops() {
  const [roleStatus, setRoleStatus] = useState("");
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const list = useApi<any[]>(`admin/marketplace/shops?${roleStatus ? `roleStatus=${roleStatus}&` : ""}${query ? `q=${encodeURIComponent(query)}` : ""}`, [roleStatus, query]);
  const m = useMutation();
  const review = async (partnerId: string, role: string, decision: string) => {
    if (await m.run(() => api(`admin/marketplace/shops/${partnerId}/roles/${role}`, { method: "POST", body: { decision } }))) list.reload();
  };
  const rows = list.data ?? [];
  return (
    <div className="stack">
      <PageHeader title="Shops & vendors" subtitle="Retail shops, wholesalers and distributors selling on BUZZER"
        actions={<ExportMenu name="shops" title="Shops & vendors" rows={rows.map((r) => ({ ...r, roles: (r.roles ?? []).map((x: any) => `${x.role}:${x.status}`).join(", ") }))}
          columns={[{ key: "display_name", label: "Shop" }, { key: "contact_phone", label: "Phone" }, { key: "roles", label: "Partner types" }, { key: "registration_status", label: "Registration" }, { key: "kyc_status", label: "KYC" }, { key: "active_listings", label: "Live products" }, { key: "pending_listings", label: "Products in review" }, { key: "orders", label: "Orders" }, { key: "completed_orders", label: "Completed" }]} />} />
      <Notice>A shop is visible to customers only when its registration is approved, KYC verified, its shop-type partner role approved, its categories approved and each listing reviewed. Approving a partner&apos;s registration (Partners &amp; KYC) also approves the partner types it asked for; suspend a type here to hide the shop without deleting anything.</Notice>
      <div className="row">
        {["", "REQUESTED", "APPROVED", "SUSPENDED"].map((s) => <button key={s} className={`btn btn-sm ${roleStatus === s ? "btn-primary" : "btn-outline"}`} onClick={() => setRoleStatus(s)}>{s ? s.toLowerCase() : "All"}</button>)}
        <form className="row" onSubmit={(e) => { e.preventDefault(); setQuery(q.trim()); }} style={{ flex: 1 }}>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Shop name or phone" style={{ flex: 1 }} />
          <button className="btn btn-outline btn-sm" type="submit">Search</button>
        </form>
      </div>
      {m.error ? <ErrorBox message={m.error} /> : null}
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !rows.length ? <Empty title="No shops" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Shop</th><th>Partner types</th><th>Verification</th><th>Locations</th><th>Products</th><th>Orders</th></tr></thead>
            <tbody>{rows.map((s) => (
              <tr key={s.id}>
                <td><Link href={`/partners/${s.id}`}><b>{s.display_name}</b></Link><div className="muted small">{s.contact_phone} · {s.city_name}</div></td>
                <td>{(s.roles ?? []).map((r: any) => (
                  <div key={r.role} className="row" style={{ gap: 6, alignItems: "center", marginBottom: 4 }}>
                    <Badge tone={toneOf(r.status)}>{ROLE[r.role] ?? r.role}: {r.status.toLowerCase()}</Badge>
                    {["RETAIL_SHOP", "WHOLESALER", "DISTRIBUTOR"].includes(r.role) ? (
                      r.status === "APPROVED"
                        ? <button className="btn btn-outline btn-sm" disabled={m.busy} onClick={() => review(s.id, r.role, "SUSPENDED")}>Suspend</button>
                        : <button className="btn btn-primary btn-sm" disabled={m.busy} onClick={() => review(s.id, r.role, "APPROVED")}>Approve</button>
                    ) : null}
                  </div>
                ))}</td>
                <td><Status value={s.registration_status} /> <Status value={s.kyc_status} /><div className="muted small">{s.availability === "AVAILABLE" ? "Open" : "Closed"}</div></td>
                <td>{s.locations}</td>
                <td><b>{s.active_listings}</b> live{Number(s.pending_listings) ? <div><Link href="/products?tab=listings" className="small">{s.pending_listings} in review</Link></div> : null}</td>
                <td>{s.orders}<div className="muted small">{s.completed_orders} completed</div></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
