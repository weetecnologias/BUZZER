"""Request engine: create, list, detail, status transitions, assignment, notes, invites, milestones."""
import hashlib
from datetime import datetime, timedelta, timezone

from flask import g, jsonify, request

from ..db import insert, jdump, jload, next_ref, now, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, has_perm, json_body, page_args
from ..workflow import (AGENT_MANUAL, STATUS, TERMINAL, TRANSITIONS, agent_name, change_status, pick_agent_for,
                        refresh_workload, set_request_agent)
from . import bp
from .common import (CUSTOMER_VISIBLE_QUOTE, agent_card, can_view_request, get_request, is_staff_agent,
                     provider_card, provider_of, quote_payload, require_request)

TERMINAL_SQL = "('completed','cancelled','rejected','expired')"

LIST_SQL = f"""
SELECT r.id, r.reference, r.title, r.status, r.priority, r.locality, r.preferred_at, r.created_at, r.updated_at,
       r.status_changed_at, r.assigned_agent_id, r.customer_id, r.description,
       c.id AS category_id, c.name AS category_name, c.icon AS category_icon, c.color AS category_color,
       c.slug AS category_slug, u.name AS customer_name, a.display_name AS agent_name,
       (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id AND x.status<>'withdrawn') AS quotes_total,
       (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id
          AND x.status IN ('shared','approved','revised_pending_consent','clarification_requested')) AS quotes_shared,
       (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id AND x.status='submitted') AS quotes_to_validate,
       (SELECT b.id FROM bookings b WHERE b.request_id=r.id AND b.status<>'cancelled') AS booking_id,
       CASE WHEN ws.sla_hours IS NOT NULL AND r.status NOT IN {TERMINAL_SQL}
            AND (julianday('now') - julianday(r.status_changed_at)) * 24 > ws.sla_hours THEN 1 ELSE 0 END AS overdue,
       ROUND((julianday('now') - julianday(r.created_at)) * 24, 1) AS age_hours
FROM requests r
JOIN categories c ON c.id = r.category_id
JOIN users u ON u.id = r.customer_id
LEFT JOIN agents a ON a.user_id = r.assigned_agent_id
LEFT JOIN workflow_steps ws ON ws.template_id = r.workflow_template_id AND ws.status_key = r.status
"""


def _staff_can_manage(user, r) -> bool:
    if has_perm(user, "requests") and user["roles"] & {"ops_admin", "super_admin"}:
        return True
    if "team_lead" in user["roles"]:
        return True
    return "agent" in user["roles"] and r["assigned_agent_id"] == user["id"]


def _require_manage(user, r):
    if not _staff_can_manage(user, r):
        fail(403, "forbidden", "This request is assigned to another agent.")


@bp.get("/requests")
@auth("customer", "agent", "team_lead", "ops_admin", "super_admin", "finance_admin")
def list_requests():
    u = g.user
    page, size = page_args()
    where, args = ["1=1"], []
    if "customer" in u["roles"] and not (u["roles"] - {"customer"}):
        where.append("customer_id=?")
        args.append(u["id"])
    elif u["roles"] & {"team_lead"} or has_perm(u, "requests") or has_perm(u, "oversight"):
        if request.args.get("agent_id"):
            where.append("assigned_agent_id=?")
            args.append(request.args["agent_id"])
        if request.args.get("scope") == "pool":
            where.append("assigned_agent_id IS NULL")
    else:  # plain agent
        if request.args.get("scope") == "pool":
            where.append("assigned_agent_id IS NULL")
        else:
            where.append("assigned_agent_id=?")
            args.append(u["id"])
    grp = request.args.get("status_group")
    if grp == "open":
        where.append(f"status NOT IN {TERMINAL_SQL}")
    elif grp == "completed":
        where.append("status='completed'")
    elif grp == "cancelled":
        where.append("status IN ('cancelled','rejected','expired')")
    for key in ("status", "category_id", "locality", "priority"):
        if request.args.get(key):
            where.append(f"{key}=?")
            args.append(request.args[key])
    if request.args.get("sla") == "overdue":
        where.append("overdue=1")
    text = (request.args.get("q") or "").strip()
    if text:
        where.append("(reference LIKE ? OR title LIKE ? OR description LIKE ? OR customer_name LIKE ?)")
        args += [f"%{text}%"] * 4
    sort = {"oldest": "created_at ASC", "updated": "updated_at DESC"}.get(request.args.get("sort"),
                                                                        "created_at DESC")
    sql = f"SELECT * FROM ({LIST_SQL}) WHERE {' AND '.join(where)}"
    res = paginate(sql, args, page, size, f"ORDER BY {sort}")
    for it in res["items"]:
        it["status_label"] = STATUS[it["status"]]["label"]
        it["status_tone"] = STATUS[it["status"]]["tone"]
    return jsonify(res)


