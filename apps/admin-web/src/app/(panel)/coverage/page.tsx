"use client";

import { useApi } from "@/lib/api";
import { ExportMenu } from "@/components/export";
import { ErrorBox, Loading, PageHeader } from "@/components/ui";

export default function Coverage() {
  const { data, error, loading, reload } = useApi<any[]>("admin/partners/coverage");
  return (
    <div className="stack">
      <PageHeader title="Partner coverage" subtitle="Registered / pending / verified / available partners per service"
        actions={<ExportMenu name="partner-coverage" title="Partner coverage" rows={data} columns={[{ key: "category_name", label: "Category" }, { key: "subcategory_name", label: "Subcategory" }, { key: "registered", label: "Registered" }, { key: "pending", label: "Pending" }, { key: "verified", label: "Verified" }, { key: "available", label: "Available" }]} />} />
      {loading && !data ? <Loading /> : error ? <ErrorBox message={error} onRetry={reload} /> : (
        <div className="card table-wrap" style={{ padding: 0 }}>
          <table>
            <thead><tr><th>Category</th><th>Subcategory</th><th>Registered</th><th>Pending</th><th>Verified</th><th>Available now</th></tr></thead>
            <tbody>{(data ?? []).map((r) => (
              <tr key={r.subcategory_id}>
                <td>{r.category_name}</td><td>{r.subcategory_name}</td><td>{r.registered}</td><td>{r.pending}</td><td>{r.verified}</td>
                <td style={{ fontWeight: 700, color: Number(r.available) === 0 ? "var(--danger)" : "var(--emerald)" }}>{r.available}{Number(r.available) === 0 ? " — no coverage" : ""}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
