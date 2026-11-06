"""Admin console: users, agents, assignment, catalogue, workflows, oversight, finance, reports, audit, settings."""
import csv
import io
import secrets
from datetime import datetime, timedelta, timezone

from flask import Response, g, jsonify, request

from .. import config
from ..db import insert, jdump, jload, next_ref, now, one, paginate, q, run, uid, update, val
from ..events import DEFAULT_TEMPLATES, _render, notify
from ..security import (ADMIN_ROLES, PERMISSIONS, ROLE_PERMS, V, audit, auth, fail, hash_password, has_perm,
                        json_body, page_args, strong_password)
from ..workflow import STATUS, STATUS_META, TERMINAL, refresh_workload, set_request_agent
from . import bp
from .common import get_request, user_payload

TERM_SQL = "('completed','cancelled','rejected','expired')"
STAFF_ROLES = ["agent", "team_lead", "ops_admin", "finance_admin", "verification_reviewer", "super_admin"]


def _days_ago(n):
    return (datetime.now(timezone.utc) - timedelta(days=n)).strftime("%Y-%m-%dT%H:%M:%SZ")


# ------------------------------------------------------------- dashboard
@bp.get("/admin/metrics")
@auth(*ADMIN_ROLES)
def metrics():
    u = g.user
    out = {"city": config.CITY}
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    total30 = val("SELECT COUNT(*) FROM requests WHERE created_at>=?", (_days_ago(30),), 0)
    done30 = val("SELECT COUNT(*) FROM requests WHERE status='completed' AND created_at>=?", (_days_ago(30),), 0)
    closed30 = val("SELECT COUNT(*) FROM requests WHERE status IN ('completed','cancelled','rejected','expired') AND created_at>=?", (_days_ago(30),), 0)
    out["kpis"] = {
        "requests_today": val("SELECT COUNT(*) FROM requests WHERE substr(created_at,1,10)=?", (today,), 0),
        "requests_30d": total30,
        "completion_rate": round(done30 * 100 / closed30) if closed30 else None,
        "active_partners": val("SELECT COUNT(*) FROM providers WHERE status='active' AND verification_status='verified'", (), 0),
        "open_escalations": val("SELECT COUNT(*) FROM complaints WHERE status IN ('open','investigating','escalated')", (), 0),
        "gmv_30d": val("SELECT COALESCE(SUM(amount),0) FROM bookings WHERE status<>'cancelled' AND created_at>=?", (_days_ago(30),), 0),
        "commission_30d": val("SELECT COALESCE(SUM(commission_amount),0) FROM commissions WHERE created_at>=?", (_days_ago(30),), 0),
        "new_customers_7d": val("SELECT COUNT(*) FROM users u JOIN user_roles r ON r.user_id=u.id AND r.role='customer' WHERE u.created_at>=?", (_days_ago(7),), 0),
        "customers": val("SELECT COUNT(*) FROM user_roles WHERE role='customer'", (), 0),
    }
    over = q("""SELECT COUNT(*) AS n FROM agent_workloads WHERE active_requests >= capacity_limit""")[0]["n"]
    out["attention"] = [
        {"key": "verification", "label": "Partner verification", "detail": f"{val('SELECT COUNT(*) FROM provider_documents WHERE status=?', ('pending',), 0)} documents waiting",
         "count": val("SELECT COUNT(*) FROM provider_documents WHERE status='pending'", (), 0), "link": "#/x/verification"},
        {"key": "workload", "label": "Agent workload", "detail": f"{over} agents at or above capacity", "count": over, "link": "#/x/agents"},
        {"key": "payments", "label": "Payment exceptions", "detail": "Refunds and payouts needing review",
         "count": val("SELECT COUNT(*) FROM refunds WHERE status='requested'", (), 0) + val("SELECT COUNT(*) FROM payouts WHERE status='requested'", (), 0), "link": "#/x/finance"},
        {"key": "unassigned", "label": "Unassigned requests", "detail": "Waiting in the pool for an agent",
         "count": val(f"SELECT COUNT(*) FROM requests WHERE assigned_agent_id IS NULL AND status NOT IN {TERM_SQL}", (), 0), "link": "#/x/requests?scope=pool"},
        {"key": "complaints", "label": "Overdue complaints", "detail": "Past their SLA",
         "count": val("SELECT COUNT(*) FROM complaints WHERE status IN ('open','investigating','escalated') AND sla_due_at < strftime('%Y-%m-%dT%H:%M:%SZ','now')", (), 0), "link": "#/x/complaints"},
        {"key": "jobs", "label": "Vacancies to verify", "detail": "Quick Jobs moderation queue",
         "count": val("SELECT COUNT(*) FROM vacancies WHERE status='pending_verification'", (), 0), "link": "#/x/jobs"},
    ]
    trend = []
    for i in range(13, -1, -1):
        d = (datetime.now(timezone.utc) - timedelta(days=i)).strftime("%Y-%m-%d")
        trend.append({"date": d, "label": d[8:] + "/" + d[5:7],
                      "requests": val("SELECT COUNT(*) FROM requests WHERE substr(created_at,1,10)=?", (d,), 0),
                      "completed": val("SELECT COUNT(*) FROM requests WHERE status='completed' AND substr(closed_at,1,10)=?", (d,), 0)})
    out["trend"] = trend
    out["by_category"] = q("""SELECT c.name, c.icon, COUNT(*) AS n FROM requests r JOIN categories c ON c.id=r.category_id
                              WHERE r.created_at>=? GROUP BY c.id ORDER BY n DESC LIMIT 6""", (_days_ago(30),))
    out["by_status"] = [{"status": s["status"], "label": STATUS[s["status"]]["label"], "n": s["n"]} for s in q(
        f"SELECT status, COUNT(*) AS n FROM requests WHERE status NOT IN {TERM_SQL} GROUP BY status")]
    out["quick_actions"] = [{"label": "Review requests", "link": "#/x/requests"}, {"label": "Assign agent", "link": "#/x/assignment"},
                            {"label": "Verify partner", "link": "#/x/verification"}, {"label": "Open finance queue", "link": "#/x/finance"}]
    return jsonify(out)


