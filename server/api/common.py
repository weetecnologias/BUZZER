"""Shared serializers and record-level access checks."""
from ..db import one, q, val
from ..security import ADMIN_ROLES, PERMISSIONS, ROLE_PERMS, fail, has_perm


def agent_card(agent_id):
    if not agent_id:
        return None
    a = one("""SELECT a.user_id AS id, a.display_name AS name, a.phone, a.specialty, w.availability_status AS availability
               FROM agents a LEFT JOIN agent_workloads w ON w.agent_id=a.user_id WHERE a.user_id=?""", (agent_id,))
    if a:
        r = one("SELECT AVG(rating) AS avg, COUNT(*) AS n FROM reviews WHERE subject_type='agent' AND subject_id=?",
                (agent_id,))
        a["rating"] = round(r["avg"], 1) if r and r["avg"] else None
        a["color"] = val("SELECT avatar_color FROM users WHERE id=?", (agent_id,))
    return a


def provider_of(user_id):
    return one("SELECT * FROM providers WHERE user_id=?", (user_id,))


def employer_of(user_id):
    return one("SELECT * FROM employers WHERE user_id=?", (user_id,))


def user_payload(u):
    roles = sorted(u["roles"])
    out = {k: u.get(k) for k in ("id", "name", "mobile", "email", "language", "avatar_color", "portal")}
    out["roles"] = roles
    out["session_expires_at"] = u.get("session_expires_at")
    if "customer" in roles:
        asg = one("SELECT primary_agent_id FROM customer_agent_assignments WHERE customer_id=?", (u["id"],))
        out["agent"] = agent_card(asg["primary_agent_id"]) if asg else None
        m = one("""SELECT m.*, p.name AS plan_name, p.code AS plan_code FROM memberships m JOIN plans p ON p.id=m.plan_id
                   WHERE m.user_id=? AND m.status IN ('active','paused') ORDER BY m.started_at DESC""", (u["id"],))
        out["membership"] = m
    if "provider" in roles:
        out["provider"] = provider_of(u["id"])
    if "employer" in roles:
        out["employer"] = employer_of(u["id"])
    if roles and set(roles) & {"agent", "team_lead"}:
        out["agent_profile"] = agent_card(u["id"])
    if set(roles) & ADMIN_ROLES:
        out["permissions"] = [p for p in PERMISSIONS if has_perm(u, p)]
    return out


def get_request(rid):
    r = one("SELECT * FROM requests WHERE id=? OR reference=?", (rid, rid))
    if not r:
        fail(404, "not_found", "That request was not found.")
    return r


def can_view_request(user, r, provider=None) -> bool:
    roles = user["roles"]
    if "customer" in roles and r["customer_id"] == user["id"]:
        return True
    if roles & {"team_lead"} or has_perm(user, "requests") or has_perm(user, "oversight"):
        return True
    if "agent" in roles and (r["assigned_agent_id"] == user["id"] or r["assigned_agent_id"] is None):
        return True
    if "provider" in roles:
        p = provider or provider_of(user["id"])
        if p and one("SELECT 1 FROM request_invites WHERE request_id=? AND provider_id=?", (r["id"], p["id"])):
            return True
    return False


def require_request(user, rid):
    r = get_request(rid)
    if not can_view_request(user, r):
        fail(403, "forbidden", "You don't have access to this request.")
    return r


def is_staff_agent(user) -> bool:
    return bool(user["roles"] & {"agent", "team_lead"})


def status_label(s):
    from ..workflow import STATUS
    return STATUS.get(s, {}).get("label", s)


def provider_card(pid):
    return one("""SELECT id, business_name, type, kind, verification_status, rating_avg, rating_count, jobs_completed,
                  locality, logo_color, response_rate FROM providers WHERE id=?""", (pid,))


def quote_payload(row, items=True):
    if not row:
        return None
    qd = dict(row)
    qd["provider"] = provider_card(qd["provider_id"])
    if items:
        qd["items"] = q("SELECT id, description, qty, unit_price, amount FROM quotation_items WHERE quotation_id=?",
                        (qd["id"],))
    qd["deposit_amount"] = round(qd["total"] * qd["deposit_pct"] / 100)
    return qd


CUSTOMER_VISIBLE_QUOTE = ("shared", "approved", "rejected", "clarification_requested",
                          "revised_pending_consent", "not_selected")
