"""Trust & resolution: ratings/reviews, complaints, partner issues and disputes."""
from datetime import datetime, timedelta, timezone

from flask import g, jsonify, request

from ..db import insert, next_ref, now, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, has_perm, json_body, page_args
from ..workflow import change_status
from . import bp
from .common import get_request, provider_of

STAFF = ("agent", "team_lead", "ops_admin", "super_admin")
CUSTOMER_TOPICS = ["Quality of work", "Late or no-show", "Overcharged", "Behaviour", "Safety", "Billing / refund", "Other"]
PARTNER_TOPICS = ["Payment issue", "Booking issue", "Customer conduct", "Payout delay", "App problem", "Other"]
HIGH = {"Safety", "Billing / refund", "Payment issue", "Payout delay"}


def _sla_hours():
    return int(val("SELECT value FROM settings WHERE key='complaint_sla_hours'", (), 24))


# ---------------------------------------------------------------- reviews
@bp.post("/requests/<rid>/reviews")
@auth("customer")
def review_create(rid):
    r = get_request(rid)
    if r["customer_id"] != g.user["id"]:
        fail(403, "forbidden", "This isn't your request.")
    if r["status"] != "completed":
        fail(409, "not_completed", "You can rate once the service is completed.")
    b = one("SELECT * FROM bookings WHERE request_id=? AND status='completed'", (r["id"],))
    if not b:
        fail(409, "no_booking", "There is no completed booking to rate.")
    if one("SELECT 1 FROM reviews WHERE request_id=? AND reviewer_id=?", (r["id"], g.user["id"])):
        fail(409, "already_rated", "You've already rated this service. Thank you!")
    d = json_body()
    v = V(d)
    pr = v.int("provider_rating", min=1, max=5, required=True)
    ar = v.int("agent_rating", min=1, max=5)
    pc = v.str("provider_comment", 0, 500, required=False)
    ac = v.str("agent_comment", 0, 500, required=False)
    v.done()
    insert("reviews", id=uid(), booking_id=b["id"], request_id=r["id"], reviewer_id=g.user["id"], subject_type="provider",
           subject_id=b["provider_id"], rating=pr, comment=pc, created_at=now())
    row = one("SELECT AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE subject_type='provider' AND subject_id=?", (b["provider_id"],))
    update("providers", "id", b["provider_id"], rating_avg=round(row["a"], 1), rating_count=row["n"])
    if ar and r["assigned_agent_id"]:
        insert("reviews", id=uid(), booking_id=b["id"], request_id=r["id"], reviewer_id=g.user["id"], subject_type="agent",
               subject_id=r["assigned_agent_id"], rating=ar, comment=ac, created_at=now())
    audit("review.create", "request", r["id"], {"provider": pr, "agent": ar})
    return jsonify({"ok": True}), 201


# -------------------------------------------------------------- complaints
COMPLAINT_SQL = """
SELECT c.*, u.name AS raised_by_name, r.reference AS request_reference, o.display_name AS owner_name,
       CASE WHEN c.status IN ('open','investigating','escalated') AND c.sla_due_at < strftime('%Y-%m-%dT%H:%M:%SZ','now') THEN 1 ELSE 0 END AS overdue
FROM complaints c JOIN users u ON u.id=c.raised_by LEFT JOIN requests r ON r.id=c.request_id LEFT JOIN agents o ON o.user_id=c.owner_id
"""


def _complaint(u, cid):
    c = one(COMPLAINT_SQL + " WHERE c.id=? OR c.reference=?", (cid, cid))
    if not c:
        fail(404, "not_found", "That ticket was not found.")
    roles = u["roles"]
    ok = (c["raised_by"] == u["id"] or roles & {"team_lead", "ops_admin", "super_admin"} or has_perm(u, "complaints")
          or ("agent" in roles and (c["owner_id"] == u["id"] or (c["request_id"] and get_request(c["request_id"])["assigned_agent_id"] == u["id"]))))
    if not ok:
        fail(403, "forbidden", "You don't have access to this ticket.")
    return c


