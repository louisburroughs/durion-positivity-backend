#!/usr/bin/env node

/**
 * MCP Server: Project Analysis
 * 
 * Provides analysis tools for:
 * - Component dependencies
 * - Domain relationships
 * - RACI matrix queries
 * - Architecture documentation
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
const docsPath = path.join(projectRoot, process.env.DOCUMENTATION_PATH || '.github/docs');

const server = new Server({
  name: 'project-analysis',
  version: '1.0.0',
});

const durionComponents = {
  'durion-common': {
    tier: 'foundation',
    description: 'Shared entities, services, and utilities',
    entities: ['Party', 'Organization', 'Contact', 'Address', 'Phone', 'Email'],
    dependencies: ['framework', 'mantle-udm']
  },
  'durion-crm': {
    tier: 'business',
    description: 'Customer Relationship Management',
    entities: ['Account', 'Lead', 'Opportunity', 'Contact', 'Activity'],
    dependencies: ['durion-common', 'mantle-udm']
  },
  'durion-product': {
    tier: 'business',
    description: 'Product Catalog and Management',
    entities: ['Product', 'ProductCategory', 'ProductPrice', 'InventoryItem'],
    dependencies: ['durion-common', 'PopCommerce']
  },
  'durion-inventory': {
    tier: 'business',
    description: 'Inventory Management',
    entities: ['InventoryItem', 'InventoryLocation', 'StockMovement', 'Adjustment'],
    dependencies: ['durion-product', 'durion-common', 'mantle-udm']
  },
  'durion-accounting': {
    tier: 'business',
    description: 'Financial Management',
    entities: ['GlAccount', 'Invoice', 'Payment', 'Transaction', 'FinancialPeriod'],
    dependencies: ['durion-common', 'mantle-udm', 'SimpleScreens']
  },
  'durion-workexec': {
    tier: 'business',
    description: 'Work Execution and Scheduling',
    entities: ['WorkOrder', 'WorkTask', 'Resource', 'Schedule', 'TimeEntry'],
    dependencies: ['durion-product', 'durion-inventory', 'durion-common', 'HiveMind']
  },
  'durion-experience': {
    tier: 'business',
    description: 'Customer Experience and Portal',
    entities: ['CustomerPortalAccess', 'ServiceRequest', 'OrderTracking'],
    dependencies: ['durion-crm', 'durion-product', 'durion-accounting']
  },
  'durion-positivity': {
    tier: 'integration',
    description: 'External System Integrations',
    entities: ['IntegrationAdapter', 'DataTransformation'],
    dependencies: ['all-durion-components-as-needed']
  },
  'durion-theme': {
    tier: 'ui',
    description: 'UI Theme and Styling',
    entities: [],
    dependencies: []
  },
  'durion-demo-data': {
    tier: 'data',
    description: 'Demo and Sample Data',
    entities: [],
    dependencies: ['all-durion-components']
  },
  'durion-mcp': {
    tier: 'tooling',
    description: 'Master Control Program',
    entities: [],
    dependencies: []
  }
};

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  if (request.params.name === 'list_durion_components') {
    const components = Object.entries(durionComponents).map(([name, info]) => ({
      name,
      tier: info.tier,
      description: info.description,
      dependencyCount: info.dependencies.length
    }));
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(components, null, 2)
        }
      ]
    };
  }

  if (request.params.name === 'get_component_info') {
    const componentName = request.params.arguments?.component;
    if (!componentName || !durionComponents[componentName]) {
      throw new McpError(ErrorCode.InvalidParams, `Unknown component: ${componentName}`);
    }
    const info = durionComponents[componentName];
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(info, null, 2)
        }
      ]
    };
  }

  if (request.params.name === 'get_component_dependencies') {
    const componentName = request.params.arguments?.component;
    if (!componentName || !durionComponents[componentName]) {
      throw new McpError(ErrorCode.InvalidParams, `Unknown component: ${componentName}`);
    }
    const dependencies = durionComponents[componentName].dependencies;
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify({ 
            component: componentName,
            dependencies: dependencies 
          }, null, 2)
        }
      ]
    };
  }

  if (request.params.name === 'get_layering_rules') {
    const layering = {
      layers: [
        'UI (Screens, Vue, Forms)',
        'Services (Business Logic)',
        'Entities (Data Model)',
        'Integration (Positivity Layer)'
      ],
      rules: [
        'Screens/Vue → Services ONLY',
        'Services → Entities ONLY',
        'Entities → No outward calls',
        'All external calls → Positivity Layer ONLY'
      ],
      domainBoundaries: {
        'durion-crm': 'Customer data and relationships',
        'durion-product': 'Products and catalog',
        'durion-inventory': 'Stock and warehouse',
        'durion-accounting': 'Financial transactions',
        'durion-workexec': 'Work execution',
        'durion-experience': 'Customer portal',
        'durion-positivity': 'External integrations'
      }
    };
    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(layering, null, 2)
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
  console.error('Project Analysis MCP server running on stdio');
}

main().catch(console.error);
