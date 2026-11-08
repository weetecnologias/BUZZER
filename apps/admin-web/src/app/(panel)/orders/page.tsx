"use client";

import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Loading, money, Notice, PageHeader, Status, when } from "@/components/ui";

const STATUSES = ["", "PAYMENT_PENDING", "PLACED", "PAID", "VENDOR_CONFIRMED", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "COMPLETED", "CANCELLED", "VENDOR_REJECTED"];

/** Marketplace orders: direct purchases and accepted quotation requests, one shop each. */
export default function Orders() {
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const list = useApi<any[]>(`admin/orders?${status ? `status=${status}&` : ""}${query ? `q=${encodeURIComponent(query)}` : ""}`, [status, query]);
  const rows = list.data ?? [];
  return (
    <div className="stack">
      <PageHeader title="Orders" subtitle="Direct purchases and accepted quotation requests. Prices, taxes and fulfilment details are frozen on each order."
        actions={<ExportMenu name="orders" title="Marketplace orders" rows={list.data} columns={[{ key: "order_number", label: "Order" }, { key: "request_number", label: "Request" }, { key: "source", label: "Source" }, { key: "shop_name", label: "Shop" }, { key: "customer_name", label: "Customer" }, { key: "summary", label: "Items" }, { key: "fulfilment", label: "Fulfilment" }, { key: "payment_method", label: "Payment" }, { key: "payment_status", label: "Payment status" }, { key: "total_amount", label: "Total" }, { key: "status", label: "Status" }, { key: "created_at", label: "Placed" }]} />} />
      <div className="row">
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status" style={{ maxWidth: 240 }}>
          {STATUSES.map((s) => <option key={s} value={s}>{s ? s.replace(/_/g, " ") : "All statuses"}</option>)}
        </select>
        <form className="row" onSubmit={(e) => { e.preventDefault(); setQuery(q.trim()); }} style={{ flex: 1 }}>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Order / request number, shop or customer" style={{ flex: 1 }} />
          <button className="btn btn-outline btn-sm" type="submit">Search</button>
        </form>
      </div>
      <Notice>Shops see an online order only once it is paid. The customer's hand-over code is never shown to shops or admins.</Notice>
      {list.loading && !list.data ? <Loading /> : list.error ? <ErrorBox message={list.error} onRetry={list.reload} /> : !rows.length ? <Empty title="No orders yet" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Order</th><th>Shop</th><th>Customer</th><th>Items</th><th>Fulfilment</th><th>Payment</th><th>Total</th><th>Status</th><th>Placed</th></tr></thead>
            <tbody>{rows.map((o) => (
              <tr key={o.id}>
                <td><Link href={`/orders/${o.id}`}><b>{o.order_number}</b></Link><div className="muted small">{o.source === "RFQ" ? "From quote · " : ""}<Link href={`/requests/${o.request_id}`}>{o.request_number}</Link></div></td>
                <td><Link href={`/partners/${o.partner_id}`}>{o.shop_name}</Link></td>
                <td>{o.customer_name}</td>
                <td className="small" style={{ maxWidth: 260 }}>{o.summary}</td>
                <td>{o.fulfilment === "PICKUP" ? "Pickup" : "Shop delivery"}</td>
                <td>{o.payment_method === "ONLINE" ? "Online" : "Pay later"}<div><Status value={o.payment_status} /></div></td>
                <td><b>{money(o.total_amount)}</b></td>
                <td><Status value={o.status} /></td>
                <td>{when(o.created_at)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
