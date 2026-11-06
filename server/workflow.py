"""Request lifecycle state machine and dedicated-agent allocation engine."""
from .db import insert, now, one, q, run, uid, val
from .events import notify
from .security import audit, fail

# key, plain-language label, what happens next, tone
STATUS_META = [
    ("submitted", "Request received", "Your agent will review the details shortly.", "info"),
    ("under_review", "Being reviewed", "Your agent is checking the scope and choosing the right providers.", "info"),
    ("finding_providers", "Finding providers", "We're contacting verified providers for quotes.", "info"),
    ("quotes_received", "Quotes received", "Your agent is checking the quotes before sharing the best options.", "info"),
    ("awaiting_approval", "Waiting for your approval", "Compare the quotes and approve one to confirm your booking.", "warn"),
    ("confirmed", "Booking confirmed", "Your provider will arrive at the scheduled time.", "success"),
    ("in_progress", "Work in progress", "Your provider is working on this request.", "info"),
    ("completed", "Completed", "Thanks! Please rate your experience.", "success"),
    ("cancelled", "Cancelled", "This request was cancelled.", "neutral"),
    ("rejected", "Not accepted", "BUZZER could not take this request on.", "danger"),
    ("expired", "Expired", "No response in time, so this request was closed.", "neutral"),
    ("disputed", "Under dispute", "A BUZZER specialist is reviewing the issue.", "danger"),
]
STATUS = {k: {"label": l, "next": n, "tone": t} for k, l, n, t in STATUS_META}
TERMINAL = {"completed", "cancelled", "rejected", "expired"}

TRANSITIONS = {
    "submitted": {"under_review", "rejected", "cancelled"},
    "under_review": {"finding_providers", "rejected", "cancelled"},
    "finding_providers": {"quotes_received", "cancelled", "expired"},
    "quotes_received": {"awaiting_approval", "finding_providers", "cancelled", "expired"},
    "awaiting_approval": {"confirmed", "quotes_received", "finding_providers", "cancelled", "expired"},
    "confirmed": {"in_progress", "cancelled", "disputed"},
    "in_progress": {"completed", "disputed", "cancelled"},
    "completed": {"disputed"},
    "disputed": {"completed", "cancelled"},
}
# Statuses an agent may set by hand. `confirmed` only comes from customer approval;
# `in_progress`/`completed` come from the booking lifecycle.
AGENT_MANUAL = {"under_review", "finding_providers", "quotes_received", "awaiting_approval",
                "cancelled", "rejected", "expired", "disputed"}
NEEDS_REASON = {"cancelled", "rejected", "expired", "disputed"}


def active_status_sql():
    return "(" + ",".join(f"'{s}'" for s in TERMINAL) + ")"


# ----------------------------------------------------------- allocation
def refresh_workload(agent_id):
    if not agent_id:
        return
    n = val(f"SELECT COUNT(*) FROM requests WHERE assigned_agent_id=? AND status NOT IN {active_status_sql()}",
            (agent_id,), 0)
    run("UPDATE agent_workloads SET active_requests=? WHERE agent_id=?", (n, agent_id))


def agent_available(agent_id) -> bool:
    w = one("SELECT * FROM agent_workloads WHERE agent_id=?", (agent_id,))
    return bool(w and w["availability_status"] == "available" and w["active_requests"] < w["capacity_limit"])


def pick_pool_agent(exclude=None):
    rows = q("""SELECT a.user_id, w.active_requests FROM agents a JOIN agent_workloads w ON w.agent_id=a.user_id
                WHERE a.is_pool=1 AND w.availability_status='available' AND w.active_requests < w.capacity_limit
                ORDER BY w.active_requests ASC, a.display_name""")
    for r in rows:
        if r["user_id"] != exclude:
            return r["user_id"]
    return None


def pick_agent_for(customer_id):
    """Dedicated primary agent first; then backup; then least-loaded pool agent."""
    a = one("SELECT * FROM customer_agent_assignments WHERE customer_id=? AND status='active'", (customer_id,))
    if a:
        if a["primary_agent_id"] and agent_available(a["primary_agent_id"]):
            return a["primary_agent_id"], "Dedicated primary agent"
        if a["backup_agent_id"] and agent_available(a["backup_agent_id"]):
            return a["backup_agent_id"], "Primary agent unavailable - routed to backup agent"
    pool = pick_pool_agent()
    if pool:
        return pool, "Hybrid pool allocation"
    return None, "No agent available - queued for team lead"


