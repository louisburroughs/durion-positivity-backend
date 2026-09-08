-- V48: re-derive HrFacadeTool after the employee-PII permission split (issue #1898).
--
-- pos-people moved GET /v1/people/employees/{employeeId} off people:employee:view and onto the
-- narrower people:employee_pii:view, because that read is the only one returning
-- EmployeeProfileDto.contactInfo — home address, personal phone numbers and emergency contact —
-- while people:employee:view is seeded to twelve roles including TECHNICIAN and SERVICE_ADVISOR.
--
-- HrFacadeTool.getEmployee fronts exactly that endpoint, so its permission group has to move with
-- it. Left alone, the selection layer would keep offering getEmployee to every holder of
-- people:employee:view and the downstream call would 403 — the gate here is a pre-filter over the
-- real guard, not a second guard, so a stale group costs a wasted turn rather than a disclosure.
-- The other two methods are unchanged: searchEmployees returns EmployeeSummaryDto, which carries
-- no contact block, and getEmployeeSchedule reads availability.
--
-- Net effect on who reaches the tool: nobody loses HrFacadeTool, because searchEmployees still
-- qualifies on people:employee:view and a group is satisfied independently. What changes is that
-- getEmployee is no longer part of what a technician-level caller is offered.
--
-- ─── Why the description does not change ───────────────────────────────────────────────────────
-- Unlike V42/V44/V47 this adds no capability: the tool's methods and what they answer are
-- identical, so mcp_tool.description and its embedding stay as they are.
--
-- ─── H2 twin ───────────────────────────────────────────────────────────────────────────────────
-- None, matching V38-V44 and V46-V47: the tool-registry tables exist only in the Postgres chain.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

-- Full delete then re-seed, the V40/V41 shape, so the derivation is replayable rather than
-- incrementally patched.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'HrFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getEmployee',         'people:employee_pii:view'),
    ('getEmployeeSchedule', 'people:availability:view'),
    ('searchEmployees',     'people:employee:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'HrFacadeTool'
ON CONFLICT DO NOTHING;