@bp.post("/complaints")
@auth("customer", "provider")
def complaint_create():
    u, d = g.user, json_body()
    v = V(d)
    is_partner = "provider" in u["roles"]
    topics = PARTNER_TOPICS if is_partner else CUSTOMER_TOPICS
    topic = v.str("topic", choices=topics)
    desc = v.str("description", 10, 1500)
    kind = "partner_issue" if is_partner else v.str("kind", choices=["complaint", "dispute"], default="complaint")
    v.done()
    r = b = None
    if d.get("request_id"):
        r = get_request(d["request_id"])
        if not is_partner and r["customer_id"] != u["id"]:
            fail(403, "forbidden", "This isn't your request.")
    if d.get("booking_id"):
        b = one("SELECT * FROM bookings WHERE id=?", (d["booking_id"],))
        if not b or (not is_partner and b["customer_id"] != u["id"]) or (is_partner and b["provider_id"] != (provider_of(u["id"]) or {}).get("id")):
            fail(403, "forbidden", "That booking isn't yours.")
        r = r or get_request(b["request_id"])
    if is_partner:
        p = provider_of(u["id"])
        against_type, against_id = ("customer", r["customer_id"]) if r else (None, None)
    else:
        against_type, against_id = ("provider", b["provider_id"]) if b else (None, None)
    owner = r["assigned_agent_id"] if r else None
    cid = uid()
    insert("complaints", id=cid, reference=next_ref("complaint", "TKT", 5), kind=kind, request_id=r["id"] if r else None,
           booking_id=b["id"] if b else None, raised_by=u["id"], raised_by_role="provider" if is_partner else "customer",
           against_type=against_type, against_id=against_id, topic=topic, description=desc, status="open",
           priority="high" if topic in HIGH else "normal", owner_id=owner,
           sla_due_at=(datetime.now(timezone.utc) + timedelta(hours=_sla_hours())).strftime("%Y-%m-%dT%H:%M:%SZ"), created_at=now())
    insert("complaint_events", id=uid(), complaint_id=cid, actor_id=u["id"], event="opened", note=desc[:200], created_at=now())
    for att in (d.get("attachments") or [])[:5]:
        run("UPDATE attachments SET complaint_id=? WHERE id=? AND owner_id=?", (cid, att, u["id"]))
    if kind == "dispute" and r and b and b["status"] not in ("cancelled",):
        update("bookings", "id", b["id"], status="disputed", updated_at=now())
        if r["status"] in ("confirmed", "in_progress", "completed"):
            change_status(r, "disputed", u, f"Dispute raised: {topic}")
    ref = val("SELECT reference FROM complaints WHERE id=?", (cid,))
    if owner:
        notify(owner, "complaint.opened", {"reference": ref, "topic": topic}, f"#/a/complaint/{cid}")
    audit("complaint.create", "complaint", cid, {"kind": kind, "topic": topic})
    return jsonify(_complaint_payload(u, cid)), 201


def _complaint_payload(u, cid):
    c = _complaint(u, cid)
    staff = bool(u["roles"] & set(STAFF)) or has_perm(u, "complaints")
    ev = q("""SELECT e.event, e.note, e.created_at, x.name AS actor FROM complaint_events e LEFT JOIN users x ON x.id=e.actor_id
              WHERE e.complaint_id=? ORDER BY e.created_at, e.rowid""", (c["id"],))
    if not staff:
        ev = [e for e in ev if e["event"] != "internal_note"]
    c["events"] = ev
    c["attachments"] = q("SELECT id, filename, mime FROM attachments WHERE complaint_id=?", (c["id"],))
    if c["against_type"] == "provider":
        c["against_name"] = val("SELECT business_name FROM providers WHERE id=?", (c["against_id"],))
    elif c["against_type"] == "customer" and staff:
        c["against_name"] = val("SELECT name FROM users WHERE id=?", (c["against_id"],))
    return c


