-- CAP:550 S44 (#2618): the tenant's locale (BCP 47, ADR-0030 §2), set by platform operators and published on every
-- tenant lifecycle fact. The platform tenant and the pre-production alpha default tenant are en-US.
ALTER TABLE public.tenant ADD COLUMN locale character varying(35);
UPDATE public.tenant SET locale = 'en-US' WHERE locale IS NULL;
ALTER TABLE public.tenant ALTER COLUMN locale SET NOT NULL;
