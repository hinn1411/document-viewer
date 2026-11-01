-- Who viewed which vehicle's documents. Insert-only.
CREATE TABLE document_access_audit (
    id              UUID         PRIMARY KEY,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    user_id         TEXT         NOT NULL,
    dealership_id   TEXT         NOT NULL,
    vin             CHAR(17)     NOT NULL,
    trace_id        TEXT         NOT NULL,
    outcome         TEXT         NOT NULL CHECK (outcome IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    source_statuses JSONB        NOT NULL,  -- e.g. {"SALES": "OK", "SERVICE": "STALE"}
    document_count  INTEGER      NOT NULL
);
CREATE INDEX idx_audit_vin_time  ON document_access_audit (vin, occurred_at DESC);
CREATE INDEX idx_audit_user_time ON document_access_audit (user_id, occurred_at DESC);

-- Last good result per VIN and source.
CREATE TABLE source_snapshot (
    vin         CHAR(17)     NOT NULL,
    source      TEXT         NOT NULL,
    documents   JSONB        NOT NULL,      -- list of Document, see 05-domain-model.md
    fetched_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (vin, source)
);
CREATE INDEX idx_snapshot_fetched_at ON source_snapshot (fetched_at);
