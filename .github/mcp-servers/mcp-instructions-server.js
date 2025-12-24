#!/usr/bin/env node

/**
 * MCP Server: Agents, Instructions, and Prompts
 * 
 * Exposes the Durion ERP development ecosystem:
 * - Specialized agents (i18n, typescript, language agents, etc.)
 * - Coding instructions and guidelines
 * - Reusable prompts and templates
 * 
 * Enables Copilot to leverage project-specific expertise and patterns.
 */

const { Server } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const {
  CallToolRequestSchema,
  ErrorCode,
  McpError,
} = require('@modelcontextprotocol/sdk/types.js');
const fs = require('fs');
const path = require('path');

const projectRoot = process.env.MOQUI_PROJECT_ROOT || '/home/n541342/IdeaProjects/durion-moqui-frontend';
const agentsPath = path.join(projectRoot, '.github/agents');
const instructionsPath = path.join(projectRoot, '.github/instructions');
const promptsPath = path.join(projectRoot, '.github/prompts');

const server = new Server({
  name: 'mcp-instructions',
  version: '1.0.0',
});

/**
 * Load all markdown files from a directory
 */
function loadMarkdownFiles(dirPath) {
  if (!fs.existsSync(dirPath)) {
    return [];
  }
  
  return fs.readdirSync(dirPath)
    .filter(f => f.endsWith('.md'))
    .map(filename => {
      const filePath = path.join(dirPath, filename);
      const content = fs.readFileSync(filePath, 'utf-8');
      return {
        filename,
        name: filename.replace('.md', '').replace('.instructions', '').replace('.agent', '').replace('.prompt', ''),
        content,
        type: determineFileType(filename),
        path: filePath
      };
    });
}

/**
 * Determine file type from filename
 */
function determineFileType(filename) {
  if (filename.endsWith('.instructions.md')) return 'instruction';
  if (filename.endsWith('.agent.md')) return 'agent';
  if (filename.endsWith('.prompt.md')) return 'prompt';
  return 'document';
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
      metadata[key.trim()] = valueParts.join(':').trim().replace(/^['"]|['"]$/g, '');
    }
  }
  
  return metadata;
}

/**
 * Get file body (content after frontmatter)
 */
function getFileBody(content) {
  const frontmatterRegex = /^---\n([\s\S]*?)\n---\n([\s\S]*)/;
  const match = content.match(frontmatterRegex);
  return match ? match[2] : content;
}

/**
 * Search files by keyword
 */
function searchFiles(files, keyword) {
  const lowerKeyword = keyword.toLowerCase();
  return files.filter(file => {
    const metadata = extractMetadata(file.content);
    const description = (metadata.description || '').toLowerCase();
    const applyTo = (metadata.applyTo || '').toLowerCase();
    const body = getFileBody(file.content).toLowerCase().substring(0, 500);
    
    return file.name.toLowerCase().includes(lowerKeyword) ||
           description.includes(lowerKeyword) ||
           applyTo.includes(lowerKeyword) ||
           body.includes(lowerKeyword);
  });
}

// Load all files at startup
const agents = loadMarkdownFiles(agentsPath);
const instructions = loadMarkdownFiles(instructionsPath);
const prompts = loadMarkdownFiles(promptsPath);

