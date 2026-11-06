"""Runtime configuration. Secrets come from the environment, never from source."""
import os
import secrets
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
DATA = BASE / "data"
DATA.mkdir(exist_ok=True)

ENV = os.environ.get("BUZZER_ENV", "development")  # development | production
DB_PATH = os.environ.get("BUZZER_DB", str(DATA / "buzzer.db"))
UPLOAD_DIR = Path(os.environ.get("BUZZER_UPLOADS", str(DATA / "uploads")))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
WEB_DIR = BASE / "web"

HOST = os.environ.get("BUZZER_HOST", "127.0.0.1")
PORT = int(os.environ.get("BUZZER_PORT", "8000"))


def _secret() -> str:
    env = os.environ.get("BUZZER_SECRET")
    if env:
        return env
    f = DATA / "secret.key"
    if not f.exists():
        f.write_text(secrets.token_hex(32))
    return f.read_text().strip()


SECRET = _secret()

# Session lifetimes (hours)
TTL_HOURS = {"customer": 24 * 30, "partner": 24 * 7, "agent": 8, "admin": 8}

MAX_UPLOAD_BYTES = 5 * 1024 * 1024
ALLOWED_MIME = {
    "image/png", "image/jpeg", "image/webp", "application/pdf",
    "image/gif", "text/plain",
}

CITY = "Hyderabad"
LOCALITIES = [
    "Kondapur", "Gachibowli", "Madhapur", "Kukatpally", "Hitec City", "Banjara Hills",
    "Jubilee Hills", "Begumpet", "Secunderabad", "Miyapur", "Manikonda", "Ameerpet",
    "Dilsukhnagar", "Uppal", "Kothapet", "Nizampet", "Film Nagar", "Tolichowki",
]

DEFAULT_AGENT_CAPACITY = 40  # configurable in admin; never hard-coded elsewhere
