-- Per-binding, classification-driven payload redaction (issue #1259; ADR-0050 §7 minimization:
-- "redaction extends beyond credential headers to configured body fields, driven by data
-- classification; redaction configuration lives with the binding").
--
-- A child table rather than a CSV column on supplier_endpoint_binding: the classification set is a
-- real set with a CHECK-constrained vocabulary, and a delimited string could neither be constrained
-- nor indexed honestly. The vocabulary is CLASSIFICATIONS, not free-text field names, deliberately --
-- the field-name list each classification covers is compiled into PayloadRedactor (exactly as the
-- credential list is), so the same class of data is treated identically across vendors and widening a
-- class widens it everywhere at once.
--
-- Scope honesty, mirrored from the code: classifications are name-based, like all of PayloadRedactor.
-- They narrow REDACTED captures of XML/JSON/form documents. For a positional format (an EDIFACT
-- segment carries values with no names) they redact nothing, and METADATA_ONLY remains the only
-- capture level that guarantees no content is retained; codec-aware positional redaction arrives with
-- the CAP-318 codecs.
CREATE TABLE supplier_endpoint_binding_redaction (
    binding_id uuid NOT NULL,
    classification character varying(64) NOT NULL,

    -- The pair is the identity: declaring a classification twice is meaningless.
    CONSTRAINT supplier_endpoint_binding_redaction_pkey PRIMARY KEY (binding_id, classification),

    -- A real FK, unlike the audit tables' plain UUIDs: this is CONFIGURATION, not evidence. It has no
    -- life of its own after its binding is deleted, and an orphaned redaction row would be config that
    -- governs nothing.
    CONSTRAINT fk_sbinding_redaction_binding FOREIGN KEY (binding_id)
        REFERENCES supplier_endpoint_binding (id) ON DELETE CASCADE,

    -- Pinned to the RedactionClassification enum by SupplierContractKeyParityTest, like every other
    -- enum-valued CHECK in this module.
    CONSTRAINT chk_sbinding_redaction_classification CHECK (classification IN (
        'CUSTOMER_IDENTIFIER', 'COMMERCIAL_PRICING'))
);
