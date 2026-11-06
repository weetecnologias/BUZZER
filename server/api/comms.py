"""Conversations, messages, agent tasks and the contact/call log."""
from flask import g, jsonify, request

from ..db import insert, now, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, json_body, page_args
from . import bp
from .common import get_request, provider_of

AGENTS = ("agent", "team_lead", "ops_admin", "super_admin")


def _conv(u, cid):
    c = one("SELECT * FROM conversations WHERE id=?", (cid,))
    if not c:
        fail(404, "not_found", "Conversation not found.")
    roles = u["roles"]
    p = provider_of(u["id"]) if "provider" in roles else None
    ok = ((c["customer_id"] == u["id"]) or (p and c["provider_id"] == p["id"])
          or ("agent" in roles and c["agent_id"] == u["id"]) or roles & {"team_lead", "ops_admin", "super_admin"})
    if not ok:
        fail(403, "forbidden", "You don't have access to this conversation.")
    return c


def _decorate(c, u):
    agent = one("SELECT display_name AS name, phone FROM agents WHERE user_id=?", (c["agent_id"],)) if c["agent_id"] else None
    cust = one("SELECT id, name FROM users WHERE id=?", (c["customer_id"],)) if c["customer_id"] else None
    prov = one("SELECT id, business_name AS name, logo_color FROM providers WHERE id=?", (c["provider_id"],)) if c["provider_id"] else None
    last = one("SELECT body, created_at, sender_id FROM messages WHERE conversation_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1", (c["id"],))
    req = one("SELECT id, reference, title FROM requests WHERE id=?", (c["request_id"],)) if c["request_id"] else None
    out = dict(c, agent=agent, customer=cust, provider=prov, last=last, request=req)
    roles = u["roles"]
    if "customer" in roles or "provider" in roles and not (roles & set(AGENTS)):
        out["title"] = agent["name"] if agent else "BUZZER support"
        out["subtitle"] = "Your personal agent" if c["kind"] == "customer_agent" else "BUZZER agent"
    else:
        out["title"] = (cust or prov or {}).get("name", "Conversation")
        out["subtitle"] = ("Customer" if c["kind"] == "customer_agent" else "Provider") + (f" - {req['reference']}" if req else "")
    return out


@bp.get("/conversations")
@auth("customer", "provider", *AGENTS)
def conversations():
    u = g.user
    roles = u["roles"]
    if roles & {"team_lead", "ops_admin", "super_admin"} and not (roles & {"agent"}):
        rows = q("SELECT * FROM conversations ORDER BY updated_at DESC LIMIT 100")
    elif "agent" in roles:
        rows = q("SELECT * FROM conversations WHERE agent_id=? ORDER BY updated_at DESC", (u["id"],))
    elif "provider" in roles:
        p = provider_of(u["id"])
        rows = q("SELECT * FROM conversations WHERE provider_id=? ORDER BY updated_at DESC", (p["id"] if p else "-",))
    else:
        rows = q("SELECT * FROM conversations WHERE customer_id=? ORDER BY updated_at DESC", (u["id"],))
    return jsonify({"items": [_decorate(c, u) for c in rows]})


@bp.post("/conversations")
@auth("agent", "team_lead")
def conversation_open():
    v = V(json_body())
    pid = v.str("provider_id")
    rid = v.str("request_id", required=False)
    v.done()
    p = one("SELECT * FROM providers WHERE id=?", (pid,))
    if not p:
        fail(404, "not_found", "Provider not found.")
    if not p["user_id"]:
        fail(409, "no_account", "This provider isn't on the BUZZER partner app yet. Use the outreach log to record calls.")
    c = one("SELECT * FROM conversations WHERE kind='provider_agent' AND provider_id=? AND agent_id=? AND COALESCE(request_id,'')=?",
            (pid, g.user["id"], rid or ""))
    if not c:
        cid = uid()
        insert("conversations", id=cid, kind="provider_agent", provider_id=pid, agent_id=g.user["id"], request_id=rid,
               created_at=now(), updated_at=now())
        c = one("SELECT * FROM conversations WHERE id=?", (cid,))
    return jsonify(_decorate(c, g.user)), 201


@bp.get("/conversations/<cid>/messages")
@auth("customer", "provider", *AGENTS)
def messages(cid):
    c = _conv(g.user, cid)
    items = q("""SELECT m.id, m.sender_id, m.body, m.attachment_id, m.request_id, m.created_at, u.name AS sender_name,
                        a.filename AS attachment_name, a.mime AS attachment_mime
                 FROM messages m JOIN users u ON u.id=m.sender_id LEFT JOIN attachments a ON a.id=m.attachment_id
                 WHERE m.conversation_id=? ORDER BY m.created_at, m.rowid LIMIT 300""", (cid,))
    return jsonify({"conversation": _decorate(c, g.user), "items": items})


@bp.post("/conversations/<cid>/messages")
@auth("customer", "provider", *AGENTS)
def send_message(cid):
    u = g.user
    c = _conv(u, cid)
    d = json_body()
    v = V(d)
    body = v.str("body", 1, 2000, required=not d.get("attachment_id"), default="")
    v.done()
    att = d.get("attachment_id")
    if att and not one("SELECT 1 FROM attachments WHERE id=? AND owner_id=?", (att, u["id"])):
        fail(422, "validation_error", "That attachment isn't available.", {"attachment_id": "Invalid attachment."})
    rid = d.get("request_id")
    if rid:
        r = get_request(rid)
        if c["customer_id"] and r["customer_id"] != c["customer_id"]:
            rid = None
    mid = uid()
    insert("messages", id=mid, conversation_id=cid, sender_id=u["id"], body=body or "(attachment)", attachment_id=att,
           request_id=rid, created_at=now())
    run("UPDATE conversations SET updated_at=? WHERE id=?", (now(), cid))
    # counterpart notification
    sender_is_agent = bool(u["roles"] & set(AGENTS))
    target = None
    if sender_is_agent:
        target = c["customer_id"] or val("SELECT user_id FROM providers WHERE id=?", (c["provider_id"],))
        if rid:
            run("UPDATE requests SET first_response_at=COALESCE(first_response_at, ?) WHERE id=?", (now(), rid))
    else:
        target = c["agent_id"]
    notify(target, "message.new", {"sender": u["name"], "preview": (body or "Sent an attachment")[:80]},
           f"#/{'c' if c['customer_id'] and sender_is_agent else 'a' if not sender_is_agent else 'p'}/messages")
    return jsonify({"id": mid}), 201


