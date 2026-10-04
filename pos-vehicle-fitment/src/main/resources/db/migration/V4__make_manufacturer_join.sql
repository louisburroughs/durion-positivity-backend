-- #2453: a vPIC make can belong to several manufacturers (Make_ID 482 is returned for more than one Mfr_ID).
-- make.manufacturer_id could hold one, so each manufacturer refresh took the shared make over from the last
-- one. Make is the brand and Manufacturer the corporate entity: one make row per vPIC Make_ID, linked to
-- every manufacturer vPIC (or a fitment request) pairs it with, through make_manufacturer.
--
-- Year stays on part_fitment_entity.vehicle_year: the vPIC lookup carries no year, so a link says only that a
-- manufacturer builds the make.
--
-- Tenancy: make and make_manufacturer are global reference tables (db/tenancy-global-tables.txt), so the
-- join carries no tenant_id and no policy.

CREATE TABLE public.make_manufacturer (
    make_id uuid NOT NULL,
    manufacturer_id uuid NOT NULL,
    CONSTRAINT make_manufacturer_pkey PRIMARY KEY (make_id, manufacturer_id),
    CONSTRAINT fk_make_manufacturer_make FOREIGN KEY (make_id) REFERENCES public.make(id) ON DELETE CASCADE,
    CONSTRAINT fk_make_manufacturer_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES public.manufacturer(id)
);

CREATE INDEX idx_make_manufacturer_manufacturer_id ON public.make_manufacturer USING btree (manufacturer_id);

-- Makes that did not come from vPIC were unique per manufacturer, so the same name can exist under several
-- manufacturers. The new unique name index needs one row per name: fold such rows into the lowest id, moving
-- their models, vehicle types and fitments, and dropping the child rows that then collide by name.
CREATE TEMPORARY TABLE make_fold ON COMMIT DROP AS
SELECT m.id AS dup_id, k.keep_id
FROM public.make m
JOIN (
    SELECT lower(name) AS lname, (array_agg(id ORDER BY id))[1] AS keep_id
    FROM public.make
    WHERE nhtsa_id IS NULL AND name IS NOT NULL
    GROUP BY lower(name)
    HAVING count(*) > 1
) k ON lower(m.name) = k.lname
WHERE m.nhtsa_id IS NULL AND m.id <> k.keep_id;

-- Copy the existing pairs: the make's own manufacturer, then any pair a fitment already uses.
INSERT INTO public.make_manufacturer (make_id, manufacturer_id)
SELECT DISTINCT COALESCE(f.keep_id, m.id), m.manufacturer_id
FROM public.make m
LEFT JOIN make_fold f ON f.dup_id = m.id
WHERE m.manufacturer_id IS NOT NULL;

UPDATE public.part_fitment_entity p
SET vehicle_make_id = f.keep_id
FROM make_fold f
WHERE p.vehicle_make_id = f.dup_id;

INSERT INTO public.make_manufacturer (make_id, manufacturer_id)
SELECT DISTINCT p.vehicle_make_id, p.vehicle_manufacturer_id
FROM public.part_fitment_entity p
WHERE p.vehicle_make_id IS NOT NULL AND p.vehicle_manufacturer_id IS NOT NULL
ON CONFLICT DO NOTHING;

UPDATE public.model x SET make_id = f.keep_id FROM make_fold f WHERE x.make_id = f.dup_id;
UPDATE public.vehicle_type x SET make_id = f.keep_id FROM make_fold f WHERE x.make_id = f.dup_id;

-- Models and vehicle types that now share a make and a name: keep the lowest id, repoint fitments.
CREATE TEMPORARY TABLE model_fold ON COMMIT DROP AS
SELECT x.id AS dup_id, k.keep_id
FROM public.model x
JOIN (
    SELECT make_id, lower(name) AS lname, (array_agg(id ORDER BY id))[1] AS keep_id
    FROM public.model
    WHERE make_id IS NOT NULL
    GROUP BY make_id, lower(name)
    HAVING count(*) > 1
) k ON k.make_id = x.make_id AND k.lname = lower(x.name)
WHERE x.id <> k.keep_id;
UPDATE public.part_fitment_entity p SET vehicle_model_id = f.keep_id FROM model_fold f WHERE p.vehicle_model_id = f.dup_id;
DELETE FROM public.model x USING model_fold f WHERE x.id = f.dup_id;

CREATE TEMPORARY TABLE vehicle_type_fold ON COMMIT DROP AS
SELECT x.id AS dup_id, k.keep_id
FROM public.vehicle_type x
JOIN (
    SELECT make_id, lower(vehicle_type_name) AS lname, (array_agg(id ORDER BY id))[1] AS keep_id
    FROM public.vehicle_type
    WHERE make_id IS NOT NULL
    GROUP BY make_id, lower(vehicle_type_name)
    HAVING count(*) > 1
) k ON k.make_id = x.make_id AND k.lname = lower(x.vehicle_type_name)
WHERE x.id <> k.keep_id;
UPDATE public.part_fitment_entity p SET vehicle_type_id = f.keep_id FROM vehicle_type_fold f WHERE p.vehicle_type_id = f.dup_id;
DELETE FROM public.vehicle_type x USING vehicle_type_fold f WHERE x.id = f.dup_id;

DELETE FROM public.make m USING make_fold f WHERE m.id = f.dup_id;

-- The single-manufacturer column, its foreign key and its indexes.
DROP INDEX public.ux_make_manufacturer_name_lower;
DROP INDEX public.ux_make_name_lower_no_manufacturer;
DROP INDEX public.idx_make_manufacturer_id;
ALTER TABLE public.make DROP CONSTRAINT fk_make_manufacturer;
ALTER TABLE public.make DROP COLUMN manufacturer_id;

-- One row per vPIC Make_ID; a make with no vPIC id is unique by name.
CREATE UNIQUE INDEX ux_make_nhtsa_id ON public.make USING btree (nhtsa_id) WHERE (nhtsa_id IS NOT NULL);
CREATE UNIQUE INDEX ux_make_name_lower_no_nhtsa_id ON public.make USING btree (lower((name)::text)) WHERE (nhtsa_id IS NULL);

-- A fitment may pair a manufacturer only with a make that manufacturer is linked to. MATCH SIMPLE (the
-- default): a fitment with no manufacturer or no make is not checked.
ALTER TABLE public.part_fitment_entity
    ADD CONSTRAINT fk_part_fitment_make_manufacturer FOREIGN KEY (vehicle_make_id, vehicle_manufacturer_id)
    REFERENCES public.make_manufacturer (make_id, manufacturer_id) MATCH SIMPLE;
