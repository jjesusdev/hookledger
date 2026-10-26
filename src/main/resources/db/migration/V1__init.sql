CREATE TABLE webhook_event (
    id UUID PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_webhook_event_source_external UNIQUE (source, external_id),
    CONSTRAINT ck_webhook_event_status CHECK (status IN ('RECEIVED', 'PUBLISHED', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_webhook_event_received_at ON webhook_event (received_at DESC);
CREATE INDEX idx_webhook_event_status ON webhook_event (status);

CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES webhook_event (id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_outbox_event_event UNIQUE (event_id)
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (id) WHERE published_at IS NULL;
