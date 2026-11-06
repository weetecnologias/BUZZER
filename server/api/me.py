"""Profile, addresses, consents, preferences, notifications, membership and privacy controls."""
from datetime import datetime, timedelta, timezone

from flask import Response, g, jsonify, request

from .. import config
from ..db import insert, jdump, jload, next_ref, now, one, paginate, q, run, uid, update, val
from ..events import DEFAULT_PREFS, get_prefs, notify
from ..security import V, audit, auth, fail, json_body, page_args
from . import bp
from .common import user_payload


@bp.get("/me")
@auth()
def me():
    return jsonify(user_payload(g.user))


@bp.patch("/me")
@auth()
def me_update():
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 80, required=False)
    email = v.str("email", 5, 120, required=False)
    lang = v.str("language", required=False, choices=["en", "hi", "te", "ur"])
    if email and one("SELECT 1 FROM users WHERE lower(email)=lower(?) AND id<>?", (email, g.user["id"])):
        v.errors["email"] = "This email is already used by another account."
    v.done()
    cols = {k: x for k, x in (("name", name), ("email", email), ("language", lang)) if x}
    update("users", "id", g.user["id"], **cols)
    audit("profile.update", "user", g.user["id"], list(cols))
    g.user.update(cols)
    return jsonify(user_payload(g.user))


# --------------------------------------------------------------- addresses
def _addr_fields(d):
    v = V(d)
    out = dict(
        label=v.str("label", 2, 30, default="Home"),
        line1=v.str("line1", 5, 200),
        locality=v.str("locality", 2, 60),
        landmark=v.str("landmark", 0, 120, required=False),
        pincode=v.str("pincode", 6, 6, required=False),
    )
    if out["pincode"] and not out["pincode"].isdigit():
        v.errors["pincode"] = "Enter a 6-digit pincode."
    v.done()
    return out, bool(d.get("is_default"))


@bp.get("/addresses")
@auth("customer")
def addresses():
    return jsonify({"items": q("SELECT * FROM addresses WHERE user_id=? ORDER BY is_default DESC, created_at",
                               (g.user["id"],))})


@bp.post("/addresses")
@auth("customer")
def address_create():
    f, default = _addr_fields(json_body())
    first = val("SELECT COUNT(*) FROM addresses WHERE user_id=?", (g.user["id"],), 0) == 0
    if default or first:
        run("UPDATE addresses SET is_default=0 WHERE user_id=?", (g.user["id"],))
    a = uid()
    insert("addresses", id=a, user_id=g.user["id"], is_default=1 if (default or first) else 0,
           city=config.CITY, created_at=now(), **f)
    return jsonify(one("SELECT * FROM addresses WHERE id=?", (a,))), 201


@bp.patch("/addresses/<aid>")
@auth("customer")
def address_update(aid):
    a = one("SELECT * FROM addresses WHERE id=? AND user_id=?", (aid, g.user["id"]))
    if not a:
        fail(404, "not_found", "Address not found.")
    f, default = _addr_fields({**a, **json_body()})
    if default:
        run("UPDATE addresses SET is_default=0 WHERE user_id=?", (g.user["id"],))
    update("addresses", "id", aid, is_default=1 if default else a["is_default"], **f)
    return jsonify(one("SELECT * FROM addresses WHERE id=?", (aid,)))


@bp.delete("/addresses/<aid>")
@auth("customer")
def address_delete(aid):
    a = one("SELECT * FROM addresses WHERE id=? AND user_id=?", (aid, g.user["id"]))
    if not a:
        fail(404, "not_found", "Address not found.")
    if val("SELECT COUNT(*) FROM requests WHERE service_address_id=?", (aid,), 0):
        fail(409, "in_use", "This address is attached to a request, so it can't be deleted. You can edit it instead.")
    run("DELETE FROM addresses WHERE id=?", (aid,))
    return jsonify({"ok": True})


# ------------------------------------------------------- consents & prefs
CONSENT_KINDS = {"whatsapp": "WhatsApp updates", "promotional": "Offers and promotions",
                 "candidate_data_sharing": "Share my candidate profile with verified employers"}


@bp.get("/me/consents")
@auth()
def consents_get():
    rows = {r["kind"]: r for r in q("SELECT * FROM consents WHERE user_id=?", (g.user["id"],))}
    return jsonify({"items": [{"kind": k, "label": lbl, "granted": bool(rows.get(k, {}).get("granted"))}
                              for k, lbl in CONSENT_KINDS.items()],
                    "terms_accepted": bool(rows.get("terms", {}).get("granted")),
                    "privacy_accepted": bool(rows.get("privacy", {}).get("granted"))})


