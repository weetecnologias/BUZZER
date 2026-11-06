"""Quotations, customer approval gate, bookings, sandbox payments, completion and commission ledger."""
import sqlite3

from flask import g, jsonify, request

from ..db import insert, now, next_ref, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, has_perm, json_body, page_args
from ..workflow import STATUS, change_status, refresh_workload
from . import bp
from .common import (CUSTOMER_VISIBLE_QUOTE, can_view_request, get_request, provider_card, provider_of,
                     quote_payload, require_request)

QUOTE_OPEN = ("submitted", "shared", "clarification_requested", "revised_pending_consent")
QUOTABLE = ("under_review", "finding_providers", "quotes_received", "awaiting_approval")


def _manages(user, r) -> bool:
    return bool(user["roles"] & {"team_lead", "ops_admin", "super_admin"}
                or ("agent" in user["roles"] and r["assigned_agent_id"] == user["id"]))


def _step_status(r, path, actor):
    """Walk a request through intermediate statuses (e.g. finding_providers -> quotes_received -> awaiting_approval)."""
    start = path.index(r["status"]) + 1 if r["status"] in path else 0  # only ever move forward
    for s in path[start:]:
        change_status(r, s, actor, "Quote activity")


def _validate_quote(d):
    v = V(d)
    scope = v.str("scope", 10, 800)
    exclusions = v.str("exclusions", 0, 500, required=False)
    terms = v.str("terms", 0, 800, required=False)
    warranty = v.int("warranty_days", min=0, max=3650, default=0)
    deposit = v.int("deposit_pct", min=0, max=100, default=0)
    valid_until = v.dt("valid_until", required=True, future=True)
    est_start = v.dt("est_start_at", future=True)
    est_duration = v.str("est_duration", 0, 60, required=False)
    items_raw = d.get("items")
    items, total = [], 0
    if not isinstance(items_raw, list) or not (1 <= len(items_raw) <= 20):
        v.errors["items"] = "Add between 1 and 20 line items."
    else:
        for i, it in enumerate(items_raw):
            try:
                desc = str(it.get("description", "")).strip()
                qty, price = int(it.get("qty", 1)), int(float(it.get("unit_price", 0)))
            except (TypeError, ValueError, AttributeError):
                v.errors[f"items.{i}"] = "Check this line item."
                continue
            if len(desc) < 2 or not (1 <= qty <= 1000) or not (0 <= price <= 10_000_000):
                v.errors[f"items.{i}"] = "Each item needs a description, quantity 1-1000 and a price."
                continue
            items.append((desc[:200], qty, price, qty * price))
            total += qty * price
        if items and not (0 < total <= 10_000_000):
            v.errors["items"] = "The total must be between Rs 1 and Rs 1,00,00,000."
    v.done()
    return dict(scope=scope, exclusions=exclusions, terms=terms, warranty_days=warranty, deposit_pct=deposit,
                valid_until=valid_until, est_start_at=est_start, est_duration=est_duration, total=total), items


def _write_items(qid, items):
    run("DELETE FROM quotation_items WHERE quotation_id=?", (qid,))
    for desc, qty, price, amt in items:
        insert("quotation_items", id=uid(), quotation_id=qid, description=desc, qty=qty, unit_price=price, amount=amt)


