from flask import Blueprint

bp = Blueprint("api", __name__, url_prefix="/api/v1")

from . import (admin, agent, auth, catalogue, comms, files, jobs, me, providers,  # noqa: E402,F401
               quotes, requests_, trust)
