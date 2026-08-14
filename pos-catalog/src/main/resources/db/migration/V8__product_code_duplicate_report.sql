-- CAP-318 / #1232: pre-constraint data-quality report for EAN/UPC product codes.
--
-- ADR-0053 §5 requires deterministic product matching by exact EAN/UPC, which requires the codes
-- to be unique per type. This migration records every offending value BEFORE the constraint is
-- applied in V9, so that a failed rollout names the rows an operator has to fix instead of only
-- reporting a constraint violation. The table is kept afterwards as the audit trail of what the
-- rollout found; on clean data it is simply empty.

CREATE TABLE product_code_duplicate_report (
    code_type     varchar(32)  NOT NULL,
    code_value    varchar(255) NOT NULL,
    product_count integer      NOT NULL,
    product_ids   text         NOT NULL,
    detected_at   timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_code_duplicate_report PRIMARY KEY (code_type, code_value)
);

COMMENT ON TABLE product_code_duplicate_report IS
    'EAN/UPC values carried by more than one product at the time the per-type uniqueness constraint was applied (#1232).';

INSERT INTO product_code_duplicate_report (code_type, code_value, product_count, product_ids)
SELECT p.product_code_type,
       p.product_code,
       COUNT(*)::integer,
       string_agg(p.id::text, ',' ORDER BY p.id)
FROM product p
WHERE p.product_code_type IS NOT NULL
  AND p.product_code IS NOT NULL
GROUP BY p.product_code_type, p.product_code
HAVING COUNT(*) > 1;
