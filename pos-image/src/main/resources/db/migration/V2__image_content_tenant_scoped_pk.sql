-- ADR-0062: give image_content a tenant-scoped primary key.
--
-- The baseline keyed this table on the SHA-256 content hash alone. Every other rule in
-- docs/TENANCY_SCHEMA.md was applied correctly here -- tenant_id, row-level security enabled and
-- forced, the tenant_isolation policy, and a UNIQUE (tenant_id, content_hash) -- but the primary
-- key stayed global, because the schema rule "primary keys are unchanged (UUID v7, globally
-- unique)" assumes a surrogate key that no two tenants can ever produce. A content hash is the
-- opposite: it is a business key derived from the bytes, and two tenants storing the same picture
-- derive it identically.
--
-- Unique and primary key constraints are enforced across every row of the table regardless of
-- row-level security, so a global key over that value made the table cross-tenant in the one way
-- RLS cannot cover:
--
--   1. Tenant A stores a picture      -> row (A, H).
--   2. Tenant B stores the same bytes -> its existence check reads nothing (RLS hides A's row),
--                                        so it inserts, and the insert collides on H.
--   3. ImageStorageServiceImpl treats that collision as "stored concurrently, keep the existing
--      copy" -- true within a tenant, false here -- and writes B's image row anyway.
--   4. B's image now points at content B can never read. The upload reported success.
--
-- That is silent data loss for the second tenant, plus an existence oracle: the collision tells B
-- that somebody else holds byte-identical content.
--
-- Leading the key with tenant_id is the same rule every other unique constraint on a scoped table
-- already follows. The new key is strictly weaker than the old one, so every existing row already
-- satisfies it and no data can be lost by applying this.
ALTER TABLE public.image_content DROP CONSTRAINT image_content_pkey;

ALTER TABLE public.image_content
    ADD CONSTRAINT image_content_pkey PRIMARY KEY (tenant_id, content_hash);

-- image_content_tenant_key was UNIQUE (tenant_id, content_hash): the composite FK target that
-- scripts/db/tenancy/flatten.py adds beside a scoped table's primary key. The primary key above now
-- covers exactly those columns, so keeping it would maintain a second identical index on a table
-- holding image bytes. Dropped; a composite foreign key into this table can target the primary key.
ALTER TABLE public.image_content DROP CONSTRAINT image_content_tenant_key;
