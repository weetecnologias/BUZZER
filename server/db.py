"""SQLite access layer. One connection per request; commit on success, rollback on error."""
import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

from flask import g, has_request_context, request

from . import config

SCHEMA = Path(__file__).with_name("schema.sql")


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def uid() -> str:
    return str(uuid.uuid4())


def jdump(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def jload(s, default=None):
    if s in (None, ""):
        return default
    try:
        return json.loads(s)
    except (TypeError, ValueError):
        return default


def _connect() -> sqlite3.Connection:
    c = sqlite3.connect(config.DB_PATH, timeout=10, isolation_level=None, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    c.execute("PRAGMA busy_timeout=8000")
    return c


def db() -> sqlite3.Connection:
    if "db" not in g:
        c = _connect()
        writing = (not has_request_context()) or request.method not in ("GET", "HEAD", "OPTIONS")
        c.execute("BEGIN IMMEDIATE" if writing else "BEGIN")
        g.db = c
    return g.db


def commit():
    c = g.get("db")
    if c is not None and c.in_transaction:
        c.execute("COMMIT")


def rollback():
    c = g.get("db")
    if c is not None and c.in_transaction:
        c.execute("ROLLBACK")


def close_db(_exc=None):
    c = g.pop("db", None)
    if c is not None:
        try:
            if c.in_transaction:
                c.execute("ROLLBACK")
        finally:
            c.close()


def init_db() -> bool:
    """Create schema if needed. Returns True when the database is empty (needs seeding)."""
    c = _connect()
    c.execute("PRAGMA journal_mode=WAL")
    c.executescript(SCHEMA.read_text(encoding="utf-8"))
    empty = c.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0
    c.close()
    return empty


# --- query helpers ---------------------------------------------------------

def q(sql, args=()):
    return [dict(r) for r in db().execute(sql, args).fetchall()]


def one(sql, args=()):
    r = db().execute(sql, args).fetchone()
    return dict(r) if r else None


def val(sql, args=(), default=None):
    r = db().execute(sql, args).fetchone()
    return default if r is None or r[0] is None else r[0]


def run(sql, args=()):
    return db().execute(sql, args)


def insert(table, **cols):
    keys = list(cols)
    db().execute(
        f"INSERT INTO {table} ({','.join(keys)}) VALUES ({','.join('?' * len(keys))})",
        [cols[k] for k in keys],
    )
    return cols.get("id")


def update(table, key, where_val, **cols):
    if not cols:
        return
    sets = ",".join(f"{k}=?" for k in cols)
    db().execute(f"UPDATE {table} SET {sets} WHERE {key}=?", [*cols.values(), where_val])


def paginate(sql, args, page=1, size=20, order=""):
    page = max(1, int(page or 1))
    size = min(100, max(1, int(size or 20)))
    total = val(f"SELECT COUNT(*) FROM ({sql})", args, 0)
    items = q(f"{sql} {order} LIMIT ? OFFSET ?", [*args, size, (page - 1) * size])
    return {"items": items, "page": page, "page_size": size, "total": total}


def next_ref(name: str, prefix: str, pad=6) -> str:
    d = db()
    d.execute("INSERT OR IGNORE INTO counters(name, value) VALUES (?, 0)", (name,))
    d.execute("UPDATE counters SET value = value + 1 WHERE name = ?", (name,))
    n = d.execute("SELECT value FROM counters WHERE name = ?", (name,)).fetchone()[0]
    return f"{prefix}-{datetime.now(timezone.utc).year}-{n:0{pad}d}"
