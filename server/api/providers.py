"""Provider network: agent/admin directory, verification, and the partner portal API."""
from datetime import datetime, timedelta, timezone

from flask import g, jsonify, request

from ..db import insert, jdump, jload, now, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, has_perm, json_body, page_args
from ..workflow import TERMINAL
from . import bp
from .common import get_request, provider_of

STAFF = ("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
REQUIRED_DOCS = ("id_proof", "address_proof")
DOC_LABELS = {"id_proof": "ID proof (Aadhaar / PAN)", "address_proof": "Business address proof",
              "gst": "GST certificate (optional)", "business_photos": "Business photos", "licence": "Trade licence"}


def _me_provider():
    p = provider_of(g.user["id"])
    if not p:
        fail(404, "no_provider", "No business profile is linked to this account.")
    return p


@bp.get("/showcase")
@auth("customer")
def showcase():
    """Public-facing provider cards (no contact details) so customers can compare portfolios and packages."""
    slug = request.args.get("category")
    where, args = ["p.verification_status='verified'", "p.status='active'"], []
    if slug:
        where.append("EXISTS (SELECT 1 FROM provider_services s JOIN categories c ON c.id=s.category_id WHERE s.provider_id=p.id AND c.slug=?)")
        args.append(slug)
    items = q(f"""SELECT p.id, p.business_name, p.kind, p.locality, p.rating_avg, p.rating_count, p.jobs_completed, p.logo_color, p.description
                  FROM providers p WHERE {' AND '.join(where)} ORDER BY p.rating_avg DESC, p.jobs_completed DESC LIMIT 12""", args)
    for p in items:
        p["packages"] = q("SELECT name, price, details_json FROM provider_packages WHERE provider_id=? AND active=1 ORDER BY price", (p["id"],))
        for k in p["packages"]:
            k["details"] = jload(k.pop("details_json"), {})
        p["portfolio"] = q("SELECT title, color FROM portfolio_items WHERE provider_id=? ORDER BY created_at DESC LIMIT 6", (p["id"],))
    return jsonify({"items": items})


# ------------------------------------------------- agent / admin directory
PROVIDER_LIST_SQL = """
SELECT p.id, p.business_name, p.type, p.kind, p.phone, p.locality, p.verification_status, p.status, p.rating_avg,
       p.rating_count, p.jobs_completed, p.response_rate, p.source, p.logo_color, p.user_id, p.created_at,
       (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT DISTINCT c.name FROM provider_services s
          JOIN categories c ON c.id=s.category_id WHERE s.provider_id=p.id AND s.active=1)) AS categories,
       (SELECT GROUP_CONCAT(locality, ', ') FROM provider_areas WHERE provider_id=p.id) AS areas,
       (SELECT COUNT(*) FROM provider_documents d WHERE d.provider_id=p.id AND d.status='pending') AS docs_pending
FROM providers p
"""


@bp.get("/providers")
@auth(*STAFF)
def providers_list():
    page, size = page_args()
    where, args = ["1=1"], []
    a = request.args
    if a.get("verification_status"):
        where.append("p.verification_status=?")
        args.append(a["verification_status"])
    if a.get("status"):
        where.append("p.status=?")
        args.append(a["status"])
    if a.get("kind"):
        where.append("p.kind=?")
        args.append(a["kind"])
    if a.get("category_id"):
        where.append("EXISTS (SELECT 1 FROM provider_services s WHERE s.provider_id=p.id AND s.category_id=? AND s.active=1)")
        args.append(a["category_id"])
    if a.get("locality"):
        where.append("(p.locality=? OR EXISTS (SELECT 1 FROM provider_areas z WHERE z.provider_id=p.id AND z.locality=?))")
        args += [a["locality"]] * 2
    if a.get("q"):
        where.append("(p.business_name LIKE ? OR p.phone LIKE ?)")
        args += [f"%{a['q']}%"] * 2
    sort = {"rating": "rating_avg DESC", "jobs": "jobs_completed DESC", "newest": "created_at DESC"}.get(a.get("sort"), "business_name")
    return jsonify(paginate(f"SELECT * FROM ({PROVIDER_LIST_SQL} WHERE {' AND '.join(where)})", args, page, size, f"ORDER BY {sort}"))


def _provider_full(pid, include_docs=True):
    p = one("SELECT * FROM providers WHERE id=?", (pid,))
    if not p:
        fail(404, "not_found", "That provider was not found.")
    p["services"] = q("""SELECT s.id, s.name, s.base_price, s.unit, s.description, s.active, c.name AS category_name,
                         c.id AS category_id, c.icon FROM provider_services s JOIN categories c ON c.id=s.category_id
                         WHERE s.provider_id=? ORDER BY c.name, s.name""", (pid,))
    p["areas"] = [r["locality"] for r in q("SELECT locality FROM provider_areas WHERE provider_id=? ORDER BY locality", (pid,))]
    if include_docs:
        p["documents"] = q("SELECT id, doc_type, doc_number, status, expires_at, review_note, reviewed_at, created_at, attachment_id "
                           "FROM provider_documents WHERE provider_id=? ORDER BY created_at DESC", (pid,))
    return p


@bp.get("/providers/<pid>")
@auth(*STAFF)
def provider_detail(pid):
    p = _provider_full(pid)
    if not (g.user["roles"] & {"ops_admin", "super_admin", "verification_reviewer"}):
        for d in p["documents"]:  # agents never see raw document numbers
            d["doc_number"] = None
    p["reviews"] = q("""SELECT v.rating, v.comment, v.created_at, u.name AS reviewer FROM reviews v JOIN users u ON u.id=v.reviewer_id
                        WHERE v.subject_type='provider' AND v.subject_id=? ORDER BY v.created_at DESC LIMIT 5""", (pid,))
    p["packages"] = q("SELECT * FROM provider_packages WHERE provider_id=? AND active=1", (pid,))
    for k in p["packages"]:
        k["details"] = jload(k.pop("details_json"), {})
    p["recent_contacts"] = q("SELECT channel, outcome, notes, created_at FROM contact_log WHERE party_id=? ORDER BY created_at DESC LIMIT 5", (pid,))
    return jsonify(p)


@bp.post("/providers")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def provider_create():
    d = json_body()
    v = V(d)
    name = v.str("business_name", 2, 120)
    phone = v.mobile("phone")
    locality = v.str("locality", 2, 60)
    ptype = v.str("type", choices=["individual", "business"], default="business")
    kind = v.str("kind", choices=["general", "catering", "photography"], default="general")
    cats = d.get("category_ids") or []
    if not cats:
        v.errors["category_ids"] = "Choose at least one category."
    v.done()
    pid = uid()
    insert("providers", id=pid, business_name=name, type=ptype, kind=kind, phone=phone, locality=locality,
           verification_status="unverified", status="active", source="internal_directory", logo_color="#C7D3C4",
           created_at=now(), description=(d.get("description") or "")[:500])
    insert("provider_areas", id=uid(), provider_id=pid, locality=locality)
    for loc in (d.get("areas") or [])[:10]:
        run("INSERT OR IGNORE INTO provider_areas(id, provider_id, locality) VALUES (?,?,?)", (uid(), pid, loc))
    for c in cats[:6]:
        if one("SELECT 1 FROM categories WHERE id=?", (c,)):
            insert("provider_services", id=uid(), provider_id=pid, category_id=c, name=name, base_price=0, unit="per job", active=1)
    audit("provider.create", "provider", pid, {"source": "agent"})
    return jsonify(_provider_full(pid)), 201


@bp.post("/providers/<pid>/verify")
@auth(perm="verification")
def provider_verify(pid):
    p = _provider_full(pid)
    d = json_body()
    v = V(d)
    action = v.str("action", choices=["approve", "reject"])
    note = v.str("note", 0, 400, required=False)
    if action == "reject" and not note:
        v.errors["note"] = "Tell the partner what to fix."
    v.done()
    if action == "approve":
        missing = [t for t in REQUIRED_DOCS if not any(x["doc_type"] == t and x["status"] == "verified" for x in p["documents"])]
        if missing:
            fail(409, "documents_pending", "Verify these documents first: " + ", ".join(DOC_LABELS[m] for m in missing))
    status = "verified" if action == "approve" else "rejected"
    update("providers", "id", pid, verification_status=status)
    notify(p["user_id"], "partner.verification", {"status": status, "note": note or "You can now receive leads and quotes."},
           "#/p/documents")
    audit("provider.verify", "provider", pid, {"action": action, "note": note})
    return jsonify(_provider_full(pid))


@bp.post("/provider-documents/<did>/review")
@auth(perm="verification")
def document_review(did):
    doc = one("SELECT * FROM provider_documents WHERE id=?", (did,))
    if not doc:
        fail(404, "not_found", "Document not found.")
    d = json_body()
    v = V(d)
    st = v.str("status", choices=["verified", "rejected"])
    note = v.str("note", 0, 300, required=False)
    if st == "rejected" and not note:
        v.errors["note"] = "Say why the document was rejected."
    v.done()
    update("provider_documents", "id", did, status=st, reviewed_by=g.user["id"], reviewed_at=now(), review_note=note)
    audit("provider.document", "provider_document", did, {"status": st})
    return jsonify({"ok": True})


@bp.post("/providers/<pid>/suspend")
@auth(perm="partners")
def provider_suspend(pid):
    p = one("SELECT * FROM providers WHERE id=?", (pid,))
    if not p:
        fail(404, "not_found", "Provider not found.")
    d = json_body()
    suspend = bool(d.get("suspend", True))
    if suspend and not (d.get("reason") or "").strip():
        fail(422, "validation_error", "A reason is required to suspend.", {"reason": "Required."})
    update("providers", "id", pid, status="suspended" if suspend else "active")
    audit("provider.suspend" if suspend else "provider.reinstate", "provider", pid, d.get("reason"))
    return jsonify(_provider_full(pid))


# ------------------------------------------------------------- partner portal
def _money(pid):
    led = one("""SELECT COALESCE(SUM(credit),0) AS earned, COALESCE(SUM(debit),0) AS paid FROM ledger_entries
                 WHERE provider_id=? AND account='provider_payable'""", (pid,))
    requested = val("SELECT COALESCE(SUM(amount),0) FROM payouts WHERE provider_id=? AND status='requested'", (pid,), 0)
    com = one("SELECT COALESCE(SUM(base_amount),0) AS gross, COALESCE(SUM(commission_amount),0) AS commission, "
              "COALESCE(AVG(rate),0) AS rate FROM commissions WHERE provider_id=?", (pid,))
    return {"net_earned": led["earned"], "paid_out": led["paid"], "pending_payout": requested,
            "available": led["earned"] - led["paid"] - requested, "gross": com["gross"],
            "commission": com["commission"], "commission_rate": round(com["rate"], 1)}


def _month_net(pid, offset):
    """Net earnings in a rolling 30-day window (offset 0 = last 30 days, 1 = the 30 days before)."""
    end = datetime.now(timezone.utc) - timedelta(days=30 * offset)
    start = end - timedelta(days=30)
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    return val("""SELECT COALESCE(SUM(credit),0) FROM ledger_entries WHERE provider_id=? AND account='provider_payable'
                  AND created_at>? AND created_at<=?""", (pid, start.strftime(fmt), end.strftime(fmt)), 0)


@bp.get("/partner/dashboard")
@auth("provider")
def partner_dashboard():
    p = _me_provider()
    money = _money(p["id"])
    this_m, last_m = _month_net(p["id"], 0), _month_net(p["id"], 1)
    growth = round((this_m - last_m) * 100 / last_m) if last_m else None
    new_leads = val(f"""SELECT COUNT(*) FROM request_invites i JOIN requests r ON r.id=i.request_id WHERE i.provider_id=?
                        AND i.status='invited' AND r.status NOT IN ('completed','cancelled','rejected','expired')""", (p["id"],), 0)
    docs = {d["doc_type"]: d["status"] for d in q("SELECT doc_type, status FROM provider_documents WHERE provider_id=?", (p["id"],))}
    return jsonify({
        "provider": p, "money": money, "month_earnings": this_m, "growth_pct": growth,
        "counters": {
            "new_leads": new_leads,
            "in_progress": val("SELECT COUNT(*) FROM bookings WHERE provider_id=? AND status IN ('confirmed','on_the_way','in_progress','work_done','pending_reconsent')", (p["id"],), 0),
            "completed": p["jobs_completed"],
            "pending_quotes": val("SELECT COUNT(*) FROM quotations WHERE provider_id=? AND status IN ('submitted','shared')", (p["id"],), 0),
        },
        "missing_docs": [t for t in REQUIRED_DOCS if docs.get(t) not in ("pending", "verified")],
        "verification_status": p["verification_status"],
    })


LEAD_SQL = """
SELECT i.id AS invite_id, i.status AS invite_status, i.created_at AS invited_at, r.id AS request_id, r.reference,
       r.title, r.description, r.locality, r.preferred_at, r.priority, r.budget_min, r.budget_max, r.status AS request_status,
       c.name AS category_name, c.icon AS category_icon, c.color AS category_color,
       (SELECT x.status FROM quotations x WHERE x.request_id=r.id AND x.provider_id=i.provider_id ORDER BY x.created_at DESC LIMIT 1) AS quote_status,
       (SELECT x.total FROM quotations x WHERE x.request_id=r.id AND x.provider_id=i.provider_id ORDER BY x.created_at DESC LIMIT 1) AS quote_total,
       (SELECT x.id FROM quotations x WHERE x.request_id=r.id AND x.provider_id=i.provider_id ORDER BY x.created_at DESC LIMIT 1) AS quote_id,
       ROUND((julianday('now') - julianday(i.created_at)) * 24 * 60) AS posted_minutes_ago
FROM request_invites i JOIN requests r ON r.id=i.request_id JOIN categories c ON c.id=r.category_id
WHERE i.provider_id=?
"""


@bp.get("/leads")
@auth("provider")
def leads():
    p = _me_provider()
    page, size = page_args()
    areas = [a["locality"] for a in q("SELECT locality FROM provider_areas WHERE provider_id=?", (p["id"],))]
    tab = request.args.get("tab", "all")
    where, args = [], []
    if tab == "responded":
        where.append("(invite_status IN ('quoted','declined'))")
    else:
        where.append("invite_status='invited' AND request_status NOT IN ('completed','cancelled','rejected','expired','confirmed','in_progress')")
    if tab == "nearby":
        marks = ",".join("?" for _ in areas) or "''"
        where.append(f"locality IN ({marks})")
        args += areas
    if tab == "urgent":
        where.append("priority='urgent'")
    if request.args.get("q"):
        where.append("(title LIKE ? OR reference LIKE ? OR locality LIKE ?)")
        args += [f"%{request.args['q']}%"] * 3
    res = paginate(f"SELECT * FROM ({LEAD_SQL}) WHERE {' AND '.join(where)}", [p["id"], *args], page, size, "ORDER BY invited_at DESC")
    for it in res["items"]:
        it["in_service_area"] = it["locality"] in areas
    counts = {
        "all": val(f"SELECT COUNT(*) FROM ({LEAD_SQL}) WHERE invite_status='invited' AND request_status NOT IN ('completed','cancelled','rejected','expired','confirmed','in_progress')", (p["id"],), 0),
        "urgent": val(f"SELECT COUNT(*) FROM ({LEAD_SQL}) WHERE invite_status='invited' AND priority='urgent' AND request_status NOT IN ('completed','cancelled','rejected','expired','confirmed','in_progress')", (p["id"],), 0),
    }
    res["counts"] = counts
    return jsonify(res)


@bp.get("/leads/<rid>")
@auth("provider")
def lead_detail(rid):
    """Providers see only what's needed to quote: no customer identity or exact address."""
    p = _me_provider()
    r = get_request(rid)
    inv = one("SELECT * FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], p["id"]))
    if not inv:
        fail(403, "forbidden", "You haven't been invited to this request.")
    cat = one("SELECT id,name,icon,color FROM categories WHERE id=?", (r["category_id"],))
    qt = one("SELECT id, status, total, version FROM quotations WHERE request_id=? AND provider_id=? ORDER BY created_at DESC", (r["id"], p["id"]))
    if qt:
        qt["items"] = q("SELECT description, qty, unit_price, amount FROM quotation_items WHERE quotation_id=?", (qt["id"],))
    return jsonify({
        "request_id": r["id"], "reference": r["reference"], "title": r["title"], "description": r["description"],
        "locality": r["locality"], "preferred_at": r["preferred_at"], "priority": r["priority"],
        "budget_min": r["budget_min"], "budget_max": r["budget_max"], "request_status": r["status"],
        "category": cat, "invite": inv, "details": q("SELECT field_label AS label, value FROM request_details WHERE request_id=?", (r["id"],)),
        "brief": one("SELECT * FROM event_briefs WHERE request_id=?", (r["id"],)),
        "attachments": q("SELECT id, filename, mime FROM attachments WHERE request_id=?", (r["id"],)),
        "quote": qt, "can_quote": inv["status"] != "declined" and r["status"] in ("under_review", "finding_providers", "quotes_received", "awaiting_approval") and not (qt and qt["status"] not in ("withdrawn", "rejected", "not_selected")),
    })


@bp.post("/leads/<rid>/decline")
@auth("provider")
def lead_decline(rid):
    p = _me_provider()
    r = get_request(rid)
    inv = one("SELECT * FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], p["id"]))
    if not inv or inv["status"] != "invited":
        fail(409, "invalid_state", "This lead can't be declined.")
    v = V(json_body())
    reason = v.str("reason", 3, 200, default="Not available")
    v.done()
    run("UPDATE request_invites SET status='declined', decline_reason=?, responded_at=? WHERE id=?", (reason, now(), inv["id"]))
    if r["assigned_agent_id"]:
        insert("contact_log", id=uid(), request_id=r["id"], agent_id=r["assigned_agent_id"], party_type="provider",
               party_id=p["id"], party_name=p["business_name"], channel="sms", direction="inbound",
               outcome="Declined", notes=reason, created_at=now())
    return jsonify({"ok": True})


@bp.get("/partner/profile")
@auth("provider")
def partner_profile():
    p = _provider_full(_me_provider()["id"])
    p["packages"] = q("SELECT * FROM provider_packages WHERE provider_id=? ORDER BY name", (p["id"],))
    for k in p["packages"]:
        k["details"] = jload(k.pop("details_json"), {})
    p["portfolio"] = q("SELECT * FROM portfolio_items WHERE provider_id=? ORDER BY created_at DESC", (p["id"],))
    p["money"] = _money(p["id"])
    return jsonify(p)


@bp.patch("/partner/profile")
@auth("provider")
def partner_profile_update():
    p = _me_provider()
    d = json_body()
    v = V(d)
    cols = dict(business_name=v.str("business_name", 2, 120, default=p["business_name"]),
                description=v.str("description", 0, 600, required=False, default=p["description"]),
                phone=v.mobile("phone", required=False) or p["phone"],
                email=v.str("email", 5, 120, required=False, default=p["email"]),
                address=v.str("address", 0, 200, required=False, default=p["address"]),
                locality=v.str("locality", 0, 60, required=False, default=p["locality"]),
                upi_id=v.str("upi_id", 0, 80, required=False, default=p["upi_id"]))
    acct = str(d.get("bank_account") or "").strip()
    if acct:
        if not acct.isdigit() or not (9 <= len(acct) <= 18):
            v.errors["bank_account"] = "Enter a valid account number (9-18 digits)."
        else:
            cols["bank_masked"] = "XXXX" + acct[-4:]  # only the masked form is stored
    v.done()
    update("providers", "id", p["id"], **cols)
    audit("partner.profile", "provider", p["id"], [k for k in cols])
    return partner_profile()


@bp.post("/partner/services")
@auth("provider")
def service_add():
    p = _me_provider()
    d = json_body()
    v = V(d)
    cat = v.str("category_id")
    name = v.str("name", 2, 80)
    price = v.int("base_price", min=0, max=1_000_000, required=True)
    unit = v.str("unit", 0, 30, default="per job")
    v.done()
    if not one("SELECT 1 FROM categories WHERE id=?", (cat,)):
        fail(422, "validation_error", "Choose a category.", {"category_id": "Invalid category."})
    sid = uid()
    insert("provider_services", id=sid, provider_id=p["id"], category_id=cat, name=name, base_price=price, unit=unit,
           description=(d.get("description") or "")[:300], active=1)
    return jsonify({"id": sid}), 201


@bp.patch("/partner/services/<sid>")
@auth("provider")
def service_update(sid):
    p = _me_provider()
    s = one("SELECT * FROM provider_services WHERE id=? AND provider_id=?", (sid, p["id"]))
    if not s:
        fail(404, "not_found", "Service not found.")
    d = json_body()
    v = V({**s, **d})
    cols = dict(name=v.str("name", 2, 80), base_price=v.int("base_price", min=0, max=1_000_000, required=True),
                unit=v.str("unit", 0, 30, default="per job"), description=(d.get("description", s["description"]) or "")[:300],
                active=1 if d.get("active", s["active"]) else 0)
    v.done()
    update("provider_services", "id", sid, **cols)
    return jsonify({"ok": True})


@bp.delete("/partner/services/<sid>")
@auth("provider")
def service_delete(sid):
    p = _me_provider()
    run("DELETE FROM provider_services WHERE id=? AND provider_id=?", (sid, p["id"]))
    return jsonify({"ok": True})


@bp.put("/partner/areas")
@auth("provider")
def areas_put():
    p = _me_provider()
    areas = json_body().get("areas")
    if not isinstance(areas, list) or not areas:
        fail(422, "validation_error", "Choose at least one service area.", {"areas": "Choose at least one area."})
    run("DELETE FROM provider_areas WHERE provider_id=?", (p["id"],))
    for loc in areas[:25]:
        run("INSERT OR IGNORE INTO provider_areas(id, provider_id, locality) VALUES (?,?,?)", (uid(), p["id"], str(loc)[:60]))
    return jsonify({"areas": areas})


@bp.get("/partner/documents")
@auth("provider")
def documents_list():
    p = _me_provider()
    docs = q("SELECT id, doc_type, doc_number, status, expires_at, review_note, created_at, attachment_id FROM provider_documents "
             "WHERE provider_id=? ORDER BY created_at DESC", (p["id"],))
    latest = {}
    for d in docs:
        latest.setdefault(d["doc_type"], d)
    items = []
    for t, label in DOC_LABELS.items():
        d = latest.get(t)
        items.append({"doc_type": t, "label": label, "required": t in REQUIRED_DOCS, "document": d})
    return jsonify({"items": items, "verification_status": p["verification_status"]})


@bp.post("/partner/documents")
@auth("provider")
def document_upload():
    p = _me_provider()
    d = json_body()
    v = V(d)
    t = v.str("doc_type", choices=list(DOC_LABELS))
    number = v.str("doc_number", 0, 40, required=False)
    exp = v.str("expires_at", 0, 12, required=False)
    att = d.get("attachment_id")
    if not att or not one("SELECT 1 FROM attachments WHERE id=? AND owner_id=?", (att, g.user["id"])):
        v.errors["attachment_id"] = "Upload the document file first."
    v.done()
    masked = ("XXXX" + number[-4:]) if number and len(number) > 4 else number
    run("DELETE FROM provider_documents WHERE provider_id=? AND doc_type=? AND status IN ('pending','rejected')", (p["id"], t))
    insert("provider_documents", id=uid(), provider_id=p["id"], doc_type=t, doc_number=masked, attachment_id=att,
           status="pending", expires_at=exp, created_at=now())
    return jsonify({"ok": True}), 201


@bp.post("/partner/verification/submit")
@auth("provider")
def verification_submit():
    p = _me_provider()
    have = {d["doc_type"] for d in q("SELECT doc_type FROM provider_documents WHERE provider_id=? AND status IN ('pending','verified')", (p["id"],))}
    missing = [t for t in REQUIRED_DOCS if t not in have]
    if missing:
        fail(422, "validation_error", "Upload: " + ", ".join(DOC_LABELS[m] for m in missing), {"documents": "Required documents are missing."})
    if p["verification_status"] == "verified":
        fail(409, "already_verified", "Your business is already verified.")
    update("providers", "id", p["id"], verification_status="pending")
    audit("partner.verification_submit", "provider", p["id"])
    return jsonify({"verification_status": "pending"})


@bp.get("/partner/availability")
@auth("provider")
def availability():
    p = _me_provider()
    hours = {h["weekday"]: h for h in q("SELECT * FROM provider_availability WHERE provider_id=?", (p["id"],))}
    items = [hours.get(i, {"weekday": i, "start_time": "09:00", "end_time": "18:00", "active": 1 if i < 6 else 0}) for i in range(7)]
    month = request.args.get("month") or datetime.now(timezone.utc).strftime("%Y-%m")
    bookings = q("""SELECT b.id, b.reference, b.status, b.scheduled_at, r.title, r.locality FROM bookings b JOIN requests r ON r.id=b.request_id
                    WHERE b.provider_id=? AND b.status<>'cancelled' AND substr(b.scheduled_at,1,7)=? ORDER BY b.scheduled_at""", (p["id"], month))
    return jsonify({"hours": items, "blocked": q("SELECT date, reason FROM provider_blocked_dates WHERE provider_id=? ORDER BY date", (p["id"],)),
                    "bookings": bookings, "max_jobs_per_day": p["max_jobs_per_day"], "month": month})


@bp.put("/partner/availability")
@auth("provider")
def availability_put():
    p = _me_provider()
    d = json_body()
    for h in d.get("hours", [])[:7]:
        st, en = str(h.get("start_time", "09:00"))[:5], str(h.get("end_time", "18:00"))[:5]
        if en <= st:
            fail(422, "validation_error", "End time must be after start time.", {"hours": "End time must be after start time."})
        run("""INSERT INTO provider_availability(id, provider_id, weekday, start_time, end_time, active) VALUES (?,?,?,?,?,?)
               ON CONFLICT(provider_id, weekday) DO UPDATE SET start_time=excluded.start_time, end_time=excluded.end_time, active=excluded.active""",
            (uid(), p["id"], int(h["weekday"]), st, en, 1 if h.get("active") else 0))
    if "max_jobs_per_day" in d:
        v = V(d)
        n = v.int("max_jobs_per_day", min=1, max=50, required=True)
        v.done()
        update("providers", "id", p["id"], max_jobs_per_day=n)
    return availability()


@bp.post("/partner/availability/blocked")
@auth("provider")
def blocked_add():
    p = _me_provider()
    v = V(json_body())
    date = v.str("date", 10, 10)
    reason = v.str("reason", 0, 100, required=False)
    v.done()
    run("INSERT OR REPLACE INTO provider_blocked_dates(id, provider_id, date, reason) VALUES (?,?,?,?)", (uid(), p["id"], date, reason))
    return availability()


@bp.delete("/partner/availability/blocked/<date>")
@auth("provider")
def blocked_remove(date):
    p = _me_provider()
    run("DELETE FROM provider_blocked_dates WHERE provider_id=? AND date=?", (p["id"], date))
    return availability()


@bp.get("/partner/earnings")
@auth("provider")
def earnings():
    p = _me_provider()
    page, size = page_args()
    res = paginate("""SELECT c.id, c.base_amount AS gross, c.commission_amount AS commission, c.rate,
                             c.base_amount - c.commission_amount AS net, c.created_at, b.reference AS booking, r.title
                      FROM commissions c JOIN bookings b ON b.id=c.booking_id JOIN requests r ON r.id=b.request_id
                      WHERE c.provider_id=?""", [p["id"]], page, size, "ORDER BY c.created_at DESC")
    res["money"] = _money(p["id"])
    res["payouts"] = q("SELECT * FROM payouts WHERE provider_id=? ORDER BY requested_at DESC LIMIT 20", (p["id"],))
    res["payout_ready"] = bool(p["bank_masked"] or p["upi_id"])
    res["verification_status"] = p["verification_status"]
    return jsonify(res)


@bp.post("/partner/payouts")
@auth("provider")
def payout_request():
    p = _me_provider()
    v = V(json_body())
    amount = v.int("amount", min=100, required=True)
    v.done()
    if p["verification_status"] != "verified":
        fail(403, "not_verified", "Payouts are available once your business is verified.")
    if not (p["bank_masked"] or p["upi_id"]):
        fail(409, "no_payout_account", "Add a bank account or UPI ID in your business profile first.")
    if amount > _money(p["id"])["available"]:
        fail(409, "insufficient", "That's more than your available balance.", {"amount": "More than available."})
    insert("payouts", id=uid(), provider_id=p["id"], amount=amount, status="requested", requested_at=now())
    audit("payout.request", "provider", p["id"], {"amount": amount})
    return jsonify(_money(p["id"])), 201


@bp.get("/partner/reviews")
@auth("provider")
def partner_reviews():
    p = _me_provider()
    items = q("""SELECT v.id, v.rating, v.comment, v.reply, v.created_at, u.name AS reviewer FROM reviews v JOIN users u ON u.id=v.reviewer_id
                 WHERE v.subject_type='provider' AND v.subject_id=? ORDER BY v.created_at DESC""", (p["id"],))
    hist = {i: 0 for i in range(1, 6)}
    for r in items:
        hist[r["rating"]] += 1
    return jsonify({"items": items, "histogram": hist, "average": p["rating_avg"], "count": p["rating_count"]})


@bp.post("/partner/reviews/<rid>/reply")
@auth("provider")
def review_reply(rid):
    p = _me_provider()
    v = V(json_body())
    text = v.str("reply", 2, 400)
    v.done()
    n = run("UPDATE reviews SET reply=? WHERE id=? AND subject_type='provider' AND subject_id=?", (text, rid, p["id"])).rowcount
    if not n:
        fail(404, "not_found", "Review not found.")
    return jsonify({"ok": True})


# ------------------------------------------- packages & portfolio (catering / photography)
@bp.post("/partner/packages")
@auth("provider")
def package_add():
    p = _me_provider()
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 80)
    price = v.int("price", min=0, max=50_000_000, required=True)
    kind = v.str("kind", choices=["catering", "photography", "other"], default=p["kind"] if p["kind"] != "general" else "other")
    v.done()
    details = d.get("details") if isinstance(d.get("details"), dict) else {}
    pid = uid()
    insert("provider_packages", id=pid, provider_id=p["id"], kind=kind, name=name, price=price,
           details_json=jdump({k: str(x)[:200] for k, x in details.items()}), active=1)
    return jsonify({"id": pid}), 201


@bp.delete("/partner/packages/<pid>")
@auth("provider")
def package_delete(pid):
    p = _me_provider()
    run("DELETE FROM provider_packages WHERE id=? AND provider_id=?", (pid, p["id"]))
    return jsonify({"ok": True})


@bp.post("/partner/portfolio")
@auth("provider")
def portfolio_add():
    p = _me_provider()
    v = V(json_body())
    title = v.str("title", 2, 80)
    cap = v.str("caption", 0, 200, required=False)
    v.done()
    colors = ["#BEEB5D", "#9BD3AE", "#F2C14E", "#8FB8ED", "#F4A9A0", "#C7B8EA"]
    n = val("SELECT COUNT(*) FROM portfolio_items WHERE provider_id=?", (p["id"],), 0)
    insert("portfolio_items", id=uid(), provider_id=p["id"], title=title, caption=cap, color=colors[n % len(colors)], created_at=now())
    return jsonify({"ok": True}), 201


@bp.delete("/partner/portfolio/<iid>")
@auth("provider")
def portfolio_delete(iid):
    p = _me_provider()
    run("DELETE FROM portfolio_items WHERE id=? AND provider_id=?", (iid, p["id"]))
    return jsonify({"ok": True})
