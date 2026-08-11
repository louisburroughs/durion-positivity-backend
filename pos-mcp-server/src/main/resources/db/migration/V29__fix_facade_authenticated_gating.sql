-- V29: Remove the AUTHENTICATED grant from facade tools that also bundle privileged operations.
--
-- Issue #1115. V18 seeds each facade the UNION of the permissions of every operation it wraps, and an
-- operation gated only by isAuthenticated() (or ungated) contributes the pseudo-permission
-- AUTHENTICATED. Every logged-in caller holds AUTHENTICATED, so any facade whose union includes it
-- passes the selection-layer permission gate (findEnabledByPermissionsAndWorkflow) for EVERY
-- authenticated user — including the facade's privileged operations.
--
-- Three facades in V18 carry AUTHENTICATED alongside privileged codes. Issue #1115 enumerated two;
-- TaxFacadeTool is a third instance of the same union-floor defect, found by reading the V18 grants:
--   * WorkorderFacadeTool  — AUTHENTICATED (from getWorkorderStatus) + workorder:workorder:view
--   * AdminFacadeTool       — AUTHENTICATED (from getSystemStatus)   + security:{user,permission,audit}:view
--   * TaxFacadeTool         — AUTHENTICATED (from getTaxRate/getTaxSummary, whose endpoints do not
--                             resolve so V18 fell back to AUTHENTICATED) + tax:calculate
--
-- Fix (issue option 1): drop only the AUTHENTICATED row for these three, so each is gated on its
-- privileged code(s) — workorder:workorder:view, the security:*:view set, and tax:calculate
-- respectively. The benign single-purpose AUTHENTICATED facades (Order, Pricing, Catalog, Events) are
-- untouched — every operation they wrap is genuinely isAuthenticated()-level, so their AUTHENTICATED
-- grant is correct.
--
-- Consequence (accepted): the isAuthenticated()-level operations on these facades (getWorkorderStatus,
-- getSystemStatus, getTaxRate/getTaxSummary) are no longer offered to a bare authenticated user
-- through the facade. This is not a downstream authorization change — the gateway still enforces
-- per-operation authorization on the REST call — only which facades the tool-selection layer offers
-- the model. Splitting the low-privilege operations into their own tool (issue option 2) is a
-- separate follow-up.
--
-- Idempotent: DELETE on a code that is not present is a no-op, so re-running is safe.

DELETE FROM mcp_tool_permission
WHERE permission_code = 'AUTHENTICATED'
  AND tool_id IN (
      SELECT id FROM mcp_tool WHERE name IN ('WorkorderFacadeTool', 'AdminFacadeTool', 'TaxFacadeTool')
  );
