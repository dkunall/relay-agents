CREATE TABLE run (
    id                   uuid        PRIMARY KEY,
    tenant_id            text        NOT NULL,
    workflow_id          uuid        NOT NULL,
    workflow_version     int         NOT NULL,
    status               text        NOT NULL,
    mode                 text        NOT NULL CHECK (mode IN ('LIVE', 'REPLAY_RECORDED', 'REPLAY_LIVE')),
    source_run_id        uuid        REFERENCES run (id),
    input                jsonb       NOT NULL,
    output               jsonb,
    failure              text,
    max_tokens           bigint      NOT NULL CHECK (max_tokens > 0),
    max_cost_micros      bigint      NOT NULL CHECK (max_cost_micros >= 0),
    max_tool_calls       int         NOT NULL CHECK (max_tool_calls >= 0),
    deadline             timestamptz NOT NULL,
    spent_tokens         bigint      NOT NULL DEFAULT 0,
    spent_cost_micros    bigint      NOT NULL DEFAULT 0,
    tool_calls           int         NOT NULL DEFAULT 0,
    reserved_tokens      bigint      NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    reserved_cost_micros bigint      NOT NULL DEFAULT 0 CHECK (reserved_cost_micros >= 0),
    last_seq             bigint      NOT NULL DEFAULT 0,
    created_by           text        NOT NULL,
    idempotency_key      text,
    request_hash         text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workflow_id, workflow_version) REFERENCES workflow (id, version),
    CHECK (tool_calls <= max_tool_calls),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX run_tenant_status_idx ON run (tenant_id, status);
CREATE INDEX run_tenant_created_idx ON run (tenant_id, created_at);
CREATE INDEX run_deadline_idx ON run (deadline) WHERE status IN ('RUNNING', 'WAITING_APPROVAL');

-- The source of truth for a run. The run and step tables are projections kept in the same transaction.
CREATE TABLE run_event (
    run_id     uuid        NOT NULL REFERENCES run (id),
    seq        bigint      NOT NULL CHECK (seq > 0),
    type       text        NOT NULL,
    step_id    text,
    payload    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

CREATE TRIGGER run_event_append_only BEFORE UPDATE OR DELETE ON run_event
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TABLE step (
    run_id           uuid        NOT NULL REFERENCES run (id),
    step_id          text        NOT NULL,
    type             text        NOT NULL,
    status           text        NOT NULL,
    attempt          int         NOT NULL DEFAULT 0,
    max_attempts     int         NOT NULL CHECK (max_attempts > 0),
    available_at     timestamptz NOT NULL DEFAULT now(),
    lease_owner      text,
    lease_expires_at timestamptz,
    last_error       text,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, step_id)
);

CREATE INDEX step_ready_idx ON step (available_at) WHERE status = 'READY';
CREATE INDEX step_lease_idx ON step (lease_expires_at) WHERE status = 'RUNNING';

CREATE TABLE approval (
    id           uuid        PRIMARY KEY,
    run_id       uuid        NOT NULL REFERENCES run (id),
    step_id      text        NOT NULL,
    iteration    int         NOT NULL,
    tenant_id    text        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('TOOL', 'GATE')),
    tool         text,
    arguments    jsonb,
    reason       text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    requested_by text        NOT NULL,
    decided_by   text,
    decided_at   timestamptz,
    comment      text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, step_id, iteration)
);

CREATE INDEX approval_pending_idx ON approval (created_at) WHERE status = 'PENDING';

-- One row per tool call that actually executed. A step re-run after a lost lease finds its row here
-- and reuses the result instead of calling the tool again.
CREATE TABLE tool_invocation (
    idempotency_key text        PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES run (id),
    step_id         text        NOT NULL,
    iteration       int         NOT NULL,
    tool            text        NOT NULL,
    arguments       jsonb       NOT NULL,
    result          jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_quota (
    tenant_id             text   PRIMARY KEY,
    max_concurrent_runs   int    NOT NULL CHECK (max_concurrent_runs > 0),
    daily_cost_cap_micros bigint NOT NULL CHECK (daily_cost_cap_micros >= 0)
);

CREATE TABLE outbox (
    id           bigserial   PRIMARY KEY,
    event_id     uuid        NOT NULL UNIQUE,
    topic        text        NOT NULL,
    message_key  text        NOT NULL,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
