"""Identity & access: OTP sign-in (customers), password sign-in (partner/agent/admin), refresh, logout."""
import secrets
from datetime import datetime, timedelta, timezone

from flask import g, jsonify

from .. import config
from ..db import commit, insert, now, one, q, run, uid, val
from ..security import (PORTAL_ROLES, V, audit, auth, check_password, client_ip, fail, hash_otp,
                        hash_password, issue_session, json_body, load_user, ratelimit, revoke_current,
                        strong_password)
from ..workflow import assign_new_customer
from . import bp
from .common import user_payload

AVATARS = ["#BEEB5D", "#9BD3AE", "#F2C14E", "#8FB8ED", "#F4A9A0", "#C7B8EA"]


def _session_response(user_id, portal, extra=None):
    token, exp = issue_session(user_id, portal)
    run("UPDATE users SET last_login_at=? WHERE id=?", (now(), user_id))
    u = load_user_for(user_id, portal, exp)
    out = {"token": token, "expires_at": exp, "user": user_payload(u)}
    out.update(extra or {})
    return out


def load_user_for(user_id, portal, exp):
    u = one("SELECT id,name,mobile,email,status,language,avatar_color FROM users WHERE id=?", (user_id,))
    u["roles"] = {r["role"] for r in q("SELECT role FROM user_roles WHERE user_id=?", (user_id,))}
    u["portal"], u["session_expires_at"] = portal, exp
    return u


