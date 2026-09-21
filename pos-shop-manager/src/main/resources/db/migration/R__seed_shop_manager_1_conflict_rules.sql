-- CAP-326 (durion#483): DECISION-SHOPMGMT-002's rule catalog. Eight rows -- the record's four, the
-- two SKILL rules #2035 settled (spec D10.1) and the two HOURS rules D18.1 ruled so a booking outside
-- hours or on a closed day has a rule to reference. The code is the API reason code verbatim: there
-- is no mapping table, so there is nothing for the two to drift between.
--
-- conflict_rule is platform-global (D18.2): no tenant binding here. Ids are md5-derived from the code
-- so reruns are deterministic; ON CONFLICT (code) lets a template or a severity be corrected by
-- editing this file. Column-targeted ON CONFLICT is permitted on a global table (TENANCY_SCHEMA.md).
--
-- MECHANIC_OVERTIME is seeded inactive: SchedulingConflictEvaluator has no timekeeping input to fire
-- it from, and an active rule the evaluator never evaluates advertises enforcement that does not
-- exist (#2045 review). Flip is_active here when weekly hours arrive.
--
-- Templates use {placeholders} rendered by the enforcement tier: {resource}, {start}, {end}, {zone},
-- {date}, {reason}, {skills}. Times render in the facility's timezone (DECISION-SHOPMGMT-015), and
-- every template that quotes a window names that zone through {zone} (#2139): a caller who sent
-- 09:00Z and is refused for "04:00-05:00" reads the bare converted time as a platform error rather
-- than as the facility-local conversion it is. {zone} renders as the IANA id the times are in.
--
-- MECHANIC_UNAVAILABLE states the question the rule actually asks -- whether an ACTIVE technician
-- staffing assignment covers that facility-local date -- rather than asserting nobody is in the
-- building (#2140). Its {reason} carries which of the two noes fired: no assignment at the location
-- at all, or assignments that exist and none effective on the date.
SET TIME ZONE 'UTC';

INSERT INTO public.conflict_rule (id, code, severity, resource_type, message_template, is_active)
SELECT md5('conflict_rule:' || r.code)::uuid, r.code, r.severity, r.resource_type, r.message_template, r.is_active
FROM (VALUES
    ('BAY_DOUBLE_BOOKED',             'HARD', 'BAY',
        'Bay {resource} is already booked for part of {start}–{end} {zone}.', true),
    ('MECHANIC_UNAVAILABLE',          'HARD', 'MECHANIC',
        'No technician staffing assignment covers {start}–{end} {zone} at this location{reason}.', true),
    ('MECHANIC_OVERTIME',             'SOFT', 'MECHANIC',
        'Booking {start}–{end} {zone} puts the assigned mechanic into overtime.', false),
    ('FACILITY_NEAR_CAPACITY',        'SOFT', 'CAPACITY',
        'The location is near capacity for {start}–{end} {zone}.', true),
    ('COMPETENT_MECHANIC_UNAVAILABLE','SOFT', 'SKILL',
        'A mechanic holding {skills} works at this location but none is free for {start}–{end} {zone}.', true),
    ('NO_COMPETENT_MECHANIC_ROSTERED','SOFT', 'SKILL',
        'No mechanic at this location holds {skills}.', true),
    ('OUTSIDE_OPERATING_HOURS',       'HARD', 'HOURS',
        '{start}–{end} {zone} falls outside the location''s operating hours for that day.', true),
    ('FACILITY_CLOSED',               'HARD', 'HOURS',
        'The location is closed on {date}{reason}.', true)
) AS r(code, severity, resource_type, message_template, is_active)
ON CONFLICT (code) DO UPDATE SET
    severity = EXCLUDED.severity,
    resource_type = EXCLUDED.resource_type,
    message_template = EXCLUDED.message_template,
    is_active = EXCLUDED.is_active;
