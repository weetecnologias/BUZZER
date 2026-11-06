"""Agent workspace: dashboard, customer portfolio, approvals, live tracking, events, performance, search."""
from datetime import datetime, timedelta, timezone

from flask import g, jsonify, request

from ..db import now, one, paginate, q, run, uid, insert, val
from ..events import notify
from ..security import audit, auth, fail, has_perm, json_body, page_args
from ..workflow import STATUS, TERMINAL
from . import bp
from .common import agent_card, get_request, require_request

AGENT = ("agent", "team_lead")
STAFF = ("agent", "team_lead", "ops_admin", "super_admin")
TERM_SQL = "('completed','cancelled','rejected','expired')"

NEXT_ACTION = {
    "submitted": ("New request - triage now", "Due now"),
    "under_review": ("Choose providers and send invites", "Due now"),
    "finding_providers": ("Chase providers for quotes", "Follow up"),
    "quotes_received": ("Validate and share the quotes", "Ready"),
    "awaiting_approval": ("Nudge the customer for a decision", "Follow up"),
    "confirmed": ("Confirm schedule and payment", "Follow up"),
    "in_progress": ("Track the job to completion", "Monitor"),
    "disputed": ("Resolve the dispute", "Due now"),
}


def _is_lead(u):
    return bool(u["roles"] & {"team_lead", "ops_admin", "super_admin"})


def _scope_agent(u):
    """Agent id to scope queries to: self for agents, optional ?agent_id for leads."""
    if _is_lead(u) and request.args.get("agent_id"):
        return request.args["agent_id"]
    return u["id"]


@bp.get("/agents")
@auth(*STAFF)
def agents_list():
    rows = q("""SELECT a.user_id AS id, a.display_name AS name, a.specialty, a.is_pool, a.team_lead_id, w.active_requests,
                       w.capacity_limit, w.availability_status AS availability,
                       (SELECT COUNT(*) FROM customer_agent_assignments c WHERE c.primary_agent_id=a.user_id AND c.status='active') AS customers
                FROM agents a JOIN agent_workloads w ON w.agent_id=a.user_id ORDER BY a.display_name""")
    return jsonify({"items": rows})


@bp.get("/agent/dashboard")
@auth(*AGENT)
def dashboard():
    u = g.user
    aid = _scope_agent(u)
    w = one("SELECT * FROM agent_workloads WHERE agent_id=?", (aid,))
    open_n = val(f"SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status NOT IN {TERM_SQL}", (aid,), 0)
    queue = q(f"""SELECT r.id, r.reference, r.title, r.status, r.priority, r.locality, r.status_changed_at, c.icon, u.name AS customer_name,
                         (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id AND x.status='submitted') AS to_validate,
                         ROUND((julianday('now') - julianday(r.status_changed_at)) * 24, 1) AS hours_in_status
                  FROM requests r JOIN categories c ON c.id=r.category_id JOIN users u ON u.id=r.customer_id
                  WHERE r.assigned_agent_id=? AND r.status NOT IN {TERM_SQL}
                  ORDER BY CASE WHEN r.priority='urgent' THEN 0 ELSE 1 END,
                           CASE r.status WHEN 'submitted' THEN 0 WHEN 'disputed' THEN 1 WHEN 'quotes_received' THEN 2 WHEN 'awaiting_approval' THEN 3
                                         WHEN 'under_review' THEN 4 WHEN 'finding_providers' THEN 5 WHEN 'confirmed' THEN 6 ELSE 7 END,
                           r.status_changed_at LIMIT 8""", (aid,))
    for it in queue:
        text, tag = NEXT_ACTION.get(it["status"], ("Review", "Monitor"))
        if it["status"] == "quotes_received" and not it["to_validate"]:
            text, tag = "Waiting for more quotes", "Follow up"
        it["next_action"], it["tag"] = text, tag
        it["status_label"] = STATUS[it["status"]]["label"]
    tasks = q("""SELECT t.id, t.title, t.due_at, t.priority, r.reference,
                        CASE WHEN t.due_at < strftime('%Y-%m-%dT%H:%M:%SZ','now') THEN 1 ELSE 0 END AS overdue
                 FROM tasks t LEFT JOIN requests r ON r.id=t.request_id WHERE t.agent_id=? AND t.status='open'
                 ORDER BY CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END, t.due_at LIMIT 6""", (aid,))
    nba = None
    if queue:
        top = queue[0]
        nba = {"request_id": top["id"], "text": f"{top['next_action']} on {top['reference']} ({top['customer_name']}).",
               "reference": top["reference"]}
    name = val("SELECT display_name FROM agents WHERE user_id=?", (aid,), "Agent")
    return jsonify({
        "agent": {"id": aid, "name": name}, "workload": w,
        "counts": {
            "open": open_n,
            "followups_due": val("SELECT COUNT(*) FROM tasks WHERE agent_id=? AND status='open' AND due_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now','+1 day')", (aid,), 0),
            "awaiting_approval": val("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status='awaiting_approval'", (aid,), 0),
            "quotes_to_validate": val("SELECT COUNT(*) FROM quotations x JOIN requests r ON r.id=x.request_id WHERE r.assigned_agent_id=? AND x.status='submitted'", (aid,), 0),
            "pool": val("SELECT COUNT(*) FROM requests WHERE assigned_agent_id IS NULL AND status NOT IN " + TERM_SQL, (), 0),
            "completed_week": val("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status='completed' AND closed_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now','-7 days')", (aid,), 0),
        },
        "queue": queue, "tasks": tasks, "next_best_action": nba,
    })