# ------------------------------------------------------------------- tasks
@bp.get("/tasks")
@auth("agent", "team_lead")
def tasks():
    page, size = page_args()
    where, args = ["t.agent_id=?"], [g.user["id"]]
    st = request.args.get("status", "open")
    if st in ("open", "done"):
        where.append("t.status=?")
        args.append(st)
    if request.args.get("due") == "today":
        where.append("t.due_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now','+1 day')")
    sql = f"""SELECT t.*, r.reference, u.name AS customer_name,
                     CASE WHEN t.status='open' AND t.due_at IS NOT NULL AND t.due_at < strftime('%Y-%m-%dT%H:%M:%SZ','now') THEN 1 ELSE 0 END AS overdue
              FROM tasks t LEFT JOIN requests r ON r.id=t.request_id LEFT JOIN users u ON u.id=t.customer_id WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END, t.due_at"))


@bp.post("/tasks")
@auth("agent", "team_lead")
def task_create():
    d = json_body()
    v = V(d)
    title = v.str("title", 3, 160)
    due = v.dt("due_at")
    pr = v.str("priority", choices=["low", "normal", "high"], default="normal")
    v.done()
    rid = None
    cust = None
    if d.get("request_id"):
        r = get_request(d["request_id"])
        rid, cust = r["id"], r["customer_id"]
    tid = uid()
    insert("tasks", id=tid, agent_id=g.user["id"], request_id=rid, customer_id=cust, title=title, due_at=due,
           priority=pr, status="open", created_at=now())
    return jsonify({"id": tid}), 201


@bp.patch("/tasks/<tid>")
@auth("agent", "team_lead")
def task_update(tid):
    t = one("SELECT * FROM tasks WHERE id=? AND agent_id=?", (tid, g.user["id"]))
    if not t:
        fail(404, "not_found", "Task not found.")
    d = json_body()
    cols = {}
    if "status" in d:
        if d["status"] not in ("open", "done"):
            fail(422, "validation_error", "Invalid status.", {"status": "Invalid status."})
        cols["status"] = d["status"]
        cols["completed_at"] = now() if d["status"] == "done" else None
    if "due_at" in d:
        v = V(d)
        cols["due_at"] = v.dt("due_at")
        v.done()
    update("tasks", "id", tid, **cols)
    return jsonify({"ok": True})


@bp.delete("/tasks/<tid>")
@auth("agent", "team_lead")
def task_delete(tid):
    run("DELETE FROM tasks WHERE id=? AND agent_id=?", (tid, g.user["id"]))
    return jsonify({"ok": True})


# ------------------------------------------------------------ contact log
@bp.get("/contact-log")
@auth("agent", "team_lead", "ops_admin", "super_admin")
def contact_log():
    page, size = page_args()
    where, args = ["1=1"], []
    if "agent" in g.user["roles"] and not (g.user["roles"] & {"team_lead", "ops_admin", "super_admin"}):
        where.append("l.agent_id=?")
        args.append(g.user["id"])
    for key, col in (("request_id", "l.request_id"), ("party_type", "l.party_type"), ("channel", "l.channel")):
        if request.args.get(key):
            where.append(f"{col}=?")
            args.append(request.args[key])
    sql = f"""SELECT l.*, r.reference FROM contact_log l LEFT JOIN requests r ON r.id=l.request_id WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY l.created_at DESC"))


@bp.post("/contact-log")
@auth("agent", "team_lead")
def contact_log_add():
    d = json_body()
    v = V(d)
    party_type = v.str("party_type", choices=["customer", "provider"])
    channel = v.str("channel", choices=["call", "whatsapp", "sms", "email"])
    outcome = v.str("outcome", 2, 80)
    notes = v.str("notes", 0, 500, required=False)
    direction = v.str("direction", choices=["outbound", "inbound"], default="outbound")
    v.done()
    rid = None
    if d.get("request_id"):
        r = get_request(d["request_id"])
        rid = r["id"]
        run("UPDATE requests SET first_response_at=COALESCE(first_response_at, ?) WHERE id=?", (now(), rid))
    party_id, party_name = d.get("party_id"), d.get("party_name")
    if party_type == "provider" and party_id:
        party_name = val("SELECT business_name FROM providers WHERE id=?", (party_id,), party_name)
    if party_type == "customer" and rid:
        party_id = get_request(rid)["customer_id"]
        party_name = val("SELECT name FROM users WHERE id=?", (party_id,), party_name)
    if not party_name:
        fail(422, "validation_error", "Who did you speak to?", {"party_name": "Choose a provider or request."})
    insert("contact_log", id=uid(), request_id=rid, agent_id=g.user["id"], party_type=party_type, party_id=party_id,
           party_name=party_name, channel=channel, direction=direction, outcome=outcome, notes=notes, created_at=now())
    audit("contact.log", "request", rid, {"channel": channel, "party": party_type})
    return jsonify({"ok": True}), 201
