-- #2267, DECISION-LOCATION-029: mobile units gain a duty-class ceiling on the same GVWR axis
-- ext_bay.max_duty_class already carries (V4, CAP-325 D13) — #2269 checks it at placement, so the
-- dashboard's mobile-unit roster needs it replicated the same way ext_bay does.
--
-- The optional identity fields DECISION-LOCATION-029 also adds to mobile_units (unitNumber, vin,
-- licensePlate, plateRegion) are display-only and never evaluated by placement, so they are not
-- replicated here.
ALTER TABLE public.ext_mobile_unit ADD COLUMN max_duty_class integer;
ALTER TABLE public.ext_mobile_unit
    ADD CONSTRAINT ext_mobile_unit_max_duty_class_check
    CHECK (max_duty_class IS NULL OR max_duty_class BETWEEN 1 AND 8);
