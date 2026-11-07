"use client";

import { useState } from "react";
import { api, useApi, useMutation } from "@/lib/api";
import { Badge, Card, Empty, ErrorBox, Field, Loading, Modal, money, Notice, PageHeader, Status } from "@/components/ui";
import { ExcelImport, ImageField, previewSrc } from "@/components/workflow";

const VERTICALS = [["MARKETPLACE", "Shop & Buy (marketplace)"], ["SERVICES", "Book Services"], ["TASKS", "Tasks & Errands"], ["ASSISTANCE", "Personal Assistance"]];
const WORKFLOWS = ["MENU_DRIVEN", "PROPERTY_REQUIREMENT", "CATEGORY_SPECIAL_REQUEST", "GLOBAL_SPECIAL_REQUEST", "JOB_RECRUITMENT", "EVENT_BRIEF", "API_PRICED"];
const PRICING = ["QUOTE_REQUIRED", "FIXED", "STARTING_FROM", "RANGE", "PER_UNIT", "HOURLY", "VISIT_PLUS_LABOUR", "PACKAGE", "API_PRICED"];

type Editing =
  | { kind: "category"; item?: any }
  | { kind: "subcategory"; item?: any }
  | { kind: "service"; item?: any };

function parseSchema(text: string): { ok: true; value: unknown } | { ok: false; error: string } {
  if (!text.trim()) return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}