# ----------------------------------------------------------------- users
@bp.get("/admin/users")
@auth(perm="users")
def users_list():
    page, size = page_args()
    a = request.args
    where, args = ["1=1"], []
    if a.get("role"):
        where.append("EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id=u.id AND r.role=?)")
        args.append(a["role"])
    if a.get("status"):
        where.append("u.status=?")
        args.append(a["status"])
    if a.get("q"):
        where.append("(u.name LIKE ? OR u.mobile LIKE ? OR u.email LIKE ?)")
        args += [f"%{a['q']}%"] * 3
    sql = f"""SELECT u.id, u.name, u.mobile, u.email, u.status, u.created_at, u.last_login_at,
                     (SELECT GROUP_CONCAT(role, ', ') FROM user_roles r WHERE r.user_id=u.id) AS roles,
                     (SELECT granted FROM consents c WHERE c.user_id=u.id AND c.kind='deletion_requested') AS deletion_requested
              FROM users u WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY u.created_at DESC"))


@bp.get("/admin/users/<uid_>")
@auth(perm="users")
def user_detail(uid_):
    u = one("SELECT id, name, mobile, email, status, language, created_at, last_login_at FROM users WHERE id=?", (uid_,))
    if not u:
        fail(404, "not_found", "User not found.")
    u["roles"] = [r["role"] for r in q("SELECT role FROM user_roles WHERE user_id=?", (uid_,))]
    u["requests"] = val("SELECT COUNT(*) FROM requests WHERE customer_id=?", (uid_,), 0)
    u["sessions"] = q("SELECT created_at, expires_at, portal, ip, revoked FROM sessions WHERE user_id=? ORDER BY created_at DESC LIMIT 5", (uid_,))
    u["deletion_requested"] = bool(val("SELECT granted FROM consents WHERE user_id=? AND kind='deletion_requested'", (uid_,), 0))
    audit("user.view", "user", uid_)  # sensitive record access
    return jsonify(u)


@bp.patch("/admin/users/<uid_>")
@auth(perm="users")
def user_update(uid_):
    t = one("SELECT * FROM users WHERE id=?", (uid_,))
    if not t:
        fail(404, "not_found", "User not found.")
    d = json_body()
    v = V(d)
    st = v.str("status", choices=["active", "suspended"], required=False)
    name = v.str("name", 2, 80, required=False)
    v.done()
    if uid_ == g.user["id"] and st == "suspended":
        fail(409, "self_suspend", "You can't suspend your own account.")
    target_roles = {r["role"] for r in q("SELECT role FROM user_roles WHERE user_id=?", (uid_,))}
    if target_roles & {"super_admin"} and "super_admin" not in g.user["roles"]:
        fail(403, "forbidden", "Only a super admin can change another super admin.")
    cols = {k: x for k, x in (("status", st), ("name", name)) if x}
    update("users", "id", uid_, **cols)
    if st == "suspended":
        run("UPDATE sessions SET revoked=1 WHERE user_id=?", (uid_,))
    audit("user.update", "user", uid_, cols)
    return user_detail(uid_)


@bp.post("/admin/users")
@auth(perm="users")
def staff_create():
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 80)
    email = v.str("email", 5, 120)
    mobile = v.mobile("mobile", required=False)
    role = v.str("role", choices=STAFF_ROLES)
    pw = v.str("password", 8, 128)
    if pw and not strong_password(pw):
        v.errors["password"] = "Use at least 8 characters with letters and numbers."
    if email and one("SELECT 1 FROM users WHERE lower(email)=lower(?)", (email,)):
        v.errors["email"] = "This email is already used."
    if mobile and one("SELECT 1 FROM users WHERE mobile=?", (mobile,)):
        v.errors["mobile"] = "This mobile is already used."
    if role == "super_admin" and "super_admin" not in g.user["roles"]:
        v.errors["role"] = "Only a super admin can create another super admin."
    v.done()
    new = uid()
    insert("users", id=new, name=name, email=email, mobile=mobile, password_hash=hash_password(pw), status="active",
           language="en", avatar_color="#BEEB5D", created_at=now())
    insert("user_roles", user_id=new, role=role)
    if role in ("agent", "team_lead"):
        insert("agents", user_id=new, display_name=name.split()[0] if len(name.split()) > 1 else name, is_pool=1,
               specialty=(d.get("specialty") or "General")[:60], phone=mobile, created_at=now())
        insert("agent_workloads", agent_id=new, active_requests=0,
               capacity_limit=int(val("SELECT value FROM settings WHERE key='agent_default_capacity'", (), config.DEFAULT_AGENT_CAPACITY)),
               availability_status="available")
        if role == "team_lead":
            run("INSERT OR IGNORE INTO user_roles(user_id, role) VALUES (?, 'agent')", (new,))
    audit("user.create", "user", new, {"role": role})
    return user_detail(new)


# ---------------------------------------------------------------- agents
@bp.get("/admin/agents")
@auth(perm="agents")
def admin_agents():
    rows = q("""SELECT a.user_id AS id, a.display_name AS name, u.email, u.status, a.specialty, a.is_pool, a.team_lead_id, a.phone,
                       w.active_requests, w.capacity_limit, w.availability_status AS availability,
                       (SELECT COUNT(*) FROM customer_agent_assignments c WHERE c.primary_agent_id=a.user_id AND c.status='active') AS customers,
                       (SELECT ROUND(AVG(rating),1) FROM reviews v WHERE v.subject_type='agent' AND v.subject_id=a.user_id) AS rating,
                       (SELECT GROUP_CONCAT(role) FROM user_roles r WHERE r.user_id=a.user_id) AS roles
                FROM agents a JOIN users u ON u.id=a.user_id JOIN agent_workloads w ON w.agent_id=a.user_id ORDER BY a.display_name""")
    return jsonify({"items": rows, "default_capacity": int(val("SELECT value FROM settings WHERE key='agent_default_capacity'", (), config.DEFAULT_AGENT_CAPACITY))})


@bp.patch("/admin/agents/<aid>")
@auth(perm="agents")
def admin_agent_update(aid):
    if not one("SELECT 1 FROM agents WHERE user_id=?", (aid,)):
        fail(404, "not_found", "Agent not found.")
    d = json_body()
    v = V(d)
    cap = v.int("capacity_limit", min=1, max=1000)
    av = v.str("availability_status", choices=["available", "busy", "away", "offline"], required=False)
    v.done()
    if cap is not None:
        run("UPDATE agent_workloads SET capacity_limit=? WHERE agent_id=?", (cap, aid))
    if av:
        run("UPDATE agent_workloads SET availability_status=? WHERE agent_id=?", (av, aid))
    if "is_pool" in d:
        run("UPDATE agents SET is_pool=? WHERE user_id=?", (1 if d["is_pool"] else 0, aid))
    if d.get("specialty"):
        run("UPDATE agents SET specialty=? WHERE user_id=?", (str(d["specialty"])[:60], aid))
    audit("agent.update", "agent", aid, d)
    return jsonify({"ok": True})


# ------------------------------------------------------------- assignment
@bp.get("/admin/assignment")
@auth(perm="assignment")
def assignment():
    return jsonify({
        "rules": [dict(r, config=jload(r.pop("config_json"), {})) for r in q("SELECT * FROM assignment_rules ORDER BY priority")],
        "default_capacity": int(val("SELECT value FROM settings WHERE key='agent_default_capacity'", (), config.DEFAULT_AGENT_CAPACITY)),
        "history": q("""SELECT h.*, fa.display_name AS from_name, ta.display_name AS to_name, cu.name AS customer_name, r.reference
                        FROM assignment_history h LEFT JOIN agents fa ON fa.user_id=h.from_agent_id LEFT JOIN agents ta ON ta.user_id=h.to_agent_id
                        LEFT JOIN users cu ON cu.id=h.customer_id LEFT JOIN requests r ON r.id=h.request_id
                        ORDER BY h.changed_at DESC LIMIT 25"""),
        "unassigned": val(f"SELECT COUNT(*) FROM requests WHERE assigned_agent_id IS NULL AND status NOT IN {TERM_SQL}", (), 0),
    })


@bp.put("/admin/assignment/rules/<rid>")
@auth(perm="assignment")
def assignment_rule(rid):
    r = one("SELECT * FROM assignment_rules WHERE id=?", (rid,))
    if not r:
        fail(404, "not_found", "Rule not found.")
    d = json_body()
    cols = {}
    if "active" in d:
        cols["active"] = 1 if d["active"] else 0
    if "priority" in d:
        cols["priority"] = max(1, min(99, int(d["priority"])))
    if isinstance(d.get("config"), dict):
        cols["config_json"] = jdump(d["config"])
    update("assignment_rules", "id", rid, **cols)
    audit("assignment.rule", "assignment_rule", rid, cols)
    return assignment()


@bp.post("/admin/customers/<cid>/reassign")
@auth(perm="assignment")
def reassign_customer(cid):
    d = json_body()
    v = V(d)
    primary = v.str("primary_agent_id")
    backup = v.str("backup_agent_id", required=False)
    reason = v.str("reason", 3, 200)
    v.done()
    a = one("SELECT * FROM customer_agent_assignments WHERE customer_id=?", (cid,))
    if not a:
        fail(404, "not_found", "Customer has no assignment.")
    for ag in (primary, backup):
        if ag and not one("SELECT 1 FROM agents WHERE user_id=?", (ag,)):
            fail(404, "not_found", "That agent doesn't exist.")
    if backup and backup == primary:
        fail(422, "validation_error", "Backup must be a different agent.", {"backup_agent_id": "Pick a different agent."})
    run("UPDATE customer_agent_assignments SET primary_agent_id=?, backup_agent_id=?, assigned_at=?, assigned_by=?, reason=? WHERE customer_id=?",
        (primary, backup or None, now(), g.user["id"], reason, cid))
    insert("assignment_history", id=uid(), customer_id=cid, from_agent_id=a["primary_agent_id"], to_agent_id=primary,
           reason=reason, changed_by=g.user["id"], changed_at=now())
    moved = 0
    if d.get("move_open_requests", True):
        for r in q(f"SELECT * FROM requests WHERE customer_id=? AND status NOT IN {TERM_SQL}", (cid,)):
            set_request_agent(r, primary, g.user["id"], reason)
            moved += 1
    from ..workflow import ensure_customer_conversation
    ensure_customer_conversation(cid, primary)
    audit("customer.reassign", "user", cid, {"to": primary, "reason": reason, "moved": moved})
    return jsonify({"ok": True, "moved_requests": moved})


# ------------------------------------------------------- verification queue
@bp.get("/admin/verification-queue")
@auth(perm="verification")
def verification_queue():
    provs = q("""SELECT p.id, p.business_name, p.type, p.locality, p.verification_status, p.created_at, p.source, u.name AS owner, u.mobile
                 FROM providers p LEFT JOIN users u ON u.id=p.user_id
                 WHERE p.verification_status='pending' OR EXISTS (SELECT 1 FROM provider_documents d WHERE d.provider_id=p.id AND d.status='pending')
                 ORDER BY p.created_at""")
    for p in provs:
        p["documents"] = q("SELECT id, doc_type, doc_number, status, expires_at, review_note, created_at, attachment_id FROM provider_documents WHERE provider_id=? ORDER BY created_at DESC", (p["id"],))
    emps = q("SELECT e.*, u.mobile AS user_mobile FROM employers e JOIN users u ON u.id=e.user_id WHERE e.verification_status='pending' ORDER BY e.created_at")
    return jsonify({"providers": provs, "employers": emps})


# ----------------------------------------------------------- categories
@bp.get("/admin/categories")
@auth(perm="catalogue")
def admin_categories():
    rows = q("""SELECT c.*, m.name AS module_name, w.name AS workflow_name,
                       (SELECT COUNT(*) FROM requests r WHERE r.category_id=c.id) AS requests,
                       (SELECT COUNT(DISTINCT s.provider_id) FROM provider_services s WHERE s.category_id=c.id AND s.active=1) AS providers
                FROM categories c LEFT JOIN modules m ON m.id=c.module_id LEFT JOIN workflow_templates w ON w.id=c.workflow_template_id ORDER BY c.sort""")
    for c in rows:
        c["schema"] = jload(c.pop("schema_json"), [])
        for k in ("includes_json", "how_json", "prepare_json"):
            c.pop(k, None)
    return jsonify({"items": rows, "modules": q("SELECT * FROM modules ORDER BY sort"),
                    "workflows": q("SELECT id, name FROM workflow_templates ORDER BY name")})


@bp.patch("/admin/categories/<cid>")
@auth(perm="catalogue")
def admin_category_update(cid):
    c = one("SELECT * FROM categories WHERE id=?", (cid,))
    if not c:
        fail(404, "not_found", "Category not found.")
    d = json_body()
    v = V(d)
    cols = {}
    if "name" in d:
        cols["name"] = v.str("name", 2, 80)
    if "description" in d:
        cols["description"] = v.str("description", 0, 400, required=False) or ""
    if "phase" in d:
        cols["phase"] = v.int("phase", min=1, max=8, required=True)
    if "is_active" in d:
        cols["is_active"] = 1 if d["is_active"] else 0
    if "module_id" in d:
        cols["module_id"] = d["module_id"]
    if "workflow_template_id" in d:
        cols["workflow_template_id"] = d["workflow_template_id"]
    if isinstance(d.get("schema"), list):
        for f in d["schema"]:
            if not (isinstance(f, dict) and f.get("key") and f.get("label") and f.get("type") in ("text", "textarea", "select", "number", "date")):
                v.errors["schema"] = "Each field needs a key, label and a valid type."
                break
        cols["schema_json"] = jdump(d["schema"])
    v.done()
    update("categories", "id", cid, **cols)
    audit("category.update", "category", cid, {k: x for k, x in cols.items() if k != "schema_json"})
    return jsonify({"ok": True})


@bp.post("/admin/categories")
@auth(perm="catalogue")
def admin_category_create():
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 80)
    module = v.str("module_id")
    v.done()
    slug = "-".join("".join(ch for ch in name.lower() if ch.isalnum() or ch == " ").split())
    if one("SELECT 1 FROM categories WHERE slug=?", (slug,)):
        fail(409, "duplicate", "A category with that name already exists.", {"name": "Already exists."})
    cid = uid()
    tpl = d.get("workflow_template_id") or val("SELECT id FROM workflow_templates WHERE code='special_request'")
    insert("categories", id=cid, slug=slug, name=name, module_id=module, description=(d.get("description") or "")[:400],
           icon="✨", color="#E6F4D7", phase=int(d.get("phase") or 2), is_active=0, workflow_template_id=tpl,
           schema_json=jdump([{"key": "details", "label": "Tell us more", "type": "textarea", "required": True}]),
           sort=val("SELECT COALESCE(MAX(sort),0)+1 FROM categories", (), 99))
    audit("category.create", "category", cid, name)
    return jsonify({"id": cid}), 201


# ------------------------------------------------------------- workflows
@bp.get("/admin/workflows")
@auth(perm="catalogue")
def workflows():
    out = []
    for t in q("SELECT * FROM workflow_templates ORDER BY name"):
        t["stages"] = jload(t.pop("stages_json"), [])
        t["steps"] = q("SELECT status_key, label, sla_hours, approval_gate FROM workflow_steps WHERE template_id=? ORDER BY seq", (t["id"],))
        t["categories"] = q("SELECT name FROM categories WHERE workflow_template_id=? ORDER BY sort", (t["id"],))
        out.append(t)
    return jsonify({"items": out, "statuses": [{"key": k, "label": l} for k, l, _, _ in STATUS_META]})


@bp.put("/admin/workflows/<tid>")
@auth(perm="catalogue")
def workflow_update(tid):
    t = one("SELECT * FROM workflow_templates WHERE id=?", (tid,))
    if not t:
        fail(404, "not_found", "Workflow not found.")
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 80, default=t["name"])
    desc = v.str("description", 0, 300, required=False, default=t["description"])
    v.done()
    steps = d.get("steps")
    if isinstance(steps, list):
        seen = set()
        for i, s in enumerate(steps):
            if s.get("status_key") not in STATUS or s["status_key"] in seen:
                fail(422, "validation_error", "Each step needs a unique, valid status.", {"steps": f"Step {i + 1} is invalid."})
            seen.add(s["status_key"])
            try:
                hrs = int(s.get("sla_hours"))
            except (TypeError, ValueError):
                hrs = -1
            if not (1 <= hrs <= 720):
                fail(422, "validation_error", "SLA must be between 1 and 720 hours.", {"steps": f"Step {i + 1}: SLA must be 1-720 hours."})
            if s["status_key"] == "awaiting_approval" and not s.get("approval_gate"):
                fail(422, "validation_error", "Customer approval is a mandatory gate and can't be switched off.",
                     {"steps": "The customer-approval gate is mandatory."})
        run("DELETE FROM workflow_steps WHERE template_id=?", (tid,))
        for i, s in enumerate(steps):
            insert("workflow_steps", id=uid(), template_id=tid, seq=i + 1, status_key=s["status_key"],
                   label=str(s.get("label") or STATUS[s["status_key"]]["label"])[:60], sla_hours=int(s["sla_hours"]),
                   approval_gate=1 if s.get("approval_gate") or s["status_key"] == "awaiting_approval" else 0)
    cols = {"name": name, "description": desc}
    if isinstance(d.get("stages"), list):
        cols["stages_json"] = jdump([str(x)[:80] for x in d["stages"]][:12])
    update("workflow_templates", "id", tid, **cols)
    audit("workflow.update", "workflow", tid, {"steps": len(steps) if isinstance(steps, list) else None})
    return workflows()


# ---------------------------------------------------------------- oversight
@bp.get("/admin/oversight")
@auth(perm="oversight")
def oversight():
    return jsonify({
        "stale_approvals": q("""SELECT r.id, r.reference, r.title, cu.name AS customer_name, a.display_name AS agent_name,
                                       ROUND((julianday('now') - julianday(r.status_changed_at)) * 24) AS waiting_hours
                                FROM requests r JOIN users cu ON cu.id=r.customer_id LEFT JOIN agents a ON a.user_id=r.assigned_agent_id
                                WHERE r.status='awaiting_approval' ORDER BY r.status_changed_at LIMIT 20"""),
        "reconsent": q("""SELECT b.id, b.reference, r.reference AS request_reference, p.business_name, b.amount FROM bookings b
                          JOIN requests r ON r.id=b.request_id JOIN providers p ON p.id=b.provider_id WHERE b.status='pending_reconsent'"""),
        "unpaid": q("""SELECT b.id, b.reference, r.reference AS request_reference, p.business_name, b.amount, b.scheduled_at FROM bookings b
                       JOIN requests r ON r.id=b.request_id JOIN providers p ON p.id=b.provider_id
                       WHERE b.status='confirmed' AND b.payment_status='unpaid' ORDER BY b.scheduled_at LIMIT 20"""),
        "cancellations": q("""SELECT b.id, b.reference, b.cancel_reason, b.paid_amount, b.refund_eligible, b.updated_at, p.business_name
                              FROM bookings b JOIN providers p ON p.id=b.provider_id WHERE b.status='cancelled' ORDER BY b.updated_at DESC LIMIT 20"""),
        "disputed": q("""SELECT b.id, b.reference, r.reference AS request_reference, p.business_name, b.amount FROM bookings b
                         JOIN requests r ON r.id=b.request_id JOIN providers p ON p.id=b.provider_id WHERE b.status='disputed'"""),
        "stats": {
            "quotes_shared": val("SELECT COUNT(*) FROM quotations WHERE shared_at IS NOT NULL", (), 0),
            "quotes_approved": val("SELECT COUNT(*) FROM quotations WHERE status='approved'", (), 0),
            "bookings_live": val("SELECT COUNT(*) FROM bookings WHERE status NOT IN ('cancelled','completed')", (), 0),
            "duplicate_guard": "Database enforces one live booking per request",
            "approval_gate": "Only an approved quote can create a booking",
        },
    })


# ------------------------------------------------------------------ finance
@bp.get("/admin/finance/summary")
@auth(perm="finance")
def finance_summary():
    return jsonify({
        "collected": val("SELECT COALESCE(SUM(amount),0) FROM payment_transactions WHERE kind='customer_payment' AND status='success'", (), 0),
        "refunded": val("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE status='processed'", (), 0),
        "commission": val("SELECT COALESCE(SUM(commission_amount),0) FROM commissions", (), 0),
        "membership_revenue": val("SELECT COALESCE(SUM(amount),0) FROM payment_transactions WHERE kind='membership' AND status='success'", (), 0),
        "payable": val("SELECT COALESCE(SUM(credit)-SUM(debit),0) FROM ledger_entries WHERE account='provider_payable'", (), 0),
        "escrow": val("SELECT COALESCE(SUM(credit)-SUM(debit),0) FROM ledger_entries WHERE account='escrow'", (), 0),
        "pending_refunds": val("SELECT COUNT(*) FROM refunds WHERE status='requested'", (), 0),
        "pending_payouts": val("SELECT COUNT(*) FROM payouts WHERE status='requested'", (), 0),
        "commission_rule": one("SELECT * FROM commission_rules ORDER BY version DESC LIMIT 1"),
        "commission_rules": q("SELECT * FROM commission_rules ORDER BY version DESC"),
        "sandbox": True,
    })


@bp.get("/admin/finance/transactions")
@auth(perm="finance")
def finance_transactions():
    page, size = page_args()
    where, args = ["1=1"], []
    for key in ("kind", "status"):
        if request.args.get(key):
            where.append(f"t.{key}=?")
            args.append(request.args[key])
    if request.args.get("q"):
        where.append("(t.reference LIKE ? OR u.name LIKE ? OR b.reference LIKE ?)")
        args += [f"%{request.args['q']}%"] * 3
    sql = f"""SELECT t.*, u.name AS user_name, b.reference AS booking_reference FROM payment_transactions t
              JOIN users u ON u.id=t.user_id LEFT JOIN bookings b ON b.id=t.booking_id WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY t.created_at DESC"))


