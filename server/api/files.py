"""Private file storage. Uploads are validated, stored outside the web root, and served via short-lived signed URLs."""
import hashlib
import hmac
import time

from flask import g, jsonify, request, send_file

from .. import config
from ..db import insert, now, one, uid
from ..security import audit, auth, fail, load_user
from . import bp
from .common import can_view_request, get_request

MAGIC = {
    b"\x89PNG": "image/png", b"\xff\xd8\xff": "image/jpeg", b"GIF8": "image/gif", b"%PDF": "application/pdf",
    b"RIFF": "image/webp",
}


def _sniff(head: bytes, claimed: str) -> str:
    for sig, mime in MAGIC.items():
        if head.startswith(sig):
            return mime
    if claimed == "text/plain" and all(b < 128 for b in head[:512]):
        return "text/plain"
    return ""


@bp.post("/files")
@auth()
def upload():
    f = request.files.get("file")
    if not f or not f.filename:
        fail(422, "validation_error", "Choose a file to upload.", {"file": "Choose a file."})
    data = f.read(config.MAX_UPLOAD_BYTES + 1)
    if len(data) > config.MAX_UPLOAD_BYTES:
        fail(413, "file_too_large", "That file is too large (max 5 MB).")
    if not data:
        fail(422, "validation_error", "That file is empty.", {"file": "Empty file."})
    mime = _sniff(data[:16], f.mimetype or "")
    if not mime or mime not in config.ALLOWED_MIME:
        fail(415, "unsupported_type", "Only images (PNG, JPG, WebP, GIF), PDF and text files are allowed.",
             {"file": "Unsupported file type."})
    aid = uid()
    (config.UPLOAD_DIR / aid).write_bytes(data)
    name = "".join(ch for ch in f.filename if ch.isalnum() or ch in "._- ")[:100] or "file"
    req_id = request.form.get("request_id")
    if req_id:
        r = get_request(req_id)
        if not can_view_request(g.user, r):
            req_id = None
        else:
            req_id = r["id"]
    insert("attachments", id=aid, owner_id=g.user["id"], filename=name, mime=mime, size=len(data), path=aid,
           request_id=req_id, created_at=now())
    return jsonify({"id": aid, "filename": name, "mime": mime, "size": len(data)}), 201


def _sig(aid: str, exp: int) -> str:
    return hmac.new(config.SECRET.encode(), f"{aid}.{exp}".encode(), hashlib.sha256).hexdigest()[:32]


def _may_access(u, a) -> bool:
    if a["owner_id"] == u["id"] or u["roles"] & {"agent", "team_lead", "ops_admin", "super_admin", "verification_reviewer"}:
        return True
    if a["request_id"]:
        return can_view_request(u, get_request(a["request_id"]))
    # message attachments are visible to the other conversation participant
    m = one("SELECT c.* FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.attachment_id=?", (a["id"],))
    if m:
        return m["customer_id"] == u["id"] or (m["provider_id"] and one("SELECT 1 FROM providers WHERE id=? AND user_id=?", (m["provider_id"], u["id"])))
    return False


@bp.get("/files/<aid>/url")
@auth()
def file_url(aid):
    a = one("SELECT * FROM attachments WHERE id=?", (aid,))
    if not a or not _may_access(g.user, a):
        fail(404, "not_found", "File not found.")
    exp = int(time.time()) + 300
    return jsonify({"url": f"/api/v1/files/{aid}/download?exp={exp}&sig={_sig(aid, exp)}", "expires_in": 300,
                    "filename": a["filename"], "mime": a["mime"]})


@bp.get("/files/<aid>/download")
def file_download(aid):
    try:
        exp = int(request.args.get("exp", "0"))
    except ValueError:
        exp = 0
    if exp < time.time() or not hmac.compare_digest(_sig(aid, exp), request.args.get("sig", "")):
        fail(403, "link_expired", "This download link has expired.")
    a = one("SELECT * FROM attachments WHERE id=?", (aid,))
    if not a:
        fail(404, "not_found", "File not found.")
    path = config.UPLOAD_DIR / a["path"]
    if not path.exists():
        fail(404, "not_found", "File not found.")
    resp = send_file(path, mimetype=a["mime"], download_name=a["filename"], as_attachment=a["mime"] not in ("image/png", "image/jpeg", "image/webp", "image/gif"))
    resp.headers["Cache-Control"] = "private, max-age=60"
    return resp