# --------------------------------------------------------------- create
def _validate_details(schema, details):
    errors, clean = {}, {}
    for f in schema:
        key, val_ = f["key"], details.get(f["key"])
        if val_ in (None, "", []):
            if f.get("required"):
                errors[f"details.{key}"] = "This field is required."
            continue
        if f["type"] == "select" and val_ not in f.get("options", []):
            errors[f"details.{key}"] = "Choose one of the listed options."
            continue
        if f["type"] == "number":
            try:
                float(val_)
            except (TypeError, ValueError):
                errors[f"details.{key}"] = "Enter a number."
                continue
        if isinstance(val_, list):
            val_ = ", ".join(str(x) for x in val_)
        clean[key] = (f["label"], str(val_)[:500])
    return errors, clean


@bp.post("/requests")
@auth("customer")
def create_request():
    u, d = g.user, json_body()
    v = V(d)
    cat_ref = v.str("category_id")
    desc = v.str("description", 10, 1000)
    priority = v.str("priority", choices=["normal", "urgent"], default="normal")
    preferred_at = v.dt("preferred_at", future=True)
    bmin = v.int("budget_min", min=0, max=10_000_000)
    bmax = v.int("budget_max", min=0, max=10_000_000)
    if bmin is not None and bmax is not None and bmax < bmin:
        v.errors["budget_max"] = "Maximum budget must be at least the minimum."
    v.done()
    cat = one("SELECT * FROM categories WHERE id=? OR slug=?", (cat_ref, cat_ref))
    if not cat:
        fail(422, "validation_error", "Choose a service category.", {"category_id": "Choose a category."})
    if not cat["is_active"]:
        fail(422, "category_inactive", f"{cat['name']} isn't live yet. Try Special requests and we'll help manually.",
             {"category_id": "This category is coming soon."})
    sub = None
    if d.get("subcategory_id"):
        sub = one("SELECT * FROM subcategories WHERE id=? AND category_id=?", (d["subcategory_id"], cat["id"]))
        if not sub:
            fail(422, "validation_error", "Unknown service type.", {"subcategory_id": "Choose a valid service type."})

    # Address: saved one, or a new inline one.
    addr_id, locality = None, (d.get("locality") or "").strip() or None
    if d.get("service_address_id"):
        a = one("SELECT * FROM addresses WHERE id=? AND user_id=?", (d["service_address_id"], u["id"]))
        if not a:
            fail(422, "validation_error", "Choose one of your saved addresses.", {"service_address_id": "Invalid address."})
        addr_id, locality = a["id"], a["locality"]
    elif isinstance(d.get("address"), dict):
        from .me import _addr_fields
        f, _ = _addr_fields(d["address"])
        addr_id = uid()
        insert("addresses", id=addr_id, user_id=u["id"], is_default=0 if val("SELECT COUNT(*) FROM addresses WHERE user_id=?", (u["id"],), 0) else 1,
               city="Hyderabad", created_at=now(), **f)
        locality = f["locality"]
    if not locality:
        fail(422, "validation_error", "Where do you need the service?", {"service_address_id": "Choose or add an address."})

    errors, clean = _validate_details(jload(cat["schema_json"], []), d.get("details") or {})
    if errors:
        fail(422, "validation_error", "Please fix the highlighted fields.", errors)

    fp = hashlib.sha1(f"{u['id']}|{cat['id']}|{desc.lower()}".encode()).hexdigest()
    cutoff = (datetime.now(timezone.utc) - timedelta(minutes=2)).strftime("%Y-%m-%dT%H:%M:%SZ")
    dup = one("SELECT id, reference, status, created_at FROM requests WHERE fingerprint=? AND created_at>? "
              "AND status NOT IN ('cancelled','rejected')", (fp, cutoff))
    if dup:
        return jsonify({**dup, "duplicate": True, "assigned_agent": None}), 200

    rid, t = uid(), now()
    agent_id, why = pick_agent_for(u["id"])
    ref = next_ref("request", "BZ")
    insert("requests", id=rid, reference=ref, customer_id=u["id"], assigned_agent_id=None,
           category_id=cat["id"], subcategory_id=sub["id"] if sub else None,
           workflow_template_id=cat["workflow_template_id"], status="submitted", priority=priority,
           service_address_id=addr_id, locality=locality, preferred_at=preferred_at, budget_min=bmin,
           budget_max=bmax, title=(sub["name"] if sub else cat["name"]), description=desc, fingerprint=fp,
           created_at=t, updated_at=t, status_changed_at=t)
    for key, (label, value) in clean.items():
        insert("request_details", id=uid(), request_id=rid, field_key=key, field_label=label, value=value)
    insert("request_status_history", id=uid(), request_id=rid, from_status=None, to_status="submitted",
           changed_by=u["id"], reason="Request created", created_at=t)
    for att in (d.get("attachments") or [])[:8]:
        run("UPDATE attachments SET request_id=? WHERE id=? AND owner_id=? AND request_id IS NULL", (rid, att, u["id"]))

    brief = d.get("brief")
    if isinstance(brief, dict) and brief.get("kind") in ("wedding", "photography"):
        bv = V(brief)
        bv.str("event_type", 2, 60)
        bv.dt("event_date", required=True)
        bv.int("guest_count", min=1, max=20000)
        bv.int("coverage_hours", min=1, max=48)
        bv.done()
        services = brief.get("services") or []
        deliverables = brief.get("deliverables") or []
        insert("event_briefs", request_id=rid, kind=brief["kind"], event_type=brief.get("event_type"),
               event_date=brief.get("event_date"), venue=(brief.get("venue") or "")[:200], locality=locality,
               guest_count=brief.get("guest_count"), budget_min=bmin, budget_max=bmax,
               services=", ".join(map(str, services))[:300], coverage_hours=brief.get("coverage_hours"),
               coverage_style=brief.get("coverage_style"), deliverables=", ".join(map(str, deliverables))[:300],
               package_pref=brief.get("package_pref"), notes=(brief.get("notes") or "")[:600])
        for m in (brief.get("milestones") or [])[:12]:
            if isinstance(m, dict) and (m.get("title") or "").strip():
                insert("event_milestones", id=uid(), request_id=rid, title=m["title"][:120],
                       due_date=m.get("due_date"), status="pending", created_at=t)

    req = one("SELECT * FROM requests WHERE id=?", (rid,))
    req["title"] = req["title"]
    set_request_agent(req, agent_id, u["id"], why)
    audit("request.create", "request", rid, {"category": cat["name"]})
    notify(u["id"], "request.submitted", {"reference": ref, "category": cat["name"], "agent": agent_name(agent_id) if agent_id else "A BUZZER agent"},
           f"#/c/request/{rid}")
    return jsonify({
        "id": rid, "reference": ref, "status": "submitted", "created_at": t, "duplicate": False,
        "assigned_agent": ({"id": agent_id, "display_name": agent_name(agent_id), "allocation": why} if agent_id else None),
        "next_update": "Your agent will review your request within 2 hours.",
    }), 201


