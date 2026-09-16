-- CAP-325: a bay declares the catalog operation codes its type is the only one able to perform
-- (D14), and the heaviest GVWR class it accepts (D13).
--
-- service_capability_ids -> service_capability_codes. The column always held codes, never
-- identifiers; BayServiceImpl resolved them by code and BayController's own example showed
-- ["ALIGNMENT"]. The name lied and the @Schema examples showed UUIDs. Renamed rather than
-- shimmed, per the pre-production policy in CLAUDE.md. Under D14 the values are now catalog
-- operationCodes (UPPER-DASH, ADR-0059 §3) validated against ext_catalog_service, and any
-- registry codes that were stored are meaningless — every seeded environment has zero rows in
-- this column, so nothing is lost; a populated environment resets it.
--
-- skill_requirement_ids is dropped. A bay does not hold a competence requirement — the service
-- does (CAP-329) — and the column was free text copied through with no lookup, no
-- normalization and no registry (BayServiceImpl create :91-92, patch :178-179).
--
-- max_duty_class is a GVWR class ceiling, 1..8, null meaning unconstrained. A class number,
-- not a light/heavy token: Light 1-3, Medium 4-6, Heavy 7-8, with the class-4 boundary chosen
-- because it is where ASE's Medium/Heavy Truck certification series begins.

ALTER TABLE public.bays RENAME COLUMN service_capability_ids TO service_capability_codes;
UPDATE public.bays SET service_capability_codes = NULL;

ALTER TABLE public.bays DROP COLUMN skill_requirement_ids;

ALTER TABLE public.bays ADD COLUMN max_duty_class integer;
ALTER TABLE public.bays
    ADD CONSTRAINT bays_max_duty_class_check
    CHECK (max_duty_class IS NULL OR max_duty_class BETWEEN 1 AND 8);
