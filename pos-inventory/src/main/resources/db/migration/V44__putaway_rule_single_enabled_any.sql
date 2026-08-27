-- Enforce "at most one enabled ANY putaway rule" in the database (issue #1514, review follow-up).
--
-- The invariant was enforced only by a read-before-write check in PutawayRuleServiceImpl, which is
-- race-prone: two concurrent requests can both observe no enabled ANY rule and both create one. The
-- result is a rule an operator authored, saw accepted, and can never see used — ANY is the terminal
-- tier, so the first one the priority order reaches always wins and any second is unreachable by
-- construction. That is the same class of silent no-op as the dead `criteria` column this issue
-- removed, so it is worth closing properly rather than documenting.
--
-- A partial unique index (`... WHERE match_type = 'ANY' AND is_enabled`) is the natural fix, but H2
-- has no partial indexes and the dev profile runs Flyway on H2. Instead, a nullable guard column
-- holds a constant exactly when a rule is an enabled ANY rule and NULL otherwise, with a plain
-- UNIQUE constraint over it. Both H2 and PostgreSQL permit unlimited NULLs in a UNIQUE column, so
-- this constrains precisely the enabled-ANY rows and nothing else, on both engines.
--
-- The column is maintained by the entity's @PrePersist/@PreUpdate rather than by a trigger, so the
-- rule stays visible in the code that owns the invariant.

ALTER TABLE putaway_rule ADD COLUMN enabled_any_guard character varying(3);

-- Backfill. The winner is the rule the matcher would actually reach: lowest priority, then lowest
-- rule_id, matching PutawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc.
UPDATE putaway_rule
SET    enabled_any_guard = 'ANY'
WHERE  rule_id = (SELECT rule_id
                  FROM   putaway_rule
                  WHERE  match_type = 'ANY' AND is_enabled = TRUE
                  ORDER  BY priority ASC, rule_id ASC
                  LIMIT  1);

-- Any further enabled ANY rules were already unreachable — the matcher stops at the winner above —
-- so disabling them changes no routing decision and makes the stored data satisfy the constraint
-- being added. Failing the migration instead would block startup on a state the application itself
-- created and has always ignored.
UPDATE putaway_rule
SET    is_enabled = FALSE
WHERE  match_type = 'ANY'
  AND  is_enabled = TRUE
  AND  enabled_any_guard IS NULL;

ALTER TABLE putaway_rule
    ADD CONSTRAINT putaway_rule_single_enabled_any UNIQUE (enabled_any_guard);