@bp.put("/me/consents")
@auth()
def consents_put():
    d = json_body()
    for k, granted in d.items():
        if k in CONSENT_KINDS:
            run("""INSERT INTO consents(user_id, kind, granted, version, updated_at) VALUES (?,?,?,?,?)
                   ON CONFLICT(user_id, kind) DO UPDATE SET granted=excluded.granted, updated_at=excluded.updated_at""",
                (g.user["id"], k, 1 if granted else 0, "1.0", now()))
            if k == "whatsapp":
                prefs = get_prefs(g.user["id"])
                prefs["whatsapp"] = bool(granted)
                run("UPDATE customers SET notification_prefs=? WHERE user_id=?", (jdump(prefs), g.user["id"]))
    audit("consent.update", "user", g.user["id"], d)
    return consents_get()


PREF_LABELS = [
    ("request_updates", "Request updates", "Status changes on your requests"),
    ("quote_alerts", "Quote alerts", "When new quotes are ready to compare"),
    ("booking_reminders", "Booking reminders", "Reminders before your service"),
    ("payment_receipts", "Payment receipts", "Receipts and refund updates"),
    ("promotions", "Promotional offers", "Optional - off unless you opt in"),
    ("whatsapp", "WhatsApp copies", "Send key updates to WhatsApp as well"),
]


@bp.get("/me/preferences")
@auth("customer")
def prefs_get():
    p = get_prefs(g.user["id"])
    return jsonify({"items": [{"key": k, "label": l, "help": h, "enabled": bool(p.get(k))} for k, l, h in PREF_LABELS]})


@bp.put("/me/preferences")
@auth("customer")
def prefs_put():
    d = json_body()
    p = get_prefs(g.user["id"])
    for k in DEFAULT_PREFS:
        if k in d:
            p[k] = bool(d[k])
    run("UPDATE customers SET notification_prefs=? WHERE user_id=?", (jdump(p), g.user["id"]))
    for k in ("promotions", "whatsapp"):
        if k in d:
            run("""INSERT INTO consents(user_id, kind, granted, version, updated_at) VALUES (?,?,?,?,?)
                   ON CONFLICT(user_id, kind) DO UPDATE SET granted=excluded.granted, updated_at=excluded.updated_at""",
                (g.user["id"], "promotional" if k == "promotions" else "whatsapp", 1 if d[k] else 0, "1.0", now()))
    return prefs_get()


# ----------------------------------------------------------- notifications
@bp.get("/notifications")
@auth()
def notifications():
    page, size = page_args()
    where = "user_id=? AND channel='in_app'"
    args = [g.user["id"]]
    if request.args.get("unread") == "1":
        where += " AND is_read=0"
    res = paginate(f"SELECT * FROM notifications WHERE {where}", args, page, size, "ORDER BY created_at DESC")
    res["unread"] = val("SELECT COUNT(*) FROM notifications WHERE user_id=? AND channel='in_app' AND is_read=0",
                        (g.user["id"],), 0)
    return jsonify(res)


@bp.post("/notifications/read")
@auth()
def notifications_read():
    d = request.get_json(silent=True) or {}
    ids = d.get("ids")
    if ids:
        for i in ids[:200]:
            run("UPDATE notifications SET is_read=1 WHERE id=? AND user_id=?", (i, g.user["id"]))
    else:
        run("UPDATE notifications SET is_read=1 WHERE user_id=?", (g.user["id"],))
    return jsonify({"ok": True})


# --------------------------------------------------------- privacy controls
@bp.get("/me/export")
@auth()
def export_data():
    uid_ = g.user["id"]
    data = {
        "exported_at": now(),
        "profile": one("SELECT id,name,mobile,email,language,created_at FROM users WHERE id=?", (uid_,)),
        "addresses": q("SELECT * FROM addresses WHERE user_id=?", (uid_,)),
        "requests": q("SELECT reference,title,status,description,created_at FROM requests WHERE customer_id=?", (uid_,)),
        "consents": q("SELECT kind,granted,updated_at FROM consents WHERE user_id=?", (uid_,)),
        "notifications": q("SELECT title,body,created_at FROM notifications WHERE user_id=? LIMIT 200", (uid_,)),
    }
    audit("privacy.export", "user", uid_)
    return Response(jdump(data), mimetype="application/json",
                    headers={"Content-Disposition": "attachment; filename=buzzer-my-data.json"})


