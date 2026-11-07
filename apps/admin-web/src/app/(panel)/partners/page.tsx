"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { Empty, ErrorBox, Loading, PageHeader, Status, when } from "@/components/ui";

export default function Partners() {
  const router = useRouter();
  const [reg, setReg] = useState("");
  const [kyc, setKyc] = useState("");
  const qs = new URLSearchParams();
  if (reg) qs.set("registrationStatus", reg);
  if (kyc) qs.set("kycStatus", kyc);
  const { data, error, loading, reload } = useApi<any[]>(`admin/partners?${qs}`, [reg, kyc]);
  return (
    <div className="stack">
      <PageHeader title="Partners & KYC" subtitle="Registration, KYC and service approvals are separate decisions"
        actions={<ExportMenu name="partners" title="Partners" rows={data} columns={[{ key: "display_name", label: "Partner" }, { key: "business_name", label: "Business" }, { key: "partner_type", label: "Type" }, { key: "contact_phone", label: "Phone" }, { key: "city_name", label: "City" }, { key: "registration_status", label: "Registration" }, { key: "kyc_status", label: "KYC" }, { key: "approved_capabilities", label: "Approved services" }, { key: "availability", label: "Availability" }, { key: "rating_avg", label: "Rating" }, { key: "cancellations_30d", label: "Cancellations (30d)" }]} />} />
      <div className="row">
        <select value={reg} onChange={(e) => setReg(e.target.value)} style={{ width: 220 }} aria-label="Registration status">
          {["", "SUBMITTED", "APPROVED", "REJECTED", "SUSPENDED"].map((s) => <option key={s} value={s}>{s || "Any registration status"}</option>)}
        </select>
        <select value={kyc} onChange={(e) => setKyc(e.target.value)} style={{ width: 200 }} aria-label="KYC status">
          {["", "PENDING", "SUBMITTED", "VERIFIED", "REJECTED"].map((s) => <option key={s} value={s}>{s || "Any KYC status"}</option>)}
        </select>
      </div>
      {loading && !data ? <Loading /> : error ? <ErrorBox message={error} onRetry={reload} /> : !data?.length ? <Empty title="No partners match" /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Partner</th><th>Type</th><th>City</th><th>Registration</th><th>KYC</th><th>Services</th><th>Availability</th><th>Rating</th><th>Cancels 30d</th><th>Joined</th></tr></thead>
            <tbody>{data.map((p) => (
              <tr key={p.id} className="clickable" onClick={() => router.push(`/partners/${p.id}`)}>
                <td><b>{p.display_name}</b><div className="muted small">{p.business_name ?? p.contact_phone}</div></td>
                <td>{p.partner_type}</td><td>{p.city_name}</td>
                <td><Status value={p.registration_status} /></td><td><Status value={p.kyc_status} /></td>
                <td>{p.approved_capabilities} approved{Number(p.pending_capabilities) ? <div><Status value="REQUESTED" /> {p.pending_capabilities}</div> : null}</td>
                <td><Status value={p.availability} /></td>
                <td>{Number(p.rating_count) ? `★ ${p.rating_avg} (${p.rating_count})` : "—"}</td>
                <td style={{ color: Number(p.cancellations_30d) > 3 ? "var(--danger)" : undefined, fontWeight: 700 }}>{p.cancellations_30d}</td>
                <td>{when(p.created_at)}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
