CREATE TABLE delivery_attempt (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES webhook_event (id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    target VARCHAR(512) NOT NULL,
    status_code INTEGER,
    outcome VARCHAR(32) NOT NULL,
    error VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_delivery_attempt_outcome CHECK (outcome IN ('DELIVERED', 'RETRY', 'FAILED')),
    CONSTRAINT uq_delivery_attempt_event_attempt UNIQUE (event_id, attempt)
);

CREATE INDEX idx_delivery_attempt_event ON delivery_attempt (event_id, created_at DESC);
CREATE INDEX idx_delivery_attempt_outcome ON delivery_attempt (outcome);
