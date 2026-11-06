"""BUZZER - modular monolith. Flask app factory."""
import logging

from flask import Flask, jsonify, request, send_from_directory

from . import config
from .db import close_db, commit, init_db, rollback
from .security import ApiError

log = logging.getLogger("buzzer")


def create_app() -> Flask:
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = config.MAX_UPLOAD_BYTES + 512 * 1024
    app.json.sort_keys = False
    app.teardown_appcontext(close_db)

    from .api import bp
    app.register_blueprint(bp)

    @app.after_request
    def finish(resp):
        if resp.status_code < 400:
            commit()
        else:
            rollback()
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        resp.headers.setdefault("Referrer-Policy", "same-origin")
        resp.headers.setdefault(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
            "script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
        if not request.path.startswith("/api/"):
            resp.headers["Cache-Control"] = "no-cache"
        return resp

    @app.errorhandler(ApiError)
    def api_error(e):
        body = {"error": {"code": e.code, "message": e.message}}
        if e.fields:
            body["error"]["fields"] = e.fields
        return jsonify(body), e.status

    @app.errorhandler(404)
    def not_found(_e):
        if request.path.startswith("/api/"):
            return jsonify({"error": {"code": "not_found", "message": "That resource was not found."}}), 404
        return send_from_directory(config.WEB_DIR, "index.html")

    @app.errorhandler(405)
    def bad_method(_e):
        return jsonify({"error": {"code": "method_not_allowed", "message": "Method not allowed."}}), 405

    @app.errorhandler(413)
    def too_big(_e):
        return jsonify({"error": {"code": "file_too_large", "message": "That file is too large (max 5 MB)."}}), 413

    @app.errorhandler(Exception)
    def crash(e):
        log.exception("unhandled error")
        return jsonify({"error": {"code": "server_error",
                                  "message": "Something went wrong on our side. Please try again."}}), 500

    @app.get("/")
    def index():
        return send_from_directory(config.WEB_DIR, "index.html")

    @app.get("/<path:path>")
    def assets(path):
        if path.startswith("api/"):
            return not_found(None)
        return send_from_directory(config.WEB_DIR, path)

    empty = init_db()
    if empty:
        from .seed import seed
        with app.app_context():
            seed()
            commit()
    return app