# --------------------------------------------------------------- detail
def _request_detail(r, user):
    roles = user["roles"]
    staff = bool(roles & {"agent", "team_lead", "ops_admin", "super_admin", "finance_admin", "verification_reviewer"})
    cat = one("SELECT id,name,slug,icon,color,workflow_template_id FROM categories WHERE id=?", (r["category_id"],))
    out = {k: r[k] for k in ("id", "reference", "title", "description", "status", "priority", "locality", "preferred_at",
                             "budget_min", "budget_max", "created_at", "updated_at", "status_changed_at",
                             "assigned_agent_id", "cancel_reason", "closed_at", "first_response_at")}
    out["status_label"] = STATUS[r["status"]]["label"]
    out["status_next"] = STATUS[r["status"]]["next"]
    out["status_tone"] = STATUS[r["status"]]["tone"]
    out["category"] = cat
    out["subcategory"] = one("SELECT id,name FROM subcategories WHERE id=?", (r["subcategory_id"],))
    out["address"] = one("SELECT label,line1,locality,landmark,city,pincode FROM addresses WHERE id=?",
                         (r["service_address_id"],))
    out["details"] = q("SELECT field_key AS key, field_label AS label, value FROM request_details WHERE request_id=?", (r["id"],))
    out["attachments"] = q("SELECT id, filename, mime, size FROM attachments WHERE request_id=?", (r["id"],))
    out["history"] = q("""SELECT h.id, h.from_status, h.to_status, h.reason, h.created_at, u.name AS actor_name
                          FROM request_status_history h LEFT JOIN users u ON u.id=h.changed_by
                          WHERE h.request_id=? ORDER BY h.created_at, h.rowid""", (r["id"],))
    for h in out["history"]:
        h["label"] = STATUS[h["to_status"]]["label"]
    out["agent"] = agent_card(r["assigned_agent_id"])
    cust = one("SELECT id, name, mobile FROM users WHERE id=?", (r["customer_id"],))
    out["customer"] = cust if staff else {"id": cust["id"], "name": cust["name"]}
    rows = q("SELECT * FROM quotations WHERE request_id=? AND status<>'withdrawn' ORDER BY total", (r["id"],))
    if not staff:
        rows = [x for x in rows if x["status"] in CUSTOMER_VISIBLE_QUOTE]
    out["quotes"] = [quote_payload(x) for x in rows]
    b = one("SELECT * FROM bookings WHERE request_id=? AND status<>'cancelled'", (r["id"],))
    if b:
        b["provider"] = provider_card(b["provider_id"])
    out["booking"] = b
    out["brief"] = one("SELECT * FROM event_briefs WHERE request_id=?", (r["id"],))
    out["milestones"] = q("""SELECT m.*, p.business_name AS provider_name FROM event_milestones m
                             LEFT JOIN providers p ON p.id=m.provider_id WHERE m.request_id=? ORDER BY m.due_date""", (r["id"],))
    out["review"] = one("SELECT rating, comment FROM reviews WHERE request_id=? AND subject_type='provider' AND reviewer_id=?",
                        (r["id"], user["id"]))
    if staff:
        out["invites"] = q("""SELECT i.id, i.status, i.created_at, i.responded_at, i.decline_reason, p.id AS provider_id,
                              p.business_name, p.verification_status, p.rating_avg FROM request_invites i
                              JOIN providers p ON p.id=i.provider_id WHERE i.request_id=? ORDER BY i.created_at""", (r["id"],))
        out["notes"] = q("""SELECT n.id, n.body, n.created_at, u.name AS author FROM request_notes n
                            JOIN users u ON u.id=n.author_id WHERE n.request_id=? ORDER BY n.created_at DESC""", (r["id"],))
        out["allowed_transitions"] = sorted(t for t in TRANSITIONS.get(r["status"], set()) if t in AGENT_MANUAL)
        out["assignment_history"] = q("""SELECT h.*, fa.display_name AS from_name, ta.display_name AS to_name FROM assignment_history h
                                         LEFT JOIN agents fa ON fa.user_id=h.from_agent_id
                                         LEFT JOIN agents ta ON ta.user_id=h.to_agent_id
                                         WHERE h.request_id=? ORDER BY h.changed_at DESC""", (r["id"],))
        out["can_manage"] = _staff_can_manage(user, r)
    cust_view = "customer" in roles and r["customer_id"] == user["id"]
    out["can_cancel"] = cust_view and r["status"] in ("submitted", "under_review", "finding_providers", "quotes_received",
                                                      "awaiting_approval", "confirmed")
    out["can_confirm_completion"] = cust_view and bool(b and b["status"] == "work_done")
    out["can_rate"] = cust_view and r["status"] == "completed" and not out["review"]
    return out


