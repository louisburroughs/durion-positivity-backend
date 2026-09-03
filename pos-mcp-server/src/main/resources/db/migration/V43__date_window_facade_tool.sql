-- V43: register DateWindowFacadeTool (issue #1675, plan A1 of
-- docs/gate-closeout-plan-1660-1676.md).
--
-- Three rounds of prompt-only arithmetic (#1664, #1670, #1672) left multi-period calendar spans
-- unreliable: q09 "in the last twelve months" resolved rolling instead of calendar, q12/q15
-- likewise, while single-period questions (q01 "last month", q03 "this quarter") already worked.
-- The window for a question and a "today" is a pure function, so it moved to code
-- (DateWindowResolver, pure java.time) behind one new facade method, resolveDateWindow. This is
-- a genuinely new mcp_tool row (unlike V39/V42, which added methods to existing classes) — the
-- class did not exist before this migration.
--
-- ─── mcp_tool row ──────────────────────────────────────────────────────────────────────────
-- DateWindowFacadeTool makes no HTTP call (pure in-process arithmetic), hence the low
-- avg_latency_ms relative to every other facade row (all of which call a downstream service).
-- priority 1.0 matches the other foundational facades (Inventory, Order) — every dated question
-- needs this tool, so it should rank near the top of candidate selection. embedding is left NULL
-- (V4 precedent: new rows never carry a literal embedding vector; ToolEmbeddingInitializer
-- derives and backfills it from the description on next startup, same as every re-embedded row
-- since V11/V13/V39/V42).
--
-- ─── Permission group (V40 AND-group semantics, rule R4) ─────────────────────────────────────
-- resolveDateWindow calls no downstream endpoint and enforces no permission of its own — any
-- authenticated caller who can ask a dated question may resolve a date window for it. That is
-- exactly R4's shape (a tool whose only guard is the AUTHENTICATED sentinel keeps a single group
-- named 'AUTHENTICATED'), the same shape EventsFacadeTool has carried since V37/V40.
--
-- ─── Workflow availability ─────────────────────────────────────────────────────────────────
-- Linked to every mcp_workflow_state row (not just IDLE): a dated question can arrive in any
-- workflow state, and the tool must be a selection candidate wherever a dated tool call would be.
--
-- ─── Method → endpoint evidence ────────────────────────────────────────────────────────────
-- pos-mcp-server/src/test/resources/facade-contract.yaml, DateWindowFacadeTool.resolveDateWindow
-- (verb: NONE — no HTTP call, same form as AdminFacadeTool.getSystemStatus, V4/facade-contract
-- header).
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V34/V38/V39/V40/V41/V42: the tool-registry tables exist only in the Postgres
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
      'DateWindowFacadeTool',
      'Date Window',
      'Resolve a relative date range ("last month", "in the last six months", "this quarter", '
        || '"over the last twelve months compared with the same twelve months last year") to '
        || 'concrete calendar dates. Call this before any other tool argument that takes a date '
        || 'or a date range, then copy its startDate/endDate into that tool''s arguments verbatim '
        || 'and quote its statement in the answer.',
      'date-window',
      1.0,
      'low',
      5,
      TRUE,
      'dateWindowFacadeTool'
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
WHERE t.name = 'DateWindowFacadeTool'
ON CONFLICT DO NOTHING;

INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED', 'AUTHENTICATED')
) AS perms(grp, code)
WHERE mcp_tool.name = 'DateWindowFacadeTool'
ON CONFLICT DO NOTHING;
