-- pos-workorder's first catalog replica: the service master's operation taxonomy
-- (#1569 Phase 1, ADR-0058 §5, ADR-0044 §3).
--
-- Fed by catalog.service.updated (schema v2) on catalog.events.v1. Carries the
-- vehicle-agnostic default hours ONLY — the vehicle-keyed labor-time matrix deliberately never
-- rides events (volume + licensing) and is reached through the scoped labor-time resolution
-- edge instead. This replica is the degraded/offline path: when the edge cannot answer, the
-- default hours prefill an estimate line and the writer overrides; and operation_code is what
-- the overlap-aware summation uses to recognise a line named in another line's included
-- operations.
--
-- aggregate_version carries the fact envelope's monotonic version for the stale-event guard,
-- per the ext_* replica convention. active=false rows are tombstones: kept, never resolved.

CREATE TABLE ext_catalog_service (
    service_id          uuid PRIMARY KEY,
    name                text,
    operation_code      varchar(64),
    operation_category  varchar(32),
    default_labor_hours numeric(5,1),
    active              boolean     NOT NULL,
    aggregate_version   bigint      NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE INDEX ix_ext_catalog_service_op_code ON ext_catalog_service (operation_code)
    WHERE operation_code IS NOT NULL;