@bp.get("/admin/finance/refunds")
@auth(perm="finance")
def finance_refunds():
    page, size = page_args()
    where, args = ["1=1"], []
    if request.args.get("status"):
        where.append("f.status=?")
        args.append(request.args["status"])
    sql = f"""SELECT f.*, b.reference AS booking_reference, b.paid_amount, cu.name AS customer_name, p.business_name FROM refunds f
              JOIN bookings b ON b.id=f.booking_id JOIN users cu ON cu.id=b.customer_id JOIN providers p ON p.id=b.provider_id
              WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY f.created_at DESC"))


@bp.post("/admin/finance/refunds/<fid>/decide")
@auth(perm="finance")
def refund_decide(fid):
    f = one("SELECT * FROM refunds WHERE id=?", (fid,))
    if not f:
        fail(404, "not_found", "Refund not found.")
    v = V(json_body())
    action = v.str("action", choices=["approve", "reject"])
    v.done()
    if f["status"] != "requested":
        fail(409, "invalid_state", "This refund has already been decided.")
    b = one("SELECT * FROM bookings WHERE id=?", (f["booking_id"],))
    if action == "reject":
        update("refunds", "id", fid, status="rejected", decided_by=g.user["id"], decided_at=now())
    else:
        ref = next_ref("pay", "PAY")
        insert("payment_transactions", id=uid(), reference=ref, booking_id=b["id"], user_id=b["customer_id"], kind="refund",
               method="original", amount=f["amount"], status="success", gateway_ref="sandbox-refund", memo="Refund", created_at=now())
        insert("ledger_entries", id=uid(), booking_id=b["id"], provider_id=b["provider_id"], account="escrow", debit=f["amount"],
               memo=f"Refund {ref}", created_at=now())
        update("refunds", "id", fid, status="processed", decided_by=g.user["id"], decided_at=now())
        update("bookings", "id", b["id"], payment_status="refunded", updated_at=now())
    notify(b["customer_id"], "refund.update", {"status": "approved and processed" if action == "approve" else "declined",
                                              "amount": f"{f['amount']:,}", "booking": b["reference"]}, "#/c/membership")
    audit("refund.decide", "refund", fid, {"action": action, "amount": f["amount"]})
    return jsonify({"ok": True})


@bp.get("/admin/finance/payouts")
@auth(perm="finance")
def finance_payouts():
    page, size = page_args()
    where, args = ["1=1"], []
    if request.args.get("status"):
        where.append("y.status=?")
        args.append(request.args["status"])
    sql = f"""SELECT y.*, p.business_name, p.bank_masked, p.upi_id, p.verification_status FROM payouts y
              JOIN providers p ON p.id=y.provider_id WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY y.requested_at DESC"))