// Tool definitions
const tools = [
  {
    name: 'list_agents',
    description: 'List all available Durion ERP agents with their expertise areas',
    inputSchema: {
      type: 'object',
      properties: {
        filter: {
          type: 'string',
          description: 'Optional: Filter agents by name or expertise (e.g., "i18n", "cloud", "language")'
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
          description: 'Name of the agent (e.g., "i18n-agent", "typescript-agent", "en-US-language-agent")'
        }
      },
      required: ['agent_name']
    }
  },
  {
    name: 'list_instructions',
    description: 'List all coding instruction sets for different languages and frameworks',
    inputSchema: {
      type: 'object',
      properties: {
        language: {
          type: 'string',
          description: 'Optional: Filter by programming language (e.g., "java", "groovy", "typescript", "vue")'
        }
      }
    }
  },
  {
    name: 'get_instructions',
    description: 'Get the full instruction set for a specific language, framework, or pattern',
    inputSchema: {
      type: 'object',
      properties: {
        instruction_name: {
          type: 'string',
          description: 'Name of the instruction file (e.g., "java", "groovy", "typescript-5-es2022", "code-review-generic")'
        }
      },
      required: ['instruction_name']
    }
  },
  {
    name: 'list_prompts',
    description: 'List all reusable prompts and templates',
    inputSchema: {
      type: 'object',
      properties: {
        category: {
          type: 'string',
          description: 'Optional: Filter prompts by category'
        }
      }
    }
  },
  {
    name: 'get_prompt',
    description: 'Get a specific prompt or template',
    inputSchema: {
      type: 'object',
      properties: {
        prompt_name: {
          type: 'string',
          description: 'Name of the prompt'
        }
      },
      required: ['prompt_name']
    }
  },
  {
    name: 'search_knowledge',
    description: 'Search across agents, instructions, and prompts for relevant knowledge',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: {
          type: 'string',
          description: 'Search keyword (e.g., "authentication", "cloud", "Vue.js", "i18n")'
        },
        document_type: {
          type: 'string',
          enum: ['agent', 'instruction', 'prompt', 'all'],
          description: 'Filter by document type. Default: "all"'
        }
      },
      required: ['keyword']
    }
  },
  {
    name: 'get_agent_collaboration',
    description: 'Get information about how agents work together in workflows',
    inputSchema: {
      type: 'object',
      properties: {}
    }
  },
  {
    name: 'get_quick_reference',
    description: 'Get a quick reference guide for Durion ERP tech stack and agents',
    inputSchema: {
      type: 'object',
      properties: {
        topic: {
          type: 'string',
          description: 'Topic for quick reference (e.g., "i18n", "moqui", "vue", "typescript")'
        }
      }
    }
  }
];

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request;

  try {
    switch (name) {
      case 'list_agents': {
        const filtered = args.filter ? 
          searchFiles(agents, args.filter) : 
          agents;
        
        const summary = filtered.map(agent => {
          const metadata = extractMetadata(agent.content);
          return {
            name: agent.name,
            description: metadata.description || 'No description',
            type: agent.type
          };
        });

        return {
          content: [
            {
              type: 'text',
              text: `Found ${summary.length} agent(s):\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_agent': {
        const agent = agents.find(a => 
          a.name.toLowerCase() === args.agent_name.toLowerCase()
        );

        if (!agent) {
          throw new McpError(
            ErrorCode.InvalidRequest,
            `Agent "${args.agent_name}" not found. Available agents: ${agents.map(a => a.name).join(', ')}`
          );
        }

        return {
          content: [
            {
              type: 'text',
              text: `# Agent: ${agent.name}\n\n${agent.content}`
            }
          ]
        };
      }

      case 'list_instructions': {
        const filtered = args.language ? 
          searchFiles(instructions, args.language) : 
          instructions;

        const summary = filtered.map(inst => {
          const metadata = extractMetadata(inst.content);
          return {
            name: inst.name,
            description: metadata.description || 'No description',
            applies_to: metadata.applyTo || 'N/A'
          };
        });

        return {
          content: [
            {
              type: 'text',
              text: `Found ${summary.length} instruction set(s):\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_instructions': {
        const inst = instructions.find(i => 
          i.name.toLowerCase() === args.instruction_name.toLowerCase()
        );

        if (!inst) {
          throw new McpError(
            ErrorCode.InvalidRequest,
            `Instructions "${args.instruction_name}" not found. Available: ${instructions.map(i => i.name).join(', ')}`
          );
        }

        return {
          content: [
            {
              type: 'text',
              text: `# Instructions: ${inst.name}\n\n${inst.content}`
            }
          ]
        };
      }

      case 'list_prompts': {
        const filtered = args.category ? 
          searchFiles(prompts, args.category) : 
          prompts;

        const summary = filtered.map(prompt => {
          const metadata = extractMetadata(prompt.content);
          return {
            name: prompt.name,
            description: metadata.description || 'No description'
          };
        });

        return {
          content: [
            {
              type: 'text',
              text: `Found ${summary.length} prompt(s):\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_prompt': {
        const prompt = prompts.find(p => 
          p.name.toLowerCase() === args.prompt_name.toLowerCase()
        );

        if (!prompt) {
          throw new McpError(
            ErrorCode.InvalidRequest,
            `Prompt "${args.prompt_name}" not found. Available: ${prompts.map(p => p.name).join(', ')}`
          );
        }

        return {
          content: [
            {
              type: 'text',
              text: `# Prompt: ${prompt.name}\n\n${prompt.content}`
            }
          ]
        };
      }

      case 'search_knowledge': {
        const docTypes = args.document_type === 'all' || !args.document_type ? 
          ['agent', 'instruction', 'prompt'] : 
          [args.document_type];

        const allDocs = [
          ...agents.filter(d => docTypes.includes('agent')),
          ...instructions.filter(d => docTypes.includes('instruction')),
          ...prompts.filter(d => docTypes.includes('prompt'))
        ];

        const results = searchFiles(allDocs, args.keyword);

        const summary = results.map(result => {
          const metadata = extractMetadata(result.content);
          return {
            name: result.name,
            type: result.type,
            description: metadata.description || 'No description',
            applies_to: metadata.applyTo || 'N/A'
          };
        });

        return {
          content: [
            {
              type: 'text',
              text: `Search results for "${args.keyword}" (${results.length} found):\n\n${JSON.stringify(summary, null, 2)}`
            }
          ]
        };
      }

      case 'get_agent_collaboration': {
        const collaborationPath = path.join(projectRoot, '.github/docs/AGENT_COLLABORATION.md');
        if (fs.existsSync(collaborationPath)) {
          const content = fs.readFileSync(collaborationPath, 'utf-8');
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
              text: 'Agent collaboration guide not found. Describe your workflow and I can help you coordinate agents.'
            }
          ]
        };
      }

      case 'get_quick_reference': {
        const topic = args.topic || 'overview';
        
        let reference = `# Durion ERP - Quick Reference\n\n`;
        reference += `## Tech Stack\n`;
        reference += `- **Backend**: Moqui Framework (Java), Groovy\n`;
        reference += `- **Frontend**: Vue.js 2.7.14, Quasar v1.22.10, TypeScript 4.x+\n`;
        reference += `- **Database**: PostgreSQL (primary), MySQL (supported)\n`;
        reference += `- **Templating**: FreeMarker (.ftl)\n\n`;

        reference += `## Available Agents\n`;
        agents.slice(0, 5).forEach(agent => {
          reference += `- **${agent.name}**: ${extractMetadata(agent.content).description}\n`;
        });
        if (agents.length > 5) {
          reference += `- ... and ${agents.length - 5} more agents\n`;
        }

        reference += `\n## Getting Started\n`;
        reference += `1. Use \`@i18n-agent\` for internationalization features\n`;
        reference += `2. Use \`@typescript-agent\` for type-safe component development\n`;
        reference += `3. Use \`@[language]-language-agent\` for language-specific validation\n`;
        reference += `4. Use \`@architecture_agent\` for system design\n`;
        reference += `5. Consult \`.github/instructions\` for coding standards\n`;

        return {
          content: [
            {
              type: 'text',
              text: reference
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

server.setRequestHandler('tools/list', async () => {
  return { tools };
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('MCP Instructions Server running on stdio');
}

main().catch(console.error);
