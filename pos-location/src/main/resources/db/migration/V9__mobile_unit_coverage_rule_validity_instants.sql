-- #2265 (DECISION-LOCATION-017, DECISION-LOCATION-027 rule 4): coverage rule validity windows were
-- a plain `date`, compared against a UTC calendar date derived from the eligibility request's
-- Instant (MobileUnitServiceImpl.findEligibleMobileUnits). DECISION-LOCATION-017 already treats
-- effective windows as UTC instants; this migration and the entity/DTO changes that follow bring
-- mobile_unit_coverage_rules in line: valid_from becomes the start of that UTC day (inclusive, as
-- before), and valid_to becomes the start of the NEXT UTC day (exclusive), so the window still
-- covers what the old inclusive end-of-day date comparison (`validTo >= atDate`) did.
--
-- `date::timestamp AT TIME ZONE 'UTC'` reads the date's midnight as a UTC instant directly (no
-- session-timezone dependency); NULL stays NULL through the cast and through `valid_to + 1`.
--
-- mobile_unit_coverage_rules is under FORCE ROW LEVEL SECURITY and Flyway connects as the owner
-- with a transitional default tenant binding (postgres/init-tenancy.sh); FORCE is lifted for this
-- transaction so the column rewrite converts every tenant's rows, and restored before commit
-- (pos-shop-manager V11, pos-people V8/V9 pattern).
ALTER TABLE public.mobile_unit_coverage_rules NO FORCE ROW LEVEL SECURITY;

ALTER TABLE public.mobile_unit_coverage_rules
    ALTER COLUMN valid_from TYPE timestamptz
    USING (valid_from::timestamp AT TIME ZONE 'UTC');

ALTER TABLE public.mobile_unit_coverage_rules
    ALTER COLUMN valid_to TYPE timestamptz
    USING ((valid_to + 1)::timestamp AT TIME ZONE 'UTC');

ALTER TABLE public.mobile_unit_coverage_rules FORCE ROW LEVEL SECURITY;