@bp.get("/requests/<rid>")
@auth("customer", "agent", "team_lead", "ops_admin", "super_admin", "finance_admin", "verification_reviewer")
def request_detail(rid):
    r = require_request(g.user, rid)
    if g.user["roles"] & {"ops_admin", "super_admin", "finance_admin"} and "customer" not in g.user["roles"]:
        audit("request.view", "request", r["id"])
    return jsonify(_request_detail(r, g.user))


# ----------------------------------------------------- status / cancel / etc.
@bp.patch("/requests/<rid>/status")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def update_status(rid):
    r = require_request(g.user, rid)
    _require_manage(g.user, r)
    d = json_body()
    v = V(d)
    to = v.str("status", choices=list(STATUS))
    reason = v.str("reason", 0, 300, required=False)
    v.done()
    if to not in AGENT_MANUAL:
        fail(409, "not_allowed", f"'{STATUS[to]['label']}' is set automatically by the booking process.")
    if to == "awaiting_approval" and not val("SELECT COUNT(*) FROM quotations WHERE request_id=? AND status='shared'", (r["id"],), 0):
        fail(409, "no_quotes", "Share at least one validated quote with the customer first.")
    change_status(r, to, g.user, reason)
    return jsonify(_request_detail(get_request(r["id"]), g.user))


