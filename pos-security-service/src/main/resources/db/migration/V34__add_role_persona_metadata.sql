-- Issue #1613: persona metadata becomes an attribute of the role, so pos-mcp-server can derive
-- every role-keyed prompt artifact from data instead of a hardcoded Java list.
--
-- Structured slots only (D1/D9 control 1): pos-mcp-server keeps the rolePersona(title, focus, tone)
-- template and renders these into it. Storing prompt *text* here would make every prompt wording
-- change a pos-security-service release, and would let a role author inject instructions into the
-- assembled prompt. Column widths mirror the validation caps enforced by @PersonaText.
ALTER TABLE roles
ADD COLUMN IF NOT EXISTS persona_title VARCHAR(60);

ALTER TABLE roles
ADD COLUMN IF NOT EXISTS persona_focus VARCHAR(200);

ALTER TABLE roles
ADD COLUMN IF NOT EXISTS persona_tone VARCHAR(120);

-- Resolution priority (D2). NULL sorts after every ranked role but still ahead of the ROLE_USER
-- fallback, so a role created without a rank still gets its own persona rather than the generic one.
ALTER TABLE roles
ADD COLUMN IF NOT EXISTS mcp_persona_rank SMALLINT;

-- Issue #1613 decision 2: roles with no MCP access are excluded from persona resolution by design.
-- Distinguishing "ineligible" from "missing" is what lets pos-mcp-server stop counting a designed
-- state as a sync failure in mcp.prompt.fallback.
ALTER TABLE roles
ADD COLUMN IF NOT EXISTS mcp_persona_eligible BOOLEAN NOT NULL DEFAULT TRUE;

-- Ranked lookups order by (mcp_persona_rank, name) over eligible roles only.
CREATE INDEX IF NOT EXISTS idx_roles_persona_rank
    ON roles (mcp_persona_rank, name)
    WHERE mcp_persona_eligible;
