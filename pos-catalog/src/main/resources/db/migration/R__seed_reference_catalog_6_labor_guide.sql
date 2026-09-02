-- =============================================================
-- R__seed_reference_catalog_6_labor_guide.sql
-- MOCKGUIDE vendor-code cross-reference + default source policy
-- (#1569 Phase 1, sourcing plan §5.3 step 4 / §3.4)
-- =============================================================
-- The pos-reference-mock vendor publishes codes of the form MG-<DURION-CODE> for the
-- reference services seeded in R__seed_reference_catalog_3_services.sql. This xref is what
-- lets the chunked import land those lines on the right service rows.
--
-- HEADLIGHT-RESTORATION is deliberately NOT mapped: the mock also publishes
-- MG-HEADLIGHT-RESTORATION (and MG-FOG-LAMP-ALIGN, which has no Durion counterpart at all),
-- so every import exercises the unmapped-operation curation queue with real rows instead of
-- the queue existing untested.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when the checksum changes.
SET TIME ZONE 'UTC';

INSERT INTO service_operation_xref (id, service_id, source_code, provider_op_code, created_at, updated_at)
SELECT md5('xref:' || src.source_code || ':' || s.operation_code)::uuid,
       s.id,
       src.source_code,
       'MG-' || s.operation_code,
       NOW(),
       NOW()
FROM service s
CROSS JOIN (VALUES ('MOCKGUIDE'), ('MOCKGUIDE_LIVE')) AS src (source_code)
WHERE s.operation_code IS NOT NULL
  AND s.operation_code <> 'HEADLIGHT-RESTORATION'
ON CONFLICT (source_code, provider_op_code) DO NOTHING;

-- Default resolution precedence (lower wins). One row per (time_type, source) that stores
-- rows; the QUERY_ONLY live source and any unlisted pair fall back to the provider's
-- configured default precedence.
INSERT INTO labor_time_source_policy (id, time_type, source_code, precedence, enabled, created_at, updated_at)
VALUES
    (md5('ltsp:RETAIL_FLAT_RATE:MOCKGUIDE')::uuid,     'RETAIL_FLAT_RATE',     'MOCKGUIDE', 100, true, NOW(), NOW()),
    (md5('ltsp:OEM_WARRANTY:MOCKGUIDE')::uuid,         'OEM_WARRANTY',         'MOCKGUIDE', 100, true, NOW(), NOW()),
    (md5('ltsp:MANUFACTURER_INSTALL:MOCKGUIDE')::uuid, 'MANUFACTURER_INSTALL', 'MOCKGUIDE', 100, true, NOW(), NOW()),
    (md5('ltsp:DURION_STANDARD:DURION')::uuid,         'DURION_STANDARD',      'DURION',    100, true, NOW(), NOW())
ON CONFLICT (time_type, source_code) DO NOTHING;
