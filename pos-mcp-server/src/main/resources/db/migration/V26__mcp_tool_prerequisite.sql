-- Answer resolution ladder, rung 2: tool prerequisite edges.
-- Some tools need a parameter the user did not supply (e.g. workorder_listwip needs
-- locationId). Today that dependency is implicit, so the model guesses or stalls.
-- These edges make it explicit: a required_param can be produced by calling producing_tool
-- and reading producing_field off its result. Resolution is bounded to one hop.

CREATE TABLE IF NOT EXISTS mcp_tool_prerequisite (
    tool_name       VARCHAR(150) NOT NULL,   -- consuming tool, e.g. 'workorder_listwip'
    required_param  VARCHAR(120) NOT NULL,   -- param it needs, e.g. 'locationId'
    producing_tool  VARCHAR(150) NOT NULL,   -- tool that can supply it
    producing_field VARCHAR(120) NOT NULL,   -- field on the producer's result to bind
    PRIMARY KEY (tool_name, required_param)
);

-- Seed edges. Tool names are real discovered OpenAPI operations; producing_field is the
-- result field carrying the value. Extend as more location/entity-scoped tools are added.
INSERT INTO mcp_tool_prerequisite (tool_name, required_param, producing_tool, producing_field)
VALUES
    ('workorder_listwip', 'locationId', 'location_getcurrentuserprimarylocation', 'id'),
    ('workorder_getestimatesbylocation', 'locationId', 'location_getcurrentuserprimarylocation', 'id')
ON CONFLICT (tool_name, required_param) DO NOTHING;
