-- Putaway rule match criteria (issue #1514).
--
-- WHAT WAS BROKEN. `criteria` was a TEXT column holding a JSON blob that no production code has
-- ever read. PutawayGenerationServiceImpl took findAllByIsEnabledTrueOrderByPriorityAsc() and used
-- get(0), so exactly one rule won for every line of every receipt regardless of what was being put
-- away. The seeded row's `{"sku_prefix":"OIL-"}` therefore never restricted anything: that rule was
-- an unconditional catch-all in practice.
--
-- WHAT REPLACES IT. A structured two-column key the matcher actually evaluates:
--   match_type   SKU | SUBCATEGORY | CATEGORY | ANY
--   match_value  productId / subcategoryId / categoryId, and NULL for ANY
-- Rules are resolved per line item in strict precedence SKU > SUBCATEGORY > CATEGORY > ANY, lowest
-- `priority` winning inside a tier. SUBCATEGORY has to outrank CATEGORY because `Batteries` is a
-- subcategory of `Electrical System`: a category-only key cannot express the containment the
-- narrower level carries.
--
-- `criteria` is DROPPED rather than deprecated in place, per the platform's pre-production policy
-- (no back-compat shims). Verified before writing this: no production code reads the column — the
-- only references anywhere were the entity field, the seed INSERT, the baseline DDL, an archived
-- pre-baseline migration and the ERD doc. Nothing parses it, so nothing can regress.
--
-- DATA MIGRATION. Every pre-#1514 row is translated to ANY with a NULL value. That is
-- behaviour-preserving, not a guess: because `criteria` was never evaluated, an existing enabled
-- rule already applied unconditionally to every line. ANY is what it was already doing, now stated
-- honestly — and an enabled ANY rule is exactly the terminal fallback that replaces the removed
-- hardcoded DEFAULT_LOCATION, so a brand-new uncategorised SKU still lands somewhere instead of
-- being routed at a fabricated UUID.
--
-- Written to be H2(MODE=PostgreSQL)- and PostgreSQL-compatible: one column per ALTER, no
-- IF NOT EXISTS, and the CHECK constraints use the `IN (...)` form rather than the Postgres-dump
-- `(col)::text = ANY ((ARRAY[...])::text[])` idiom that V1__baseline_inventory_schema.sql:63
-- carries — that idiom is a syntax error on H2, and the `dev` profile runs H2. Same deviation, and
-- the same reason, as pos-location's V8__storage_location_capability.sql.
-- PutawayRuleMatchCriteriaMigrationTest runs this file against H2 to keep that true.

ALTER TABLE putaway_rule ADD COLUMN match_type character varying(20);

ALTER TABLE putaway_rule ADD COLUMN match_value character varying(128);

-- Behaviour-preserving translation of every existing row (see DATA MIGRATION above).
UPDATE putaway_rule SET match_type = 'ANY', match_value = NULL WHERE match_type IS NULL;

ALTER TABLE putaway_rule ALTER COLUMN match_type SET NOT NULL;

ALTER TABLE putaway_rule DROP COLUMN criteria;

ALTER TABLE putaway_rule ADD CONSTRAINT putaway_rule_match_type_check
    CHECK (match_type IN ('SKU', 'SUBCATEGORY', 'CATEGORY', 'ANY'));

-- A typed rule without a value matches nothing; an ANY rule with a value implies a restriction the
-- matcher does not apply. Both are silent misconfiguration, so the database refuses them outright.
ALTER TABLE putaway_rule ADD CONSTRAINT putaway_rule_match_value_check
    CHECK ((match_type = 'ANY' AND match_value IS NULL)
        OR (match_type <> 'ANY' AND match_value IS NOT NULL));

-- The matcher reads enabled rules by tier and value, ordered by priority.
CREATE INDEX idx_putaway_rule_match ON putaway_rule (match_type, match_value);