@bp.get("/agent/customers")
@auth(*AGENT)
def customers():
    u = g.user
    page, size = page_args()
    aid = _scope_agent(u)
    where, args = ["(a.primary_agent_id=? OR a.backup_agent_id=?)"], [aid, aid]
    if request.args.get("q"):
        where.append("(cu.name LIKE ? OR cu.mobile LIKE ?)")
        args += [f"%{request.args['q']}%"] * 2
    sql = f"""SELECT cu.id, cu.name, cu.mobile, cu.avatar_color, a.primary_agent_id, a.backup_agent_id,
                     CASE WHEN a.primary_agent_id=? THEN 'primary' ELSE 'backup' END AS my_role,
                     (SELECT COUNT(*) FROM requests r WHERE r.customer_id=cu.id) AS total_requests,
                     (SELECT COUNT(*) FROM requests r WHERE r.customer_id=cu.id AND r.status NOT IN {TERM_SQL}) AS open_requests,
                     (SELECT MAX(r.created_at) FROM requests r WHERE r.customer_id=cu.id) AS last_request_at,
                     (SELECT p.name FROM memberships m JOIN plans p ON p.id=m.plan_id WHERE m.user_id=cu.id AND m.status='active' LIMIT 1) AS plan
              FROM customer_agent_assignments a JOIN users cu ON cu.id=a.customer_id WHERE {' AND '.join(where)}"""
    res = paginate(sql, [aid, *args], page, size, "ORDER BY open_requests DESC, last_request_at DESC")
    res["capacity"] = one("SELECT active_requests, capacity_limit FROM agent_workloads WHERE agent_id=?", (aid,))
    res["total_customers"] = val("SELECT COUNT(*) FROM customer_agent_assignments WHERE primary_agent_id=?", (aid,), 0)
    return jsonify(res)


