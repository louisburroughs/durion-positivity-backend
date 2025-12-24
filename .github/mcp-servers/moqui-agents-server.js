#!/usr/bin/env node

/**
 * MCP Server: Moqui Agents Information
 * 
 * Provides access to:
 * - Agent descriptions and capabilities
 * - Agent collaboration patterns
 * - Agent workflow guidelines
 * - Cross-agent communication
 */

const { Server } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const {
  CallToolRequestSchema,
  TextContent,
  ErrorCode,
  McpError,
} = require('@modelcontextprotocol/sdk/types.js');
const fs = require('fs');
const path = require('path');

const projectRoot = process.env.MOQUI_PROJECT_ROOT || '/home/n541342/IdeaProjects/durion-moqui-frontend';
const agentsPath = path.join(projectRoot, process.env.AGENTS_PATH || '.github/agents');

const server = new Server({
  name: 'moqui-agents',
  version: '1.0.0',
});

const agents = {
  architecture_agent: 'Chief Architect - Domain-driven design and architectural integrity',
  moqui_developer_agent: 'Moqui Implementation Expert - Turns architecture and design into working code',
  dba_agent: 'Expert Database Administrator - Performance tuning, schema design, and database security',
  sre_agent: 'SRE/Observability Agent - Functional & Operational Metrics, OpenTelemetry, Grafana Integration',
  test_agent: 'QA Software Engineer - Writes, runs, and analyzes tests',
  lint_agent: 'Code Quality Engineer - Style enforcement and static analysis',
  api_agent: 'Senior Software Engineer - REST API development and error handling',
  dev_deploy_agent: 'Senior DevOps Engineer - Local development deployment and containerization',
  docs_agent: 'Expert Technical Writer - Documentation and knowledge base'
};

/**
 * Load all agent files and extract metadata
 */
function loadAllAgents() {
  if (!fs.existsSync(agentsPath)) {
    return [];
  }

  return fs.readdirSync(agentsPath)
    .filter(f => f.endsWith('.md'))
    .map(filename => {
      const filePath = path.join(agentsPath, filename);
      const content = fs.readFileSync(filePath, 'utf-8');
      const metadata = extractMetadata(content);
      return {
        filename,
        name: filename.replace('.md', ''),
        description: metadata.description || 'No description',
        expertise: metadata.expertise || [],
        collaborations: metadata.collaborations || [],
        content
      };
    });
}

/**
 * Extract frontmatter metadata from markdown
 */
