"use client";

import { useMemo, useRef, useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { ErrorBox, Field, Modal, Notice } from "@/components/ui";

/** Uploads a file (multipart) through the admin BFF. */
export async function uploadFile<T>(path: string, file: File): Promise<T> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`/api/backend/${path}`, { method: "POST", body: fd });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(json.error?.message ?? "Upload failed") as Error & { details?: any };
    err.details = json.error?.details;
    throw err;
  }
  return json.data as T;
}

type PreviewRow = { row: number; action?: string; errors: string[]; [k: string]: unknown };
type Preview = { rows: PreviewRow[]; error_rows: number; [k: string]: unknown };

/**
 * Excel round-trip widget: download template -> fill -> upload -> preview (row-level errors) -> import.
 * Imports are all-or-nothing on the server; invalid rows are never silently skipped.
 */
export function ExcelImport({ title, templates, previewPath, importPath, describe, onDone }: {
  title: string;
  templates: { label: string; href: string }[];
  previewPath: string;
  importPath: string;
  describe: (r: PreviewRow) => string;
  onDone: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const pick = async (f: File | undefined) => {
    if (!f) return;
    setFile(f);
    setError(null);
    setDone(null);
    setPreview(null);
    setBusy(true);
    try {
      setPreview(await uploadFile<Preview>(previewPath, f));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  };

  const doImport = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await uploadFile(importPath, file);
      setDone(`Imported ${file.name} successfully.`);
      setPreview(null);
      setFile(null);
      onDone();
    } catch (e) {
      const err = e as Error & { details?: any };
      setError(err.message);
      if (err.details?.preview) setPreview(err.details.preview);
    } finally {
      setBusy(false);
    }
  };

  const errorRows = (preview?.rows ?? []).filter((r) => r.errors?.length);
  return (
    <div className="dropzone stack" style={{ gap: 10 }}>
      <div className="spread">
        <b>{title}</b>
        <div className="row">
          {templates.map((t) => <a key={t.href} className="btn btn-outline btn-sm" href={t.href}>⬇ {t.label}</a>)}
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => input.current?.click()}>⬆ Upload Excel</button>
          <input ref={input} type="file" accept=".xlsx" hidden onChange={(e) => pick(e.target.files?.[0])} />
        </div>
      </div>
      <div className="muted small">Download the sheet, edit it in Excel / Google Sheets, then upload. You'll see a preview before anything changes.</div>
      {busy ? <div className="muted">Working…</div> : null}
      {error ? <ErrorBox message={error} /> : null}
      {done ? <Notice tone="success">{done}</Notice> : null}
      {preview ? (
        <div className="card stack" style={{ gap: 8 }}>
          <div className="row">
            <b>Preview of {file?.name}</b>
            {Object.entries(preview).filter(([k, v]) => typeof v === "number").map(([k, v]) => (
              <span key={k} className={`badge ${k === "error_rows" && Number(v) > 0 ? "tone-danger" : "tone-info"}`}>{k.replace(/_/g, " ")}: {String(v)}</span>
            ))}
          </div>
          {errorRows.length ? (
            <div className="table-wrap" style={{ maxHeight: 260, overflow: "auto" }}>
              <table><thead><tr><th>Row</th><th>Item</th><th>Errors</th></tr></thead>
                <tbody>{errorRows.map((r) => <tr key={r.row}><td>{r.row}</td><td>{describe(r)}</td><td style={{ color: "var(--danger)" }}>{r.errors.join("; ")}</td></tr>)}</tbody>
              </table>
            </div>
          ) : <Notice tone="success">All rows are valid.</Notice>}
          <div className="row">
            <button className="btn btn-primary" disabled={busy || errorRows.length > 0} onClick={doImport}>Import now</button>
            <button className="btn btn-ghost" onClick={() => { setPreview(null); setFile(null); }}>Cancel</button>
            {errorRows.length ? <span className="muted small">Fix the rows above in your sheet and upload again.</span> : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

type Staff = { user_id: string; full_name: string | null; display_name: string | null; agent_id: string | null; wing: string | null; is_manager: boolean | null;
  availability: string | null; roles: string; open_requests: number };

/** Assign an exception / transfer a request to another agent or manager (grouped by wing). */
export function TransferModal({ open, onClose, onDone, mode, exceptionId, requestId, canMoveRequest }: {
  open: boolean; onClose: () => void; onDone: () => void; mode: "exception" | "request";
  exceptionId?: string; requestId?: string; canMoveRequest?: boolean;
}) {
  const staff = useApi<Staff[]>(open ? "admin/staff" : null, [open]);
  const m = useMutation();
  const [to, setTo] = useState("");
  const [note, setNote] = useState("");
  const [moveRequest, setMoveRequest] = useState(true);
  const list = mode === "request" ? (staff.data ?? []).filter((s) => s.agent_id) : staff.data ?? [];
  const wings = useMemo(() => {
    const g = new Map<string, Staff[]>();
    list.forEach((s) => {
      const w = s.wing ?? "Admin & support";
      g.set(w, [...(g.get(w) ?? []), s]);
    });
    return [...g.entries()];
  }, [list]);
  const chosen = list.find((s) => (mode === "request" ? s.agent_id : s.user_id) === to);

  const submit = async () => {
    const ok = mode === "request"
      ? await m.run(() => api(`admin/requests/${requestId}/transfer`, { method: "POST", body: { agent_id: to, reason: note } }))
      : await m.run(() => api(`admin/exceptions/${exceptionId}/assign`, { method: "POST", body: {
          owner_user_id: to, note: note || null, transfer_request_to_agent_id: canMoveRequest && moveRequest && chosen?.agent_id ? chosen.agent_id : null } }));
    if (ok) {
      setTo("");
      setNote("");
      onDone();
    }
  };

  return (
    <Modal title={mode === "request" ? "Transfer request" : "Assign / transfer"} open={open} onClose={onClose}
      footer={<button className="btn btn-primary" disabled={!to || m.busy || (mode === "request" && note.trim().length < 3)} onClick={submit}>{m.busy ? "Saving…" : "Transfer"}</button>}>
      <Field label={mode === "request" ? "Transfer to agent / manager" : "New owner"}>
        <select value={to} onChange={(e) => setTo(e.target.value)}>
          <option value="">Select…</option>
          {wings.map(([w, people]) => (
            <optgroup key={w} label={w}>
              {people.map((s) => {
                const id = mode === "request" ? s.agent_id! : s.user_id;
                return <option key={id} value={id}>{(s.display_name ?? s.full_name ?? "Staff") + (s.is_manager ? " (manager)" : "") + (s.availability ? ` — ${s.availability}` : "") + ` • ${s.open_requests} open`}</option>;
              })}
            </optgroup>
          ))}
        </select>
      </Field>
      {mode === "exception" && canMoveRequest ? (
        <label className="row" style={{ marginBottom: 10 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={moveRequest} onChange={(e) => setMoveRequest(e.target.checked)} />
          Also transfer the related request to this person {chosen && !chosen.agent_id ? "(not an agent — only ownership changes)" : ""}
        </label>
      ) : null}
      <Field label={mode === "request" ? "Reason (required, shown in the timeline)" : "Note"}><textarea value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      {m.error ? <ErrorBox message={m.error} /> : null}
    </Modal>
  );
}

/** Shows a stored image URL (https:// or /api/v1/media/...) through the admin proxy when it is local. */
export function previewSrc(url: string | null | undefined) {
  if (!url) return "";
  return url.startsWith("/api/v1/") ? url.replace("/api/v1/", "/api/backend/") : url;
}

/** Image URL input with an "Upload" button that stores the file in the BUZZER media library. */
export function ImageField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pick = async (f: File | undefined) => {
    if (!f) return;
    setBusy(true);
    setError(null);
    try {
      const res = await uploadFile<{ url: string }>("admin/media", f);
      onChange(res.url);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  };
  return (
    <Field label={label}>
      <div className="row" style={{ alignItems: "center" }}>
        {value ? <img className="thumb" src={previewSrc(value)} alt="" /> : null}
        <input value={value} onChange={(e) => onChange(e.target.value)} placeholder="https://… or upload" style={{ flex: 1, minWidth: 180 }} />
        <button type="button" className="btn btn-outline btn-sm" disabled={busy} onClick={() => input.current?.click()}>{busy ? "Uploading…" : "⬆ Upload"}</button>
        {value ? <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange("")}>Remove</button> : null}
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={(e) => pick(e.target.files?.[0])} />
      </div>
      <div className="muted small">PNG / JPG / WebP up to 2 MB, square (e.g. 600×600) looks best.</div>
      {error ? <div style={{ color: "var(--danger)" }} className="small">{error}</div> : null}
    </Field>
  );
}
