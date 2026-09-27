-- #2264, DECISION-LOCATION-026: ext_bay gains display_order, fed additively by
-- location.bay.updated (BayUpdatedV1) so the dashboard's bay roster can sort the same way
-- pos-location's own bay list does — displayOrder (nulls last), then name. NULL until the next
-- emission or a facts replay reaches a bay that predates this column.

ALTER TABLE public.ext_bay ADD COLUMN display_order integer;