@bp.get("/agent/customers/<cid>")
@auth(*STAFF)
def customer_profile(cid):
    u = g.user
    cu = one("SELECT id, name, mobile, avatar_color, created_at, language FROM users WHERE id=?", (cid,))
    if not cu:
        fail(404, "not_found", "Customer not found.")
    asg = one("SELECT * FROM customer_agent_assignments WHERE customer_id=?", (cid,))
    mine = bool(asg and u["id"] in (asg["primary_agent_id"], asg["backup_agent_id"])) or one(
        "SELECT 1 FROM requests WHERE customer_id=? AND assigned_agent_id=?", (cid, u["id"]))
    if not (mine or _is_lead(u)):
        fail(403, "forbidden", "This customer isn't assigned to you.")
    audit("customer.view", "user", cid)  # sensitive record access is audited
    out = dict(cu)
    out["assignment"] = {"primary": agent_card(asg["primary_agent_id"]) if asg else None,
                         "backup": agent_card(asg["backup_agent_id"]) if asg else None,
                         "since": asg["assigned_at"] if asg else None}
    out["addresses"] = q("SELECT label, locality, landmark, city, is_default FROM addresses WHERE user_id=?", (cid,))  # street hidden
    out["membership"] = one("SELECT m.status, m.renewal_at, p.name AS plan FROM memberships m JOIN plans p ON p.id=m.plan_id WHERE m.user_id=? AND m.status IN ('active','paused')", (cid,))
    out["whatsapp_consent"] = bool(val("SELECT granted FROM consents WHERE user_id=? AND kind='whatsapp'", (cid,), 0))
    out["requests"] = q("""SELECT r.id, r.reference, r.title, r.status, r.created_at, c.icon FROM requests r JOIN categories c ON c.id=r.category_id
                           WHERE r.customer_id=? ORDER BY r.created_at DESC LIMIT 15""", (cid,))
    for r in out["requests"]:
        r["status_label"] = STATUS[r["status"]]["label"]
    out["stats"] = {
        "total": val("SELECT COUNT(*) FROM requests WHERE customer_id=?", (cid,), 0),
        "completed": val("SELECT COUNT(*) FROM requests WHERE customer_id=? AND status='completed'", (cid,), 0),
        "complaints": val("SELECT COUNT(*) FROM complaints WHERE raised_by=?", (cid,), 0),
    }
    out["conversation_id"] = val("SELECT id FROM conversations WHERE kind='customer_agent' AND customer_id=?", (cid,))
    return jsonify(out)


@bp.get("/agent/approvals")
@auth(*AGENT)
def approvals():
    u = g.user
    aid = _scope_agent(u)
    rows = q("""SELECT r.id AS request_id, r.reference, r.title, r.status_changed_at, cu.name AS customer_name, cu.mobile AS customer_mobile,
                       (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id AND x.status IN ('shared','clarification_requested')) AS quotes_open,
                       (SELECT MIN(x.total) FROM quotations x WHERE x.request_id=r.id AND x.status IN ('shared','clarification_requested')) AS best_price,
                       (SELECT MAX(x.shared_at) FROM quotations x WHERE x.request_id=r.id AND x.status IN ('shared','clarification_requested')) AS shared_at,
                       (SELECT COUNT(*) FROM quotations x WHERE x.request_id=r.id AND x.status='clarification_requested') AS questions,
                       ROUND((julianday('now') - julianday(r.status_changed_at)) * 24, 1) AS waiting_hours
                FROM requests r JOIN users cu ON cu.id=r.customer_id
                WHERE r.assigned_agent_id=? AND r.status='awaiting_approval' ORDER BY r.status_changed_at""", (aid,))
    return jsonify({"items": rows, "total": len(rows)})


@bp.post("/requests/<rid>/nudge")
@auth(*AGENT)
def nudge(rid):
    r = require_request(g.user, rid)
    if r["status"] != "awaiting_approval":
        fail(409, "invalid_state", "This request isn't waiting for approval.")
    notify(r["customer_id"], "request.status", {"reference": r["reference"], "status_label": "Waiting for your approval",
                                               "next": "Your quotes are ready - please compare and approve one."},
           f"#/c/request/{r['id']}/quotes")
    insert("contact_log", id=uid(), request_id=r["id"], agent_id=g.user["id"], party_type="customer", party_id=r["customer_id"],
           party_name=val("SELECT name FROM users WHERE id=?", (r["customer_id"],)), channel="sms", direction="outbound",
           outcome="Reminder sent", notes="Approval reminder", created_at=now())
    return jsonify({"ok": True})


