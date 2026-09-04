-- V46: register GlossaryFacadeTool (issue #1688).
--
-- The assistant had no rule for when a clarifying question is the right answer. It decided case by
-- case: sometimes answering "who are our best customers?" on a silently chosen metric, sometimes
-- asking about a date range the DATE_WINDOW contract already defaults (#1681). Both are wrong, in
-- opposite directions, and neither was visible in a trace.
--
-- The decided rule is: ask when the METRIC is undefined, answer when only the RANGE is unstated.
-- That rule is only usable if "defined" means something checkable, so the agreed definitions now
-- live in a versioned catalog (BusinessGlossary) behind one facade method, lookupBusinessTerm.
-- Like V43's DateWindowFacadeTool this is a genuinely new mcp_tool row — the class did not exist
-- before this migration.
--
-- ─── mcp_tool row ──────────────────────────────────────────────────────────────────────────
-- GlossaryFacadeTool makes no HTTP call (an in-process map lookup), so avg_latency_ms matches
-- V43's DateWindowFacadeTool rather than the downstream-calling facades. priority 1.0 for the same
-- reason V43 uses it: any question phrased in business language needs this tool before it can be
-- answered correctly, so it should rank near the top of candidate selection. embedding is left NULL
-- (V4 precedent: ToolEmbeddingInitializer derives and backfills it from the description on next
-- startup).
--
-- ─── Permission group (V40 AND-group semantics, rule R4) ─────────────────────────────────────
-- lookupBusinessTerm returns definitions, not data. It reads no customer, invoice or workorder row
-- and calls no downstream endpoint, so it enforces no permission of its own: any authenticated
-- caller who may ask the question may resolve the term in it. That is R4's shape — a tool whose
-- only guard is the AUTHENTICATED sentinel keeps a single group named 'AUTHENTICATED' — the same
-- shape V43 carries.
--
-- ─── Workflow availability ─────────────────────────────────────────────────────────────────
-- Linked to every mcp_workflow_state row: a business term can appear in a question asked from any
-- workflow state.
--
-- ─── Method → endpoint evidence ────────────────────────────────────────────────────────────
-- pos-mcp-server/src/test/resources/facade-contract.yaml, GlossaryFacadeTool.lookupBusinessTerm
-- (verb: NONE — no HTTP call, same form as DateWindowFacadeTool.resolveDateWindow).
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V34/V38/V39/V40/V41/V42/V43: the tool-registry tables exist only in the Postgres
-- chain (H2 V27 header). Nothing to mirror.
-- ═════════════════════════════════════════════════════════════════════════════════════════

WITH tools(
  name,
  display_name,
  description,
  domain,
  priority,
  cost_level,
  avg_latency_ms,
  enabled,
  handler_bean
) AS (
  VALUES (
      'GlossaryFacadeTool',
      'Business Glossary',
      'Look up the agreed business definition of an analytical phrase ("best customers", "payment '
        || 'problems", "running low", "backed up in the shop", "most productive technicians"). Call '
        || 'before answering any question whose metric is a business term rather than a plain field: '
        || 'returns the agreed metric and default window to apply, or reports that the metric has no '
        || 'agreed definition and must be clarified with the user.',
      'glossary',
      1.0,
      'low',
      5,
      TRUE,
      'glossaryFacadeTool'
    )
)
INSERT INTO mcp_tool (
    name,
    display_name,
    description,
    domain,
    priority,
    cost_level,
    avg_latency_ms,
    enabled,
    handler_bean
  )
SELECT name,
  display_name,
  description,
  domain,
  priority,
  cost_level,
  avg_latency_ms,
  enabled,
  handler_bean
FROM tools ON CONFLICT DO NOTHING;

INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
SELECT t.id, ws.id
FROM mcp_tool t
  CROSS JOIN mcp_workflow_state ws
WHERE t.name = 'GlossaryFacadeTool'
ON CONFLICT DO NOTHING;

INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED', 'AUTHENTICATED')
) AS perms(grp, code)
WHERE mcp_tool.name = 'GlossaryFacadeTool'
ON CONFLICT DO NOTHING;
