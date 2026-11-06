"""Domain events -> notification log. A notification failure must never break the business action."""
import logging
import re

from .db import db, insert, jload, now, one, uid

log = logging.getLogger("buzzer.events")

# (code, name, audience, title, body)
DEFAULT_TEMPLATES = [
    ("request.submitted", "Request submitted", "customer", "Request submitted",
     "We've received {reference} ({category}). {agent} will review it shortly."),
    ("request.new_for_agent", "New request for agent", "agent", "New request {reference}",
     "{category} request from {customer} needs triage."),
    ("request.status", "Request status changed", "customer", "Update on {reference}",
     "Your request is now: {status_label}. {next}"),
    ("request.reassigned", "Agent changed", "customer", "Your BUZZER agent has changed",
     "{agent} is now looking after {reference}. Reason: {reason}"),
    ("lead.new", "New lead for partner", "provider", "New lead {reference}",
     "{category} in {locality}. Open the lead and send your quote."),
    ("quote.received", "Quote received (agent)", "agent", "Quote received for {reference}",
     "{provider} quoted Rs {amount}. Validate and share it with the customer."),
    ("quote.shared", "Quote shared with customer", "customer", "New quote on {reference}",
     "{count} quote(s) are ready to compare. Nothing is booked until you approve."),
    ("quote.approved", "Quote approved", "provider", "Your quote was approved",
     "Booking {booking} for {reference} is confirmed for Rs {amount}."),
    ("quote.not_selected", "Quote not selected", "provider", "Quote not selected",
     "The customer chose another provider for {reference}. Thank you for responding."),
    ("quote.clarification", "Customer needs clarification", "agent", "Customer asked a question",
     "On {reference}: {note}"),
    ("quote.revised", "Quote revised", "customer", "Please re-approve the revised quote",
     "The price or scope changed on {reference}. Your booking stays on hold until you approve again."),
    ("booking.confirmed", "Booking confirmed", "customer", "Booking confirmed",
     "{provider} is booked for {reference}. Pay the deposit to secure your slot."),
    ("booking.status", "Service update", "customer", "Service update on {reference}",
     "{provider}: {status_label}."),
    ("booking.cancelled", "Booking cancelled", "provider", "Booking cancelled",
     "Booking {booking} was cancelled. Reason: {reason}"),
    ("booking.work_done", "Work marked done", "customer", "Please confirm completion",
     "{provider} marked {reference} as done. Confirm if you're happy, or raise an issue."),
    ("payment.success", "Payment received", "customer", "Payment received",
     "Rs {amount} paid for {reference}. Receipt {txn}."),
    ("payment.provider", "Customer paid", "provider", "Customer payment received",
     "Rs {amount} paid for booking {booking}."),
    ("message.new", "New message", "customer", "New message",
     "{sender}: {preview}"),
    ("complaint.opened", "Complaint opened", "agent", "New complaint {reference}",
     "{topic}. Please review and respond within SLA."),
    ("complaint.update", "Complaint update", "customer", "Update on {reference}",
     "Status: {status}. {note}"),
    ("job.application", "New application", "employer", "New application for {title}",
     "{candidate} applied for {title}."),
    ("job.status", "Application update", "customer", "Application update",
     "Your application for {title} is now: {status}."),
    ("job.verified", "Vacancy verified", "employer", "Vacancy {reference} is live",
     "{title} passed verification and is visible to candidates."),
    ("job.rejected", "Vacancy rejected", "employer", "Vacancy {reference} needs changes",
     "{title} was not published. Reason: {reason}"),
    ("partner.verification", "Verification result", "provider", "Verification update",
     "Your business verification is now: {status}. {note}"),
    ("payout.paid", "Payout paid", "provider", "Payout processed",
     "Rs {amount} was sent to your bank account. Reference {reference}."),
    ("refund.update", "Refund update", "customer", "Refund {status}",
     "Rs {amount} for booking {booking}: {status}."),
    ("task.due", "Follow-up due", "agent", "Follow-up due", "{title}"),
]

PREF_FOR_PREFIX = {
    "request": "request_updates",
    "quote": "quote_alerts",
    "booking": "booking_reminders",
    "payment": "payment_receipts",
}
DEFAULT_PREFS = {
    "request_updates": True, "quote_alerts": True, "booking_reminders": True,
    "payment_receipts": True, "promotions": False, "whatsapp": False,
}


def _render(text, ctx):
    return re.sub(r"\{(\w+)\}", lambda m: str(ctx.get(m.group(1), "")), text or "")


def get_prefs(user_id):
    row = one("SELECT notification_prefs FROM customers WHERE user_id=?", (user_id,))
    prefs = dict(DEFAULT_PREFS)
    if row:
        prefs.update(jload(row["notification_prefs"], {}))
    return prefs


def notify(user_id, event, ctx=None, link=None):
    """Create an in-app notification (and a simulated WhatsApp copy if the user opted in)."""
    if not user_id:
        return
    ctx = ctx or {}
    d = db()
    d.execute("SAVEPOINT notify")
    try:
        t = one("SELECT title, body, active FROM message_templates WHERE code=?", (event,))
        if t is None:
            fb = next((x for x in DEFAULT_TEMPLATES if x[0] == event), None)
            t = {"title": fb[3], "body": fb[4], "active": 1} if fb else {"title": event, "body": "", "active": 1}
        if t["active"]:
            prefs = get_prefs(user_id)
            key = PREF_FOR_PREFIX.get(event.split(".")[0])
            if not (key and prefs.get(key) is False):
                row = dict(user_id=user_id, type=event.split(".")[0], event=event,
                           title=_render(t["title"], ctx), body=_render(t["body"], ctx), link=link,
                           created_at=now())
                insert("notifications", id=uid(), channel="in_app", delivery_status="delivered",
                       is_read=0, **row)
                if prefs.get("whatsapp") and event.split(".")[0] in ("request", "booking", "quote"):
                    insert("notifications", id=uid(), channel="whatsapp", delivery_status="simulated",
                           is_read=1, **row)
        d.execute("RELEASE notify")
    except Exception:  # noqa: BLE001 - notification failure must not break the workflow
        log.exception("notification failed for %s", event)
        d.execute("ROLLBACK TO notify")
        d.execute("RELEASE notify")