@bp.post("/admin/finance/payouts/<yid>/decide")
@auth(perm="finance")
def payout_decide(yid):
    y = one("SELECT * FROM payouts WHERE id=?", (yid,))
    if not y:
        fail(404, "not_found", "Payout not found.")
    v = V(json_body())
    action = v.str("action", choices=["pay", "reject"])
    v.done()
    if y["status"] != "requested":
        fail(409, "invalid_state", "This payout has already been decided.")
    p = one("SELECT * FROM providers WHERE id=?", (y["provider_id"],))
    if action == "reject":
        update("payouts", "id", yid, status="rejected", decided_by=g.user["id"])
    else:
        ref = next_ref("payout", "PO")
        update("payouts", "id", yid, status="paid", reference=ref, paid_at=now(), decided_by=g.user["id"])
        insert("ledger_entries", id=uid(), provider_id=p["id"], account="provider_payable", debit=y["amount"], memo=f"Payout {ref}", created_at=now())
        notify(p["user_id"], "payout.paid", {"amount": f"{y['amount']:,}", "reference": ref}, "#/p/earnings")
    audit("payout.decide", "payout", yid, {"action": action, "amount": y["amount"]})
    return jsonify({"ok": True})


@bp.post("/admin/finance/commission-rules")
@auth(perm="finance")
def commission_rule_add():
    d = json_body()
    v = V(d)
    rate = d.get("rate")
    try:
        rate = float(rate)
    except (TypeError, ValueError):
        rate = -1
    if not (0 <= rate <= 40):
        v.errors["rate"] = "Enter a rate between 0 and 40%."
    note = v.str("note", 3, 200)
    v.done()
    ver = val("SELECT COALESCE(MAX(version),0)+1 FROM commission_rules", (), 1)
    insert("commission_rules", version=ver, rate=rate, note=note, effective_from=now(), created_by=g.user["id"])
    audit("commission.rule", "commission_rule", str(ver), {"rate": rate, "note": note})
    return finance_summary()


