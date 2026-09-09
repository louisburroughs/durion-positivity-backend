-- Reinstates correlation_id on supplier_audit_access (issue #1264).
--
-- V4 deliberately omitted this column because nothing put an inbound X-Correlation-Id in scope, so
-- it could only ever have been null -- and a column that is always null reads as "this read had no
-- correlation" rather than "we never captured one". That precondition has now been met:
-- SupplierCorrelationFilter opens a SupplierCorrelationContext scope for every inbound request to
-- this module (reusing the caller's header or generating an id), so an access row finally has a
-- non-null value that joins it to the operator request that caused the read.
--
-- NULLABLE, deliberately: rows written before this migration genuinely have no captured
-- correlation, and inventing one would be a lie in an evidentiary record. AuditAccessRecorder
-- always writes a value from here on, so null now means exactly "recorded before inbound
-- correlation existed".
--
-- varchar(100), matching supplier_exchange_audit.correlation_id: the value is caller-influenced
-- (reused from the inbound header) and the recorder truncates to this width before insert, so an
-- oversized header cannot fail the insert. That matters more here than on the exchange table --
-- AuditAccessRecorder fails CLOSED (a failed access insert fails the payload read, ADR-0050 §7),
-- so an untruncated oversized header would let a client make payloads unreadable. The column is
-- never the validation boundary.
ALTER TABLE supplier_audit_access ADD COLUMN correlation_id character varying(100);

-- "What did this request touch?" -- the join from an operator request (or a scheduled page) to the
-- payload reads it caused, mirroring idx_saudit_correlation on the exchange table.
CREATE INDEX idx_saccess_correlation ON supplier_audit_access (correlation_id);
