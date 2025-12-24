# MCP Server Configuration for Moqui Project

This directory contains Model Context Protocol (MCP) server implementations for the Moqui project.

## Overview

MCP servers provide Copilot and other AI assistants with standardized access to project-specific information and tools. Each server handles a specific domain of knowledge.

## Available MCP Servers

### 1. moqui-agents-server.js

**Purpose:** Discover and access the 27+ specialized agents in the Durion ERP ecosystem

**Enhanced Tools:**

- `list_agents` - Lists all available agents with optional filtering by name or expertise
- `get_agent` - Returns full agent definition and expertise areas
- `search_agents` - Search agents by keyword in name, description, or expertise
- `get_agents_by_expertise` - Find agents specializing in specific areas (e.g., "aws", "i18n", "database")
- `get_collaboration_framework` - Shows how agents collaborate and work together
- `get_agent_description` - Legacy tool for backward compatibility

**Example Usage:**

```bash
@moqui-agents-server list agents with AWS expertise
@moqui-agents-server get aws-cloud-architect
@moqui-agents-server search for agents related to internationalization
```

**Supported Agents:**

- i18n-agent - Internationalization expert
- typescript-agent - TypeScript/type safety expert
- vue-agent - Vue.js 2.7 expert
- quasar-agent - Quasar v1.22 component expert
- architecture_agent - Chief Architect
- moqui_developer_agent - Moqui implementation expert
- aws-cloud-architect - AWS cloud deployment specialist
- dba_agent - Database administration and performance
- sre_agent - SRE/observability/metrics
- api_agent - REST API development
- And 17+ more specialized agents...

### 2. mcp-instructions-server.js

**Purpose:** Access coding instructions, guidelines, and best practices for the Durion ERP tech stack

**Tools:**

- `list_instructions` - List all instruction sets with optional language filtering
- `get_instructions` - Get the full instruction set for a language/framework
- `list_prompts` - List all available prompts and templates
- `get_prompt` - Get a specific prompt/template
- `search_knowledge` - Search across agents, instructions, and prompts
- `get_agent_collaboration` - Get agent collaboration patterns
- `get_quick_reference` - Get quick reference guides for topics

**Example Usage:**

```bash
@mcp-instructions-server get Java development guidelines
@mcp-instructions-server get Groovy service patterns for Moqui
@mcp-instructions-server search knowledge about Vue.js best practices
@mcp-instructions-server get code review instructions
```

**Supported Instruction Sets:**

- java.instructions - Java development standards
- groovy.instructions - Groovy and Moqui service development (NEW)
- java-mcp-server.instructions - Building MCP servers with Moqui integration (ENHANCED)
- code-review-generic.instructions - Code review guidelines (updated for Moqui)
- typescript-5-es2022.instructions - TypeScript development
- vuejs3.instructions - Vue.js 3 best practices
- quasar.instructions - Quasar framework guidelines
- security-and-owasp.instructions - Security best practices

### 3. project-analysis-server.js

**Purpose:** Component analysis, dependencies, and architecture

**Tools:**

- `list_durion_components` - Lists all Durion components with metadata
- `get_component_info` - Returns component details (tier, description, entities, dependencies)
- `get_component_dependencies` - Lists what a component depends on
- `get_architecture_docs` - Lists available architecture documentation
- `get_layering_rules` - Returns architectural layering rules and domain boundaries

**Use Cases:**

- Component dependency analysis
- Architecture compliance checking
- Cross-domain relationship validation
- Service placement decisions

### 4. awesome-copilot (Docker-based)

**Purpose:** Access the awesome-copilot ecosystem with 118+ agents, prompts, and templates

**Configuration:** Docker-based MCP server providing multi-language agent support

**Example Usage:**

```bash
@awesome-copilot list javascript agents
@awesome-copilot search for Python development patterns
```

## Setup Instructions

### Prerequisites

```bash
npm install @modelcontextprotocol/sdk
```

### Installation with GitHub Copilot in VS Code

This is the **recommended** approach for Moqui development:

1. **Edit VS Code Settings** (`.vscode/settings.json`):
   ```json
   {
     "mcp.servers": {
       "moqui-agents-server": {
         "command": "node",
         "args": [".github/mcp-servers/moqui-agents-server.js"],
         "disabled": false
       },
       "mcp-instructions-server": {
         "command": "node",
         "args": [".github/mcp-servers/mcp-instructions-server.js"],
         "disabled": false
       },
       "project-analysis": {
         "command": "node",
         "args": [".github/mcp-servers/project-analysis-server.js"],
         "disabled": false
       },
       "awesome-copilot": {
         "command": "docker",
         "args": ["run", "--rm", "-i", "awesome-copilot"],
         "disabled": false
       }
     }
   }
   ```

2. **Ensure MCP SDK is installed:**
   ```bash
   npm install @modelcontextprotocol/sdk
   ```

3. **Restart VS Code** to activate MCP servers

4. **Open Copilot Chat** (Ctrl+Shift+I) and use:
   ```
   @moqui-agents-server list agents
   @mcp-instructions-server get groovy.instructions
   ```

### Installation with Claude Desktop

1. **Copy MCP server files** to your MCP server directory:
   ```bash
   cp .github/mcp-servers/*.js /path/to/your/mcp-servers/
   ```

