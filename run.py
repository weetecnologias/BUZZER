"""BUZZER - start the app:  python run.py   (then open http://127.0.0.1:8000)"""
import logging
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from server import config, create_app  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()

if __name__ == "__main__":
    print(f"\n  BUZZER is running ->  http://{config.HOST}:{config.PORT}   (env: {config.ENV})\n"
          f"  Demo password for every seeded partner/agent/admin account: Buzzer@123\n")
    app.run(host=config.HOST, port=config.PORT, debug=False, threaded=True)
