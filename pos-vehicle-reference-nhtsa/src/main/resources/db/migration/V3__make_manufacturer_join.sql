-- #2471: a vPIC make can belong to several manufacturers (Make_ID 482 is returned for more than one Mfr_ID).
-- make.manufacturer_id could hold one, so each manufacturer refresh took the shared make over from the last one.
-- Make is the brand and Manufacturer the corporate entity: one make row per vPIC Make_ID, linked to every
-- manufacturer vPIC pairs it with, through make_manufacturer (same decision as pos-vehicle-fitment, #2453).
--
-- Tenancy: make and make_manufacturer are global reference tables (db/tenancy-global-tables.txt), so the join
-- carries no tenant_id and no policy.

CREATE TABLE public.make_manufacturer (
    make_id uuid NOT NULL,
    manufacturer_id uuid NOT NULL,
    CONSTRAINT make_manufacturer_pkey PRIMARY KEY (make_id, manufacturer_id),
    CONSTRAINT fk_make_manufacturer_make FOREIGN KEY (make_id) REFERENCES public.make(id) ON DELETE CASCADE,
    CONSTRAINT fk_make_manufacturer_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES public.manufacturer(id)
);

CREATE INDEX idx_make_manufacturer_manufacturer_id ON public.make_manufacturer USING btree (manufacturer_id);

-- When this manufacturer's make list was last refreshed from vPIC. Per manufacturer: a make shared with another
-- manufacturer carries one cache_timestamp, so either's refresh would make the other look fresh. NULL until the
-- next refresh, which is one vPIC call per manufacturer.
ALTER TABLE public.manufacturer ADD COLUMN makes_refreshed_at timestamp without time zone;

-- Makes without a vPIC id (cached before #2454) could repeat a name under several manufacturers. The unique name
-- index below needs one row per name: fold such rows into the lowest id. NULL names are left alone.
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

-- Copy the existing pairs, pointing a folded make's pair at the survivor.
INSERT INTO public.make_manufacturer (make_id, manufacturer_id)
SELECT DISTINCT COALESCE(f.keep_id, m.id), m.manufacturer_id
FROM public.make m
LEFT JOIN make_fold f ON f.dup_id = m.id
WHERE m.manufacturer_id IS NOT NULL;

-- Models and vehicle types that would repeat under the survivor (same name and same vPIC id) are merged BEFORE
-- they move: keep the lowest id, delete the others. Nothing in this module references them.
CREATE TEMPORARY TABLE model_fold ON COMMIT DROP AS
SELECT g.id AS dup_id
FROM (
    SELECT x.id,
           row_number() OVER (
               PARTITION BY COALESCE(f.keep_id, x.make_id), lower(x.name), x.nhtsa_id ORDER BY x.id) AS rn
    FROM public.model x
    LEFT JOIN make_fold f ON f.dup_id = x.make_id
    WHERE x.make_id IS NOT NULL AND x.name IS NOT NULL
) g
WHERE g.rn > 1;
DELETE FROM public.model x USING model_fold f WHERE x.id = f.dup_id;
UPDATE public.model x SET make_id = f.keep_id FROM make_fold f WHERE x.make_id = f.dup_id;

CREATE TEMPORARY TABLE vehicle_type_fold ON COMMIT DROP AS
SELECT g.id AS dup_id
FROM (
    SELECT x.id,
           row_number() OVER (
               PARTITION BY COALESCE(f.keep_id, x.make_id), lower(x.vehicle_type_name), x.vehicle_type_id
               ORDER BY x.id) AS rn
    FROM public.vehicle_type x
    LEFT JOIN make_fold f ON f.dup_id = x.make_id
    WHERE x.make_id IS NOT NULL AND x.vehicle_type_name IS NOT NULL
) g
WHERE g.rn > 1;
DELETE FROM public.vehicle_type x USING vehicle_type_fold f WHERE x.id = f.dup_id;
UPDATE public.vehicle_type x SET make_id = f.keep_id FROM make_fold f WHERE x.make_id = f.dup_id;

DELETE FROM public.make m USING make_fold f WHERE m.id = f.dup_id;

-- The single-manufacturer column, its foreign key and its index.
DROP INDEX public.idx_make_manufacturer_id;
ALTER TABLE public.make DROP CONSTRAINT fk_make_manufacturer;
ALTER TABLE public.make DROP COLUMN manufacturer_id;

-- One row per vPIC Make_ID; a make with no vPIC id is unique by name.
CREATE UNIQUE INDEX ux_make_nhtsa_id ON public.make USING btree (nhtsa_id) WHERE (nhtsa_id IS NOT NULL);
CREATE UNIQUE INDEX ux_make_name_lower_no_nhtsa_id ON public.make USING btree (lower((name)::text)) WHERE (nhtsa_id IS NULL);
