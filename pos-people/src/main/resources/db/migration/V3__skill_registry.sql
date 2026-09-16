-- CAP-328 (durion#485, spec D2, D8, D13, §4.4): the skill registry and the vendor cross-reference.
--
-- A skill is reference data -- a code, a name, the competence it names and the GVWR class range it
-- applies to. It is platform-global (spec D2, precedent VehicleType): an ASE certification is issued
-- by a national body and means the same thing in every shop, so a per-tenant vocabulary would let
-- two tenants disagree about what T4-BRAKES certifies. No tenant_id, no RLS; both tables are listed
-- in db/tenancy-global-tables.txt. Nothing can hold a skill that is not in this table.
--
-- One row per (competence, class range): BRAKES-LIGHT (classes 1-3) and BRAKES-MEDIUM_HEAVY (4-8)
-- are two rows, because ASE certifies them separately (A5 and T4). The range is data, not a token
-- (spec D13): the LIGHT/MEDIUM line sits at class 4 because that is where ASE's T-series begins,
-- and moving it later is a data change here rather than a decode change anywhere.
--
-- Vendor codes map onto Durion codes through skill_code_xref, never the reverse (ADR-0059 §3,
-- precedent service_operation_xref): HR keeps sending 'A5-BRAKES' forever, and an unresolvable
-- source code fails the ingest loudly rather than becoming a skill nobody holds.
CREATE TABLE public.skill (
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    competence_code character varying(64) NOT NULL,
    min_gvwr_class smallint NOT NULL,
    max_gvwr_class smallint NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT skill_pkey PRIMARY KEY (id),
    CONSTRAINT skill_code_key UNIQUE (code),
    CONSTRAINT skill_gvwr_class_range_check
        CHECK (min_gvwr_class BETWEEN 1 AND 8 AND max_gvwr_class BETWEEN 1 AND 8 AND min_gvwr_class <= max_gvwr_class)
);
COMMENT ON TABLE public.skill IS
    'CAP-328 skill registry: platform reference data, one row per (competence, GVWR class range). Seeded by R__seed_people_2_skill_registry.sql.';
COMMENT ON COLUMN public.skill.min_gvwr_class IS
    'Lowest FHWA GVWR class the skill certifies work on (spec D13). ASE A-series rows are 1-3, T-series 4-8.';

CREATE TABLE public.skill_code_xref (
    id uuid NOT NULL,
    skill_id uuid NOT NULL,
    source_code character varying(32) NOT NULL,
    source_skill_code character varying(128) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT skill_code_xref_pkey PRIMARY KEY (id),
    CONSTRAINT skill_code_xref_source_key UNIQUE (source_code, source_skill_code),
    CONSTRAINT fk_skill_code_xref_skill FOREIGN KEY (skill_id) REFERENCES public.skill(id)
);
CREATE INDEX skill_code_xref_skill_idx ON public.skill_code_xref USING btree (skill_id);
COMMENT ON TABLE public.skill_code_xref IS
    'Vendor credential codes (source_code, e.g. ASE) cross-referenced onto skill rows (ADR-0059 §3). Lookups normalise to upper-case and trim; stored values are already so.';
