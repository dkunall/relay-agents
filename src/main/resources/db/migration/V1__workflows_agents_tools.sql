CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'integrity_constraint_violation';
END
$$;

CREATE TABLE agent (
    tenant_id     text        NOT NULL,
    name          text        NOT NULL,
    model         text        NOT NULL,
    system_prompt text        NOT NULL,
    tools         text[]      NOT NULL DEFAULT '{}',
    scopes        text[]      NOT NULL DEFAULT '{}',
    params        jsonb       NOT NULL DEFAULT '{}',
    memory_window int         NOT NULL CHECK (memory_window BETWEEN 2 AND 100),
    updated_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);

-- A workflow version never changes once written; a run pins (id, version).
CREATE TABLE workflow (
    id         uuid        NOT NULL,
    version    int         NOT NULL CHECK (version > 0),
    tenant_id  text        NOT NULL,
    name       text        NOT NULL,
    definition jsonb       NOT NULL,
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version)
);

CREATE INDEX workflow_tenant_idx ON workflow (tenant_id, created_at DESC);

CREATE TRIGGER workflow_immutable BEFORE UPDATE OR DELETE ON workflow
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- Tenant-defined HTTP tools. Built-in tools live in code and their names are reserved.
CREATE TABLE http_tool (
    tenant_id         text        NOT NULL,
    name              text        NOT NULL,
    description       text        NOT NULL,
    json_schema       jsonb       NOT NULL,
    scopes            text[]      NOT NULL,
    requires_approval boolean     NOT NULL,
    method            text        NOT NULL CHECK (method IN ('GET', 'POST')),
    url_template      text        NOT NULL,
    headers           jsonb       NOT NULL DEFAULT '{}',
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);
