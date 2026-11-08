"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ReactNode, useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Card, ErrorBox, Field, Loading, Modal, money, PageHeader, Status, when } from "@/components/ui";

function KV({ k, v }: { k: string; v: ReactNode }) {
  return <tr><td className="muted">{k}</td><td>{v}</td></tr>;
}

/** One order: frozen snapshot of items, prices, shop and fulfilment, payments and the shared timeline. */
export default function OrderDetail() {
  const { id } = useParams<{ id: string }>();
  const q = useApi<any>(`admin/orders/${id}`, [id]);
  const m = useMutation();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState("");
  const o = q.data;
  if (q.loading && !o) return <Loading />;
  if (q.error || !o) return <ErrorBox message={q.error ?? "Not found"} onRetry={q.reload} />;
  const v = o.vendor_snapshot ?? {};
  const f = o.fulfilment_snapshot ?? {};
  const open = !["COMPLETED", "CANCELLED", "VENDOR_REJECTED"].includes(o.status);
  return (
    <div className="stack">
      <PageHeader title={`Order ${o.order_number}`} subtitle={`${o.status_label} · ${o.source === "RFQ" ? "accepted quotation" : "direct purchase"}`}
        actions={<>{open ? <button className="btn btn-outline" onClick={() => setCancelOpen(true)}>Cancel order</button> : null}<Link className="btn btn-outline" href="/orders">Back</Link></>} />
      {m.error ? <ErrorBox message={m.error} /> : null}
      <div className="grid grid-2">
        <Card title="Summary">
          <table><tbody>
            <KV k="Status" v={<Status value={o.status} />} />
            <KV k="Request" v={<Link href={`/requests/${o.request_id}`}>{o.request_number}</Link>} />
            {o.quotation_number ? <KV k="Quotation" v={o.quotation_number} /> : null}
            <KV k="Customer" v={`${o.customer_name} (${o.customer_phone})`} />
            <KV k="Shop" v={<Link href={`/partners/${o.partner_id}`}>{v.name}</Link>} />
            <KV k="Fulfilment" v={o.fulfilment === "PICKUP" ? `Pickup at ${f.pickup_location?.name ?? v.location?.name ?? "the shop"}`
              : `Delivery to ${f.delivery_address?.line1 ?? ""}${f.delivery_address?.locality_name ? ", " + f.delivery_address.locality_name : ""}`} />
            <KV k="Payment" v={o.payment_method === "ONLINE" ? "Online" : "Pay at pickup / on delivery"} />
            <KV k="Placed" v={when(o.created_at)} />
            {o.vendor_confirmed_at ? <KV k="Shop confirmed" v={when(o.vendor_confirmed_at)} /> : null}
            {o.completed_at ? <KV k="Completed" v={when(o.completed_at)} /> : null}
            {o.cancel_reason ? <KV k="Reason" v={o.cancel_reason} /> : null}
            <KV k="Wrong code attempts" v={o.code_attempts} />
            {f.quote_terms ? <KV k="Shop's terms" v={f.quote_terms} /> : null}
          </tbody></table>
        </Card>
        <Card title="Items (frozen at order time)">
          <table>
            <thead><tr><th>Item</th><th>Qty</th><th>Unit price</th><th>Disc.</th><th>GST</th><th>Total</th></tr></thead>
            <tbody>{(o.items ?? []).map((i: any) => (
              <tr key={i.line_no}>
                <td>{i.name}{i.variant_name && i.variant_name !== "Standard" ? ` (${i.variant_name})` : ""}<div className="muted small">{[i.brand, i.specs].filter(Boolean).join(" · ")}</div></td>
                <td>{i.quantity} {i.unit ?? ""}</td>
                <td>{money(i.unit_price)}</td>
                <td>{Number(i.discount_amount) ? money(i.discount_amount) : "—"}</td>
                <td>{Number(i.tax_rate)}% · {money(i.tax_amount)}</td>
                <td><b>{money(i.line_total)}</b></td>
              </tr>
            ))}</tbody>
          </table>
          <table><tbody>
            <KV k="Items" v={money(o.items_total)} />
            {Number(o.discount_total) ? <KV k="Discount" v={`- ${money(o.discount_total)}`} /> : null}
            <KV k={o.prices_include_tax ? "GST included" : "GST"} v={money(o.tax_total)} />
            <KV k="Delivery" v={money(o.delivery_fee)} />
            <KV k="Total" v={<b>{money(o.total_amount)}</b>} />
          </tbody></table>
        </Card>
      </div>
      <Card title="Payments">
        {(o.payments ?? []).length ? (
          <table>
            <thead><tr><th>Payment</th><th>Method</th><th>Amount</th><th>Status</th><th>When</th></tr></thead>
            <tbody>{o.payments.map((p: any) => (
              <tr key={p.id}><td>{p.payment_number}{p.test_mode ? <span className="muted small"> (test)</span> : null}</td><td>{p.method}</td><td>{money(p.amount)}</td>
                <td><Status value={p.status} />{p.failure_reason ? <div className="muted small">{p.failure_reason}</div> : null}</td><td>{when(p.created_at)}</td></tr>
            ))}</tbody>
          </table>
        ) : <span className="muted">No payments</span>}
      </Card>
      <Card title="Timeline">
        <table>
          <thead><tr><th>When</th><th>Status</th><th>By</th><th>Details</th></tr></thead>
          <tbody>{(o.timeline ?? []).map((h: any, i: number) => (
            <tr key={i}><td>{when(h.created_at)}</td><td><Status value={h.to_status} /></td><td>{h.actor_role ?? "SYSTEM"}{h.actor_name ? ` · ${h.actor_name}` : ""}</td><td className="small">{h.reason}</td></tr>
          ))}</tbody>
        </table>
      </Card>
      <Modal title="Cancel this order?" open={cancelOpen} onClose={() => setCancelOpen(false)}
        footer={<button className="btn btn-primary btn-sm" disabled={reason.trim().length < 3 || m.busy}
          onClick={async () => { if (await m.run(() => api(`admin/orders/${id}/cancel`, { method: "POST", body: { reason } }))) { setCancelOpen(false); q.reload(); } }}>Cancel order</button>}>
        <p className="small">The customer and the shop are notified. If it was paid online, a refund is opened for Finance to approve.</p>
        <Field label="Reason"><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
      </Modal>
    </div>
  );
}
