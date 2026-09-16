-- CAP-326 (durion#483): DECISION-SHOPMGMT-002's rule catalog. Eight rows -- the record's four, the
-- two SKILL rules #2035 settled (spec D10.1) and the two HOURS rules D18.1 ruled so a booking outside
-- hours or on a closed day has a rule to reference. The code is the API reason code verbatim: there
-- is no mapping table, so there is nothing for the two to drift between.
--
-- conflict_rule is platform-global (D18.2): no tenant binding here. Ids are md5-derived from the code
-- so reruns are deterministic; ON CONFLICT (code) lets a template or a severity be corrected by
-- editing this file. Column-targeted ON CONFLICT is permitted on a global table (TENANCY_SCHEMA.md).
--
-- Templates use {placeholders} rendered by the enforcement tier: {resource}, {start}, {end}, {date},
-- {reason}, {skills}. Times render in the facility's timezone (DECISION-SHOPMGMT-015).
SET TIME ZONE 'UTC';

INSERT INTO public.conflict_rule (id, code, severity, resource_type, message_template, is_active)
SELECT md5('conflict_rule:' || r.code)::uuid, r.code, r.severity, r.resource_type, r.message_template, true
FROM (VALUES
    ('BAY_DOUBLE_BOOKED',             'HARD', 'BAY',
        'Bay {resource} is already booked for part of {start}–{end}.'),
    ('MECHANIC_UNAVAILABLE',          'HARD', 'MECHANIC',
        'No mechanic is present at this location for {start}–{end}.'),
    ('MECHANIC_OVERTIME',             'SOFT', 'MECHANIC',
        'Booking {start}–{end} puts the assigned mechanic into overtime.'),
    ('FACILITY_NEAR_CAPACITY',        'SOFT', 'CAPACITY',
        'The location is near capacity for {start}–{end}.'),
    ('COMPETENT_MECHANIC_UNAVAILABLE','SOFT', 'SKILL',
        'A mechanic holding {skills} works at this location but none is free for {start}–{end}.'),
    ('NO_COMPETENT_MECHANIC_ROSTERED','SOFT', 'SKILL',
        'No mechanic at this location holds {skills}.'),
    ('OUTSIDE_OPERATING_HOURS',       'HARD', 'HOURS',
        '{start}–{end} falls outside the location''s operating hours for that day.'),
    ('FACILITY_CLOSED',               'HARD', 'HOURS',
        'The location is closed on {date}{reason}.')
) AS r(code, severity, resource_type, message_template)
ON CONFLICT (code) DO UPDATE SET
    severity = EXCLUDED.severity,
    resource_type = EXCLUDED.resource_type,
    message_template = EXCLUDED.message_template;
