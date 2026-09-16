-- CAP-327 (durion#484, spec D13): the vehicle carries its GVWR class.
--
-- gvwr_class is the FHWA class 1..8 derived from the manufacturer's gross vehicle weight rating.
-- The class is stored, never the duty category: LIGHT (1-3) / MEDIUM (4-6) / HEAVY (7-8) is a
-- derived view, and the LIGHT/MEDIUM line at class 4 is Durion's -- it is where ASE's T-series
-- begins -- so it stays a rule in code, not a column that could disagree with the class.
--
-- NULL means undetermined. Per D13.1 no VIN decode ships yet, so every non-null value today is
-- OPERATOR_SET; DECODED is reserved for the decode that arrives once real VINs exist. The source
-- is stored so an operator's correction is never silently mistaken for a decode.

ALTER TABLE public.vehicle_records ADD COLUMN gvwr_class smallint;
ALTER TABLE public.vehicle_records ADD COLUMN gvwr_class_source character varying(16);

ALTER TABLE public.vehicle_records
    ADD CONSTRAINT vehicle_records_gvwr_class_check
    CHECK (gvwr_class IS NULL OR gvwr_class BETWEEN 1 AND 8);
ALTER TABLE public.vehicle_records
    ADD CONSTRAINT vehicle_records_gvwr_class_source_check
    CHECK (gvwr_class_source IS NULL OR gvwr_class_source IN ('OPERATOR_SET', 'DECODED'));
ALTER TABLE public.vehicle_records
    ADD CONSTRAINT vehicle_records_gvwr_class_source_paired_check
    CHECK ((gvwr_class IS NULL) = (gvwr_class_source IS NULL));

COMMENT ON COLUMN public.vehicle_records.gvwr_class IS
    'FHWA GVWR class 1-8 (CAP-327 D13). NULL = undetermined. Duty category (LIGHT 1-3, MEDIUM 4-6, HEAVY 7-8) is derived, never stored.';
COMMENT ON COLUMN public.vehicle_records.gvwr_class_source IS
    'Where the current gvwr_class came from: OPERATOR_SET or DECODED. Always paired with gvwr_class; an operator-set value is never overwritten by a decode.';
