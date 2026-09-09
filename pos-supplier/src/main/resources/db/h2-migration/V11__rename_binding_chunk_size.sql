-- CAP-322 / #1228: the per-binding chunk size is no longer PRICAT-specific.
--
-- V8 introduced pricat_chunk_size for the price catalog. The stock report (B2.1) has the same
-- problem for the same reason — a country-level document is as wide as a catalog, and how many
-- lines fit a broker message depends on how wide that vendor's lines are — so the setting is one
-- property of the binding rather than one per feed. Renamed rather than duplicated: two columns
-- would let a deployment tune one feed and silently leave the other on the default.
--
-- Pre-production rename, no compatibility shim per platform policy.
ALTER TABLE supplier_endpoint_binding RENAME COLUMN pricat_chunk_size TO event_chunk_size;
