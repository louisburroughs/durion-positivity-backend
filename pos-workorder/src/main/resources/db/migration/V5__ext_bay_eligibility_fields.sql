-- CAP-325: ext_bay gains the fields bay eligibility needs. Fed by location.bay.updated, whose
-- payload gained them additively within schema version 1; rows fill on the next emission or on a
-- pos-location facts replay, and read NULL until then. Consumers must treat NULL as "not yet
-- published", never as "none" -- an empty service_capability_codes array is a general bay, which
-- is eligible for every operation no specialty bay claims (D14), whereas NULL says nothing.
--
-- service_capability_codes is text[] rather than a JSON blob so the eligibility query can use
-- = ANY(...) and a GIN index later without parsing. max_duty_class is a GVWR class 1..8 (D13).

ALTER TABLE public.ext_bay ADD COLUMN bay_type character varying(50);
ALTER TABLE public.ext_bay ADD COLUMN service_capability_codes text[];
ALTER TABLE public.ext_bay ADD COLUMN max_concurrent_vehicles integer;
ALTER TABLE public.ext_bay ADD COLUMN max_duty_class smallint;
ALTER TABLE public.ext_bay
    ADD CONSTRAINT ext_bay_max_duty_class_check
    CHECK (max_duty_class IS NULL OR max_duty_class BETWEEN 1 AND 8);
