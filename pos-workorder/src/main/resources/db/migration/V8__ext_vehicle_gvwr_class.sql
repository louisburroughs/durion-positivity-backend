-- #2263 (CAP-327, spec D11): the vehicle replica carries the owner's GVWR class so workorder
-- placement can check duty class (DECISION-SHOPMGMT-021 rule 3) without a synchronous read
-- (ADR-0044 §6). Fed by vehicle.vehicle.updated, which gained gvwrClass additively within schema
-- version 1; rows read NULL until the owner republishes or the facts are replayed, and NULL means
-- "not determined or not yet published", never a class — placement skips the duty check on NULL.

ALTER TABLE public.ext_vehicle ADD COLUMN gvwr_class integer;
ALTER TABLE public.ext_vehicle
    ADD CONSTRAINT ext_vehicle_gvwr_class_check
    CHECK (gvwr_class IS NULL OR gvwr_class BETWEEN 1 AND 8);

COMMENT ON COLUMN public.ext_vehicle.gvwr_class IS
    'Owner''s FHWA GVWR class 1-8 (#2263). NULL = undetermined or not yet published. Workorder placement skips the duty check (spec D11) when NULL.';