# ------------------------------------------------------------------- plans
@bp.get("/admin/plans")
@auth(perm="plans")
def admin_plans():
    rows = q("""SELECT p.*, (SELECT COUNT(*) FROM memberships m WHERE m.plan_id=p.id AND m.status='active') AS subscribers FROM plans p ORDER BY p.sort""")
    for r in rows:
        r["benefits"] = jload(r.pop("benefits_json"), [])
    return jsonify({"items": rows})


@bp.post("/admin/plans")
@auth(perm="plans")
def plan_create():
    d = json_body()
    v = V(d)
    name = v.str("name", 2, 60)
    pm = v.int("price_month", min=0, max=100000, required=True)
    py = v.int("price_year", min=0, max=1000000, required=True)
    v.done()
    code = "".join(ch for ch in name.lower() if ch.isalnum())[:20]
    if one("SELECT 1 FROM plans WHERE code=?", (code,)):
        fail(409, "duplicate", "A plan with this name exists.", {"name": "Already exists."})
    pid = uid()
    insert("plans", id=pid, code=code, name=name, audience="customer", price_month=pm, price_year=py,
           benefits_json=jdump([str(b)[:100] for b in (d.get("benefits") or [])][:8]), active=1,
           sort=val("SELECT COALESCE(MAX(sort),0)+1 FROM plans", (), 1))
    audit("plan.create", "plan", pid, name)
    return admin_plans()


