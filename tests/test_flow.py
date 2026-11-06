"""End-to-end API tests: the request -> quote -> approval -> booking -> completion flow plus access control.

Run:  python -m unittest discover -s tests -v      (uses a throw-away database)
"""
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_tmp = tempfile.mkdtemp(prefix="buzzer-test-")
os.environ["BUZZER_DB"] = os.path.join(_tmp, "test.db")
os.environ["BUZZER_UPLOADS"] = os.path.join(_tmp, "uploads")
os.environ["BUZZER_ENV"] = "development"

from server import create_app  # noqa: E402

PW = "Buzzer@123"


def future(days=2):
    return (datetime.now(timezone.utc) + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")


class Api:
    def __init__(self, client, token=None):
        self.c, self.token = client, token

    def call(self, method, path, body=None, expect=None):
        h = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        r = self.c.open("/api/v1" + path, method=method, json=body, headers=h)
        if expect is not None:
            assert r.status_code == expect, f"{method} {path} -> {r.status_code} (wanted {expect}): {r.get_data(as_text=True)[:300]}"
        return r.status_code, (r.get_json(silent=True) or {})

    def get(self, p, expect=200):
        return self.call("GET", p, expect=expect)[1]

    def post(self, p, b=None, expect=None):
        return self.call("POST", p, b or {}, expect)

    def patch(self, p, b=None, expect=None):
        return self.call("PATCH", p, b or {}, expect)


class FlowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = create_app()
        cls.client = cls.app.test_client()

    def anon(self):
        return Api(self.client)

    def customer(self, mobile="9876543210"):
        a = self.anon()
        _, d = a.post("/auth/otp/request", {"mobile": mobile}, 200)
        _, v = a.post("/auth/otp/verify", {"mobile": mobile, "otp": d["dev_otp"], "accept_terms": True}, 200)
        return Api(self.client, v["token"]), v["user"]

    def staff(self, email, portal):
        a = self.anon()
        _, v = a.post("/auth/login", {"identifier": email, "password": PW, "portal": portal}, 200)
        return Api(self.client, v["token"]), v["user"]

    # ------------------------------------------------------------ auth
    def test_01_auth_rules(self):
        a = self.anon()
        self.assertEqual(a.call("GET", "/me")[0], 401)
        self.assertEqual(a.call("POST", "/auth/login", {"identifier": "ops@buzzer.in", "password": "wrong", "portal": "admin"})[0], 401)
        # right password, wrong portal
        self.assertEqual(a.call("POST", "/auth/login", {"identifier": "ops@buzzer.in", "password": PW, "portal": "agent"})[0], 403)
        # wrong OTP
        _, d = a.post("/auth/otp/request", {"mobile": "9876543210"}, 200)
        bad = "000000" if d["dev_otp"] != "000000" else "111111"
        self.assertEqual(a.call("POST", "/auth/otp/verify", {"mobile": "9876543210", "otp": bad})[0], 401)
        # invalid mobile
        self.assertEqual(a.call("POST", "/auth/otp/request", {"mobile": "12345"})[0], 422)

    def test_02_new_customer_gets_dedicated_agent(self):
        a = self.anon()
        _, d = a.post("/auth/otp/request", {"mobile": "9123456780"}, 200)
        self.assertEqual(a.call("POST", "/auth/otp/verify", {"mobile": "9123456780", "otp": d["dev_otp"]})[0], 422)  # must accept terms
        _, v = a.post("/auth/otp/verify", {"mobile": "9123456780", "otp": d["dev_otp"], "accept_terms": True, "name": "Test User"}, 200)
        self.assertTrue(v["is_new"])
        self.assertIsNotNone(v["user"]["agent"], "a new customer must get one primary agent")

    # ------------------------------------------------- the core journey
    def test_10_full_journey(self):
        cust, _ = self.customer()
        priya, _ = self.staff("priya@buzzer.in", "agent")
        arun, _ = self.staff("arun@abchome.in", "partner")
        cats = cust.get("/categories")["items"]
        home = next(c for c in cats if c["slug"] == "home-repairs")
        addr = cust.get("/addresses")["items"][0]

        # validation + inactive category
        code, err = cust.call("POST", "/requests", {"category_id": home["id"], "description": "short", "service_address_id": addr["id"]})
        self.assertEqual(code, 422)
        self.assertIn("description", err["error"]["fields"])
        legal = next(c for c in cats if c["slug"] == "legal-services")
        self.assertEqual(cust.call("POST", "/requests", {"category_id": legal["id"], "description": "I need a lawyer please", "service_address_id": addr["id"]})[0], 422)
        # required dynamic field
        code, err = cust.call("POST", "/requests", {"category_id": home["id"], "description": "Ceiling fan wobbles and makes noise",
                                                    "service_address_id": addr["id"], "details": {}})
        self.assertEqual(code, 422)
        self.assertIn("details.issue_type", err["error"]["fields"])

        body = {"category_id": home["id"], "description": "Ceiling fan in the bedroom wobbles and makes a loud noise.",
                "service_address_id": addr["id"], "preferred_at": future(2), "details": {"issue_type": "Electrical", "property_type": "Apartment"}}
        _, created = cust.post("/requests", body, 201)
        rid = created["id"]
        self.assertEqual(created["status"], "submitted")
        self.assertTrue(created["reference"].startswith("BZ-"))
        self.assertTrue(created["assigned_agent"]["display_name"])
        # duplicate submission is idempotent
        _, dup = cust.post("/requests", body, 200)
        self.assertTrue(dup["duplicate"])

        # agent triages + invites two providers
        detail = priya.get(f"/requests/{rid}")
        self.assertEqual(detail["status"], "submitted")
        provs = priya.get("/providers?verification_status=verified&category_id=" + home["id"])["items"]
        abc = next(p for p in provs if p["business_name"] == "ABC Home Services")
        sai = next(p for p in provs if p["business_name"] == "Sri Sai Electricals")
        _, inv = priya.post(f"/requests/{rid}/invites", {"provider_ids": [abc["id"], sai["id"]]}, 200)
        self.assertEqual(inv["request"]["status"], "finding_providers")

        # provider sees a limited lead - no customer identity
        lead = arun.get(f"/leads/{rid}")
        self.assertNotIn("customer", lead)
        self.assertNotIn("address", lead)
        self.assertTrue(lead["can_quote"])

        # provider quotes (validation first)
        qbody = {"scope": "Replace capacitor, balance the blades and test.", "exclusions": "New fan", "warranty_days": 30, "deposit_pct": 20,
                 "valid_until": future(5), "items": [{"description": "Visit", "qty": 1, "unit_price": 150}, {"description": "Capacitor + labour", "qty": 1, "unit_price": 650}]}
        self.assertEqual(arun.call("POST", f"/requests/{rid}/quotes", {**qbody, "items": []})[0], 422)
        _, q1 = arun.post(f"/requests/{rid}/quotes", qbody, 201)
        self.assertEqual(q1["total"], 800)
        self.assertEqual(q1["status"], "submitted")
        self.assertEqual(arun.call("POST", f"/requests/{rid}/quotes", qbody)[0], 409)  # no second quote
        # agent adds the second provider's quote on their behalf
        q2body = {**qbody, "provider_id": sai["id"], "items": [{"description": "Service", "qty": 1, "unit_price": 950}]}
        _, q2 = priya.post(f"/requests/{rid}/quotes", q2body, 201)

        # customer must NOT see unvalidated quotes, and cannot approve one
        self.assertEqual(len(cust.get(f"/requests/{rid}")["quotes"]), 0)
        self.assertEqual(cust.call("POST", f"/quotes/{q1['id']}/decision", {"decision": "approve", "accept_terms": True})[0], 409)

        # agent validates + shares
        priya.post(f"/quotes/{q1['id']}/share", expect=200)
        priya.post(f"/quotes/{q2['id']}/share", expect=200)
        shared = cust.get(f"/requests/{rid}")
        self.assertEqual(shared["status"], "awaiting_approval")
        self.assertEqual(len(shared["quotes"]), 2)

        # HARD GATE: approval requires explicit consent
        code, err = cust.call("POST", f"/quotes/{q1['id']}/decision", {"decision": "approve"})
        self.assertEqual(code, 422)
        self.assertIsNone(cust.get(f"/requests/{rid}")["booking"])

        # approve
        _, booking = cust.post(f"/quotes/{q1['id']}/decision", {"decision": "approve", "accept_terms": True}, 201)
        self.assertEqual(booking["status"], "confirmed")
        self.assertEqual(booking["amount"], 800)
        after = cust.get(f"/requests/{rid}")
        self.assertEqual(after["status"], "confirmed")
        # a second approval cannot create a second booking
        self.assertEqual(cust.call("POST", f"/quotes/{q2['id']}/decision", {"decision": "approve", "accept_terms": True})[0], 409)
        statuses = {x["id"]: x["status"] for x in priya.get(f"/requests/{rid}")["quotes"]}
        self.assertEqual(statuses[q1["id"]], "approved")
        self.assertEqual(statuses[q2["id"]], "not_selected")

        # payment: deposit then the provider executes the job
        bid = booking["id"]
        paid = cust.post(f"/bookings/{bid}/pay", {"kind": "deposit", "method": "upi"}, 200)[1]
        self.assertEqual(paid["paid_amount"], 160)
        self.assertEqual(cust.call("POST", f"/bookings/{bid}/pay", {"kind": "full", "method": "upi", "simulate": "failure"})[0], 402)
        self.assertEqual(arun.call("PATCH", f"/bookings/{bid}/status", {"status": "work_done", "note": "all done"})[0], 409)  # can't skip steps
        arun.patch(f"/bookings/{bid}/status", {"status": "on_the_way"}, 200)
        arun.patch(f"/bookings/{bid}/status", {"status": "in_progress"}, 200)
        self.assertEqual(cust.get(f"/requests/{rid}")["status"], "in_progress")
        self.assertEqual(arun.call("PATCH", f"/bookings/{bid}/status", {"status": "work_done"})[0], 422)  # needs a completion note
        arun.patch(f"/bookings/{bid}/status", {"status": "work_done", "note": "Replaced the capacitor and tested."}, 200)
        # completion needs the balance settled
        self.assertEqual(cust.call("POST", f"/requests/{rid}/complete")[0], 409)
        cust.post(f"/bookings/{bid}/pay", {"kind": "balance", "method": "upi"}, 200)
        done = cust.post(f"/requests/{rid}/complete", expect=200)[1]
        self.assertEqual(done["status"], "completed")
        self.assertTrue(done["can_rate"])
        # ledger: commission is split from provider payable
        led = self.app.test_client()  # separate check through admin
        fin, _ = self.staff("finance@buzzer.in", "admin")
        summary = fin.get("/admin/finance/summary")
        self.assertGreater(summary["commission"], 0)
        # rating
        cust.post(f"/requests/{rid}/reviews", {"provider_rating": 5, "agent_rating": 5, "provider_comment": "Great"}, 201)
        self.assertEqual(cust.call("POST", f"/requests/{rid}/reviews", {"provider_rating": 4, "agent_rating": 4})[0], 409)
        # timeline recorded every transition with actor
        hist = [h["to_status"] for h in cust.get(f"/requests/{rid}")["history"]]
        self.assertEqual(hist, ["submitted", "under_review", "finding_providers", "quotes_received", "awaiting_approval", "confirmed", "in_progress", "completed"])

    # ---------------------------------------------------- access control
    def test_20_record_level_access(self):
        rahul, _ = self.customer("9876543210")
        ananya, _ = self.customer("9876500001")
        ravi, _ = self.staff("ravi@buzzer.in", "agent")
        priya, _ = self.staff("priya@buzzer.in", "agent")
        arun, _ = self.staff("arun@abchome.in", "partner")
        ops, _ = self.staff("ops@buzzer.in", "admin")
        fin, _ = self.staff("finance@buzzer.in", "admin")
        rahul_req = rahul.get("/requests")["items"][0]
        # another customer can't open it
        self.assertEqual(ananya.call("GET", f"/requests/{rahul_req['id']}")[0], 403)
        # an agent who isn't assigned can't open it or change it
        self.assertEqual(ravi.call("GET", f"/requests/{rahul_req['id']}")[0], 403)
        self.assertEqual(ravi.call("PATCH", f"/requests/{rahul_req['id']}/status", {"status": "cancelled", "reason": "x"})[0], 403)
        # a customer can't use staff or admin endpoints
        self.assertEqual(rahul.call("GET", "/admin/metrics")[0], 403)
        self.assertEqual(rahul.call("GET", "/providers")[0], 403)
        self.assertEqual(rahul.call("GET", "/agent/dashboard")[0], 403)
        # partners can't read bookings that aren't theirs or customer requests
        self.assertEqual(arun.call("GET", f"/requests/{rahul_req['id']}")[0], 403)
        self.assertEqual(arun.call("GET", "/admin/users")[0], 403)
        # admin roles are permission-scoped: finance can't manage users; ops can't see finance
        self.assertEqual(fin.call("GET", "/admin/users")[0], 403)
        self.assertEqual(ops.call("GET", "/admin/finance/summary")[0], 403)
        self.assertEqual(ops.call("GET", "/admin/users")[0], 200)
        # an agent cannot approve on behalf of the customer
        q = priya.get("/quotes?status=shared")["items"]
        if q:
            self.assertEqual(priya.call("POST", f"/quotes/{q[0]['id']}/decision", {"decision": "approve", "accept_terms": True})[0], 403)
        # invalid transitions are rejected
        self.assertEqual(priya.call("PATCH", f"/requests/{rahul_req['id']}/status", {"status": "completed"})[0], 409)

    def test_30_cancellation_creates_refund(self):
        cust, _ = self.customer("9876500009")  # Suresh - has a cancelled, paid booking in seed data
        fin, _ = self.staff("finance@buzzer.in", "admin")
        refunds = fin.get("/admin/finance/refunds?status=requested")["items"]
        self.assertTrue(refunds, "seed data should have a pending refund")
        fin.post(f"/admin/finance/refunds/{refunds[0]['id']}/decide", {"action": "approve"}, 200)
        self.assertEqual(fin.call("POST", f"/admin/finance/refunds/{refunds[0]['id']}/decide", {"action": "approve"})[0], 409)

    def test_40_jobs_module(self):
        cand, _ = self.customer("9876500005")  # Divya, has a candidate profile
        jobs = cand.get("/vacancies")["items"]
        self.assertTrue(jobs)
        self.assertTrue(all(j["status"] == "published" for j in jobs), "candidates only see published vacancies")
        target = next(j for j in jobs if not j["application_status"])
        self.assertEqual(cand.call("POST", f"/vacancies/{target['id']}/applications", {})[0], 422)  # consent required
        cand.post(f"/vacancies/{target['id']}/applications", {"consent_shared": True}, 201)
        self.assertEqual(cand.call("POST", f"/vacancies/{target['id']}/applications", {"consent_shared": True})[0], 409)
        # employer: fee-charging vacancies are rejected
        emp, _ = self.staff("arun@abchome.in", "partner")
        bad = {"title": "Helper wanted", "role_type": "Helper", "description": "Helper needed for a busy workshop in Kondapur.", "locality": "Kondapur",
               "salary_min": 12000, "salary_max": 15000, "recruitment_fee_declared": True}
        self.assertEqual(emp.call("POST", "/vacancies", bad)[0], 422)

    def test_50_file_upload_rules(self):
        cust, _ = self.customer()
        import io
        h = {"Authorization": f"Bearer {cust.token}"}
        ok = self.client.post("/api/v1/files", data={"file": (io.BytesIO(b"\x89PNG\r\n\x1a\n" + b"0" * 64), "pic.png")}, headers=h, content_type="multipart/form-data")
        self.assertEqual(ok.status_code, 201)
        exe = self.client.post("/api/v1/files", data={"file": (io.BytesIO(b"MZ\x90\x00" + b"0" * 64), "evil.png")}, headers=h, content_type="multipart/form-data")
        self.assertEqual(exe.status_code, 415)  # extension/mime lies are rejected by content sniffing
        url = cust.get(f"/files/{ok.get_json()['id']}/url")["url"]
        self.assertEqual(self.client.get(url).status_code, 200)
        self.assertEqual(self.client.get(url[:-4] + "0000").status_code, 403)  # tampered signature


if __name__ == "__main__":
    unittest.main(verbosity=2)
