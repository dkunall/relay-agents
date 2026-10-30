-- Data the read-only SQL tool may see. The tool switches to relay_sandbox for its transaction, so it
-- cannot read application tables, and row-level security keeps tenants apart.
CREATE SCHEMA sandbox;

CREATE TABLE sandbox.customer (
    id        text PRIMARY KEY,
    tenant_id text NOT NULL,
    name      text NOT NULL,
    tier      text NOT NULL
);

CREATE TABLE sandbox.orders (
    id           text        PRIMARY KEY,
    tenant_id    text        NOT NULL,
    customer_id  text        NOT NULL REFERENCES sandbox.customer (id),
    status       text        NOT NULL,
    amount_minor bigint      NOT NULL,
    placed_at    timestamptz NOT NULL
);

INSERT INTO sandbox.customer VALUES
    ('C-100', 'acme', 'Meera Iyer', 'gold'),
    ('C-101', 'acme', 'Arjun Rao', 'standard'),
    ('C-200', 'globex', 'Lena Park', 'standard');

INSERT INTO sandbox.orders VALUES
    ('O-5001', 'acme', 'C-100', 'DELIVERED', 249900, '2026-10-02T10:15:00Z'),
    ('O-5002', 'acme', 'C-100', 'DAMAGED_IN_TRANSIT', 129900, '2026-10-20T08:40:00Z'),
    ('O-5003', 'acme', 'C-101', 'SHIPPED', 59900, '2026-10-25T17:05:00Z'),
    ('O-7001', 'globex', 'C-200', 'DELIVERED', 99900, '2026-10-11T12:00:00Z');

ALTER TABLE sandbox.customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE sandbox.orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_rows ON sandbox.customer USING (tenant_id = current_setting('relay.tenant', true));
CREATE POLICY tenant_rows ON sandbox.orders USING (tenant_id = current_setting('relay.tenant', true));

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'relay_sandbox') THEN
        CREATE ROLE relay_sandbox NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA sandbox TO relay_sandbox;
GRANT SELECT ON ALL TABLES IN SCHEMA sandbox TO relay_sandbox;
GRANT relay_sandbox TO CURRENT_USER;

-- Stand-ins for downstream systems that write tools call. The unique idempotency key is what a real
-- downstream API would enforce for us.
CREATE TABLE ticket (
    id              uuid        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,
    title           text        NOT NULL,
    body            text        NOT NULL,
    priority        text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refund_request (
    id              uuid        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,
    order_id        text        NOT NULL,
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    reason          text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE run_trigger (
    id           uuid        PRIMARY KEY,
    tenant_id    text        NOT NULL,
    workflow_id  uuid        NOT NULL,
    type         text        NOT NULL CHECK (type IN ('WEBHOOK', 'CRON')),
    secret_name  text,
    cron         text,
    input        jsonb       NOT NULL DEFAULT '{}',
    budget       jsonb       NOT NULL,
    enabled      boolean     NOT NULL DEFAULT true,
    next_fire_at timestamptz,
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK ((type = 'WEBHOOK' AND secret_name IS NOT NULL) OR (type = 'CRON' AND cron IS NOT NULL))
);

CREATE INDEX run_trigger_due_idx ON run_trigger (next_fire_at) WHERE type = 'CRON' AND enabled;
