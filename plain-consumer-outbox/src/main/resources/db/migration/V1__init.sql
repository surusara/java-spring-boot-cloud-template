-- Business state -------------------------------------------------------------
CREATE TABLE payment (
    payment_id   VARCHAR(64)   PRIMARY KEY,
    account_id   VARCHAR(64)   NOT NULL,
    amount       NUMERIC(18,2) NOT NULL,
    currency     VARCHAR(3)    NOT NULL,
    risk_tier    VARCHAR(32),
    status       VARCHAR(32)   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Consumer-side idempotency: one row per business key we have already applied.
-- The listener is at-least-once (offset committed after the DB commit); this table
-- makes the DB effect effectively-once — a replayed record is a no-op.
CREATE TABLE processed_message (
    payment_id   VARCHAR(64)  PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Transactional outbox: written in the SAME DB transaction as the business change.
-- A separate relay publishes PENDING rows to Kafka and flips them to SENT. This gives
-- atomicity between the DB write and "intent to publish" without a Kafka+DB XA transaction.
CREATE TABLE outbox_event (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   VARCHAR(64)  NOT NULL UNIQUE,
    event_type VARCHAR(48)  NOT NULL,   -- PAYMENT_APPROVED | PAYMENT_AUDIT
    topic      VARCHAR(255) NOT NULL,
    event_key  VARCHAR(255) NOT NULL,
    payload    BYTEA        NOT NULL,   -- Avro binary (schema-less framing; relay re-serializes via Schema Registry)
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts   INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ
);

-- Relay polls PENDING rows in insertion order; partial index keeps the scan cheap.
CREATE INDEX idx_outbox_pending ON outbox_event (id) WHERE status = 'PENDING';