@bp.get("/complaints")
@auth("customer", "provider", *STAFF, "verification_reviewer", "finance_admin")
def complaints_list():
    u = g.user
    page, size = page_args()
    where, args = ["1=1"], []
    roles = u["roles"]
    if roles & {"team_lead", "ops_admin", "super_admin"} or has_perm(u, "complaints"):
        pass
    elif "agent" in roles:
        where.append("(c.owner_id=? OR EXISTS (SELECT 1 FROM requests rr WHERE rr.id=c.request_id AND rr.assigned_agent_id=?))")
        args += [u["id"]] * 2
    else:
        where.append("c.raised_by=?")
        args.append(u["id"])
    a = request.args
    for key in ("status", "kind", "priority"):
        if a.get(key):
            where.append(f"c.{key}=?")
            args.append(a[key])
    if a.get("open") == "1":
        where.append("c.status IN ('open','investigating','escalated')")
    if a.get("overdue") == "1":
        where.append("c.status IN ('open','investigating','escalated') AND c.sla_due_at < strftime('%Y-%m-%dT%H:%M:%SZ','now')")
    if a.get("q"):
        where.append("(c.reference LIKE ? OR c.topic LIKE ? OR c.description LIKE ?)")
        args += [f"%{a['q']}%"] * 3
    return jsonify(paginate(f"{COMPLAINT_SQL} WHERE {' AND '.join(where)}", args, page, size, "ORDER BY c.created_at DESC"))


@bp.get("/complaints/<cid>")
@auth("customer", "provider", *STAFF, "verification_reviewer", "finance_admin")
def complaint_detail(cid):
    return jsonify(_complaint_payload(g.user, cid))


@bp.post("/complaints/<cid>/notes")
@auth("customer", "provider", *STAFF)
def complaint_note(cid):
    c = _complaint(g.user, cid)
    d = json_body()
    v = V(d)
    note = v.str("note", 2, 800)
    v.done()
    staff = bool(g.user["roles"] & set(STAFF))
    internal = staff and bool(d.get("internal"))
    insert("complaint_events", id=uid(), complaint_id=c["id"], actor_id=g.user["id"],
           event="internal_note" if internal else ("update" if staff else "customer_note"), note=note, created_at=now())
    if staff and not internal:
        notify(c["raised_by"], "complaint.update", {"reference": c["reference"], "status": c["status"], "note": note[:100]},
               "#/c/support" if c["raised_by_role"] == "customer" else "#/p/support")
    return jsonify({"ok": True}), 201


@bp.patch("/complaints/<cid>")
@auth(*STAFF)
def complaint_update(cid):
    c = _complaint(g.user, cid)
    d = json_body()
    v = V(d)
    st = v.str("status", choices=["open", "investigating", "escalated", "resolved", "closed"], required=False)
    resolution = v.str("resolution", 5, 800, required=False)
    outcome = v.str("outcome", choices=["completed", "cancelled"], required=False)
    if st == "resolved" and not resolution:
        v.errors["resolution"] = "Describe how this was resolved."
    v.done()
    cols, events = {}, []
    if st and st != c["status"]:
        if c["status"] in ("resolved", "closed") and st not in ("closed", "open"):
            fail(409, "invalid_state", "This ticket is already resolved.")
        cols["status"] = st
        events.append((st, resolution or d.get("note")))
        if st == "escalated":
            lead = val("SELECT user_id FROM agents a JOIN user_roles ur ON ur.user_id=a.user_id AND ur.role='team_lead' LIMIT 1")
            if lead:
                cols["owner_id"] = lead
        if st == "resolved":
            cols["resolution"], cols["resolved_at"] = resolution, now()
            if c["kind"] == "dispute" and c["request_id"]:
                r = get_request(c["request_id"])
                if r["status"] == "disputed":
                    change_status(r, outcome or "completed", g.user, f"Dispute resolved: {resolution}")
                b = one("SELECT * FROM bookings WHERE id=?", (c["booking_id"],))
                if b and b["status"] == "disputed":
                    update("bookings", "id", b["id"], status="completed" if (outcome or "completed") == "completed" else "cancelled", updated_at=now())
    if d.get("priority") in ("normal", "high"):
        cols["priority"] = d["priority"]
    if d.get("owner_id") and one("SELECT 1 FROM agents WHERE user_id=?", (d["owner_id"],)):
        cols["owner_id"] = d["owner_id"]
        events.append(("reassigned", None))
    if cols:
        update("complaints", "id", c["id"], **cols)
    for ev, note in events:
        insert("complaint_events", id=uid(), complaint_id=c["id"], actor_id=g.user["id"], event=ev, note=note, created_at=now())
    if "status" in cols:
        notify(c["raised_by"], "complaint.update", {"reference": c["reference"], "status": cols["status"], "note": resolution or ""},
               "#/c/support" if c["raised_by_role"] == "customer" else "#/p/support")
    audit("complaint.update", "complaint", c["id"], cols)
    return jsonify(_complaint_payload(g.user, c["id"]))