@bp.post("/auth/otp/request")
def otp_request():
    d = json_body()
    v = V(d)
    mobile = v.mobile("mobile")
    v.done()
    ratelimit(f"otp-req:{mobile}", 5, 600)
    ratelimit(f"otp-ip:{client_ip()}", 30, 600)
    run("UPDATE otps SET used=1 WHERE mobile=? AND used=0", (mobile,))
    code = f"{secrets.randbelow(10 ** 6):06d}"
    exp = (datetime.now(timezone.utc) + timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%SZ")
    insert("otps", id=uid(), mobile=mobile, code_hash=hash_otp(mobile, code), expires_at=exp,
           attempts=0, used=0, created_at=now())
    out = {"sent": True, "expires_in": 300, "resend_after": 30,
           "is_new": one("SELECT 1 FROM users WHERE mobile=?", (mobile,)) is None}
    if config.ENV == "development":  # fake SMS integration for local development only
        out["dev_otp"] = code
        out["dev_note"] = "Development mode: this OTP would normally arrive by SMS."
    return jsonify(out)


@bp.post("/auth/otp/verify")
def otp_verify():
    d = json_body()
    v = V(d)
    mobile = v.mobile("mobile")
    code = v.str("otp", 6, 6)
    v.done()
    ratelimit(f"otp-ver:{mobile}", 10, 600)
    o = one("SELECT * FROM otps WHERE mobile=? AND used=0 ORDER BY created_at DESC LIMIT 1", (mobile,))
    if not o or o["expires_at"] < now():
        fail(401, "otp_expired", "That code has expired. Please request a new one.")
    if o["attempts"] >= 5:
        fail(429, "otp_locked", "Too many wrong attempts. Please request a new code.")
    if not secrets.compare_digest(o["code_hash"], hash_otp(mobile, code)):
        run("UPDATE otps SET attempts=attempts+1 WHERE id=?", (o["id"],))
        commit()  # persist the attempt even though we are about to return an error
        fail(401, "otp_invalid", "That code is not correct. Please check and try again.",
             {"otp": "Incorrect code."})
    u = one("SELECT * FROM users WHERE mobile=?", (mobile,))
    is_new = u is None
    if is_new:
        if not v.bool("accept_terms") and not d.get("accept_terms"):
            fail(422, "validation_error", "Please accept the Terms and Privacy Policy to create your account.",
                 {"accept_terms": "Please accept to continue."})
        name = (d.get("name") or "").strip()[:80] or "BUZZER customer"
        uid_ = uid()
        insert("users", id=uid_, name=name, mobile=mobile, status="active", language="en",
               avatar_color=secrets.choice(AVATARS), created_at=now())
        insert("user_roles", user_id=uid_, role="customer")
        insert("customers", user_id=uid_, created_at=now())
        for k in ("terms", "privacy"):
            insert("consents", user_id=uid_, kind=k, granted=1, version="1.0", updated_at=now())
        assign_new_customer(uid_)
        u = one("SELECT * FROM users WHERE id=?", (uid_,))
        audit("customer.register", "user", uid_, user={"id": uid_, "portal": "customer"})
    elif u["status"] != "active":
        fail(403, "account_disabled", "This account is not active. Please contact BUZZER support.")
    elif not one("SELECT 1 FROM user_roles WHERE user_id=? AND role='customer'", (u["id"],)):
        fail(403, "wrong_portal", "This number belongs to a partner or staff account. Use that sign-in page.")
    run("UPDATE otps SET used=1 WHERE id=?", (o["id"],))
    return jsonify(_session_response(u["id"], "customer", {"is_new": is_new}))


@bp.post("/auth/login")
def login():
    d = json_body()
    v = V(d)
    ident = v.str("identifier", 3, 120)
    pw = v.str("password", 1, 200)
    portal = v.str("portal", choices=[p for p in PORTAL_ROLES if p != "customer"])
    v.done()
    ratelimit(f"login:{ident.lower()}", 8, 600)
    ratelimit(f"login-ip:{client_ip()}", 40, 600)
    u = one("SELECT * FROM users WHERE lower(email)=lower(?) OR mobile=?", (ident, ident))
    if not u or not check_password(u["password_hash"], pw):
        fail(401, "invalid_credentials", "Email/mobile or password is incorrect.")
    if u["status"] != "active":
        fail(403, "account_disabled", "This account is not active. Please contact BUZZER support.")
    roles = {r["role"] for r in q("SELECT role FROM user_roles WHERE user_id=?", (u["id"],))}
    if not (roles & PORTAL_ROLES[portal]):
        fail(403, "wrong_portal", f"This account does not have access to the {portal} workspace.")
    resp = _session_response(u["id"], portal)
    audit("auth.login", "user", u["id"], {"portal": portal}, user=resp["user"])
    return jsonify(resp)


@bp.post("/auth/register")
def register_partner():
    """Partner self-registration. The business stays 'unverified' until KYC is approved."""
    d = json_body()
    v = V(d)
    business = v.str("business_name", 2, 120)
    name = v.str("name", 2, 80)
    mobile = v.mobile("mobile")
    email = v.str("email", 5, 120, required=False)
    pw = v.str("password", 8, 128)
    ptype = v.str("type", choices=["individual", "business"], default="business")
    locality = v.str("locality", 2, 60, required=False)
    cat = v.str("category_id", required=False)
    if pw and not strong_password(pw):
        v.errors["password"] = "Use at least 8 characters with letters and numbers."
    if not v.bool("consent"):
        v.errors["consent"] = "Please consent to verification checks."
    if mobile and one("SELECT 1 FROM users WHERE mobile=?", (mobile,)):
        v.errors["mobile"] = "This mobile number is already registered."
    if email and one("SELECT 1 FROM users WHERE lower(email)=lower(?)", (email,)):
        v.errors["email"] = "This email is already registered."
    v.done()
    ratelimit(f"register:{client_ip()}", 10, 3600)
    u_id, p_id = uid(), uid()
    insert("users", id=u_id, name=name, mobile=mobile, email=email or None, password_hash=hash_password(pw),
           status="active", language="en", avatar_color=secrets.choice(AVATARS), created_at=now())
    insert("user_roles", user_id=u_id, role="provider")
    insert("consents", user_id=u_id, kind="provider_verification", granted=1, version="1.0", updated_at=now())
    insert("providers", id=p_id, user_id=u_id, business_name=business, type=ptype, kind="general",
           phone=mobile, email=email or None, locality=locality, verification_status="unverified",
           status="active", source="self_registered", logo_color=secrets.choice(AVATARS), created_at=now())
    if locality:
        insert("provider_areas", id=uid(), provider_id=p_id, locality=locality)
    if cat and one("SELECT 1 FROM categories WHERE id=?", (cat,)):
        insert("provider_services", id=uid(), provider_id=p_id, category_id=cat, name=business,
               base_price=0, unit="per job", active=1)
    audit("partner.register", "provider", p_id, user={"id": u_id, "portal": "partner"})
    resp = _session_response(u_id, "partner")
    return jsonify(resp), 201


@bp.post("/auth/refresh")
@auth()
def refresh():
    u = g.user
    revoke_current()
    return jsonify(_session_response(u["id"], u["portal"]))


@bp.post("/auth/logout")
def logout():
    revoke_current()
    return jsonify({"ok": True})
