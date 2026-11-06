"""Authentication, RBAC with record-level helpers, validation, rate limiting and audit."""
import hashlib
import hmac
import re
import secrets
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from functools import wraps

from flask import g, request

from . import config
from .db import insert, jdump, now, one, q, uid


class ApiError(Exception):
    def __init__(self, status, code, message, fields=None):
        super().__init__(message)
        self.status, self.code, self.message, self.fields = status, code, message, fields


def fail(status, code, message, fields=None):
    raise ApiError(status, code, message, fields)


# ----------------------------------------------------------------- roles
PORTAL_ROLES = {
    "customer": {"customer"},
    "partner": {"provider", "employer"},
    "agent": {"agent", "team_lead"},
    "admin": {"ops_admin", "finance_admin", "super_admin", "verification_reviewer"},
}
ADMIN_ROLES = PORTAL_ROLES["admin"]
STAFF_ROLES = PORTAL_ROLES["agent"] | ADMIN_ROLES

ROLE_PERMS = {
    "super_admin": {"*"},
    "ops_admin": {"users", "agents", "assignment", "partners", "verification", "catalogue", "requests",
                  "oversight", "jobs", "complaints", "reports", "templates", "content"},
    "finance_admin": {"finance", "plans", "reports", "oversight", "requests"},
    "verification_reviewer": {"verification", "partners", "jobs"},
}
PERMISSIONS = ["users", "agents", "assignment", "partners", "verification", "catalogue", "requests",
               "oversight", "jobs", "finance", "plans", "complaints", "reports", "templates",
               "content", "audit", "settings"]


def has_perm(user, perm) -> bool:
    for r in user["roles"]:
        p = ROLE_PERMS.get(r)
        if p and ("*" in p or perm in p):
            return True
    return False


def is_staff(user) -> bool:
    return bool(user["roles"] & STAFF_ROLES)


# --------------------------------------------------------------- sessions
def _hash(token: str) -> str:
    return hmac.new(config.SECRET.encode(), token.encode(), hashlib.sha256).hexdigest()


def client_ip() -> str:
    return request.remote_addr or "-"


def issue_session(user_id: str, portal: str):
    token = secrets.token_urlsafe(32)
    ttl = timedelta(hours=config.TTL_HOURS[portal])
    exp = (datetime.now(timezone.utc) + ttl).strftime("%Y-%m-%dT%H:%M:%SZ")
    insert("sessions", token_hash=_hash(token), user_id=user_id, portal=portal,
           created_at=now(), expires_at=exp, revoked=0, ip=client_ip())
    return token, exp


def revoke_current():
    h = request.headers.get("Authorization", "")
    if h.startswith("Bearer "):
        from .db import run
        run("UPDATE sessions SET revoked=1 WHERE token_hash=?", (_hash(h[7:]),))


def load_user():
    h = request.headers.get("Authorization", "")
    if not h.startswith("Bearer "):
        return None
    s = one("SELECT * FROM sessions WHERE token_hash=?", (_hash(h[7:]),))
    if not s or s["revoked"] or s["expires_at"] < now():
        return None
    u = one("SELECT id,name,mobile,email,status,language,avatar_color FROM users WHERE id=?", (s["user_id"],))
    if not u or u["status"] != "active":
        return None
    u["roles"] = {r["role"] for r in q("SELECT role FROM user_roles WHERE user_id=?", (u["id"],))}
    u["portal"] = s["portal"]
    u["session_expires_at"] = s["expires_at"]
    return u


def auth(*roles, perm=None):
    """Require a signed-in user holding one of `roles` (or admin permission `perm`)."""
    def deco(fn):
        @wraps(fn)
        def w(*a, **kw):
            u = load_user()
            if not u:
                fail(401, "unauthenticated", "Please sign in to continue.")
            if roles and not (u["roles"] & set(roles)):
                fail(403, "forbidden", "Your role does not have access to this action.")
            if perm and not has_perm(u, perm):
                fail(403, "forbidden", "Your admin role does not include this permission.")
            g.user = u
            return fn(*a, **kw)
        return w
    return deco


def hash_password(pw: str) -> str:
    from werkzeug.security import generate_password_hash
    return generate_password_hash(pw, method="pbkdf2:sha256")


def check_password(h: str, pw: str) -> bool:
    from werkzeug.security import check_password_hash
    return bool(h) and check_password_hash(h, pw)


def hash_otp(mobile: str, code: str) -> str:
    return hmac.new(config.SECRET.encode(), f"{mobile}:{code}".encode(), hashlib.sha256).hexdigest()