function extractMetadata(content) {
  const frontmatterRegex = /^---\n([\s\S]*?)\n---/;
  const match = content.match(frontmatterRegex);
  
  if (!match) return {};
  
  const metadata = {};
  const lines = match[1].split('\n');
  
  for (const line of lines) {
    if (line.includes(':')) {
      const [key, ...valueParts] = line.split(':');
      const value = valueParts.join(':').trim().replace(/^['"]|['"]$/g, '');
      
      // Parse array values
      if (value.startsWith('[') && value.endsWith(']')) {
        metadata[key.trim()] = JSON.parse(value);
      } else {
        metadata[key.trim()] = value;
      }
    }
  }
  
  return metadata;
}

/**
 * Search agents by keyword in name or description
 */
function searchAgents(allAgents, keyword) {
  const lower = keyword.toLowerCase();
  return allAgents.filter(agent =>
    agent.name.toLowerCase().includes(lower) ||
    agent.description.toLowerCase().includes(lower) ||
    (Array.isArray(agent.expertise) && agent.expertise.some(e => 
      String(e).toLowerCase().includes(lower)
    ))
  );
}

/**
 * Get agents by expertise area
 */
function getAgentsByExpertise(allAgents, expertise) {
  return allAgents.filter(agent =>
    Array.isArray(agent.expertise) && agent.expertise.some(e =>
      String(e).toLowerCase().includes(expertise.toLowerCase())
    )
  );
}

// Cache loaded agents
let cachedAgents = null;

function getCachedAgents() {
  if (!cachedAgents) {
    cachedAgents = loadAllAgents();
  }
  return cachedAgents;
}

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;
  const allAgents = getCachedAgents();

  try {
    switch (name) {
      case 'list_agents': {
        const filter = args?.filter;
        const filtered = filter ? searchAgents(allAgents, filter) : allAgents;
        
        const agentsList = filtered.map(agent => ({
          name: agent.name,
          description: agent.description,
          expertise: agent.expertise || []
        }));

        return {
          content: [
            {
              type: 'text',
              text: `Found ${agentsList.length} agent(s):\n\n${JSON.stringify(agentsList, null, 2)}`
            }
          ]
        };
      }

      case 'get_agent': {
        const agentName = args?.agent_name;
        if (!agentName) {
          throw new McpError(ErrorCode.InvalidParams, 'Missing agent_name parameter');
        }

        const agent = allAgents.find(a => 
          a.name.toLowerCase() === agentName.toLowerCase()
        );

        if (!agent) {
          throw new McpError(
            ErrorCode.InvalidParams,
            `Agent "${agentName}" not found. Available agents: ${allAgents.map(a => a.name).join(', ')}`
          );
        }

        return {
          content: [
            {
              type: 'text',
              text: agent.content
            }
          ]
        };
      }

      case 'search_agents': {
        const keyword = args?.keyword;
        if (!keyword) {
          throw new McpError(ErrorCode.InvalidParams, 'Missing keyword parameter');
        }

        const results = searchAgents(allAgents, keyword);

        const summary = results.map(agent => ({
          name: agent.name,
          description: agent.description,
          expertise: agent.expertise || []
        }));

        return {
          content: [
            {
              type: 'text',
              text: `Found ${results.length} agent(s) matching "${keyword}":\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_agents_by_expertise': {
        const expertise = args?.expertise;
        if (!expertise) {
          throw new McpError(ErrorCode.InvalidParams, 'Missing expertise parameter');
        }

        const results = getAgentsByExpertise(allAgents, expertise);

        const summary = results.map(agent => ({
          name: agent.name,
          description: agent.description,
          expertise: agent.expertise || []
        }));

        return {
          content: [
            {
              type: 'text',
              text: `Found ${results.length} agent(s) with "${expertise}" expertise:\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_collaboration_framework': {
        try {
          const collabFile = path.join(projectRoot, '.github/AGENT_COLLABORATION.md');
          if (fs.existsSync(collabFile)) {
            const content = fs.readFileSync(collabFile, 'utf-8');
            return {
              content: [
                {
                  type: 'text',
                  text: content
                }
              ]
            };
          }
          
          return {
            content: [
              {
                type: 'text',
                text: 'No collaboration framework document found. Describe your workflow and I can help coordinate agents.'
              }
            ]
          };
        } catch (e) {
          throw new McpError(ErrorCode.InternalError, `Error reading collaboration framework: ${e.message}`);
        }
      }

      case 'get_agent_description': {
        // Legacy support
        const agentName = args?.agent;
        if (!agentName) {
          throw new McpError(ErrorCode.InvalidParams, 'Missing agent parameter');
        }

        const agent = allAgents.find(a => 
          a.name.toLowerCase() === agentName.toLowerCase()
        );

        if (!agent) {
          throw new McpError(ErrorCode.InvalidParams, `Unknown agent: ${agentName}`);
        }

        return {
          content: [
            {
              type: 'text',
              text: agent.content
            }
          ]
        };
      }

      default:
        throw new McpError(
          ErrorCode.MethodNotFound,
          `Unknown tool: ${name}`
        );
    }
  } catch (error) {
    if (error instanceof McpError) throw error;
    throw new McpError(
      ErrorCode.InternalError,
      `Tool execution failed: ${error.message}`
    );
  }
});

// Tool definitions
const tools = [
  {
    name: 'list_agents',
    description: 'List all available Durion ERP agents with optional filtering',
    inputSchema: {
      type: 'object',
      properties: {
        filter: {
          type: 'string',
          description: 'Optional: Filter agents by name or expertise (e.g., "architecture", "moqui", "aws")'
        }
      }
    }
  },
  {
    name: 'get_agent',
    description: 'Get the full definition and expertise of a specific agent',
    inputSchema: {
      type: 'object',
      properties: {
        agent_name: {
          type: 'string',
          description: 'Name of the agent (e.g., "architecture-agent", "moqui-developer-agent")'
        }
      },
      required: ['agent_name']
    }
  },
  {
    name: 'search_agents',
    description: 'Search agents by keyword in name, description, or expertise',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: {
          type: 'string',
          description: 'Search keyword (e.g., "database", "API", "cloud", "testing")'
        }
      },
      required: ['keyword']
    }
  },
  {
    name: 'get_agents_by_expertise',
    description: 'Find agents specializing in a specific area',
    inputSchema: {
      type: 'object',
      properties: {
        expertise: {
          type: 'string',
          description: 'Area of expertise (e.g., "i18n", "cloud-architecture", "performance", "security")'
        }
      },
      required: ['expertise']
    }
  },
  {
    name: 'get_collaboration_framework',
    description: 'Get information about how agents collaborate and work together',
    inputSchema: {
      type: 'object',
      properties: {}
    }
  },
  {
    name: 'get_agent_description',
    description: 'Legacy: Get agent description (use get_agent instead)',
    inputSchema: {
      type: 'object',
      properties: {
        agent: {
          type: 'string',
          description: 'Agent name'
        }
      },
      required: ['agent']
    }
  }
];

server.setRequestHandler('tools/list', async () => {
  return { tools };
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('Moqui Agents MCP server running on stdio');
}

main().catch(console.error);