@bp.get("/agent/tracking")
@auth(*AGENT)
def tracking():
    aid = _scope_agent(g.user)
    rows = q("""SELECT b.id, b.reference, b.status, b.scheduled_at, b.payment_status, b.amount, b.paid_amount, r.id AS request_id,
                       r.reference AS request_reference, r.title, r.locality, p.business_name AS provider_name, p.phone AS provider_phone,
                       cu.name AS customer_name, c.icon,
                       ROUND((julianday('now') - julianday(b.updated_at)) * 24, 1) AS hours_in_status
                FROM bookings b JOIN requests r ON r.id=b.request_id JOIN providers p ON p.id=b.provider_id
                JOIN users cu ON cu.id=b.customer_id JOIN categories c ON c.id=r.category_id
                WHERE b.agent_id=? AND b.status IN ('confirmed','on_the_way','in_progress','work_done','pending_reconsent','disputed')
                ORDER BY b.scheduled_at""", (aid,))
    t = now()
    for b in rows:
        ex = None
        if b["status"] == "confirmed" and b["scheduled_at"] and b["scheduled_at"] < t:
            ex = "Provider hasn't started - scheduled time has passed"
        elif b["status"] == "work_done" and b["hours_in_status"] > 24:
            ex = "Waiting on customer confirmation for over 24h"
        elif b["status"] == "pending_reconsent":
            ex = "Revised quote awaiting customer re-approval"
        elif b["status"] == "disputed":
            ex = "Under dispute"
        elif b["status"] == "confirmed" and b["payment_status"] == "unpaid":
            ex = "Deposit not paid yet"
        b["exception"] = ex
    return jsonify({"items": rows, "exceptions": [b for b in rows if b["exception"]]})


@bp.get("/agent/events")
@auth(*AGENT)
def events_ops():
    aid = _scope_agent(g.user)
    rows = q(f"""SELECT r.id, r.reference, r.title, r.status, r.locality, r.created_at, c.name AS category_name, c.slug, u.name AS customer_name,
                        b.kind, b.event_type, b.event_date, b.guest_count, b.budget_min, b.budget_max, b.services, b.venue,
                        (SELECT COUNT(*) FROM event_milestones m WHERE m.request_id=r.id) AS milestones_total,
                        (SELECT COUNT(*) FROM event_milestones m WHERE m.request_id=r.id AND m.status='done') AS milestones_done
                 FROM requests r JOIN categories c ON c.id=r.category_id JOIN users u ON u.id=r.customer_id
                 JOIN event_briefs b ON b.request_id=r.id
                 WHERE r.assigned_agent_id=? AND r.status NOT IN {TERM_SQL} ORDER BY b.event_date""", (aid,))
    for it in rows:
        it["status_label"] = STATUS[it["status"]]["label"]
    return jsonify({"items": rows})


# ------------------------------------------------------------ performance
def _perf(aid):
    one_ = lambda sql, a=(): val(sql, a, 0)  # noqa: E731
    total = one_("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=?", (aid,))
    done = one_("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status='completed'", (aid,))
    lost = one_("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status IN ('cancelled','rejected','expired')", (aid,))
    resp = val("SELECT AVG((julianday(first_response_at) - julianday(created_at)) * 24) FROM requests WHERE assigned_agent_id=? AND first_response_at IS NOT NULL", (aid,))
    qt = val("""SELECT AVG((julianday(x.shared_at) - julianday(r.created_at)) * 24) FROM quotations x JOIN requests r ON r.id=x.request_id
                WHERE r.assigned_agent_id=? AND x.shared_at IS NOT NULL""", (aid,))
    rat = one("SELECT AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE subject_type='agent' AND subject_id=?", (aid,))
    comp = one_("SELECT COUNT(*) FROM complaints c JOIN requests r ON r.id=c.request_id WHERE r.assigned_agent_id=?", (aid,))
    w = one("SELECT * FROM agent_workloads WHERE agent_id=?", (aid,)) or {"active_requests": 0, "capacity_limit": 1, "availability_status": "offline"}
    shared = one_("SELECT COUNT(*) FROM quotations x JOIN requests r ON r.id=x.request_id WHERE r.assigned_agent_id=? AND x.shared_at IS NOT NULL", (aid,))
    approved = one_("SELECT COUNT(*) FROM quotations x JOIN requests r ON r.id=x.request_id WHERE r.assigned_agent_id=? AND x.status='approved'", (aid,))
    return {
        "agent_id": aid, "name": val("SELECT display_name FROM agents WHERE user_id=?", (aid,), "Agent"),
        "customers": one_("SELECT COUNT(*) FROM customer_agent_assignments WHERE primary_agent_id=?", (aid,)),
        "total_requests": total, "open_requests": w["active_requests"], "capacity": w["capacity_limit"],
        "availability": w["availability_status"], "completed": done,
        "completion_rate": round(done * 100 / (done + lost)) if (done + lost) else None,
        "response_hours": round(resp, 1) if resp is not None else None,
        "quote_turnaround_hours": round(qt, 1) if qt is not None else None,
        "rating": round(rat["a"], 1) if rat["a"] else None, "rating_count": rat["n"],
        "complaint_rate": round(comp * 100 / total, 1) if total else 0,
        "quote_conversion": round(approved * 100 / shared) if shared else None,
    }


