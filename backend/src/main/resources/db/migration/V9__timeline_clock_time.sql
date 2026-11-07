-- Timeline entries written in one transaction (e.g. "request created" then "sent to partners automatically") all got the
-- same now() (transaction start time), so their order was ambiguous. clock_timestamp() records the real moment of each step.
ALTER TABLE request_status_history ALTER COLUMN created_at SET DEFAULT clock_timestamp();
