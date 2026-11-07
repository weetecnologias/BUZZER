"use client";

import { useEffect, useRef, useState } from "react";

export type ExportColumn = { key: string; label: string };

function guessColumns(rows: Record<string, unknown>[]): ExportColumn[] {
  const keys = Object.keys(rows[0] ?? {}).filter((k) => !k.endsWith("_id") && k !== "id");
  return keys.map((k) => ({ key: k, label: k.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase()) }));
}

function cell(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

function save(blob: Blob, name: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

export function exportCsv(name: string, rows: Record<string, unknown>[], columns?: ExportColumn[]) {
  const cols = columns ?? guessColumns(rows);
  const esc = (v: unknown) => {
    let s = cell(v);
    if (/^[=+\-@]/.test(s)) s = "'" + s; // spreadsheet formula injection guard
    return `"${s.replace(/"/g, '""')}"`;
  };
  const csv = [cols.map((c) => esc(c.label)).join(","), ...rows.map((r) => cols.map((c) => esc(r[c.key])).join(","))].join("\n");
  save(new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8" }), `${name}.csv`);
}

export async function exportXlsx(name: string, rows: Record<string, unknown>[], columns?: ExportColumn[]) {
  const cols = columns ?? guessColumns(rows);
  const res = await fetch("/api/backend/admin/export/xlsx", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title: name, columns: cols, rows: rows.map((r) => Object.fromEntries(cols.map((c) => [c.key, r[c.key] ?? null]))) }),
  });
  if (!res.ok) throw new Error("Excel export failed");
  save(await res.blob(), `${name}.xlsx`);
}

/** PDF via the browser's print dialog ("Save as PDF") using a clean, branded, printable table. */
export function exportPdf(title: string, rows: Record<string, unknown>[], columns?: ExportColumn[]) {
  const cols = columns ?? guessColumns(rows);
  const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title>
<style>
  body{font-family:Inter,Segoe UI,Roboto,sans-serif;color:#14251B;margin:24px}
  h1{font-size:18px;margin:0 0 4px;color:#064E32} p{margin:0 0 14px;color:#66756B;font-size:12px}
  table{width:100%;border-collapse:collapse;font-size:10.5px} th{background:#064E32;color:#fff;text-align:left;padding:6px}
  td{padding:5px 6px;border-bottom:1px solid #DCE9DF;vertical-align:top} tr:nth-child(even) td{background:#F2FAF4}
  @page{size:A4 landscape;margin:12mm}
</style></head><body>
<h1>BUZZER — ${esc(title)}</h1><p>Exported ${new Date().toLocaleString("en-IN")} • ${rows.length} rows</p>
<table><thead><tr>${cols.map((c) => `<th>${esc(c.label)}</th>`).join("")}</tr></thead>
<tbody>${rows.map((r) => `<tr>${cols.map((c) => `<td>${esc(cell(r[c.key]))}</td>`).join("")}</tr>`).join("")}</tbody></table>
<script>window.onload=()=>{window.print();}</script></body></html>`;
  const w = window.open("", "_blank");
  if (!w) {
    alert("Allow pop-ups for this site to export PDF.");
    return;
  }
  w.document.open();
  w.document.write(html);
  w.document.close();
}

/** "Export ▾" menu offering Excel, CSV and PDF for any admin table. */
export function ExportMenu({ name, rows, columns, title }: { name: string; rows: Record<string, unknown>[] | null | undefined; columns?: ExportColumn[]; title?: string }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const close = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);
  const data = rows ?? [];
  const run = async (fn: () => Promise<void> | void) => {
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      alert((e as Error).message);
    } finally {
      setBusy(false);
      setOpen(false);
    }
  };
  return (
    <div ref={ref} style={{ position: "relative" }}>
      <button className="btn btn-outline" disabled={!data.length || busy} onClick={() => setOpen((o) => !o)} aria-haspopup="menu" aria-expanded={open}>
        {busy ? "Exporting…" : "⬇ Export ▾"}
      </button>
      {open ? (
        <div role="menu" className="menu">
          <button role="menuitem" onClick={() => run(() => exportXlsx(name, data, columns))}>📗 Excel (.xlsx)</button>
          <button role="menuitem" onClick={() => run(() => exportCsv(name, data, columns))}>📄 CSV</button>
          <button role="menuitem" onClick={() => run(() => exportPdf(title ?? name, data, columns))}>📕 PDF</button>
        </div>
      ) : null}
    </div>
  );
}
