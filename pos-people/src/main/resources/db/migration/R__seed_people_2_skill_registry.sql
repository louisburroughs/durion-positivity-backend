-- CAP-328 (durion#485, spec §7.3): the skill registry. The twelve codes the alpha fixture holds
-- plus the four real ASE Automobile certifications it omitted (A1, A2, A3, A7), so the light-duty
-- column is not structurally empty, plus DOT-INSPECTOR -- a 49 CFR 396.19 annual-inspector
-- qualification distinct from PMI competence, with no ASE cross-reference (spec D10, #2035 answer 4).
--
-- Class ranges per spec D13: ASE Automobile (A-series) certifies classes 1-3, Medium/Heavy Truck
-- (T-series) classes 4-8. Titles are ASE's own. Both tables are platform-global: no tenant binding.
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT lets a title or
-- range be corrected by editing this file.
SET TIME ZONE 'UTC';

INSERT INTO public.skill (id, code, name, competence_code, min_gvwr_class, max_gvwr_class, active, created_at, updated_at)
SELECT md5('skill:' || s.code)::uuid, s.code, s.name, s.competence_code, s.min_class, s.max_class, true, NOW(), NOW()
FROM (VALUES
    -- ASE Automobile & Light Truck (A1-A8): classes 1-3
    ('ENGINE-REPAIR-LIGHT',          'Engine Repair (light duty)',                          'ENGINE_REPAIR',        1, 3),
    ('AUTO-TRANSMISSION-LIGHT',      'Automatic Transmission/Transaxle (light duty)',       'AUTO_TRANSMISSION',    1, 3),
    ('MANUAL-DRIVETRAIN-LIGHT',      'Manual Drive Train & Axles (light duty)',             'MANUAL_DRIVETRAIN',    1, 3),
    ('SUSPENSION-STEERING-LIGHT',    'Suspension & Steering (light duty)',                  'SUSPENSION_STEERING',  1, 3),
    ('BRAKES-LIGHT',                 'Brakes (light duty)',                                 'BRAKES',               1, 3),
    ('ELECTRICAL-LIGHT',             'Electrical/Electronic Systems (light duty)',          'ELECTRICAL',           1, 3),
    ('HVAC-LIGHT',                   'Heating & Air Conditioning (light duty)',             'HVAC',                 1, 3),
    ('ENGINE-PERFORMANCE-LIGHT',     'Engine Performance (light duty)',                     'ENGINE_PERFORMANCE',   1, 3),
    -- ASE Medium/Heavy Truck (T1-T8): classes 4-8
    ('GAS-ENGINES-MEDIUM_HEAVY',     'Gasoline Engines (medium/heavy truck)',               'GAS_ENGINES',          4, 8),
    ('DIESEL-ENGINES-MEDIUM_HEAVY',  'Diesel Engines (medium/heavy truck)',                 'DIESEL_ENGINES',       4, 8),
    ('DRIVE-TRAIN-MEDIUM_HEAVY',     'Drive Train (medium/heavy truck)',                    'DRIVE_TRAIN',          4, 8),
    ('BRAKES-MEDIUM_HEAVY',          'Brakes (medium/heavy truck)',                         'BRAKES',               4, 8),
    ('SUSPENSION-STEERING-MEDIUM_HEAVY', 'Suspension & Steering (medium/heavy truck)',      'SUSPENSION_STEERING',  4, 8),
    ('ELECTRICAL-MEDIUM_HEAVY',      'Electrical/Electronic Systems (medium/heavy truck)',  'ELECTRICAL',           4, 8),
    ('HVAC-MEDIUM_HEAVY',            'Heating, Ventilation & A/C (medium/heavy truck)',     'HVAC',                 4, 8),
    ('PMI-MEDIUM_HEAVY',             'Preventive Maintenance Inspection (medium/heavy truck)', 'PMI',               4, 8),
    -- Regulatory, not ASE: a qualified annual inspector under 49 CFR 396.19. Any commercial class.
    ('DOT-INSPECTOR',                'DOT Annual Inspector (49 CFR 396.19)',                'DOT_INSPECTION',       1, 8)
) AS s(code, name, competence_code, min_class, max_class)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    competence_code = EXCLUDED.competence_code,
    min_gvwr_class = EXCLUDED.min_gvwr_class,
    max_gvwr_class = EXCLUDED.max_gvwr_class;

-- ASE test codes as the fixture and HR spell them, onto the rows above. DOT-INSPECTOR has none.
INSERT INTO public.skill_code_xref (id, skill_id, source_code, source_skill_code, created_at, updated_at)
SELECT md5('skill_code_xref:ASE:' || x.source_skill_code)::uuid, md5('skill:' || x.skill_code)::uuid, 'ASE', x.source_skill_code, NOW(), NOW()
FROM (VALUES
    ('A1-ENGINE-REPAIR',  'ENGINE-REPAIR-LIGHT'),
    ('A2-AUTO-TRANS',     'AUTO-TRANSMISSION-LIGHT'),
    ('A3-MANUAL-DRIVE',   'MANUAL-DRIVETRAIN-LIGHT'),
    ('A4-SUSPENSION',     'SUSPENSION-STEERING-LIGHT'),
    ('A5-BRAKES',         'BRAKES-LIGHT'),
    ('A6-ELECTRICAL',     'ELECTRICAL-LIGHT'),
    ('A7-HVAC',           'HVAC-LIGHT'),
    ('A8-ENGINE-PERF',    'ENGINE-PERFORMANCE-LIGHT'),
    ('T1-GAS-ENGINE',     'GAS-ENGINES-MEDIUM_HEAVY'),
    ('T2-DIESEL-ENGINE',  'DIESEL-ENGINES-MEDIUM_HEAVY'),
    ('T3-DRIVE-TRAIN',    'DRIVE-TRAIN-MEDIUM_HEAVY'),
    ('T4-BRAKES',         'BRAKES-MEDIUM_HEAVY'),
    ('T5-STEERING',       'SUSPENSION-STEERING-MEDIUM_HEAVY'),
    ('T6-ELECTRICAL',     'ELECTRICAL-MEDIUM_HEAVY'),
    ('T7-HVAC',           'HVAC-MEDIUM_HEAVY'),
    ('T8-PMI',            'PMI-MEDIUM_HEAVY')
) AS x(source_skill_code, skill_code)
ON CONFLICT (source_code, source_skill_code) DO UPDATE SET skill_id = EXCLUDED.skill_id;