@bp.post("/requests/<rid>/cancel")
@auth("customer", "agent", "team_lead", "ops_admin", "super_admin")
def cancel_request(rid):
    r = require_request(g.user, rid)
    if "customer" in g.user["roles"] and r["customer_id"] != g.user["id"]:
        fail(403, "forbidden", "This isn't your request.")
    if not (g.user["id"] == r["customer_id"]):
        _require_manage(g.user, r)
    d = json_body()
    v = V(d)
    reason = v.str("reason", 3, 300)
    v.done()
    if r["status"] == "in_progress" and g.user["id"] == r["customer_id"]:
        fail(409, "contact_agent", "Work has already started. Please message your agent so we can sort this out together.")
    booking = one("SELECT * FROM bookings WHERE request_id=? AND status NOT IN ('cancelled','completed')", (r["id"],))
    if booking:
        from .quotes import cancel_booking
        cancel_booking(booking, g.user, reason, cascade=False)
    change_status(r, "cancelled", g.user, reason)
    return jsonify(_request_detail(get_request(r["id"]), g.user))


@bp.post("/requests/<rid>/complete")
@auth("customer", "agent", "team_lead")
def complete_request(rid):
    r = require_request(g.user, rid)
    b = one("SELECT * FROM bookings WHERE request_id=? AND status<>'cancelled'", (r["id"],))
    if not b:
        fail(409, "no_booking", "There is no confirmed booking to complete.")
    on_behalf = "customer" not in g.user["roles"]
    if on_behalf:
        _require_manage(g.user, r)
        if not (json_body().get("reason") or "").strip():
            fail(422, "validation_error", "Say how the customer confirmed.", {"reason": "Required when completing on behalf."})
    elif r["customer_id"] != g.user["id"]:
        fail(403, "forbidden", "This isn't your request.")
    from .quotes import confirm_completion
    confirm_completion(b, r, g.user, on_behalf)
    return jsonify(_request_detail(get_request(r["id"]), g.user))


@bp.patch("/requests/<rid>")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def patch_request(rid):
    r = require_request(g.user, rid)
    _require_manage(g.user, r)
    v = V(json_body())
    pr = v.str("priority", choices=["normal", "urgent"])
    v.done()
    update("requests", "id", r["id"], priority=pr, updated_at=now())
    audit("request.priority", "request", r["id"], pr)
    return jsonify(_request_detail(get_request(r["id"]), g.user))


