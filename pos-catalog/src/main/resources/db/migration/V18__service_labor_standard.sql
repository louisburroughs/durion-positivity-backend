-- Vehicle-keyed labor times with provenance (#1569, sourcing plan §4.1/§4.2).
--
-- One row = one published time for (service operation, vehicle key, time type) from one
-- source revision. Book time is vehicle-specific — guides resolve by year/make/model/
-- submodel/engine — so a scalar column on service models nothing real; this child table
-- is the system-of-record shape ratified on #1569.
--
-- Rows are append-preferred: a correction or a new feed revision SUPERSEDES the old row
-- (superseded_at set, replacement inserted) rather than updating in place, so an invoice
-- quoted against revision N stays explainable after revision N+1 lands. The partial
-- index keeps active-row lookups cheap.
--
-- Provenance columns are non-negotiable (#1569 "defensible on an invoice"): every time
-- names its source and revision. Hand-authored rows use source_code DURION; only DURION
-- rows are editable through the API — imported rows will be correctable only by
-- supersession once the labor-guide importer (sourcing plan §5) exists.
--
-- Columns reserved for later phases, nullable until their writers exist:
--   aces_vehicle_id     populated when a licensed source supplies ACES vehicle ids
--   import_manifest_id  ties a row to its chunked-manifest import run (§5.3)

CREATE TABLE service_labor_standard (
    id                 uuid PRIMARY KEY,                  -- UUID v7
    service_id         uuid NOT NULL REFERENCES service (id),
    -- vehicle key (denormalized strings, vocabulary aligned with pos-vehicle-fitment;
    -- null = wildcard, matching PartFitmentEntity convention)
    vehicle_year       varchar(16),
    make               varchar(64),
    model              varchar(64),
    submodel           varchar(64),
    engine_code        varchar(64),
    aces_vehicle_id    bigint,
    -- the time
    labor_hours        numeric(5,1) NOT NULL,             -- decimal hours, tenths (0.1 hr = 6 min)
    time_type          varchar(24)  NOT NULL,             -- RETAIL_FLAT_RATE | OEM_WARRANTY | MANUFACTURER_INSTALL | DURION_STANDARD
    -- relationships that make workorder summation honest (sourcing plan §2 rule 4)
    overlap_group      varchar(64),
    included_op_codes  text[],
    -- provenance
    source_code        varchar(32)  NOT NULL,
    source_revision    varchar(64)  NOT NULL,
    published_at       date,
    import_manifest_id uuid,
    superseded_at      timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL
);

CREATE INDEX ix_sls_lookup ON service_labor_standard (service_id, make, model, vehicle_year)
    WHERE superseded_at IS NULL;
CREATE INDEX ix_sls_source ON service_labor_standard (source_code, source_revision);