# ------------------------------------------------------------ rate limit
_hits = defaultdict(deque)


def ratelimit(key: str, limit: int, seconds: int):
    t = time.monotonic()
    dq = _hits[key]
    while dq and dq[0] < t - seconds:
        dq.popleft()
    if len(dq) >= limit:
        fail(429, "rate_limited", "Too many attempts. Please wait a few minutes and try again.")
    dq.append(t)


# ----------------------------------------------------------------- audit
def audit(action, entity_type=None, entity_id=None, detail=None, user=None):
    u = user or g.get("user")
    role = None
    if u:
        role = u["portal"] if "portal" in u else None
    insert("audit_logs", id=uid(), actor_id=u["id"] if u else None, actor_role=role, action=action,
           entity_type=entity_type, entity_id=entity_id,
           detail=detail if isinstance(detail, str) or detail is None else jdump(detail),
           ip=client_ip() if _in_request() else None, created_at=now())


def _in_request():
    try:
        return bool(request.remote_addr is not None or request.method)
    except RuntimeError:
        return False


# ------------------------------------------------------------ validation
def json_body() -> dict:
    d = request.get_json(silent=True)
    if not isinstance(d, dict):
        fail(400, "invalid_json", "Request body must be a JSON object.")
    return d


def page_args():
    try:
        page = int(request.args.get("page", 1))
        size = int(request.args.get("page_size", 20))
    except ValueError:
        fail(400, "bad_pagination", "page and page_size must be numbers.")
    return max(1, page), min(100, max(1, size))


IST = timezone(timedelta(hours=5, minutes=30))


def parse_dt(s):
    """Parse an ISO timestamp; naive values are treated as IST. Returns UTC ISO or None."""
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=IST)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class V:
    """Collects field-level validation errors and raises one 422 at done()."""

    def __init__(self, data):
        self.d, self.errors = data, {}

    def _err(self, key, msg):
        self.errors.setdefault(key, msg)

    def str(self, key, min=0, max=500, required=True, choices=None, default=None):
        if default is not None:  # a field with a default is optional
            required = False
        raw = self.d.get(key)
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            if required:
                self._err(key, "This field is required.")
            return default
        if not isinstance(raw, (str, int, float)):
            self._err(key, "Invalid value.")
            return default
        s = str(raw).strip()
        if len(s) < min:
            self._err(key, f"Please enter at least {min} characters.")
        elif len(s) > max:
            self._err(key, f"Please keep this under {max} characters.")
        elif choices and s not in choices:
            self._err(key, "Choose one of: " + ", ".join(choices))
        return s

    def int(self, key, min=None, max=None, required=False, default=None):
        raw = self.d.get(key)
        if raw in (None, ""):
            if required:
                self._err(key, "This field is required.")
            return default
        try:
            n = int(float(raw))
        except (TypeError, ValueError):
            self._err(key, "Enter a number.")
            return default
        if min is not None and n < min:
            self._err(key, f"Must be at least {min}.")
        if max is not None and n > max:
            self._err(key, f"Must be at most {max}.")
        return n

    def bool(self, key, default=False):
        raw = self.d.get(key, default)
        return raw is True or raw in (1, "1", "true", "on")

    def dt(self, key, required=False, future=False):
        raw = self.d.get(key)
        if not raw:
            if required:
                self._err(key, "This field is required.")
            return None
        v = parse_dt(raw)
        if not v:
            self._err(key, "Enter a valid date and time.")
            return None
        if future and v < now():
            self._err(key, "Choose a date and time in the future.")
        return v

    def mobile(self, key="mobile", required=True):
        raw = self.d.get(key)
        if not raw:
            if required:
                self._err(key, "Enter your mobile number.")
            return None
        m = normalize_mobile(str(raw))
        if not m:
            self._err(key, "Enter a valid 10-digit Indian mobile number.")
        return m

    def done(self):
        if self.errors:
            fail(422, "validation_error", "Please fix the highlighted fields.", self.errors)


def normalize_mobile(raw: str):
    digits = re.sub(r"\D", "", raw)
    if digits.startswith("91") and len(digits) == 12:
        digits = digits[2:]
    if len(digits) == 10 and digits[0] in "6789":
        return "+91" + digits
    return None


def strong_password(pw: str) -> bool:
    return bool(pw) and len(pw) >= 8 and re.search(r"[A-Za-z]", pw) and re.search(r"\d", pw)