export default function CataloguePage() {
  const tree = useApi<any[]>("admin/catalogue/tree");
  const [cat, setCat] = useState<any>(null);
  const [sub, setSub] = useState<any>(null);
  const subs = useApi<any[]>(cat ? `admin/catalogue/categories/${cat.id}/subcategories` : null, [cat?.id]);
  const svcs = useApi<any[]>(sub ? `admin/catalogue/subcategories/${sub.id}/services` : null, [sub?.id]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [form, setForm] = useState<Record<string, any>>({});
  const m = useMutation();
  const [showExcel, setShowExcel] = useState(false);
  const reloadAll = () => { tree.reload(); subs.reload(); svcs.reload(); };
  const groups = Array.from(new Set((svcs.data ?? []).map((x) => x.menu_group).filter(Boolean))) as string[];

  const open = (e: Editing) => {
    const it = e.item ?? {};
    setForm({
      code: it.code ?? "", name: it.name ?? "", image_url: it.image_url ?? "", description: it.description ?? "", icon: it.icon ?? "", sort_order: it.sort_order ?? "",
      active: it.active ?? true, workflow_template: it.workflow_template ?? "MENU_DRIVEN", vertical: it.vertical ?? "SERVICES", is_global_special: it.is_global_special ?? false,
      advisory_notice: it.advisory_notice ?? "", pricing_type: it.pricing_type ?? "QUOTE_REQUIRED", base_price: it.base_price ?? "",
      min_price: it.min_price ?? "", max_price: it.max_price ?? "", unit: it.unit ?? "", is_other: it.is_other ?? false,
      pay_on_delivery_allowed: it.pay_on_delivery_allowed ?? true, menu_group: it.menu_group ?? "",
      schema: (e.kind === "category" ? it.default_form_schema : it.form_schema) ?? "",
    });
    m.setError(null);
    setEditing(e);
  };

  const save = async () => {
    if (!editing) return;
    const schema = parseSchema(typeof form.schema === "string" ? form.schema : JSON.stringify(form.schema));
    if (!schema.ok) {
      m.setError("Form schema is not valid JSON: " + schema.error);
      return;
    }
    const num = (v: any) => (v === "" || v == null ? null : Number(v));
    const base = { code: form.code, name: form.name, description: form.description || null, icon: form.icon || null, sort_order: num(form.sort_order), active: form.active };
    let body: any;
    let path: string;
    let method: "POST" | "PUT" = editing.item ? "PUT" : "POST";
    if (editing.kind === "category") {
      body = { ...base, image_url: form.image_url || null, workflow_template: form.workflow_template, vertical: form.vertical, is_global_special: form.is_global_special, advisory_notice: form.advisory_notice || null, default_form_schema: schema.value };
      path = editing.item ? `admin/catalogue/categories/${editing.item.id}` : "admin/catalogue/categories";
    } else if (editing.kind === "subcategory") {
      body = base;
      path = editing.item ? `admin/catalogue/subcategories/${editing.item.id}` : `admin/catalogue/categories/${cat.id}/subcategories`;
    } else {
      body = { ...base, image_url: form.image_url || null, pricing_type: form.pricing_type, base_price: num(form.base_price), min_price: num(form.min_price), max_price: num(form.max_price),
        unit: form.unit || null, menu_group: form.menu_group || null, is_other: form.is_other, pay_on_delivery_allowed: form.pay_on_delivery_allowed, form_schema: schema.value };
      path = editing.item ? `admin/catalogue/services/${editing.item.id}` : `admin/catalogue/subcategories/${sub.id}/services`;
    }
    if (await m.run(() => api(path, { method, body }))) {
      setEditing(null);
      reloadAll();
    }
  };

  const move = async (list: any[], index: number, dir: -1 | 1, path: string, reload: () => void) => {
    const ids = list.map((x) => x.id);
    const j = index + dir;
    if (j < 0 || j >= ids.length) return;
    [ids[index], ids[j]] = [ids[j], ids[index]];
    if (await m.run(() => api(path, { method: "POST", body: { ordered_ids: ids } }))) reload();
  };

  return (
    <div className="stack">
      <PageHeader title="Catalogue" subtitle="Category → Subcategory → Service → Dynamic form. Changes go live without a deployment."
        actions={<>
          <button className={`btn ${showExcel ? "btn-secondary" : "btn-outline"}`} onClick={() => setShowExcel(!showExcel)}>📊 Excel import / export</button>
          <button className="btn btn-primary" onClick={() => open({ kind: "category" })}>+ New category</button>
        </>} />
      {showExcel ? (
        <ExcelImport title="Bulk edit the catalogue in Excel"
          templates={[{ label: "Full catalogue (.xlsx)", href: "/api/backend/admin/catalogue/excel/template?prefill=true" }, { label: "Empty template", href: "/api/backend/admin/catalogue/excel/template?prefill=false" }]}
          previewPath="admin/catalogue/excel/preview" importPath="admin/catalogue/excel/import"
          describe={(r: any) => [r.category, r.subcategory, r.item].filter(Boolean).join(" › ")} onDone={reloadAll} />
      ) : null}
      {showExcel ? (
        <Notice>One row = one menu item. Columns: <b>category</b> → <b>subcategory</b> → <b>menu_group</b> (the heading items are grouped under, e.g. &ldquo;Fans&rdquo;, &ldquo;Switches &amp; sockets&rdquo;) → <b>item</b> with price. Rows are matched by code: an existing code updates, a new code creates. For images, upload them in any edit form below (⬆ Upload), copy the <span className="mono">/api/v1/media/…</span> link into <span className="mono">image_url</span>, or use any https:// link. Don&apos;t paste pictures into the sheet.</Notice>
      ) : null}
      {m.error && !editing ? <ErrorBox message={m.error} /> : null}
      <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", alignItems: "start" }}>
        <Card title={`Categories (${tree.data?.length ?? 0})`}>
          {tree.loading && !tree.data ? <Loading /> : tree.error ? <ErrorBox message={tree.error} onRetry={tree.reload} /> : (tree.data ?? []).map((c, i, arr) => (
            <div key={c.id} className={`tree-item ${cat?.id === c.id ? "active" : ""}`} onClick={() => { setCat(c); setSub(null); }}>
              <span>{c.name} {!c.active ? <Badge tone="danger">inactive</Badge> : null}<div className="muted small">{(VERTICALS.find((v) => v[0] === c.vertical) ?? ["", c.vertical])[1]} • {c.subcategory_count} sub • {c.approved_partner_count} partners</div></span>
              <span className="row" onClick={(e) => e.stopPropagation()}>
                <button className="btn btn-ghost btn-sm" aria-label="Move up" onClick={() => move(arr, i, -1, "admin/catalogue/categories/reorder", tree.reload)}>↑</button>
                <button className="btn btn-ghost btn-sm" aria-label="Move down" onClick={() => move(arr, i, 1, "admin/catalogue/categories/reorder", tree.reload)}>↓</button>
                <button className="btn btn-outline btn-sm" onClick={() => open({ kind: "category", item: c })}>Edit</button>
              </span>
            </div>
          ))}
        </Card>
        <Card title={cat ? `${cat.name} › Subcategories` : "Subcategories"} actions={cat ? <button className="btn btn-secondary btn-sm" onClick={() => open({ kind: "subcategory" })}>+ Add subcategory</button> : null}>
          {!cat ? <Empty title="Select a category" hint="Click a category on the left to see and add its subcategories." /> : subs.loading && !subs.data ? <Loading /> : !(subs.data ?? []).length ? <Empty title="No subcategories yet" hint="Use “+ Add subcategory” (e.g. Electricians, Plumbers) or the Excel import." /> : (subs.data ?? []).map((s, i, arr) => (
            <div key={s.id} className={`tree-item ${sub?.id === s.id ? "active" : ""}`} onClick={() => setSub(s)}>
              <span>{s.name} {!s.active ? <Badge tone="danger">inactive</Badge> : null}<div className="muted small">{s.service_count} services • {s.approved_partner_count} partners</div></span>
              <span className="row" onClick={(e) => e.stopPropagation()}>
                <button className="btn btn-ghost btn-sm" aria-label="Move up" onClick={() => move(arr, i, -1, `admin/catalogue/categories/${cat.id}/subcategories/reorder`, subs.reload)}>↑</button>
                <button className="btn btn-ghost btn-sm" aria-label="Move down" onClick={() => move(arr, i, 1, `admin/catalogue/categories/${cat.id}/subcategories/reorder`, subs.reload)}>↓</button>
                <button className="btn btn-outline btn-sm" onClick={() => open({ kind: "subcategory", item: s })}>Edit</button>
              </span>
            </div>
          ))}
        </Card>
        <Card title={sub ? `${sub.name} › Menu items` : "Menu items / services"} actions={sub ? <button className="btn btn-secondary btn-sm" onClick={() => open({ kind: "service" })}>+ Add menu item</button> : null}>
          {!sub ? <Empty title="Select a subcategory" hint="Then add menu items (e.g. Fan repair ₹149) grouped under menu headings." /> : svcs.loading && !svcs.data ? <Loading /> : (svcs.data ?? []).map((s, i, arr) => (
            <div key={s.id}>
            {s.menu_group && s.menu_group !== arr[i - 1]?.menu_group ? <div className="nav-section" style={{ padding: "10px 4px 4px", color: "var(--muted)" }}>{s.menu_group}</div> : null}
            <div className="tree-item" onClick={() => open({ kind: "service", item: s })}>
              <span className="row" style={{ gap: 8, flexWrap: "nowrap" }}>{s.image_url ? <img className="thumb" src={previewSrc(s.image_url)} alt="" /> : null}<span>{s.name} {s.is_other ? <Badge tone="info">other / special</Badge> : null} {!s.active ? <Badge tone="danger">inactive</Badge> : null}
                <div className="muted small">{s.pricing_type.replace(/_/g, " ")}{s.base_price ? ` • ${money(s.base_price)}` : ""}{s.form_schema ? " • custom form" : " • category form"}{s.pay_on_delivery_allowed ? "" : " • no POD"}</div>
              </span></span>
              <Status value={s.active ? "ACTIVE" : "INACTIVE"} />
            </div>
            </div>
          ))}
        </Card>
      </div>

      <Modal title={`${editing?.item ? "Edit" : "New"} ${editing?.kind ?? ""}`} open={!!editing} onClose={() => setEditing(null)}
        footer={<button className="btn btn-primary" onClick={save} disabled={m.busy}>{m.busy ? "Saving…" : "Save"}</button>}>
        <div className="grid" style={{ gridTemplateColumns: "1fr 1fr" }}>
          <Field label="Code (A-Z, 0-9, _)"><input value={form.code ?? ""} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} /></Field>
          <Field label="Name"><input value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label="Icon key"><input value={form.icon ?? ""} onChange={(e) => setForm({ ...form, icon: e.target.value })} placeholder="e.g. tools, pipe" /></Field>
          <Field label="Sort order"><input value={form.sort_order ?? ""} onChange={(e) => setForm({ ...form, sort_order: e.target.value })} inputMode="numeric" /></Field>
        </div>
        {editing?.kind !== "subcategory" ? (
          <ImageField label="Image (optional — shown on category / item cards)" value={form.image_url ?? ""} onChange={(v) => setForm({ ...form, image_url: v })} />
        ) : null}
        {editing?.kind === "service" ? (
          <Field label="Menu group (heading in the app, e.g. Fans, Lights, Switches & sockets)">
            <input value={form.menu_group ?? ""} onChange={(e) => setForm({ ...form, menu_group: e.target.value })} list="menu-groups" placeholder="Leave empty for no heading" />
            <datalist id="menu-groups">{groups.map((g) => <option key={g} value={g} />)}</datalist>
          </Field>
        ) : null}
        <Field label="Description"><textarea value={form.description ?? ""} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
        {editing?.kind === "category" ? (
          <>
            <Field label="Business vertical (where customers find it)"><select value={form.vertical} onChange={(e) => setForm({ ...form, vertical: e.target.value })}>{VERTICALS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></Field>
            <Field label="Workflow template"><select value={form.workflow_template} onChange={(e) => setForm({ ...form, workflow_template: e.target.value })}>{WORKFLOWS.map((w) => <option key={w}>{w}</option>)}</select></Field>
            <Field label="Advisory notice (shown to customers)"><textarea value={form.advisory_notice ?? ""} onChange={(e) => setForm({ ...form, advisory_notice: e.target.value })} /></Field>
            <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={!!form.is_global_special} onChange={(e) => setForm({ ...form, is_global_special: e.target.checked })} /> Global special-request category</label>
          </>
        ) : null}
        {editing?.kind === "service" ? (
          <div className="grid" style={{ gridTemplateColumns: "1fr 1fr" }}>
            <Field label="Pricing type"><select value={form.pricing_type} onChange={(e) => setForm({ ...form, pricing_type: e.target.value })}>{PRICING.map((p) => <option key={p}>{p}</option>)}</select></Field>
            <Field label="Unit"><input value={form.unit ?? ""} onChange={(e) => setForm({ ...form, unit: e.target.value })} placeholder="per visit, per sq ft…" /></Field>
            <Field label="Base price ₹"><input value={form.base_price ?? ""} onChange={(e) => setForm({ ...form, base_price: e.target.value })} inputMode="decimal" /></Field>
            <Field label="Min / Max ₹"><div className="row"><input value={form.min_price ?? ""} onChange={(e) => setForm({ ...form, min_price: e.target.value })} style={{ flex: 1 }} /><input value={form.max_price ?? ""} onChange={(e) => setForm({ ...form, max_price: e.target.value })} style={{ flex: 1 }} /></div></Field>
            <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={!!form.pay_on_delivery_allowed} onChange={(e) => setForm({ ...form, pay_on_delivery_allowed: e.target.checked })} /> Pay on delivery allowed</label>
            <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={!!form.is_other} onChange={(e) => setForm({ ...form, is_other: e.target.checked })} /> &ldquo;Other requirement&rdquo; item</label>
          </div>
        ) : null}
        <label className="row" style={{ margin: "8px 0" }}><input type="checkbox" style={{ width: "auto" }} checked={!!form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Active (inactive items are hidden from apps, never deleted)</label>
        {editing?.kind !== "subcategory" ? (
          <Field label={editing?.kind === "category" ? "Default request form (JSON)" : "Request form (JSON; empty = use category form)"}>
            <textarea className="mono" style={{ minHeight: 180 }} value={typeof form.schema === "string" ? form.schema : JSON.stringify(form.schema, null, 2)}
              onChange={(e) => setForm({ ...form, schema: e.target.value })} placeholder='{"fields":[{"key":"details","label":"Describe","type":"textarea","required":true}]}' />
          </Field>
        ) : <Notice>An &ldquo;Other … Requirement&rdquo; service is created automatically for every new subcategory.</Notice>}
        {editing?.kind !== "subcategory" ? <p className="muted small">Field types: text, textarea, number, select, multiselect, date, datetime, boolean, photo. The server validates the schema and every submission.</p> : null}
        {m.error ? <ErrorBox message={m.error} /> : null}
      </Modal>
    </div>
  );
}