@bp.post("/me/delete-request")
@auth()
def delete_request():
    d = request.get_json(silent=True) or {}
    run("""INSERT INTO consents(user_id, kind, granted, version, updated_at) VALUES (?,?,?,?,?)
           ON CONFLICT(user_id, kind) DO UPDATE SET granted=1, updated_at=excluded.updated_at""",
        (g.user["id"], "deletion_requested", 1, "1.0", now()))
    audit("privacy.delete_request", "user", g.user["id"], {"reason": str(d.get("reason", ""))[:300]})
    return jsonify({"ok": True, "message": "We've logged your request. A BUZZER specialist will confirm within 7 days."})


# --------------------------------------------------------------- membership
@bp.get("/plans")
def plans():
    items = q("SELECT * FROM plans WHERE active=1 AND audience='customer' ORDER BY sort")
    for p in items:
        p["benefits"] = jload(p.pop("benefits_json"), [])
    return jsonify({"items": items})


@bp.get("/me/membership")
@auth("customer")
def membership():
    m = one("""SELECT m.*, p.name AS plan_name, p.code AS plan_code, p.price_month, p.price_year, p.benefits_json
               FROM memberships m JOIN plans p ON p.id=m.plan_id WHERE m.user_id=? AND m.status IN ('active','paused')
               ORDER BY m.started_at DESC""", (g.user["id"],))
    if m:
        m["benefits"] = jload(m.pop("benefits_json"), [])
    payments = q("""SELECT * FROM payment_transactions WHERE user_id=? AND kind IN ('membership','customer_payment','refund')
                    ORDER BY created_at DESC LIMIT 30""", (g.user["id"],))
    return jsonify({"membership": m, "payments": payments})


@bp.post("/me/membership")
@auth("customer")
def membership_subscribe():
    d = json_body()
    v = V(d)
    plan_id = v.str("plan_id")
    interval = v.str("interval", choices=["month", "year"], default="month")
    method = v.str("method", choices=["upi", "card", "netbanking", "wallet"], default="upi")
    v.done()
    plan = one("SELECT * FROM plans WHERE id=? AND active=1 AND audience='customer'", (plan_id,))
    if not plan:
        fail(404, "not_found", "That plan is not available.")
    price = plan["price_year"] if interval == "year" else plan["price_month"]
    run("UPDATE memberships SET status='cancelled', cancelled_at=? WHERE user_id=? AND status IN ('active','paused')",
        (now(), g.user["id"]))
    start = datetime.now(timezone.utc)
    renew = start + timedelta(days=365 if interval == "year" else 30)
    mid = uid()
    insert("memberships", id=mid, user_id=g.user["id"], plan_id=plan_id, interval=interval, status="active",
           started_at=now(), renewal_at=renew.strftime("%Y-%m-%dT%H:%M:%SZ"))
    if price:
        ref = next_ref("pay", "PAY")
        insert("payment_transactions", id=uid(), reference=ref, user_id=g.user["id"], kind="membership",
               method=method, amount=price, status="success", gateway_ref="sandbox-" + uid()[:8],
               memo=f"{plan['name']} ({interval}ly)", created_at=now())
        insert("ledger_entries", id=uid(), account="membership_revenue", credit=price,
               memo=f"{plan['name']} subscription", created_at=now())
        notify(g.user["id"], "payment.success", {"amount": price, "reference": plan["name"], "txn": ref}, "#/c/membership")
    audit("membership.subscribe", "plan", plan_id, {"interval": interval})
    return membership()


@bp.post("/me/membership/<action>")
@auth("customer")
def membership_action(action):
    m = one("SELECT * FROM memberships WHERE user_id=? AND status IN ('active','paused') ORDER BY started_at DESC",
            (g.user["id"],))
    if not m:
        fail(404, "not_found", "You don't have an active plan.")
    if action == "pause" and m["status"] == "active":
        run("UPDATE memberships SET status='paused', paused_at=? WHERE id=?", (now(), m["id"]))
    elif action == "resume" and m["status"] == "paused":
        run("UPDATE memberships SET status='active', paused_at=NULL WHERE id=?", (m["id"],))
    elif action == "cancel":
        run("UPDATE memberships SET status='cancelled', cancelled_at=? WHERE id=?", (now(), m["id"]))
    else:
        fail(409, "invalid_action", "That action isn't available for your plan right now.")
    audit(f"membership.{action}", "membership", m["id"])
    return membership()