@bp.post("/requests/<rid>/assign")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def assign_request(rid):
    r = require_request(g.user, rid)
    d = json_body()
    v = V(d)
    target = v.str("agent_id")
    reason = v.str("reason", 3, 200, default="Reassigned")
    v.done()
    if not one("SELECT 1 FROM agents WHERE user_id=?", (target,)):
        fail(404, "not_found", "That agent doesn't exist.")
    privileged = g.user["roles"] & {"team_lead", "ops_admin", "super_admin"}
    if not privileged:
        if not (r["assigned_agent_id"] is None and target == g.user["id"]):
            fail(403, "forbidden", "Agents can only claim unassigned requests. Ask your team lead to reassign.")
    if r["status"] in TERMINAL:
        fail(409, "closed", "This request is already closed.")
    set_request_agent(r, target, g.user["id"], reason)
    return jsonify(_request_detail(get_request(r["id"]), g.user))


# ------------------------------------------------------------ notes
@bp.post("/requests/<rid>/notes")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def add_note(rid):
    r = require_request(g.user, rid)
    _require_manage(g.user, r)
    v = V(json_body())
    body = v.str("body", 2, 1000)
    v.done()
    insert("request_notes", id=uid(), request_id=r["id"], author_id=g.user["id"], body=body, created_at=now())
    return jsonify({"ok": True}), 201


# ------------------------------------------------------- invite providers
@bp.post("/requests/<rid>/invites")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def invite_providers(rid):
    r = require_request(g.user, rid)
    _require_manage(g.user, r)
    d = json_body()
    ids = d.get("provider_ids")
    if not isinstance(ids, list) or not ids:
        fail(422, "validation_error", "Pick at least one provider.", {"provider_ids": "Pick at least one provider."})
    if r["status"] in TERMINAL or r["status"] in ("confirmed", "in_progress", "disputed"):
        fail(409, "closed", "Providers can't be invited at this stage.")
    cat = one("SELECT name FROM categories WHERE id=?", (r["category_id"],))
    added = []
    for pid in ids[:10]:
        p = one("SELECT * FROM providers WHERE id=?", (pid,))
        if not p or p["status"] != "active" or p["verification_status"] == "rejected":
            continue
        if one("SELECT 1 FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], pid)):
            continue
        insert("request_invites", id=uid(), request_id=r["id"], provider_id=pid, invited_by=g.user["id"],
               status="invited", created_at=now())
        insert("contact_log", id=uid(), request_id=r["id"], agent_id=g.user["id"], party_type="provider",
               party_id=pid, party_name=p["business_name"], channel="sms", direction="outbound",
               outcome="Lead sent", notes="Invited to quote via BUZZER partner app", created_at=now())
        notify(p["user_id"], "lead.new", {"reference": r["reference"], "category": cat["name"],
                                          "locality": r["locality"]}, f"#/p/lead/{r['id']}")
        added.append(pid)
    if added and r["status"] == "submitted":
        change_status(r, "under_review", g.user, "Agent started review")
    if added and r["status"] in ("under_review", "quotes_received"):
        change_status(r, "finding_providers", g.user, "Invited providers to quote")
    audit("request.invite", "request", r["id"], {"providers": added})
    return jsonify({"invited": added, "request": _request_detail(get_request(r["id"]), g.user)})


# -------------------------------------------------------------- milestones
@bp.post("/requests/<rid>/milestones")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def add_milestone(rid):
    r = require_request(g.user, rid)
    _require_manage(g.user, r)
    v = V(json_body())
    title, due = v.str("title", 2, 120), v.str("due_date", required=False)
    pid = v.str("provider_id", required=False)
    v.done()
    insert("event_milestones", id=uid(), request_id=r["id"], title=title, due_date=due,
           provider_id=pid, status="pending", created_at=now())
    return jsonify({"ok": True}), 201


@bp.patch("/milestones/<mid>")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def update_milestone(mid):
    m = one("SELECT * FROM event_milestones WHERE id=?", (mid,))
    if not m:
        fail(404, "not_found", "Milestone not found.")
    r = get_request(m["request_id"])
    _require_manage(g.user, r)
    d = json_body()
    v = V(d)
    st = v.str("status", choices=["pending", "in_progress", "done"], required=False)
    pid = d.get("provider_id")
    v.done()
    cols = {}
    if st:
        cols["status"] = st
    if "provider_id" in d:
        cols["provider_id"] = pid or None
    update("event_milestones", "id", mid, **cols)
    return jsonify({"ok": True})
