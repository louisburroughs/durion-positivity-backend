# GitHub Copilot MCP Integration Guide

This guide explains how to use GitHub Copilot with your Moqui agents and MCP servers.

## Configuration

Your VS Code settings (`settings.json`) now includes three MCP servers that provide context to Copilot:

### 1. **moqui-context** - Project Context Server
Provides tools for:
- Project metadata and component overview
- Understanding project structure and build details

**Access in Copilot:**
```
@moqui-context get_project_info
```

### 2. **moqui-agents** - Agent Configuration Server
Provides tools for:
- Listing all available agents
- Reading specific agent definitions with collaboration details
- Agent collaboration workflows

**Access in Copilot:**
```
@moqui-agents list_agents
@moqui-agents get_agent_description moquiDeveloper_agent
@moqui-agents get_collaboration_framework
```

### 3. **project-analysis** - Architecture & Component Analysis
Provides tools for:
- Component inventory and dependencies
- Domain boundaries and layering rules
- Architecture patterns and constraints

**Access in Copilot:**
```
@project-analysis list_durion_components
@project-analysis get_component_info durion-crm
@project-analysis get_component_dependencies durion-crm
@project-analysis get_layering_rules
```

## Using Copilot with Your Agents

### Direct Agent Invocation
In Copilot Chat, you can reference agents directly:

```
@moquiDeveloper-agent Help me understand the entity definition for Party
@api-agent Design a new REST API endpoint for product management
@dba-agent Optimize the query for customer reports
@sre-agent Monitor application health with OpenTelemetry
```

### Context-Aware Requests
Combine MCP servers with agent requests:

```
@moqui-agents list_agents to find the right agent for this task
Using @project-analysis get_component_info durion-product, 
help me design a new product API endpoint with @api-agent
```

### Architecture Discussions
```
@project-analysis shows durion-crm component structure
@architecture-agent should design the database schema
```

## Workflow Example

1. **Understand Current State** (using MCP servers):
   ```
   @project-analysis list_durion_components
   @project-analysis get_component_info durion-crm
   ```

2. **Get Agent Guidance** (using agents):
   ```
   @api-agent How should I design a new REST endpoint for inventory management?
   ```

3. **Implementation Support**:
   ```
   Using @project-analysis get_component_dependencies durion-inventory,
   help me implement this with @moquiDeveloper-agent
   ```

4. **Quality Assurance**:
   ```
   @test-agent Write tests for the new inventory API endpoint
   @lint-agent Check my code for style and best practices
   ```

## Troubleshooting

### MCP Servers Not Responding
1. Ensure Node.js is installed: `node --version`
2. Check paths in `settings.json` are absolute paths
3. Verify MCP server scripts exist at configured paths
4. Restart VS Code

### Agent Not Found
1. Verify agent files exist in `.github/agents/`
2. Check agent YAML frontmatter syntax
3. Confirm agent filename matches expected format: `{agent-name}-agent.md`

### Permission Denied
Ensure MCP server scripts are executable:
```bash
chmod +x .github/mcp-servers/*.js
```

## Environment Variables

Your MCP servers use these environment variables (from VS Code settings):

| Variable | Value | Purpose |
|----------|-------|---------|
| `MOQUI_PROJECT_ROOT` | `/home/n541342/IdeaProjects/moqui_example` | Project root directory |
| `MOQUI_COMPONENTS_PATH` | `runtime/component` | Components location |
| `MOQUI_FRAMEWORK_PATH` | `framework` | Framework location |
| `AGENTS_PATH` | `.github/agents` | Agents configuration |
| `DOCUMENTATION_PATH` | `.github/docs` | Documentation files |

## Resources

- `.github/mcp-config.json` - Central MCP server configuration
- `.github/mcp-servers/` - MCP server implementations
- `.github/agents/` - Agent definitions
- `.github/AGENT_COLLABORATION.md` - Agent relationships and workflows

## Next Steps

1. Open Copilot Chat in VS Code (Ctrl+Shift+I / Cmd+Shift+I)
2. Test MCP server connection: `@moqui-agents list-agents`
3. Ask an agent for guidance: `@moquiDeveloper-agent Help me understand Moqui entities`
4. Use combined context: Reference MCP servers and agents in the same request

---

**Last Updated:** December 9, 2025