@bp.get("/agent/performance")
@auth(*STAFF)
def performance():
    u = g.user
    aid = request.args.get("agent_id") or u["id"]
    if aid != u["id"] and not _is_lead(u):
        fail(403, "forbidden", "You can only view your own performance.")
    me = _perf(aid)
    weeks = []
    for i in range(7, -1, -1):
        start = (datetime.now(timezone.utc) - timedelta(days=7 * (i + 1))).strftime("%Y-%m-%dT%H:%M:%SZ")
        end = (datetime.now(timezone.utc) - timedelta(days=7 * i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        weeks.append({
            "label": (datetime.now(timezone.utc) - timedelta(days=7 * i)).strftime("%d %b"),
            "received": val("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND created_at>=? AND created_at<?", (aid, start, end), 0),
            "completed": val("SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status='completed' AND closed_at>=? AND closed_at<?", (aid, start, end), 0),
        })
    by_status = q(f"SELECT status, COUNT(*) AS n FROM requests WHERE assigned_agent_id=? AND status NOT IN {TERM_SQL} GROUP BY status", (aid,))
    for s in by_status:
        s["label"] = STATUS[s["status"]]["label"]
    out = {"me": me, "weeks": weeks, "open_by_status": by_status}
    if _is_lead(u):
        out["team"] = [_perf(a["user_id"]) for a in q("SELECT user_id FROM agents ORDER BY display_name")]
    return jsonify(out)


# ----------------------------------------------------------------- search
@bp.get("/staff/search")
@auth(*STAFF)
def staff_search():
    text = (request.args.get("q") or "").strip()
    if len(text) < 2:
        return jsonify({"requests": [], "customers": [], "providers": []})
    like = f"%{text}%"
    u = g.user
    agent_only = "agent" in u["roles"] and not _is_lead(u)
    reqs = q(f"""SELECT r.id, r.reference, r.title, r.status, cu.name AS customer_name FROM requests r JOIN users cu ON cu.id=r.customer_id
                 WHERE (r.reference LIKE ? OR r.title LIKE ? OR cu.name LIKE ?) {"AND (r.assigned_agent_id=? OR r.assigned_agent_id IS NULL)" if agent_only else ""}
                 ORDER BY r.created_at DESC LIMIT 6""", [like] * 3 + ([u["id"]] if agent_only else []))
    custs = q("""SELECT cu.id, cu.name, cu.mobile FROM users cu JOIN user_roles ur ON ur.user_id=cu.id AND ur.role='customer'
                 WHERE (cu.name LIKE ? OR cu.mobile LIKE ?) LIMIT 5""", (like, like)) if not agent_only else q(
        """SELECT cu.id, cu.name, cu.mobile FROM users cu JOIN customer_agent_assignments a ON a.customer_id=cu.id
           WHERE (cu.name LIKE ? OR cu.mobile LIKE ?) AND (a.primary_agent_id=? OR a.backup_agent_id=?) LIMIT 5""", (like, like, u["id"], u["id"]))
    provs = q("SELECT id, business_name, locality, verification_status FROM providers WHERE business_name LIKE ? LIMIT 5", (like,))
    for r in reqs:
        r["status_label"] = STATUS[r["status"]]["label"]
    return jsonify({"requests": reqs, "customers": custs, "providers": provs})