2. **Update your MCP configuration** (e.g., `claude_desktop_config.json`):
   ```json
   {
     "mcpServers": {
       "moqui-agents": {
         "command": "node",
         "args": ["/path/to/mcp-servers/moqui-agents-server.js"],
         "env": {
           "MOQUI_PROJECT_ROOT": "/home/n541342/IdeaProjects/durion-moqui-frontend"
         }
       },
       "mcp-instructions": {
         "command": "node",
         "args": ["/path/to/mcp-servers/mcp-instructions-server.js"],
         "env": {
           "MOQUI_PROJECT_ROOT": "/home/n541342/IdeaProjects/durion-moqui-frontend"
         }
       },
       "project-analysis": {
         "command": "node",
         "args": ["/path/to/mcp-servers/project-analysis-server.js"],
         "env": {
           "MOQUI_PROJECT_ROOT": "/home/n541342/IdeaProjects/durion-moqui-frontend"
         }
       }
     }
   }
   ```

3. **Verify MCP servers are working:**
   ```bash
   node .github/mcp-servers/moqui-agents-server.js
   node .github/mcp-servers/mcp-instructions-server.js
   ```

## Usage Examples

### Example 1: Find AWS Architecture Expertise

```
User: @moqui-agents-server what agent helps with AWS?
Response: Lists aws-cloud-architect with AWS cloud deployment specialty

User: @moqui-agents-server get aws-cloud-architect
Response: Full AWS architecture guidance, cost optimization, HA/DR patterns
```

### Example 2: Get Groovy Development Standards

```
User: @mcp-instructions-server how do I write Groovy services for Moqui?
Response: Full groovy.instructions content with patterns, examples, and best practices
```

### Example 3: Find Security Guidelines

```
User: @mcp-instructions-server search knowledge about security
Response: Results from security-and-owasp.instructions, code-review guidelines, etc.
```

### Example 4: Discover Agents by Expertise

```
User: @moqui-agents-server show me agents with i18n expertise
Response: Lists i18n-agent and language-specific agents
```

## Environment Variables

All MCP servers read these environment variables:

- `MOQUI_PROJECT_ROOT` - Root directory of the Moqui project (default: `/home/n541342/IdeaProjects/durion-moqui-frontend`)
- `AGENTS_PATH` - Path to agent definitions relative to root (default: `.github/agents`)
- `INSTRUCTIONS_PATH` - Path to instruction files relative to root (default: `.github/instructions`)
- `PROMPTS_PATH` - Path to prompt templates relative to root (default: `.github/prompts`)
- `DURION_COMPONENTS` - Comma-separated list of Durion components (for project-analysis-server)

## Extending MCP Servers

### Adding a New Agent

1. Create `.github/agents/my-agent.md` with YAML frontmatter:
   ```yaml
   ---
   description: "What this agent does"
   expertise: ["area1", "area2"]
   collaborations: ["other-agent"]
   ---
   
   # My Agent
   ... detailed agent definition ...
   ```

2. Both servers will automatically discover it on next reload

### Adding Instructions

1. Create `.github/instructions/my-language.instructions.md` with:
   ```yaml
   ---
   description: "Development guidelines"
   applyTo: "**/*.ext"
   ---
   
   # Guidelines
   ... detailed instructions ...
   ```

2. The mcp-instructions-server will index it automatically

### Creating New MCP Servers

To add new tools to existing servers or create new servers:

1. Create a new `.js` file in `.github/mcp-servers/`
2. Import the SDK:
   ```javascript
   const { Server } = require('@modelcontextprotocol/sdk/server/stdio.js');
   ```
3. Implement `setRequestHandler` with your tools
4. Update `.vscode/settings.json` to register the new server

## Troubleshooting

**MCP servers not connecting in Copilot Chat:**
1. Verify `.vscode/settings.json` is valid JSON
2. Check Node.js is installed: `node --version`
3. Verify MCP SDK installed: `npm list @modelcontextprotocol/sdk`
4. Restart VS Code: `Ctrl+Shift+P` → "Reload Window"

**Tools not found:**
- Ensure `mcp.servers` section exists in `settings.json`
- Verify server names match usage (e.g., `@moqui-agents-server`)
- Check terminal output: View → Output → MCP

**Missing data:**
- Verify `MOQUI_PROJECT_ROOT` points to correct location
- Check agent files exist: `.github/agents/*.md`
- Check instruction files exist: `.github/instructions/*.md`
- Ensure YAML frontmatter is properly formatted (between `---` markers)

**awesome-copilot not responding:**
1. Verify Docker installed: `docker --version`
2. Check awesome-copilot image: `docker image ls | grep awesome`
3. Test Docker: `docker run hello-world`

## Performance Notes

- Agent/instruction files are loaded and cached on first use
- Metadata extraction happens once per file  
- Search operations use in-memory indexes
- Results include file path for reference
- Reload servers to pick up new agents/instructions

## Security Considerations

- MCP servers run with access to `.github/` directory contents
- No sensitive data should be stored in agent/instruction files
- Settings stored in `.vscode/settings.json` (committed to repo)
- Docker-based awesome-copilot runs in isolated container
- All file access is read-only

## References

- [Model Context Protocol (MCP) Docs](https://modelcontextprotocol.io/)
- [VS Code MCP Configuration](https://code.visualstudio.com/docs/copilot/mcp)
- [GitHub Copilot Customization](https://docs.github.com/en/copilot/customization/)
- [awesome-copilot Repository](https://github.com/github/awesome-copilot)
- [MCP SDK for JavaScript](https://github.com/modelcontextprotocol/sdk-js)
