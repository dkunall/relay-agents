CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
    id              uuid        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    name            text        NOT NULL,
    embedding_model text        NOT NULL,
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE document (
    id           uuid        PRIMARY KEY,
    kb_id        uuid        NOT NULL REFERENCES knowledge_base (id),
    title        text        NOT NULL,
    content_hash text        NOT NULL,
    chunk_count  int         NOT NULL,
    redactions   int         NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kb_id, content_hash)
);

-- 256 dimensions matches the built-in hashing embedder; a different model needs a new column and a re-embed.
CREATE TABLE chunk (
    id          bigserial PRIMARY KEY,
    document_id uuid      NOT NULL REFERENCES document (id),
    kb_id       uuid      NOT NULL REFERENCES knowledge_base (id),
    ordinal     int       NOT NULL,
    text        text      NOT NULL,
    flags       text[]    NOT NULL DEFAULT '{}',
    tsv         tsvector  GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    embedding   vector(256) NOT NULL
);

CREATE INDEX chunk_kb_idx ON chunk (kb_id);
CREATE INDEX chunk_tsv_idx ON chunk USING gin (tsv);
CREATE INDEX chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);
