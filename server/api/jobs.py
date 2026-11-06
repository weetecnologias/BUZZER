"""Quick Jobs & staffing: employers, vacancies, candidates, applications, interviews, moderation."""
from datetime import datetime, timezone

from flask import g, jsonify, request

from ..db import insert, next_ref, now, one, paginate, q, run, uid, update, val
from ..events import notify
from ..security import V, audit, auth, fail, has_perm, json_body, page_args
from . import bp
from .common import employer_of

ANY = ("customer", "provider", "employer", "agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
STAFF = {"agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer"}
ROLE_TYPES = ["Driver", "Sales", "Shopkeeper", "Helper", "Reception", "Delivery", "Cook", "Security", "Office assistant", "Other"]

VAC_SQL = """
SELECT v.*, e.company_name, e.verification_status AS employer_verification, e.locality AS employer_locality,
       (SELECT COUNT(*) FROM job_applications a WHERE a.vacancy_id=v.id AND a.status<>'withdrawn') AS applicants
FROM vacancies v JOIN employers e ON e.id=v.employer_id
"""


def _is_staff(u):
    return bool(u["roles"] & STAFF)


def _candidate(u):
    return one("SELECT * FROM candidates WHERE user_id=?", (u["id"],))


def _completion(c):
    if not c:
        return 0
    parts = [c["headline"], c["skills"], c["experience_years"] is not None, c["preferred_localities"],
             c["expected_salary"], c["shift_pref"], c["data_sharing_consent"]]
    return round(sum(1 for p in parts if p) * 100 / len(parts))


# ------------------------------------------------------------ candidate
@bp.get("/candidate/profile")
@auth("customer")
def candidate_get():
    c = _candidate(g.user)
    return jsonify({"profile": c, "completion": _completion(c), "role_types": ROLE_TYPES})


@bp.put("/candidate/profile")
@auth("customer")
def candidate_put():
    d = json_body()
    v = V(d)
    cols = dict(headline=v.str("headline", 3, 80), skills=v.str("skills", 2, 200),
                experience_years=v.int("experience_years", min=0, max=50, required=True),
                preferred_localities=v.str("preferred_localities", 2, 200),
                expected_salary=v.int("expected_salary", min=0, max=1_000_000, required=True),
                shift_pref=v.str("shift_pref", choices=["Day", "Night", "Rotational", "Flexible"]),
                data_sharing_consent=1 if v.bool("data_sharing_consent") else 0)
    v.done()
    c = _candidate(g.user)
    if c:
        update("candidates", "id", c["id"], **cols)
    else:
        insert("candidates", id=uid(), user_id=g.user["id"], created_at=now(), **cols)
    run("""INSERT INTO consents(user_id, kind, granted, version, updated_at) VALUES (?,?,?,?,?)
           ON CONFLICT(user_id, kind) DO UPDATE SET granted=excluded.granted, updated_at=excluded.updated_at""",
        (g.user["id"], "candidate_data_sharing", cols["data_sharing_consent"], "1.0", now()))
    audit("candidate.profile", "user", g.user["id"])
    return candidate_get()


@bp.get("/candidate/applications")
@auth("customer")
def candidate_applications():
    c = _candidate(g.user)
    if not c:
        return jsonify({"items": [], "total": 0})
    items = q("""SELECT a.id, a.status, a.applied_at, a.updated_at, v.id AS vacancy_id, v.title, v.reference, v.locality,
                        v.salary_min, v.salary_max, v.pay_frequency, e.company_name,
                        (SELECT i.scheduled_at FROM interviews i WHERE i.application_id=a.id AND i.status='scheduled' ORDER BY i.scheduled_at LIMIT 1) AS interview_at,
                        (SELECT i.location FROM interviews i WHERE i.application_id=a.id AND i.status='scheduled' ORDER BY i.scheduled_at LIMIT 1) AS interview_location
                 FROM job_applications a JOIN vacancies v ON v.id=a.vacancy_id JOIN employers e ON e.id=v.employer_id
                 WHERE a.candidate_id=? ORDER BY a.updated_at DESC""", (c["id"],))
    return jsonify({"items": items, "total": len(items)})


# ------------------------------------------------------------- vacancies
@bp.get("/vacancies")
@auth(*ANY)
def vacancies_list():
    u = g.user
    page, size = page_args()
    a = request.args
    where, args = ["1=1"], []
    emp = employer_of(u["id"]) if "employer" in u["roles"] else None
    if a.get("mine") == "1":
        if not emp:
            return jsonify({"items": [], "page": 1, "page_size": size, "total": 0})
        where.append("v.employer_id=?")
        args.append(emp["id"])
    elif _is_staff(u):
        pass
    else:
        where.append("v.status='published' AND (v.deadline IS NULL OR v.deadline >= date('now'))")
    if a.get("status"):
        where.append("v.status=?")
        args.append(a["status"])
    for key, col in (("role_type", "v.role_type"), ("locality", "v.locality"), ("employment_type", "v.employment_type")):
        if a.get(key):
            where.append(f"{col}=?")
            args.append(a[key])
    if a.get("shift"):
        where.append("v.shift LIKE ?")
        args.append(f"%{a['shift']}%")
    if a.get("min_salary"):
        where.append("v.salary_max >= ?")
        args.append(int(a["min_salary"]))
    if a.get("q"):
        where.append("(v.title LIKE ? OR e.company_name LIKE ? OR v.skills LIKE ? OR v.locality LIKE ?)")
        args += [f"%{a['q']}%"] * 4
    cand = _candidate(u) if "customer" in u["roles"] else None
    saved = {r["vacancy_id"] for r in q("SELECT vacancy_id FROM saved_jobs WHERE candidate_id=?", (cand["id"],))} if cand else set()
    applied = {r["vacancy_id"]: r["status"] for r in q("SELECT vacancy_id, status FROM job_applications WHERE candidate_id=?", (cand["id"],))} if cand else {}
    if a.get("saved") == "1":
        where.append(f"v.id IN ({','.join('?' for _ in saved) or chr(39) * 2})")
        args += list(saved)
    res = paginate(f"{VAC_SQL} WHERE {' AND '.join(where)}", args, page, size, "ORDER BY v.created_at DESC")
    for it in res["items"]:
        it["saved"] = it["id"] in saved
        it["application_status"] = applied.get(it["id"])
    res["role_types"] = ROLE_TYPES
    return jsonify(res)


def _vacancy(vid):
    v = one(VAC_SQL + " WHERE v.id=? OR v.reference=?", (vid, vid))
    if not v:
        fail(404, "not_found", "That vacancy was not found.")
    return v


def _can_manage_vacancy(u, v):
    emp = employer_of(u["id"]) if "employer" in u["roles"] else None
    return bool((emp and emp["id"] == v["employer_id"]) or u["roles"] & {"ops_admin", "super_admin"}
                or (u["roles"] & {"agent", "team_lead"}))


@bp.get("/vacancies/<vid>")
@auth(*ANY)
def vacancy_detail(vid):
    u = g.user
    v = _vacancy(vid)
    mine = _can_manage_vacancy(u, v) or has_perm(u, "jobs")
    if v["status"] != "published" and not mine:
        fail(404, "not_found", "That vacancy was not found.")
    cand = _candidate(u) if "customer" in u["roles"] else None
    v["saved"] = bool(cand and one("SELECT 1 FROM saved_jobs WHERE candidate_id=? AND vacancy_id=?", (cand["id"], v["id"])))
    app = one("SELECT id, status FROM job_applications WHERE candidate_id=? AND vacancy_id=?", (cand["id"], v["id"])) if cand else None
    v["my_application"] = app
    v["employer"] = one("SELECT id, company_name, industry, locality, verification_status FROM employers WHERE id=?", (v["employer_id"],))
    v["safety_notice"] = ("BUZZER verifies employers and never charges candidates a recruitment fee. "
                          "Report any vacancy that asks you for money.")
    return jsonify(v)


def _vacancy_fields(d):
    v = V(d)
    cols = dict(
        title=v.str("title", 3, 100), role_type=v.str("role_type", choices=ROLE_TYPES),
        description=v.str("description", 20, 1500), locality=v.str("locality", 2, 60),
        salary_min=v.int("salary_min", min=100, max=1_000_000, required=True),
        salary_max=v.int("salary_max", min=100, max=1_000_000, required=True),
        pay_frequency=v.str("pay_frequency", choices=["month", "week", "day", "hour"], default="month"),
        employment_type=v.str("employment_type", choices=["full_time", "part_time", "contract", "temporary"], default="full_time"),
        shift=v.str("shift", 0, 60, required=False), working_days=v.str("working_days", 0, 60, required=False),
        weekly_off=v.str("weekly_off", 0, 30, required=False), experience=v.str("experience", 0, 60, required=False),
        skills=v.str("skills", 0, 200, required=False), openings=v.int("openings", min=1, max=500, default=1),
        deadline=v.str("deadline", 10, 10, required=False),
    )
    if cols["salary_min"] and cols["salary_max"] and cols["salary_max"] < cols["salary_min"]:
        v.errors["salary_max"] = "Maximum salary must be at least the minimum."
    if d.get("recruitment_fee_declared"):
        v.errors["recruitment_fee_declared"] = "Vacancies that charge candidates a fee can't be published on BUZZER."
    if cols["deadline"] and cols["deadline"] < datetime.now(timezone.utc).strftime("%Y-%m-%d"):
        v.errors["deadline"] = "Choose a deadline in the future."
    v.done()
    return cols


@bp.post("/vacancies")
@auth("employer")
def vacancy_create():
    emp = employer_of(g.user["id"])
    if not emp or emp["verification_status"] in ("rejected", "blocked"):
        fail(403, "employer_blocked", "Your employer profile can't publish vacancies. Contact BUZZER support.")
    cols = _vacancy_fields(json_body())
    vid = uid()
    insert("vacancies", id=vid, reference=next_ref("vacancy", "JOB", 5), employer_id=emp["id"], status="pending_verification",
           recruitment_fee_declared=0, created_at=now(), **cols)
    audit("vacancy.create", "vacancy", vid)
    return jsonify(_vacancy(vid)), 201


@bp.patch("/vacancies/<vid>")
@auth("employer", "agent", "team_lead", "ops_admin", "super_admin")
def vacancy_update(vid):
    v = _vacancy(vid)
    if not _can_manage_vacancy(g.user, v):
        fail(403, "forbidden", "This isn't your vacancy.")
    d = json_body()
    if set(d) <= {"status"}:
        st = d.get("status")
        if st not in ("paused", "published", "closed", "filled"):
            fail(422, "validation_error", "Invalid status.", {"status": "Invalid status."})
        if st == "published" and v["status"] not in ("paused",):
            fail(409, "invalid_state", "Only a paused vacancy can be re-published. New vacancies need verification first.")
        update("vacancies", "id", v["id"], status=st)
    else:
        cols = _vacancy_fields({**v, **d})
        pending = v["status"] == "published"
        update("vacancies", "id", v["id"], status="pending_verification" if pending else v["status"], **cols)
    audit("vacancy.update", "vacancy", v["id"])
    return jsonify(_vacancy(vid))


@bp.post("/vacancies/<vid>/verify")
@auth("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
def vacancy_verify(vid):
    v = _vacancy(vid)
    d = json_body()
    vv = V(d)
    action = vv.str("action", choices=["approve", "reject"])
    reason = vv.str("reason", 0, 300, required=False)
    if action == "reject" and not reason:
        vv.errors["reason"] = "Tell the employer what to fix."
    vv.done()
    emp_user = val("SELECT user_id FROM employers WHERE id=?", (v["employer_id"],))
    if action == "approve":
        if v["employer_verification"] != "verified":
            fail(409, "employer_unverified", "Verify the employer before publishing their vacancy.")
        update("vacancies", "id", v["id"], status="published", verified_by=g.user["id"], verified_at=now(), rejection_reason=None)
        notify(emp_user, "job.verified", {"reference": v["reference"], "title": v["title"]}, "#/p/vacancies")
    else:
        update("vacancies", "id", v["id"], status="rejected", verified_by=g.user["id"], verified_at=now(), rejection_reason=reason)
        notify(emp_user, "job.rejected", {"reference": v["reference"], "title": v["title"], "reason": reason}, "#/p/vacancies")
    audit("vacancy.verify", "vacancy", v["id"], {"action": action, "reason": reason})
    return jsonify(_vacancy(vid))


@bp.put("/vacancies/<vid>/save")
@auth("customer")
def vacancy_save(vid):
    c = _candidate(g.user)
    if not c:
        fail(409, "no_profile", "Create your candidate profile first.")
    v = _vacancy(vid)
    run("INSERT OR IGNORE INTO saved_jobs(candidate_id, vacancy_id, created_at) VALUES (?,?,?)", (c["id"], v["id"], now()))
    return jsonify({"saved": True})


@bp.delete("/vacancies/<vid>/save")
@auth("customer")
def vacancy_unsave(vid):
    c = _candidate(g.user)
    if c:
        run("DELETE FROM saved_jobs WHERE candidate_id=? AND vacancy_id=?", (c["id"], vid))
    return jsonify({"saved": False})


@bp.post("/vacancies/<vid>/report")
@auth(*ANY)
def vacancy_report(vid):
    v = _vacancy(vid)
    vv = V(json_body())
    reason = vv.str("reason", 5, 300)
    vv.done()
    insert("job_reports", id=uid(), vacancy_id=v["id"], reporter_id=g.user["id"], reason=reason, status="open", created_at=now())
    return jsonify({"ok": True, "message": "Thanks - our team will review this vacancy."}), 201


# ---------------------------------------------------------- applications
@bp.post("/vacancies/<vid>/applications")
@auth("customer")
def apply_job(vid):
    v = _vacancy(vid)
    c = _candidate(g.user)
    if not c:
        fail(409, "no_profile", "Complete your candidate profile before applying.")
    if v["status"] != "published" or (v["deadline"] and v["deadline"] < datetime.now(timezone.utc).strftime("%Y-%m-%d")):
        fail(409, "closed", "This vacancy is no longer accepting applications.")
    if not json_body().get("consent_shared"):
        fail(422, "validation_error", "Please agree to share your profile with this employer.",
             {"consent_shared": "Consent is required to apply."})
    existing = one("SELECT * FROM job_applications WHERE vacancy_id=? AND candidate_id=?", (v["id"], c["id"]))
    if existing and existing["status"] != "withdrawn":
        fail(409, "already_applied", "You've already applied for this job.")
    if existing:
        update("job_applications", "id", existing["id"], status="applied", consent_shared=1, applied_at=now(), updated_at=now())
        aid = existing["id"]
    else:
        aid = uid()
        insert("job_applications", id=aid, vacancy_id=v["id"], candidate_id=c["id"], status="applied", consent_shared=1,
               applied_at=now(), updated_at=now())
    notify(val("SELECT user_id FROM employers WHERE id=?", (v["employer_id"],)), "job.application",
           {"title": v["title"], "candidate": g.user["name"]}, "#/p/applications")
    audit("job.apply", "vacancy", v["id"])
    return jsonify({"id": aid, "status": "applied"}), 201


@bp.post("/applications/<aid>/withdraw")
@auth("customer")
def withdraw_application(aid):
    c = _candidate(g.user)
    a = one("SELECT * FROM job_applications WHERE id=? AND candidate_id=?", (aid, c["id"] if c else "-"))
    if not a:
        fail(404, "not_found", "Application not found.")
    if a["status"] in ("hired", "withdrawn"):
        fail(409, "invalid_state", "This application can't be withdrawn.")
    update("job_applications", "id", aid, status="withdrawn", updated_at=now())
    run("UPDATE interviews SET status='cancelled' WHERE application_id=? AND status='scheduled'", (aid,))
    return jsonify({"ok": True})


APP_SQL = """
SELECT a.id, a.status, a.applied_at, a.updated_at, a.hiring_outcome, v.id AS vacancy_id, v.title, v.reference, v.locality AS job_locality,
       c.headline, c.skills, c.experience_years, c.expected_salary, c.shift_pref, c.preferred_localities,
       u.name AS candidate_name,
       CASE WHEN a.status IN ('shortlisted','interview','hired') THEN u.mobile ELSE NULL END AS candidate_mobile,
       (SELECT i.scheduled_at FROM interviews i WHERE i.application_id=a.id AND i.status='scheduled' ORDER BY i.scheduled_at LIMIT 1) AS interview_at
FROM job_applications a JOIN vacancies v ON v.id=a.vacancy_id JOIN candidates c ON c.id=a.candidate_id
JOIN users u ON u.id=c.user_id
WHERE a.consent_shared=1 AND a.status<>'withdrawn'
"""


@bp.get("/employer/applications")
@auth("employer", "agent", "team_lead", "ops_admin", "super_admin")
def employer_applications():
    u = g.user
    page, size = page_args()
    where, args = [], []
    if "employer" in u["roles"] and not _is_staff(u):
        emp = employer_of(u["id"])
        where.append("v.employer_id=?")
        args.append(emp["id"] if emp else "-")
    for key, col in (("status", "a.status"), ("vacancy_id", "v.id")):
        if request.args.get(key):
            where.append(f"{col}=?")
            args.append(request.args[key])
    sql = APP_SQL + ("".join(f" AND {w}" for w in where))
    return jsonify(paginate(sql, args, page, size, "ORDER BY a.updated_at DESC"))


APP_FLOW = {"applied": {"shortlisted", "rejected"}, "shortlisted": {"interview", "rejected", "hired"},
            "interview": {"hired", "rejected", "shortlisted"}}


def _app_for_employer(u, aid):
    a = one("SELECT a.*, v.employer_id, v.title, v.openings, v.id AS vid FROM job_applications a JOIN vacancies v ON v.id=a.vacancy_id WHERE a.id=?", (aid,))
    if not a:
        fail(404, "not_found", "Application not found.")
    emp = employer_of(u["id"]) if "employer" in u["roles"] else None
    if not ((emp and emp["id"] == a["employer_id"]) or u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin"}):
        fail(403, "forbidden", "This isn't your applicant.")
    return a


@bp.patch("/applications/<aid>")
@auth("employer", "agent", "team_lead", "ops_admin", "super_admin")
def application_update(aid):
    a = _app_for_employer(g.user, aid)
    vv = V(json_body())
    to = vv.str("status", choices=["shortlisted", "interview", "hired", "rejected"])
    outcome = vv.str("outcome", 0, 200, required=False)
    vv.done()
    if to not in APP_FLOW.get(a["status"], set()):
        fail(409, "invalid_transition", f"An application that is '{a['status']}' can't move to '{to}'.")
    if to == "interview" and not one("SELECT 1 FROM interviews WHERE application_id=? AND status='scheduled'", (aid,)):
        fail(409, "no_interview", "Schedule an interview first.")
    update("job_applications", "id", aid, status=to, hiring_outcome=outcome, updated_at=now())
    if to == "hired":
        hired = val("SELECT COUNT(*) FROM job_applications WHERE vacancy_id=? AND status='hired'", (a["vid"],), 0)
        if hired >= a["openings"]:
            update("vacancies", "id", a["vid"], status="filled")
    cu = val("SELECT c.user_id FROM candidates c WHERE c.id=?", (a["candidate_id"],))
    notify(cu, "job.status", {"title": a["title"], "status": to}, "#/c/jobs")
    audit("application.update", "application", aid, {"to": to})
    return jsonify({"ok": True, "status": to})


@bp.post("/applications/<aid>/interview")
@auth("employer", "agent", "team_lead", "ops_admin", "super_admin")
def interview_schedule(aid):
    a = _app_for_employer(g.user, aid)
    d = json_body()
    vv = V(d)
    when = vv.dt("scheduled_at", required=True, future=True)
    mode = vv.str("mode", choices=["in_person", "phone", "video"], default="in_person")
    loc = vv.str("location", 0, 160, required=False)
    vv.done()
    if a["status"] not in ("applied", "shortlisted", "interview"):
        fail(409, "invalid_state", "This application can't be scheduled right now.")
    run("UPDATE interviews SET status='cancelled' WHERE application_id=? AND status='scheduled'", (aid,))
    insert("interviews", id=uid(), application_id=aid, scheduled_at=when, mode=mode, location=loc, status="scheduled",
           notes=(d.get("notes") or "")[:300])
    update("job_applications", "id", aid, status="interview", updated_at=now())
    cu = val("SELECT c.user_id FROM candidates c WHERE c.id=?", (a["candidate_id"],))
    notify(cu, "job.status", {"title": a["title"], "status": "interview scheduled"}, "#/c/jobs")
    return jsonify({"ok": True}), 201


# --------------------------------------------------------------- employers
@bp.get("/employer/profile")
@auth("provider", "employer")
def employer_profile():
    return jsonify({"employer": employer_of(g.user["id"])})


@bp.post("/employer")
@auth("provider", "employer")
def employer_create():
    if employer_of(g.user["id"]):
        fail(409, "exists", "You already have an employer profile.")
    d = json_body()
    v = V(d)
    cols = dict(company_name=v.str("company_name", 2, 120), industry=v.str("industry", 2, 60),
                locality=v.str("locality", 2, 60), contact_name=v.str("contact_name", 2, 80, default=g.user["name"]),
                phone=v.mobile("phone"))
    v.done()
    insert("employers", id=uid(), user_id=g.user["id"], verification_status="pending", created_at=now(), **cols)
    run("INSERT OR IGNORE INTO user_roles(user_id, role) VALUES (?, 'employer')", (g.user["id"],))
    audit("employer.create", "user", g.user["id"])
    return jsonify({"employer": employer_of(g.user["id"])}), 201


@bp.get("/employers")
@auth("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
def employers_list():
    page, size = page_args()
    where, args = ["1=1"], []
    if request.args.get("status"):
        where.append("e.verification_status=?")
        args.append(request.args["status"])
    sql = f"""SELECT e.*, (SELECT COUNT(*) FROM vacancies v WHERE v.employer_id=e.id) AS vacancies,
                     u.mobile AS user_mobile FROM employers e JOIN users u ON u.id=e.user_id WHERE {' AND '.join(where)}"""
    return jsonify(paginate(sql, args, page, size, "ORDER BY e.created_at DESC"))


@bp.post("/employers/<eid>/verify")
@auth("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
def employer_verify(eid):
    e = one("SELECT * FROM employers WHERE id=?", (eid,))
    if not e:
        fail(404, "not_found", "Employer not found.")
    v = V(json_body())
    action = v.str("action", choices=["verify", "reject", "block"])
    v.done()
    st = {"verify": "verified", "reject": "rejected", "block": "blocked"}[action]
    update("employers", "id", eid, verification_status=st)
    if action == "block":
        run("UPDATE vacancies SET status='closed' WHERE employer_id=? AND status IN ('published','pending_verification','paused')", (eid,))
    audit("employer.verify", "employer", eid, {"action": action})
    return jsonify({"ok": True, "verification_status": st})


@bp.get("/job-reports")
@auth("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
def job_reports():
    page, size = page_args()
    sql = """SELECT r.*, v.title, v.reference, e.company_name, u.name AS reporter FROM job_reports r
             JOIN vacancies v ON v.id=r.vacancy_id JOIN employers e ON e.id=v.employer_id JOIN users u ON u.id=r.reporter_id WHERE 1=1"""
    args = []
    if request.args.get("status"):
        sql += " AND r.status=?"
        args.append(request.args["status"])
    return jsonify(paginate(sql, args, page, size, "ORDER BY r.created_at DESC"))


@bp.post("/job-reports/<rid>/resolve")
@auth("agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer")
def job_report_resolve(rid):
    r = one("SELECT * FROM job_reports WHERE id=?", (rid,))
    if not r:
        fail(404, "not_found", "Report not found.")
    v = V(json_body())
    action = v.str("action", choices=["unpublish", "dismiss"])
    v.done()
    if action == "unpublish":
        update("vacancies", "id", r["vacancy_id"], status="closed")
    update("job_reports", "id", rid, status="actioned" if action == "unpublish" else "dismissed")
    audit("job_report.resolve", "job_report", rid, action)
    return jsonify({"ok": True})