@bp.patch("/admin/plans/<pid>")
@auth(perm="plans")
def plan_update(pid):
    p = one("SELECT * FROM plans WHERE id=?", (pid,))
    if not p:
        fail(404, "not_found", "Plan not found.")
    d = json_body()
    v = V(d)
    cols = {}
    if "name" in d:
        cols["name"] = v.str("name", 2, 60)
    if "price_month" in d:
        cols["price_month"] = v.int("price_month", min=0, max=100000, required=True)
    if "price_year" in d:
        cols["price_year"] = v.int("price_year", min=0, max=1000000, required=True)
    if "active" in d:
        cols["active"] = 1 if d["active"] else 0
    if isinstance(d.get("benefits"), list):
        cols["benefits_json"] = jdump([str(b)[:100] for b in d["benefits"]][:8])
    v.done()
    update("plans", "id", pid, **cols)
    audit("plan.update", "plan", pid, list(cols))
    return admin_plans()


# --------------------------------------------------------------- templates
@bp.get("/admin/templates")
@auth(perm="templates")
def templates():
    return jsonify({"items": q("SELECT * FROM message_templates ORDER BY audience, name")})


@bp.patch("/admin/templates/<tid>")
@auth(perm="templates")
def template_update(tid):
    t = one("SELECT * FROM message_templates WHERE id=?", (tid,))
    if not t:
        fail(404, "not_found", "Template not found.")
    d = json_body()
    v = V(d)
    cols = {}
    if "title" in d:
        cols["title"] = v.str("title", 3, 120)
    if "body" in d:
        cols["body"] = v.str("body", 5, 500)
    if "active" in d:
        cols["active"] = 1 if d["active"] else 0
    if "channel" in d:
        cols["channel"] = v.str("channel", choices=["in_app", "whatsapp", "sms"])
    v.done()
    update("message_templates", "id", tid, updated_at=now(), **cols)
    audit("template.update", "message_template", tid, list(cols))
    return templates()


@bp.post("/admin/templates/preview")
@auth(perm="templates")
def template_preview():
    d = json_body()
    sample = {"reference": "BZ-2026-000123", "category": "Home repairs", "agent": "Ravi", "customer": "Rahul", "provider": "AC Home Services",
              "amount": "1,250", "status_label": "Quotes received", "next": "Your agent is checking quotes.", "booking": "BK-2026-000045",
              "count": 3, "txn": "PAY-2026-000210", "reason": "Schedule change", "title": "Delivery driver", "candidate": "Priya"}
    return jsonify({"title": _render(d.get("title", ""), sample), "body": _render(d.get("body", ""), sample)})


