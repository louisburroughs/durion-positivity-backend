-- ADR-0062 section 6 (plan WS2b part 2): roles provisioned from the platform role template carry
-- the template key they were copied from. Such a role keeps its canonical name for the life of the
-- tenant (the frontend gates navigation on role names, ADR-0040 section 6) and rejects delete;
-- a tenant may still change its grants and add custom roles (template_key NULL).
ALTER TABLE roles ADD COLUMN template_key character varying(255);

COMMENT ON COLUMN roles.template_key IS
    'ADR-0062 section 6: key of the platform template role this row was provisioned from (its canonical name); NULL for a custom role. Template roles reject delete.';
