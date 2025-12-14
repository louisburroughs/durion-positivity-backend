#!/usr/bin/env node

/**
 * MCP Server: Moqui Context Information
 * 
 * Provides project-wide context:
 * - Project structure and overview
 * - Component listing (delegates to project-analysis for details)
 * 
 * Note: Component details and tech stack are in project-analysis server
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

const projectRoot = process.env.MOQUI_PROJECT_ROOT || '/home/n541342/IdeaProjects/moqui_example';

const server = new Server({
  name: 'moqui-context',
  version: '1.0.0',
});

// Tool: Get project structure
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  if (request.params.name === 'get_project_info') {
    const projectInfo = {
      name: 'Moqui Example - Durion Project',
      root: projectRoot,
      framework: 'Moqui 3.0+',
      language: 'Java 11, Groovy, XML',
      buildSystem: 'Gradle',
      components: [
        'durion-common', 'durion-crm', 'durion-product',
        'durion-inventory', 'durion-accounting', 'durion-workexec',
        'durion-experience', 'durion-positivity', 'durion-theme',
        'durion-demo-data', 'durion-mcp'
      ],
      referenceComponents: [
        'PopCommerce', 'SimpleScreens', 'HiveMind', 'MarbleERP',
        'mantle-udm', 'mantle-usl'
      ],
      agents: [
        'architecture_agent', 'moqui_developer_agent', 'dba_agent',
        'sre_agent', 'test_agent', 'lint_agent', 'api_agent',
        'dev_deploy_agent', 'docs_agent'
      ]
    };
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(projectInfo, null, 2)
        }
      ]
    };
  }

  throw new McpError(
    ErrorCode.MethodNotFound,
    `Unknown tool: ${request.params.name}`
  );
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('Moqui Context MCP server running on stdio');
}

main().catch(console.error);
