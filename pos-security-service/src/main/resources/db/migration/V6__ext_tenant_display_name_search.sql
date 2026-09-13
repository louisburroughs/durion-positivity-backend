-- The login form lets a user find their organization by name instead of typing a tenant slug
-- (ADR-0062 §3). display_name_key is the normalized form the search matches on: NFKC, internal
-- whitespace collapsed, trimmed, case-folded — the same key pos-tenant stores on the registry row,
-- produced by the one shared normalizer (pos-tenancy-common TenantDisplayName), so the two can
-- never drift apart and leave a tenant unfindable.
--
-- ext_tenant is a global table (no tenant_id, no policy): the search runs before any tenant is
-- bound, exactly as slug resolution does.

ALTER TABLE public.ext_tenant
    ADD COLUMN display_name_key character varying(200);

UPDATE public.ext_tenant
   SET display_name_key = lower(btrim(regexp_replace(normalize(display_name, NFKC), '\s+', ' ', 'g')))
 WHERE display_name IS NOT NULL;

-- text_pattern_ops so the anchored LIKE prefix the search uses can be served by the index. Search
-- matches a prefix of the whole name or of any word in it, never an unanchored substring.
CREATE INDEX idx_ext_tenant_display_name_key
    ON public.ext_tenant USING btree (display_name_key text_pattern_ops);
