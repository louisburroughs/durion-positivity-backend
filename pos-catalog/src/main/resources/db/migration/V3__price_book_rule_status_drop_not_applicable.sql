-- price_book_rule.status carried a third value, NOT_APPLICABLE_MISSING_BASE, that nothing could
-- ever write. PriceBookServiceImpl sets ACTIVE on create and INACTIVE on deactivate — its only two
-- writes — PriceBookRuleEntity defaults to ACTIVE, and PriceBookRuleCreateRequestDto has no status
-- field, so no client can supply one. The value existed only here and on the published response
-- schema, where it told API consumers to handle a state the system cannot reach. Dropping it from
-- PriceBookRuleStatus makes this constraint the last place it is named, so it goes too.
--
-- No UPDATE precedes this. If a row somehow carries the value — a hand-written data fix, an import
-- straight into the table — this migration fails on that row rather than rewriting it, because the
-- correct replacement is a question about that data (is the rule in force, or retired?) and not one
-- this migration can answer. Resolve such rows deliberately, then re-run.

ALTER TABLE price_book_rule DROP CONSTRAINT price_book_rule_status_check;

ALTER TABLE price_book_rule
    ADD CONSTRAINT price_book_rule_status_check
    CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])));