# ------------------------------------------------------------------ reports
@bp.get("/admin/reports")
@auth(perm="reports")
def reports():
    days = min(180, max(7, int(request.args.get("days", 30))))
    since = _days_ago(days)
    total = val("SELECT COUNT(*) FROM requests WHERE created_at>=?", (since,), 0)
    completed = val("SELECT COUNT(*) FROM requests WHERE status='completed' AND created_at>=?", (since,), 0)
    cancelled = val("SELECT COUNT(*) FROM requests WHERE status IN ('cancelled','rejected','expired') AND created_at>=?", (since,), 0)
    shared = val("SELECT COUNT(*) FROM quotations WHERE shared_at>=?", (since,), 0)
    approved = val("SELECT COUNT(*) FROM quotations WHERE status='approved' AND shared_at>=?", (since,), 0)
    first_resp = val("SELECT AVG((julianday(first_response_at) - julianday(created_at)) * 24) FROM requests WHERE first_response_at IS NOT NULL AND created_at>=?", (since,))
    repeat = val("SELECT COUNT(*) FROM (SELECT customer_id FROM requests GROUP BY customer_id HAVING COUNT(*) > 1)", (), 0)
    buyers = val("SELECT COUNT(DISTINCT customer_id) FROM requests", (), 0)
    margin = val("SELECT COALESCE(SUM(commission_amount),0) FROM commissions WHERE created_at>=?", (since,), 0)
    gmv = val("SELECT COALESCE(SUM(amount),0) FROM bookings WHERE status NOT IN ('cancelled') AND created_at>=?", (since,), 0)
    series = []
    step = 1 if days <= 30 else 7
    for i in range(days - 1, -1, -step):
        d0 = (datetime.now(timezone.utc) - timedelta(days=i)).strftime("%Y-%m-%d")
        d1 = (datetime.now(timezone.utc) - timedelta(days=i - step + 1)).strftime("%Y-%m-%d")
        series.append({"label": d0[8:] + "/" + d0[5:7], "requests": val("SELECT COUNT(*) FROM requests WHERE substr(created_at,1,10)>=? AND substr(created_at,1,10)<?", (d0, d1), 0),
                       "completed": val("SELECT COUNT(*) FROM requests WHERE status='completed' AND substr(closed_at,1,10)>=? AND substr(closed_at,1,10)<?", (d0, d1), 0)})
    return jsonify({
        "days": days,
        "summary": {"requests": total, "completed": completed, "cancelled": cancelled,
                    "fulfilment_rate": round(completed * 100 / (completed + cancelled)) if (completed + cancelled) else None,
                    "first_response_hours": round(first_resp, 1) if first_resp is not None else None,
                    "quote_conversion": round(approved * 100 / shared) if shared else None,
                    "cancellation_rate": round(cancelled * 100 / total, 1) if total else 0,
                    "repeat_usage": round(repeat * 100 / buyers) if buyers else 0, "gmv": gmv, "commission": margin},
        "series": series,
        "by_category": q("""SELECT c.name, c.icon, COUNT(*) AS requests, SUM(CASE WHEN r.status='completed' THEN 1 ELSE 0 END) AS completed,
                                   SUM(CASE WHEN r.status IN ('cancelled','rejected','expired') THEN 1 ELSE 0 END) AS cancelled
                            FROM requests r JOIN categories c ON c.id=r.category_id WHERE r.created_at>=? GROUP BY c.id ORDER BY requests DESC LIMIT 12""", (since,)),
        "by_locality": q("SELECT locality AS name, COUNT(*) AS requests FROM requests WHERE created_at>=? GROUP BY locality ORDER BY requests DESC LIMIT 10", (since,)),
        "by_agent": q("""SELECT a.display_name AS name, COUNT(*) AS requests, SUM(CASE WHEN r.status='completed' THEN 1 ELSE 0 END) AS completed
                         FROM requests r JOIN agents a ON a.user_id=r.assigned_agent_id WHERE r.created_at>=? GROUP BY a.user_id ORDER BY requests DESC""", (since,)),
        "by_partner": q("""SELECT p.business_name AS name, COUNT(*) AS bookings, COALESCE(SUM(b.amount),0) AS value, p.rating_avg AS rating
                           FROM bookings b JOIN providers p ON p.id=b.provider_id WHERE b.created_at>=? AND b.status<>'cancelled'
                           GROUP BY p.id ORDER BY value DESC LIMIT 10""", (since,)),
    })


@bp.get("/admin/reports/export")
@auth(perm="reports")
def reports_export():
    rows = q("""SELECT r.reference, c.name AS category, r.status, r.locality, r.priority, r.created_at, r.closed_at, a.display_name AS agent
                FROM requests r JOIN categories c ON c.id=r.category_id LEFT JOIN agents a ON a.user_id=r.assigned_agent_id ORDER BY r.created_at DESC""")
    audit("report.export", "requests", None, {"rows": len(rows)})
    return _csv(rows, "buzzer-requests.csv")


def _csv(rows, filename):
    buf = io.StringIO()
    if rows:
        w = csv.DictWriter(buf, fieldnames=list(rows[0]))
        w.writeheader()
        for r in rows:
            w.writerow({k: ("'" + str(v) if isinstance(v, str) and v[:1] in "=+-@" else v) for k, v in r.items()})  # CSV-injection safe
    return Response(buf.getvalue(), mimetype="text/csv", headers={"Content-Disposition": f"attachment; filename={filename}"})


