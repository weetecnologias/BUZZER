"""Category catalogue (25 configurable categories under 6 modules), search and shared metadata."""
import re

from flask import jsonify, request

from .. import config
from ..db import jload, one, q
from ..security import fail
from ..workflow import STATUS_META
from . import bp


def _cat(row, with_detail=False):
    if not row:
        return None
    c = {k: row[k] for k in ("id", "slug", "name", "module_id", "description", "icon", "color", "phase",
                             "is_active", "sort", "workflow_template_id")}
    c["is_active"] = bool(c["is_active"])
    if with_detail:
        c["schema"] = jload(row["schema_json"], [])
        c["includes"] = jload(row["includes_json"], [])
        c["how"] = jload(row["how_json"], [])
        c["prepare"] = jload(row["prepare_json"], [])
    return c


@bp.get("/health")
def health():
    return jsonify({"status": "ok", "service": "buzzer", "env": config.ENV})


@bp.get("/meta")
def meta():
    return jsonify({
        "statuses": [{"key": k, "label": l, "next": n, "tone": t} for k, l, n, t in STATUS_META],
        "localities": config.LOCALITIES,
        "city": config.CITY,
        "env": config.ENV,
    })


@bp.get("/openapi.json")
def openapi():
    """Lightweight route inventory generated from the live URL map (finalise a full OpenAPI spec before launch)."""
    from flask import current_app
    paths = {}
    for rule in current_app.url_map.iter_rules():
        if not rule.rule.startswith("/api/v1"):
            continue
        view = current_app.view_functions[rule.endpoint]
        for m in sorted(rule.methods - {"HEAD", "OPTIONS"}):
            paths.setdefault(rule.rule.replace("<", "{").replace(">", "}"), {})[m.lower()] = {
                "summary": (view.__doc__ or view.__name__.replace("_", " ")).strip().split("\n")[0]}
    return jsonify({"openapi": "3.0.0", "info": {"title": "BUZZER API", "version": "1.0"}, "servers": [{"url": "/"}],
                    "paths": dict(sorted(paths.items()))})


@bp.get("/categories")
def categories():
    show_all = request.args.get("all") == "1"
    where = "" if show_all else ""
    rows = q(f"SELECT * FROM categories {where} ORDER BY sort")
    mods = q("SELECT * FROM modules ORDER BY sort")
    flat = [_cat(r) for r in rows]
    for m in mods:
        m["categories"] = [c for c in flat if c["module_id"] == m["id"]]
    return jsonify({"modules": mods, "items": flat})


def _by_ref(ref):
    return one("SELECT * FROM categories WHERE id=? OR slug=?", (ref, ref))


@bp.get("/categories/<ref>")
def category_detail(ref):
    row = _by_ref(ref)
    if not row:
        fail(404, "not_found", "That category was not found.")
    c = _cat(row, True)
    c["subcategories"] = q("SELECT id,name FROM subcategories WHERE category_id=? ORDER BY sort", (row["id"],))
    c["module"] = one("SELECT id,name FROM modules WHERE id=?", (row["module_id"],))
    t = one("SELECT id,code,name,description,stages_json FROM workflow_templates WHERE id=?",
            (row["workflow_template_id"],))
    if t:
        t["stages"] = jload(t.pop("stages_json"), [])
    c["workflow"] = t
    return jsonify(c)


@bp.get("/categories/<ref>/schema")
def category_schema(ref):
    row = _by_ref(ref)
    if not row:
        fail(404, "not_found", "That category was not found.")
    return jsonify({"category_id": row["id"], "fields": jload(row["schema_json"], [])})


@bp.get("/search")
def search():
    """Natural-phrase search: 'fix water leakage' -> Home repairs / Plumbing."""
    text = (request.args.get("q") or "").lower().strip()
    if len(text) < 2:
        return jsonify({"items": []})
    words = [w for w in re.findall(r"[a-z0-9]+", text) if len(w) > 2]
    scored = []
    for c in q("SELECT * FROM categories ORDER BY sort"):
        hay = f"{c['name']} {c['keywords']} {c['description']}".lower()
        score = (6 if text in c["name"].lower() else 0) + sum(2 for w in words if w in hay)
        scored.append([score, c, None])
    for s in q("SELECT s.*, c.name AS cname FROM subcategories s JOIN categories c ON c.id=s.category_id"):
        hay = f"{s['name']} {s['keywords']}".lower()
        score = (7 if text in s["name"].lower() else 0) + sum(3 for w in words if w in hay)
        if score:
            cat = next(x for x in scored if x[1]["id"] == s["category_id"])
            if score > cat[0]:
                cat[0], cat[2] = score + 1, s
    scored = sorted([x for x in scored if x[0] > 0], key=lambda x: -x[0])[:6]
    return jsonify({"items": [{"category": _cat(c), "subcategory": ({"id": s["id"], "name": s["name"]} if s else None),
                               "score": sc} for sc, c, s in scored]})
