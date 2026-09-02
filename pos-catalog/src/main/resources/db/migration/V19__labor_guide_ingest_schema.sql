-- Labor-guide ingestion bookkeeping (#1569 Phase 1, sourcing plan §4.2/§5.3, ADR-0058 §3/§4).
--
-- service_operation_xref     vendor operation code -> Durion service mapping, one row per
--                            (source, vendor code). Vendor codes map onto ours, never the
--                            reverse (ADR-0059 §3); a feed line with no xref row lands in the
--                            unmapped queue instead of guessing.
-- labor_guide_import(/chunk) clone of the ADR-0053 supplier-price import shape, adapted from
--                            Kafka-consumer to adapter-pull: manifest identity is assigned by
--                            the provider, chunks apply idempotently, and completeness is a
--                            counted fact rather than an assumption.
-- labor_guide_unmapped_operation  curation queue: vendor codes the feed carried that map to no
--                            Durion operation. Import continues past them; mapping is deliberate
--                            curation work, surfaced via the admin read endpoint.
-- labor_time_source_policy   data-driven resolution precedence per (time_type, source); lower
--                            precedence wins. Policy is data, not code (sourcing plan §3.4).

CREATE TABLE service_operation_xref (
    id                uuid PRIMARY KEY,               -- UUID v7
    service_id        uuid NOT NULL REFERENCES service (id),
    source_code       varchar(32)  NOT NULL,
    provider_op_code  varchar(128) NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    UNIQUE (source_code, provider_op_code)
);
CREATE INDEX ix_sox_service ON service_operation_xref (service_id);

CREATE TABLE labor_guide_import (
    import_manifest_id  uuid PRIMARY KEY,             -- assigned by the provider, not by us
    source_code         varchar(32)  NOT NULL,
    source_revision     varchar(64)  NOT NULL,
    expected_chunk_count int         NOT NULL,
    expected_line_count bigint       NOT NULL,
    content_checksum    varchar(128) NOT NULL,
    chunks_applied      int          NOT NULL DEFAULT 0,
    lines_applied       bigint       NOT NULL DEFAULT 0,
    lines_unmapped      bigint       NOT NULL DEFAULT 0,
    status              varchar(16)  NOT NULL,
    completed_at        timestamptz,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT ck_lgi_status CHECK (status IN ('APPLYING', 'COMPLETE', 'INCOMPLETE'))
);
CREATE INDEX ix_lgi_source ON labor_guide_import (source_code, created_at);

CREATE TABLE labor_guide_import_chunk (
    id                  uuid PRIMARY KEY,             -- UUID v7
    import_manifest_id  uuid NOT NULL REFERENCES labor_guide_import (import_manifest_id),
    chunk_sequence      int  NOT NULL,
    line_count          int  NOT NULL,
    applied_at          timestamptz NOT NULL,
    UNIQUE (import_manifest_id, chunk_sequence)
);

CREATE TABLE labor_guide_unmapped_operation (
    id                uuid PRIMARY KEY,               -- UUID v7
    source_code       varchar(32)  NOT NULL,
    provider_op_code  varchar(128) NOT NULL,
    last_manifest_id  uuid,
    occurrence_count  bigint       NOT NULL DEFAULT 1,
    first_seen_at     timestamptz  NOT NULL,
    last_seen_at      timestamptz  NOT NULL,
    UNIQUE (source_code, provider_op_code)
);

CREATE TABLE labor_time_source_policy (
    id           uuid PRIMARY KEY,                    -- UUID v7
    time_type    varchar(24) NOT NULL,
    source_code  varchar(32) NOT NULL,
    precedence   int NOT NULL,
    enabled      boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (time_type, source_code),
    CONSTRAINT ck_ltsp_time_type CHECK (
        time_type IN ('RETAIL_FLAT_RATE', 'OEM_WARRANTY', 'MANUFACTURER_INSTALL', 'DURION_STANDARD'))
);