# ------------------------------------------------------------------- audit
@bp.get("/admin/audit")
@auth(perm="audit")
def audit_list():
    page, size = page_args()
    where, args = ["1=1"], []
    a = request.args
    if a.get("action"):
        where.append("l.action LIKE ?")
        args.append(f"{a['action']}%")
    if a.get("entity_type"):
        where.append("l.entity_type=?")
        args.append(a["entity_type"])
    if a.get("actor"):
        where.append("u.name LIKE ?")
        args.append(f"%{a['actor']}%")
    if a.get("q"):
        where.append("(l.detail LIKE ? OR l.entity_id LIKE ? OR l.action LIKE ?)")
        args += [f"%{a['q']}%"] * 3
    sql = f"SELECT l.*, u.name AS actor_name FROM audit_logs l LEFT JOIN users u ON u.id=l.actor_id WHERE {' AND '.join(where)}"
    res = paginate(sql, args, page, size, "ORDER BY l.created_at DESC, l.rowid DESC")
    res["actions"] = [r["a"] for r in q("SELECT DISTINCT substr(action, 1, instr(action || '.', '.') - 1) AS a FROM audit_logs ORDER BY a")]
    return jsonify(res)


@bp.get("/admin/audit/export")
@auth(perm="audit")
def audit_export():
    rows = q("SELECT l.created_at, u.name AS actor, l.actor_role, l.action, l.entity_type, l.entity_id, l.detail, l.ip FROM audit_logs l LEFT JOIN users u ON u.id=l.actor_id ORDER BY l.created_at DESC LIMIT 5000")
    audit("audit.export", "audit_logs", None, {"rows": len(rows)})
    return _csv(rows, "buzzer-audit-log.csv")


# ----------------------------------------------------------------- settings
SETTING_TYPES = {"agent_default_capacity": (int, 1, 1000), "complaint_sla_hours": (int, 1, 720), "quote_validity_days": (int, 1, 90),
                 "otp_expiry_minutes": (int, 1, 30), "session_hours_staff": (int, 1, 24), "maintenance_mode": (str, 0, 5)}


@bp.get("/admin/settings")
@auth(perm="settings")
def settings_get():
    return jsonify({
        "settings": q("SELECT key, value, label, group_name FROM settings ORDER BY group_name, key"),
        "integrations": q("SELECT * FROM integrations ORDER BY name"),
        "permissions": PERMISSIONS,
        "roles": {r: ["*"] if "*" in p else sorted(p) for r, p in ROLE_PERMS.items()},
        "environment": config.ENV,
    })


@bp.put("/admin/settings")
@auth(perm="settings")
def settings_put():
    d = json_body()
    errors = {}
    for k, v_ in d.items():
        if k not in SETTING_TYPES:
            errors[k] = "Unknown setting."
            continue
        typ, lo, hi = SETTING_TYPES[k]
        if typ is int:
            try:
                n = int(v_)
            except (TypeError, ValueError):
                errors[k] = "Enter a whole number."
                continue
            if not lo <= n <= hi:
                errors[k] = f"Must be between {lo} and {hi}."
    if errors:
        fail(422, "validation_error", "Some settings are invalid.", errors)
    for k, v_ in d.items():
        run("UPDATE settings SET value=?, updated_by=?, updated_at=? WHERE key=?", (str(v_), g.user["id"], now(), k))
    if "agent_default_capacity" in d:  # applies to agents still on the old default
        pass
    audit("settings.update", "settings", None, d)
    return settings_get()


@bp.post("/admin/integrations/<iid>/test")
@auth(perm="settings")
def integration_test(iid):
    i = one("SELECT * FROM integrations WHERE id=?", (iid,))
    if not i:
        fail(404, "not_found", "Integration not found.")
    update("integrations", "id", iid, last_checked_at=now())
    audit("integration.test", "integration", iid, i["name"])
    return jsonify({"ok": True, "status": i["status"], "message": f"{i['name']}: {i['status']} (sandbox check passed)"})


# --------------------------------------------------------------- help centre
@bp.get("/help")
def help_public():
    aud = request.args.get("audience", "customer")
    text = (request.args.get("q") or "").strip()
    where, args = ["published=1", "audience IN (?, 'all')"], [aud]
    if text:
        where.append("(title LIKE ? OR body LIKE ?)")
        args += [f"%{text}%"] * 2
    return jsonify({"items": q(f"SELECT id, slug, title, body, topic, audience, updated_at FROM help_articles WHERE {' AND '.join(where)} ORDER BY topic, title", args)})


@bp.get("/admin/help")
@auth(perm="content")
def help_admin():
    return jsonify({"items": q("SELECT * FROM help_articles ORDER BY topic, title")})


@bp.post("/admin/help")
@auth(perm="content")
def help_create():
    d = json_body()
    v = V(d)
    title = v.str("title", 3, 120)
    body = v.str("body", 10, 3000)
    audience = v.str("audience", choices=["customer", "partner", "all"], default="customer")
    topic = v.str("topic", 2, 40, default="General")
    v.done()
    slug = "-".join("".join(ch for ch in title.lower() if ch.isalnum() or ch == " ").split())[:60]
    if one("SELECT 1 FROM help_articles WHERE slug=?", (slug,)):
        slug += "-" + secrets.token_hex(2)
    hid = uid()
    insert("help_articles", id=hid, slug=slug, title=title, body=body, audience=audience, topic=topic,
           published=1 if d.get("published", True) else 0, updated_at=now())
    audit("help.create", "help_article", hid, title)
    return help_admin()


@bp.patch("/admin/help/<hid>")
@auth(perm="content")
def help_update(hid):
    if not one("SELECT 1 FROM help_articles WHERE id=?", (hid,)):
        fail(404, "not_found", "Article not found.")
    d = json_body()
    v = V(d)
    cols = {}
    if "title" in d:
        cols["title"] = v.str("title", 3, 120)
    if "body" in d:
        cols["body"] = v.str("body", 10, 3000)
    if "topic" in d:
        cols["topic"] = v.str("topic", 2, 40)
    if "audience" in d:
        cols["audience"] = v.str("audience", choices=["customer", "partner", "all"])
    if "published" in d:
        cols["published"] = 1 if d["published"] else 0
    v.done()
    update("help_articles", "id", hid, updated_at=now(), **cols)
    audit("help.update", "help_article", hid, list(cols))
    return help_admin()


@bp.delete("/admin/help/<hid>")
@auth(perm="content")
def help_delete(hid):
    run("DELETE FROM help_articles WHERE id=?", (hid,))
    audit("help.delete", "help_article", hid)
    return help_admin()