@bp.post("/requests/<rid>/quotes")
@auth("provider", "agent", "team_lead", "ops_admin", "super_admin")
def submit_quote(rid):
    u = g.user
    r = require_request(u, rid) if not (u["roles"] & {"provider"}) else get_request(rid)
    d = json_body()
    if u["roles"] & {"provider"} and not (u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin"}):
        p = provider_of(u["id"])
        if not p or not one("SELECT 1 FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], p["id"])):
            fail(403, "forbidden", "You haven't been invited to quote on this request.")
        if p["status"] != "active":
            fail(403, "suspended", "Your account is suspended. Contact BUZZER support.")
    else:
        if not _manages(u, r):
            fail(403, "forbidden", "This request is assigned to another agent.")
        p = one("SELECT * FROM providers WHERE id=?", (d.get("provider_id"),))
        if not p:
            fail(422, "validation_error", "Choose the provider this quote is from.", {"provider_id": "Choose a provider."})
    if r["status"] not in QUOTABLE:
        fail(409, "closed", "This request isn't collecting quotes right now.")
    if one("SELECT 1 FROM quotations WHERE request_id=? AND provider_id=? AND status IN "
           "('submitted','shared','clarification_requested','approved','revised_pending_consent')", (r["id"], p["id"])):
        fail(409, "already_quoted", "A quote from this provider already exists. Edit it instead of sending a new one.")
    cols, items = _validate_quote(d)
    qid, t = uid(), now()
    insert("quotations", id=qid, request_id=r["id"], provider_id=p["id"], submitted_by=u["id"], edited_by=u["id"],
           status="submitted", version=1, created_at=t, updated_at=t, **cols)
    _write_items(qid, items)
    inv = one("SELECT id FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], p["id"]))
    if inv:
        run("UPDATE request_invites SET status='quoted', responded_at=? WHERE id=?", (t, inv["id"]))
    else:
        insert("request_invites", id=uid(), request_id=r["id"], provider_id=p["id"], invited_by=u["id"],
               status="quoted", created_at=t, responded_at=t)
    if r["status"] == "finding_providers":
        change_status(r, "quotes_received", u, "First quote received")
    if r["assigned_agent_id"]:
        notify(r["assigned_agent_id"], "quote.received", {"reference": r["reference"], "provider": p["business_name"],
                                                         "amount": f"{cols['total']:,}"}, f"#/a/request/{r['id']}")
    audit("quote.submit", "quotation", qid, {"request": r["reference"], "total": cols["total"]})
    return jsonify(quote_payload(one("SELECT * FROM quotations WHERE id=?", (qid,)))), 201


def _quote_for(user, qid, owner_only=False):
    qt = one("SELECT * FROM quotations WHERE id=?", (qid,))
    if not qt:
        fail(404, "not_found", "That quote was not found.")
    r = get_request(qt["request_id"])
    p = provider_of(user["id"]) if "provider" in user["roles"] else None
    if p and qt["provider_id"] == p["id"]:
        return qt, r, "provider"
    if owner_only:
        fail(403, "forbidden", "You can't change this quote.")
    if "customer" in user["roles"] and r["customer_id"] == user["id"] and qt["status"] in CUSTOMER_VISIBLE_QUOTE:
        return qt, r, "customer"
    if can_view_request(user, r) and not (user["roles"] & {"customer"} and r["customer_id"] != user["id"] and not user["roles"] - {"customer"}):
        if user["roles"] & {"agent", "team_lead", "ops_admin", "super_admin", "finance_admin"}:
            return qt, r, "staff"
    fail(403, "forbidden", "You don't have access to this quote.")


@bp.get("/quotes/<qid>")
@auth()
def quote_detail(qid):
    qt, r, who = _quote_for(g.user, qid)
    out = quote_payload(qt)
    out["request"] = {"id": r["id"], "reference": r["reference"], "title": r["title"], "status": r["status"]}
    return jsonify(out)


@bp.get("/quotes")
@auth("provider", "agent", "team_lead", "ops_admin", "super_admin", "finance_admin")
def list_quotes():
    u = g.user
    page, size = page_args()
    where, args = ["1=1"], []
    if "provider" in u["roles"] and not (u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin", "finance_admin"}):
        p = provider_of(u["id"])
        where.append("x.provider_id=?")
        args.append(p["id"] if p else "-")
    elif "agent" in u["roles"] and not (u["roles"] & {"team_lead", "ops_admin", "super_admin"}):
        where.append("r.assigned_agent_id=?")
        args.append(u["id"])
    for key, col in (("status", "x.status"), ("request_id", "x.request_id")):
        if request.args.get(key):
            where.append(f"{col}=?")
            args.append(request.args[key])
    sql = f"""SELECT x.id, x.request_id, x.status, x.total, x.version, x.valid_until, x.created_at, x.updated_at,
                     x.deposit_pct, r.reference, r.title, r.status AS request_status, r.customer_id, p.business_name AS provider_name,
                     p.verification_status, p.rating_avg, x.provider_id
              FROM quotations x JOIN requests r ON r.id=x.request_id JOIN providers p ON p.id=x.provider_id
              WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY x.updated_at DESC"))


@bp.patch("/quotes/<qid>")
@auth("provider", "agent", "team_lead", "ops_admin", "super_admin")
def edit_quote(qid):
    u = g.user
    qt, r, who = _quote_for(u, qid)
    if who == "customer":
        fail(403, "forbidden", "Customers can't edit quotes.")
    if who == "staff" and not _manages(u, r):
        fail(403, "forbidden", "This request is assigned to another agent.")
    if qt["status"] not in ("submitted", "shared", "clarification_requested", "approved", "revised_pending_consent"):
        fail(409, "locked", "This quote can no longer be edited.")
    cols, items = _validate_quote(json_body())
    was_approved = qt["status"] in ("approved", "revised_pending_consent")
    new_status = "revised_pending_consent" if was_approved else "submitted"
    update("quotations", "id", qid, status=new_status, version=qt["version"] + 1, edited_by=u["id"],
           updated_at=now(), shared_at=None if not was_approved else qt["shared_at"], **cols)
    _write_items(qid, items)
    if was_approved:  # price/scope changed after approval -> renewed consent required
        b = one("SELECT * FROM bookings WHERE quotation_id=? AND status<>'cancelled'", (qid,))
        if b:
            update("bookings", "id", b["id"], status="pending_reconsent", amount=cols["total"],
                   deposit_amount=round(cols["total"] * cols["deposit_pct"] / 100), updated_at=now())
            insert("booking_events", id=uid(), booking_id=b["id"], event="quote_revised",
                   detail=f"Revised to Rs {cols['total']:,}; awaiting customer re-approval", actor_id=u["id"], created_at=now())
        notify(r["customer_id"], "quote.revised", {"reference": r["reference"]}, f"#/c/quote/{qid}")
    elif r["assigned_agent_id"] and who == "provider":
        notify(r["assigned_agent_id"], "quote.received", {"reference": r["reference"],
                                                         "provider": provider_card(qt["provider_id"])["business_name"],
                                                         "amount": f"{cols['total']:,}"}, f"#/a/request/{r['id']}")
    audit("quote.edit", "quotation", qid, {"version": qt["version"] + 1, "total": cols["total"]})
    return jsonify(quote_payload(one("SELECT * FROM quotations WHERE id=?", (qid,))))


@bp.post("/quotes/<qid>/share")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def share_quote(qid):
    u = g.user
    qt, r, _ = _quote_for(u, qid)
    if not _manages(u, r):
        fail(403, "forbidden", "This request is assigned to another agent.")
    if qt["status"] != "submitted":
        fail(409, "invalid_state", "Only a freshly submitted quote can be shared.")
    if r["status"] not in ("finding_providers", "quotes_received", "awaiting_approval"):
        fail(409, "closed", "This request isn't collecting quotes right now.")
    if qt["valid_until"] and qt["valid_until"] < now():
        fail(409, "expired", "This quote has expired. Ask the provider to refresh it.")
    update("quotations", "id", qid, status="shared", shared_at=now(), shared_by=u["id"], updated_at=now())
    r = get_request(r["id"])
    _step_status(r, ["quotes_received", "awaiting_approval"], u)
    n = val("SELECT COUNT(*) FROM quotations WHERE request_id=? AND status='shared'", (r["id"],), 0)
    notify(r["customer_id"], "quote.shared", {"reference": r["reference"], "count": n}, f"#/c/request/{r['id']}/quotes")
    audit("quote.share", "quotation", qid)
    return jsonify(quote_payload(one("SELECT * FROM quotations WHERE id=?", (qid,))))


@bp.post("/quotes/<qid>/withdraw")
@auth("provider", "agent", "team_lead", "ops_admin", "super_admin")
def withdraw_quote(qid):
    qt, r, who = _quote_for(g.user, qid)
    if who == "customer":
        fail(403, "forbidden", "Not allowed.")
    if qt["status"] not in ("submitted", "shared", "clarification_requested"):
        fail(409, "locked", "This quote can no longer be withdrawn.")
    update("quotations", "id", qid, status="withdrawn", updated_at=now())
    audit("quote.withdraw", "quotation", qid)
    return jsonify({"ok": True})


@bp.post("/quotes/<qid>/decision")
@auth("customer")
def quote_decision(qid):
    u = g.user
    qt = one("SELECT * FROM quotations WHERE id=?", (qid,))
    if not qt:
        fail(404, "not_found", "That quote was not found.")
    r = get_request(qt["request_id"])
    if r["customer_id"] != u["id"]:
        fail(403, "forbidden", "This isn't your request.")
    d = json_body()
    v = V(d)
    decision = v.str("decision", choices=["approve", "reject", "clarify"])
    note = v.str("note", 0, 400, required=False)
    slot = v.dt("slot", future=True)
    if decision == "clarify" and not note:
        v.errors["note"] = "Tell your agent what you'd like to know."
    if decision == "approve" and not v.bool("accept_terms"):
        v.errors["accept_terms"] = "Please confirm that you approve this quote and its terms."
    v.done()
    if qt["status"] not in ("shared", "revised_pending_consent", "clarification_requested"):
        fail(409, "invalid_state", "This quote isn't waiting for your decision.")
    p = one("SELECT * FROM providers WHERE id=?", (qt["provider_id"],))
    ip = request.remote_addr

    if decision == "clarify":
        update("quotations", "id", qid, status="clarification_requested", clarification_note=note, updated_at=now())
        insert("customer_approvals", id=uid(), request_id=r["id"], quotation_id=qid, customer_id=u["id"],
               decision="clarification", note=note, ip=ip, created_at=now())
        if r["assigned_agent_id"]:
            notify(r["assigned_agent_id"], "quote.clarification", {"reference": r["reference"], "note": note},
                   f"#/a/request/{r['id']}")
            insert("tasks", id=uid(), agent_id=r["assigned_agent_id"], request_id=r["id"], customer_id=u["id"],
                   title=f"Answer customer question on {r['reference']}", priority="high",
                   due_at=_in_hours(2), status="open", created_at=now())
        return jsonify(quote_payload(one("SELECT * FROM quotations WHERE id=?", (qid,))))

    if decision == "reject":
        update("quotations", "id", qid, status="rejected", decided_at=now(), updated_at=now())
        insert("customer_approvals", id=uid(), request_id=r["id"], quotation_id=qid, customer_id=u["id"],
               decision="rejected", note=note, ip=ip, created_at=now())
        remaining = val("SELECT COUNT(*) FROM quotations WHERE request_id=? AND status IN ('shared','clarification_requested')",
                        (r["id"],), 0)
        if not remaining and r["status"] == "awaiting_approval":
            change_status(r, "quotes_received", u, "Customer declined the shared quote(s)")
        if r["assigned_agent_id"]:
            notify(r["assigned_agent_id"], "quote.clarification",
                   {"reference": r["reference"], "note": f"Customer declined a quote{': ' + note if note else ''}"},
                   f"#/a/request/{r['id']}")
        return jsonify(quote_payload(one("SELECT * FROM quotations WHERE id=?", (qid,))))

    # ---- approve: the hard gate before any booking exists
    reconsent = qt["status"] == "revised_pending_consent"
    if not reconsent and r["status"] != "awaiting_approval":
        fail(409, "invalid_state", "This request isn't waiting for approval.")
    if qt["valid_until"] and qt["valid_until"] < now():
        update("quotations", "id", qid, status="expired", updated_at=now())
        commit_now()
        fail(409, "expired", "This quote has expired. Please ask your agent for a refreshed quote.")
    consent = (f"I approve quote v{qt['version']} from {p['business_name']} for Rs {qt['total']:,} "
               f"covering: {qt['scope'][:120]}")
    insert("customer_approvals", id=uid(), request_id=r["id"], quotation_id=qid, customer_id=u["id"],
           decision="approved", note=note, consent_text=consent, ip=ip, created_at=now())
    update("quotations", "id", qid, status="approved", decided_at=now(), updated_at=now())
    deposit = round(qt["total"] * qt["deposit_pct"] / 100)
    if reconsent:
        b = one("SELECT * FROM bookings WHERE quotation_id=? AND status='pending_reconsent'", (qid,))
        if b:
            update("bookings", "id", b["id"], status="confirmed", amount=qt["total"], deposit_amount=deposit, updated_at=now())
            insert("booking_events", id=uid(), booking_id=b["id"], event="reconsent", detail="Customer re-approved revised quote",
                   actor_id=u["id"], created_at=now())
        return jsonify(booking_payload(b["id"] if b else None) or {"ok": True})
    bid, t = uid(), now()
    try:
        insert("bookings", id=bid, reference=next_ref("booking", "BK"), request_id=r["id"], quotation_id=qid,
               provider_id=qt["provider_id"], customer_id=u["id"], agent_id=r["assigned_agent_id"], status="confirmed",
               scheduled_at=slot or qt["est_start_at"] or r["preferred_at"], amount=qt["total"],
               deposit_amount=deposit, paid_amount=0, payment_status="unpaid", created_at=t, updated_at=t)
    except sqlite3.IntegrityError:
        fail(409, "duplicate_booking", "A booking already exists for this request.")
    insert("booking_events", id=uid(), booking_id=bid, event="confirmed",
           detail=f"Customer approved quote of Rs {qt['total']:,}", actor_id=u["id"], created_at=t)
    others = q("SELECT id, provider_id FROM quotations WHERE request_id=? AND id<>? AND status IN ('submitted','shared','clarification_requested')",
               (r["id"], qid))
    for o in others:
        update("quotations", "id", o["id"], status="not_selected", updated_at=now())
        pu = val("SELECT user_id FROM providers WHERE id=?", (o["provider_id"],))
        notify(pu, "quote.not_selected", {"reference": r["reference"]}, "#/p/quotes")
    change_status(r, "confirmed", u, f"Customer approved quote from {p['business_name']}", system=True)
    bk = one("SELECT reference FROM bookings WHERE id=?", (bid,))
    notify(p["user_id"], "quote.approved", {"reference": r["reference"], "booking": bk["reference"],
                                           "amount": f"{qt['total']:,}"}, f"#/p/booking/{bid}")
    notify(u["id"], "booking.confirmed", {"reference": r["reference"], "provider": p["business_name"]}, f"#/c/booking/{bid}")
    audit("quote.approve", "quotation", qid, {"booking": bk["reference"], "total": qt["total"]})
    return jsonify(booking_payload(bid)), 201


def _in_hours(h):
    from datetime import datetime, timedelta, timezone
    return (datetime.now(timezone.utc) + timedelta(hours=h)).strftime("%Y-%m-%dT%H:%M:%SZ")


def commit_now():
    from ..db import commit
    commit()


# ------------------------------------------------------------- bookings
BOOKING_SQL = """
SELECT b.*, r.reference AS request_reference, r.title, r.locality, r.status AS request_status,
       p.business_name AS provider_name, p.phone AS provider_phone, p.logo_color AS provider_color,
       p.verification_status AS provider_verification, cu.name AS customer_name, a.display_name AS agent_name,
       c.icon AS category_icon, c.name AS category_name
FROM bookings b JOIN requests r ON r.id=b.request_id JOIN providers p ON p.id=b.provider_id
JOIN users cu ON cu.id=b.customer_id JOIN categories c ON c.id=r.category_id
LEFT JOIN agents a ON a.user_id=b.agent_id
"""


def booking_payload(bid, user=None):
    if not bid:
        return None
    b = one(BOOKING_SQL + " WHERE b.id=?", (bid,))
    if not b:
        return None
    b["balance_amount"] = b["amount"] - b["paid_amount"]
    b["events"] = q("""SELECT e.event, e.detail, e.created_at, u.name AS actor FROM booking_events e
                       LEFT JOIN users u ON u.id=e.actor_id WHERE e.booking_id=? ORDER BY e.created_at, e.rowid""", (bid,))
    b["payments"] = q("SELECT reference, kind, method, amount, status, created_at, memo FROM payment_transactions "
                      "WHERE booking_id=? ORDER BY created_at", (bid,))
    b["quote"] = quote_payload(one("SELECT * FROM quotations WHERE id=?", (b["quotation_id"],)))
    b["refund"] = one("SELECT id, amount, status, reason FROM refunds WHERE booking_id=? ORDER BY created_at DESC", (bid,))
    b["address"] = one("""SELECT a.line1, a.locality, a.landmark, a.city, a.pincode FROM addresses a
                          JOIN requests r ON r.service_address_id=a.id WHERE r.id=?""", (b["request_id"],))
    b["customer_phone"] = val("SELECT mobile FROM users WHERE id=?", (b["customer_id"],))
    b["cancellation_terms"] = ("Free cancellation before the provider starts the job. "
                               "Paid amounts are refunded after finance review (3-5 working days).")
    return b


def _booking_access(user, bid):
    b = one("SELECT * FROM bookings WHERE id=? OR reference=?", (bid, bid))
    if not b:
        fail(404, "not_found", "That booking was not found.")
    roles = user["roles"]
    p = provider_of(user["id"]) if "provider" in roles else None
    ok = (b["customer_id"] == user["id"] or (p and b["provider_id"] == p["id"])
          or (("agent" in roles) and b["agent_id"] == user["id"])
          or roles & {"team_lead"} or has_perm(user, "oversight") or has_perm(user, "finance"))
    if not ok:
        fail(403, "forbidden", "You don't have access to this booking.")
    return b


@bp.get("/bookings")
@auth("customer", "provider", "agent", "team_lead", "ops_admin", "super_admin", "finance_admin")
def list_bookings():
    u = g.user
    page, size = page_args()
    where, args = ["1=1"], []
    if u["roles"] & {"team_lead", "ops_admin", "super_admin", "finance_admin"}:
        pass
    elif "agent" in u["roles"]:
        where.append("b.agent_id=?")
        args.append(u["id"])
    elif "provider" in u["roles"]:
        p = provider_of(u["id"])
        where.append("b.provider_id=?")
        args.append(p["id"] if p else "-")
    else:
        where.append("b.customer_id=?")
        args.append(u["id"])
    grp = request.args.get("group")
    if grp == "upcoming":
        where.append("b.status IN ('confirmed','on_the_way','in_progress','work_done','pending_reconsent')")
    elif grp == "completed":
        where.append("b.status='completed'")
    elif grp == "cancelled":
        where.append("b.status='cancelled'")
    elif grp == "attention":
        where.append("(b.status='pending_reconsent' OR (b.status='confirmed' AND b.payment_status='unpaid') OR b.status='disputed')")
    if request.args.get("status"):
        where.append("b.status=?")
        args.append(request.args["status"])
    if request.args.get("q"):
        where.append("(b.reference LIKE ? OR r.reference LIKE ? OR p.business_name LIKE ? OR cu.name LIKE ?)")
        args += [f"%{request.args['q']}%"] * 4
    res = paginate(BOOKING_SQL + f" WHERE {' AND '.join(where)}", args, page, size, "ORDER BY b.created_at DESC")
    return jsonify(res)


@bp.get("/bookings/<bid>")
@auth()
def booking_detail(bid):
    b = _booking_access(g.user, bid)
    out = booking_payload(b["id"])
    if "provider" in g.user["roles"] and not (g.user["roles"] & {"agent", "team_lead", "ops_admin", "super_admin"}):
        out.pop("agent_name", None)
    elif "customer" in g.user["roles"] and b["customer_id"] == g.user["id"]:
        out.pop("customer_phone", None)
    return jsonify(out)


PROVIDER_MOVES = {"confirmed": {"on_the_way", "in_progress"}, "on_the_way": {"in_progress"}, "in_progress": {"work_done"}}


@bp.patch("/bookings/<bid>/status")
@auth("provider", "agent", "team_lead", "ops_admin", "super_admin")
def booking_status(bid):
    u = g.user
    b = _booking_access(u, bid)
    d = json_body()
    v = V(d)
    to = v.str("status", choices=["on_the_way", "in_progress", "work_done"])
    note = v.str("note", 0, 500, required=False)
    v.done()
    staff = bool(u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin"})
    if not staff and "provider" in u["roles"]:
        p = provider_of(u["id"])
        if not p or p["id"] != b["provider_id"]:
            fail(403, "forbidden", "This isn't your booking.")
    if to not in PROVIDER_MOVES.get(b["status"], set()):
        fail(409, "invalid_transition", f"A booking that is '{b['status'].replace('_', ' ')}' can't move to '{to.replace('_', ' ')}'.")
    if to == "work_done" and len(note or "") < 5:
        fail(422, "validation_error", "Add a short completion note (what was done).",
             {"note": "A completion note is required."})
    evidence = d.get("evidence_attachment_id")
    if evidence and not one("SELECT 1 FROM attachments WHERE id=? AND owner_id=?", (evidence, u["id"])):
        evidence = None
    update("bookings", "id", b["id"], status=to, updated_at=now(), completion_note=note if to == "work_done" else b["completion_note"],
           evidence_attachment_id=evidence or b["evidence_attachment_id"])
    insert("booking_events", id=uid(), booking_id=b["id"], event=to, detail=note, actor_id=u["id"], created_at=now())
    r = get_request(b["request_id"])
    if to == "in_progress" and r["status"] == "confirmed":
        change_status(r, "in_progress", u, "Provider started the job", system=True)
    pname = val("SELECT business_name FROM providers WHERE id=?", (b["provider_id"],))
    labels = {"on_the_way": "is on the way", "in_progress": "has started the work", "work_done": "finished the work"}
    ev = "booking.work_done" if to == "work_done" else "booking.status"
    notify(b["customer_id"], ev, {"reference": r["reference"], "provider": pname, "status_label": labels[to]},
           f"#/c/request/{r['id']}")
    audit("booking.status", "booking", b["id"], {"to": to})
    return jsonify(booking_payload(b["id"]))


@bp.post("/bookings/<bid>/pay")
@auth("customer", "agent", "team_lead", "ops_admin", "super_admin")
def booking_pay(bid):
    """Sandbox payment adapter. No card credentials are ever collected or stored."""
    u = g.user
    b = _booking_access(u, bid)
    d = json_body()
    v = V(d)
    kind = v.str("kind", choices=["deposit", "balance", "full"], default="full")
    staff = bool(u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin"})
    methods = ["upi", "card", "netbanking", "wallet"] + (["cash"] if staff else [])
    method = v.str("method", choices=methods, default="upi")
    v.done()
    if b["status"] in ("cancelled", "pending_reconsent"):
        fail(409, "invalid_state", "This booking can't take payments right now.")
    remaining = b["amount"] - b["paid_amount"]
    if remaining <= 0:
        fail(409, "already_paid", "This booking is already fully paid.")
    if kind == "deposit":
        amount = b["deposit_amount"] - b["paid_amount"]
        if b["deposit_amount"] <= 0 or amount <= 0:
            fail(409, "no_deposit", "There is no deposit due on this booking.")
    else:
        amount = remaining
    ref = next_ref("pay", "PAY")
    if d.get("simulate") == "failure":
        insert("payment_transactions", id=uid(), reference=ref, booking_id=b["id"], user_id=u["id"], kind="customer_payment",
               method=method, amount=amount, status="failed", gateway_ref="sandbox-declined", created_at=now())
        commit_now()
        fail(402, "payment_failed", "Your bank declined the payment (sandbox simulation). Nothing was charged - please try again.")
    insert("payment_transactions", id=uid(), reference=ref, booking_id=b["id"], user_id=b["customer_id"], kind="customer_payment",
           method=method, amount=amount, status="success", gateway_ref="sandbox-" + uid()[:8],
           memo="Recorded by agent" if staff else None, created_at=now())
    paid = b["paid_amount"] + amount
    pstat = "paid" if paid >= b["amount"] else "deposit_paid"
    update("bookings", "id", b["id"], paid_amount=paid, payment_status=pstat, updated_at=now())
    insert("ledger_entries", id=uid(), booking_id=b["id"], provider_id=b["provider_id"], account="escrow", credit=amount,
           memo=f"Customer payment {ref}", created_at=now())
    insert("booking_events", id=uid(), booking_id=b["id"], event="payment", detail=f"Rs {amount:,} via {method}",
           actor_id=u["id"], created_at=now())
    r = get_request(b["request_id"])
    notify(b["customer_id"], "payment.success", {"reference": r["reference"], "amount": f"{amount:,}", "txn": ref},
           f"#/c/booking/{b['id']}")
    puser = val("SELECT user_id FROM providers WHERE id=?", (b["provider_id"],))
    notify(puser, "payment.provider", {"booking": b["reference"], "amount": f"{amount:,}"}, f"#/p/booking/{b['id']}")
    audit("booking.pay", "booking", b["id"], {"amount": amount, "method": method})
    return jsonify(booking_payload(b["id"]))


@bp.get("/bookings/<bid>/receipt")
@auth()
def booking_receipt(bid):
    b = _booking_access(g.user, bid)
    out = booking_payload(b["id"])
    out["receipt_no"] = "RCPT-" + b["reference"].split("-", 1)[1]
    out["issued_at"] = now()
    return jsonify(out)


def cancel_booking(b, actor, reason, cascade=True):
    if b["status"] in ("completed", "cancelled"):
        fail(409, "invalid_state", "This booking can't be cancelled.")
    started = b["status"] in ("in_progress", "work_done")
    update("bookings", "id", b["id"], status="cancelled", cancel_reason=reason, updated_at=now(),
           refund_eligible=1 if (b["paid_amount"] > 0 and not started) else 0)
    insert("booking_events", id=uid(), booking_id=b["id"], event="cancelled", detail=reason, actor_id=actor["id"], created_at=now())
    if b["paid_amount"] > 0 and not started:
        insert("refunds", id=uid(), booking_id=b["id"], requested_by=actor["id"], amount=b["paid_amount"],
               reason=reason, status="requested", created_at=now())
    puser = val("SELECT user_id FROM providers WHERE id=?", (b["provider_id"],))
    notify(puser, "booking.cancelled", {"booking": b["reference"], "reason": reason}, f"#/p/booking/{b['id']}")
    audit("booking.cancel", "booking", b["id"], {"reason": reason, "refund": b["paid_amount"] > 0 and not started})
    if cascade:
        r = get_request(b["request_id"])
        if r["status"] in ("confirmed", "in_progress"):
            change_status(r, "cancelled", actor, reason)


@bp.post("/bookings/<bid>/cancel")
@auth("customer", "agent", "team_lead", "ops_admin", "super_admin")
def booking_cancel(bid):
    u = g.user
    b = _booking_access(u, bid)
    v = V(json_body())
    reason = v.str("reason", 3, 300)
    v.done()
    if "customer" in u["roles"] and b["status"] not in ("confirmed", "pending_reconsent"):
        fail(409, "contact_agent", "The job has started. Please message your agent so we can sort this out together.")
    cancel_booking(b, u, reason)
    return jsonify(booking_payload(b["id"]))


def confirm_completion(b, r, actor, on_behalf=False):
    if b["status"] not in ("work_done", "in_progress", "confirmed") or (not on_behalf and b["status"] != "work_done"):
        fail(409, "not_ready", "Your provider hasn't marked the work as done yet.")
    if b["paid_amount"] < b["amount"]:
        fail(409, "payment_due", f"Please pay the remaining Rs {b['amount'] - b['paid_amount']:,} before confirming completion.")
    t = now()
    update("bookings", "id", b["id"], status="completed", completed_at=t, customer_confirmed_at=t, updated_at=t)
    insert("booking_events", id=uid(), booking_id=b["id"], event="completed",
           detail="Confirmed by agent on behalf of customer" if on_behalf else "Customer confirmed completion",
           actor_id=actor["id"], created_at=t)
    if r["status"] == "confirmed":
        change_status(r, "in_progress", actor, "Job started", system=True)
    if r["status"] == "in_progress":
        change_status(r, "completed", actor, "Customer confirmed completion", system=True)
    _settle_commission(b)
    run("UPDATE providers SET jobs_completed=jobs_completed+1 WHERE id=?", (b["provider_id"],))
    audit("booking.complete", "booking", b["id"])


def _settle_commission(b):
    p = one("SELECT * FROM providers WHERE id=?", (b["provider_id"],))
    rule = one("SELECT * FROM commission_rules ORDER BY version DESC LIMIT 1") or {"rate": 8.0, "version": 0}
    rate = p["commission_rate"] if p["commission_rate"] is not None else rule["rate"]
    base = b["amount"]
    com = round(base * rate / 100)
    insert("commissions", id=uid(), booking_id=b["id"], provider_id=p["id"], rate=rate, rule_version=rule["version"],
           base_amount=base, commission_amount=com, created_at=now())
    insert("ledger_entries", id=uid(), booking_id=b["id"], provider_id=p["id"], account="escrow", debit=base,
           memo="Released on completion", created_at=now())
    insert("ledger_entries", id=uid(), booking_id=b["id"], provider_id=p["id"], account="provider_payable",
           credit=base - com, memo=f"Net of {rate:g}% commission", created_at=now())
    insert("ledger_entries", id=uid(), booking_id=b["id"], provider_id=p["id"], account="buzzer_commission",
           credit=com, memo=f"Commission {rate:g}%", created_at=now())