def assign_new_customer(customer_id, by=None):
    primary = pick_pool_agent()
    backup = pick_pool_agent(exclude=primary)
    insert("customer_agent_assignments", customer_id=customer_id, primary_agent_id=primary,
           backup_agent_id=backup, status="active", assigned_at=now(), assigned_by=by or "system",
           reason="New customer - dedicated agent allocated")
    insert("assignment_history", id=uid(), customer_id=customer_id, request_id=None, from_agent_id=None,
           to_agent_id=primary, reason="New customer - dedicated agent allocated",
           changed_by=by or "system", changed_at=now())
    ensure_customer_conversation(customer_id, primary)
    return primary


def ensure_customer_conversation(customer_id, agent_id):
    c = one("SELECT id FROM conversations WHERE kind='customer_agent' AND customer_id=?", (customer_id,))
    if c:
        if agent_id:
            run("UPDATE conversations SET agent_id=? WHERE id=?", (agent_id, c["id"]))
        return c["id"]
    cid = uid()
    insert("conversations", id=cid, kind="customer_agent", customer_id=customer_id, agent_id=agent_id,
           created_at=now(), updated_at=now())
    return cid


def agent_name(agent_id):
    return val("SELECT display_name FROM agents WHERE user_id=?", (agent_id,), "Your BUZZER agent")


def set_request_agent(req, agent_id, by, reason):
    """Move a request (and ownership trail) to another agent."""
    old = req["assigned_agent_id"]
    if old == agent_id:
        return
    run("UPDATE requests SET assigned_agent_id=?, updated_at=? WHERE id=?", (agent_id, now(), req["id"]))
    insert("assignment_history", id=uid(), customer_id=req["customer_id"], request_id=req["id"],
           from_agent_id=old, to_agent_id=agent_id, reason=reason, changed_by=by, changed_at=now())
    refresh_workload(old)
    refresh_workload(agent_id)
    if agent_id:
        ensure_customer_conversation(req["customer_id"], agent_id)
        if old:  # a reassignment, not first allocation
            notify(req["customer_id"], "request.reassigned",
                   {"reference": req["reference"], "agent": agent_name(agent_id), "reason": reason},
                   f"#/c/request/{req['id']}")
        notify(agent_id, "request.new_for_agent",
               {"reference": req["reference"], "category": req.get("title", ""),
                "customer": val("SELECT name FROM users WHERE id=?", (req["customer_id"],), "Customer")},
               f"#/a/request/{req['id']}")
    audit("request.assign", "request", req["id"], {"from": old, "to": agent_id, "reason": reason})


# ------------------------------------------------------- state machine
def change_status(req, to, actor, reason=None, system=False):
    """Validate and apply a status transition; record history, workload and notifications."""
    frm = req["status"]
    if to not in STATUS:
        fail(422, "validation_error", "Unknown status.", {"status": "Unknown status."})
    if to not in TRANSITIONS.get(frm, set()):
        fail(409, "invalid_transition",
             f"A request that is '{STATUS[frm]['label']}' cannot move to '{STATUS[to]['label']}'.")
    if to in NEEDS_REASON and not (reason or "").strip():
        fail(422, "validation_error", "Please give a reason.", {"reason": "A reason is required."})
    t = now()
    closed = t if to in TERMINAL else None
    run("""UPDATE requests SET status=?, updated_at=?, status_changed_at=?, closed_at=COALESCE(?, closed_at),
           cancel_reason=CASE WHEN ? IN ('cancelled','rejected','expired') THEN ? ELSE cancel_reason END
           WHERE id=?""", (to, t, t, closed, to, reason, req["id"]))
    if actor and not req.get("first_response_at") and actor.get("roles") and (actor["roles"] & {"agent", "team_lead"}):
        run("UPDATE requests SET first_response_at=? WHERE id=?", (t, req["id"]))
    insert("request_status_history", id=uid(), request_id=req["id"], from_status=frm, to_status=to,
           changed_by=actor["id"] if actor else None, reason=reason, created_at=t)
    refresh_workload(req.get("assigned_agent_id"))
    meta = STATUS[to]
    notify(req["customer_id"], "request.status",
           {"reference": req["reference"], "status_label": meta["label"], "next": meta["next"]},
           f"#/c/request/{req['id']}")
    audit("request.status", "request", req["id"], {"from": frm, "to": to, "reason": reason}, user=actor)
    req["status"] = to
    return to
