#!/usr/bin/env node

/**
 * Context-Aware Instructions MCP Server
 * 
 * Selectively exposes instructions based on:
 * - File type/language being worked on
 * - Current project context
 * - Token budget constraints
 * - User-specified focus areas
 * 
 * Features:
 * - Smart instruction loading for specific file types
 * - Context budget management (prevents token overload)
 * - Keyword-based instruction search
 * - Caching for performance
 * - Integration with agent guides and reference docs
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
const instructionsPath = path.join(projectRoot, '.github/instructions');
const docsPath = path.join(projectRoot, '.github/docs');
const agentsPath = path.join(projectRoot, '.github/agents');

// Token estimation (rough heuristic: ~4 chars per token)
const CONTEXT_BUDGET = 6000; // tokens available for instructions
const CHARS_PER_TOKEN = 4;
const MAX_CHARS = CONTEXT_BUDGET * CHARS_PER_TOKEN;

const server = new Server({
  name: 'context-aware-instructions',
  version: '1.0.0',
});

/**
 * Cache for loaded instruction files
 */
const instructionCache = new Map();

/**
 * Load instruction file with caching
 */
function loadInstruction(filename) {
  if (instructionCache.has(filename)) {
    return instructionCache.get(filename);
  }
  
  const filepath = path.join(instructionsPath, filename);
  if (!fs.existsSync(filepath)) {
    return null;
  }
  
  const content = fs.readFileSync(filepath, 'utf-8');
  instructionCache.set(filename, content);
  return content;
}

/**
 * Get instructions for specific file type
 */
function getInstructionsForFile(filepath, maxChars = MAX_CHARS) {
  const ext = path.extname(filepath).substring(1).toLowerCase();
  
  const fileTypeMap = {
    'java': 'java.instructions.md',
    'groovy': 'groovy.instructions.md',
    'js': 'typescript-5-es2022.instructions.md',
    'ts': 'typescript-5-es2022.instructions.md',
    'vue': 'vuejs3.instructions.md',
    'md': 'markdown.instructions.md',
    'ftl': 'java-mcp-server.instructions.md',
    'xml': 'java-mcp-server.instructions.md',
    'scss': 'vuejs3.instructions.md',
  };

  const instructionFile = fileTypeMap[ext] || 'code-review-generic.instructions.md';
  const content = loadInstruction(instructionFile);
  
  if (!content) {
    throw new McpError(
      ErrorCode.InvalidRequest,
      `No instructions found for ${ext}`
    );
  }

  // Truncate to context budget
  if (content.length > maxChars) {
    return {
      file: instructionFile,
      content: content.substring(0, maxChars) + '\n\n[... content truncated to fit context budget ...]',
      truncated: true,
      originalLength: content.length,
      availableChars: maxChars,
      recommendedAction: 'Use get_reference_docs for additional context or search for specific topics'
    };
  }

  return {
    file: instructionFile,
    content: content,
    truncated: false,
    length: content.length,
    fileExtension: ext
  };
}

/**
 * Determine which instructions are relevant for a task
 */
function determineRelevantInstructions(task, fileType) {
  const taskLower = task.toLowerCase();
  const relevant = ['code-review-generic.instructions.md'];

  // Add language-specific instructions
  if (fileType === 'java') relevant.push('java.instructions.md');
  if (fileType === 'groovy') relevant.push('groovy.instructions.md');
  if (fileType === 'typescript' || fileType === 'javascript') {
    relevant.push('typescript-5-es2022.instructions.md');
  }
  if (fileType === 'vue' || fileType === 'scss') {
    relevant.push('vuejs3.instructions.md');
  }

  // Add domain-specific instructions
  if (taskLower.includes('security') || taskLower.includes('auth') || taskLower.includes('owasp')) {
    relevant.push('security-and-owasp.instructions.md');
  }
  if (taskLower.includes('performance') || taskLower.includes('optimize')) {
    relevant.push('performance-optimization.instructions.md');
  }
  if (taskLower.includes('ui') || taskLower.includes('component') || taskLower.includes('quasar')) {
    relevant.push('quasar.instructions.md');
  }
  if (taskLower.includes('localize') || taskLower.includes('i18n') || taskLower.includes('translation')) {
    relevant.push('localization.instructions.md');
  }
  if (taskLower.includes('review') || taskLower.includes('code')) {
    // code-review already included
  }

  return [...new Set(relevant)]; // Remove duplicates
}

/**
 * Get instructions filtered by task and context
 */
function getContextualInstructions(task, fileType, contextBudget = MAX_CHARS) {
  const result = {
    task: task,
    fileType: fileType,
    instructions: [],
    totalChars: 0,
    budgetRemaining: contextBudget,
    allocation: {
      budget: contextBudget,
      estimatedTokens: Math.ceil(contextBudget / CHARS_PER_TOKEN)
    }
  };

  // Determine which instructions are relevant
  const relevantFiles = determineRelevantInstructions(task, fileType);
  
  for (const filename of relevantFiles) {
    if (result.budgetRemaining <= 0) break;
    
    const content = loadInstruction(filename);
    if (!content) continue;

    const charsToUse = Math.min(content.length, result.budgetRemaining);
    const truncated = content.length > charsToUse;

    result.instructions.push({
      file: filename,
      content: truncated ? content.substring(0, charsToUse) + '\n[... truncated ...]' : content,
      truncated: truncated,
      originalLength: content.length,
      usedLength: charsToUse,
      estimatedTokens: Math.ceil(charsToUse / CHARS_PER_TOKEN)
    });

    result.totalChars += charsToUse;
    result.budgetRemaining -= charsToUse;
  }

  result.summary = {
    filesLoaded: result.instructions.length,
    totalCharsUsed: result.totalChars,
    totalTokensUsed: Math.ceil(result.totalChars / CHARS_PER_TOKEN),
    remainingBudget: result.budgetRemaining,
    remainingTokens: Math.ceil(result.budgetRemaining / CHARS_PER_TOKEN)
  };

  return result;
}

/**
 * Get reference documentation for a topic
 */
function getReferenceDocs(topic, maxChars = MAX_CHARS / 2) {
  if (!fs.existsSync(docsPath)) {
    return { 
      error: 'Documentation path not found',
      topic: topic
    };
  }

  const docs = fs.readdirSync(docsPath)
    .filter(f => f.endsWith('.md') && f.toLowerCase().includes(topic.toLowerCase()));

  if (docs.length === 0) {
    return { 
      error: `No documentation found for topic: ${topic}`,
      topic: topic,
      availableDocs: fs.readdirSync(docsPath).filter(f => f.endsWith('.md'))
    };
  }

  const result = {
    topic: topic,
    docs: [],
    totalChars: 0,
    totalTokens: 0
  };

  for (const docFile of docs.slice(0, 2)) { // Limit to 2 docs to save context
    const filepath = path.join(docsPath, docFile);
    const content = fs.readFileSync(filepath, 'utf-8');
    
    // Extract just the summary/intro section
    const summary = content.substring(0, 1000) + '\n... [see full doc for complete content]';
    
    result.docs.push({
      file: docFile,
      content: summary,
      originalLength: content.length,
      summaryLength: summary.length,
      estimatedTokens: Math.ceil(summary.length / CHARS_PER_TOKEN)
    });

    result.totalChars += summary.length;
    result.totalTokens += Math.ceil(summary.length / CHARS_PER_TOKEN);
  }

  return result;
}

/**
 * Get specific agent guide summary
 */
function getAgentGuide(agentName, maxChars = MAX_CHARS / 3) {
  const filename = `${agentName}.md`;
  const filepath = path.join(agentsPath, filename);

  if (!fs.existsSync(filepath)) {
    return { 
      error: `Agent not found: ${agentName}`,
      availableAgents: fs.readdirSync(agentsPath)
        .filter(f => f.endsWith('.md'))
        .map(f => f.replace('.md', ''))
    };
  }

  const content = fs.readFileSync(filepath, 'utf-8');
  
  // Return key sections to save context
  const lines = content.split('\n');
  const summary = lines
    .slice(0, Math.min(40, lines.length)) // First 40 lines
    .join('\n');

  return {
    agent: agentName,
    summary: summary,
    originalLength: content.length,
    summaryLength: summary.length,
    estimatedTokens: Math.ceil(summary.length / CHARS_PER_TOKEN),
    fullPath: filepath
  };
}

/**
 * Search instructions by keyword
 */
function searchInstructions(keyword) {
  if (!fs.existsSync(instructionsPath)) {
    return { error: 'Instructions path not found' };
  }

  const files = fs.readdirSync(instructionsPath);
  const results = [];

  for (const file of files) {
    const content = loadInstruction(file);
    if (content && content.toLowerCase().includes(keyword.toLowerCase())) {
      const firstMatch = content.toLowerCase().indexOf(keyword.toLowerCase());
      const start = Math.max(0, firstMatch - 80);
      const end = Math.min(content.length, firstMatch + 150);
      const context = content.substring(start, end);
      
      results.push({
        file: file,
        contextSnippet: `${start > 0 ? '...' : ''}${context}${end < content.length ? '...' : ''}`,
        position: firstMatch,
        lineNumber: content.substring(0, firstMatch).split('\n').length
      });
    }
  }

  return {
    keyword: keyword,
    matchesFound: results.length,
    results: results.slice(0, 8), // Limit to top 8
    summary: {
      keyword: keyword,
      matches: results.length,
      topFiles: results.slice(0, 3).map(r => r.file)
    }
  };
}

/**
 * List all available instructions with metadata
 */
function listAvailableInstructions() {
  if (!fs.existsSync(instructionsPath)) {
    return { error: 'Instructions path not found' };
  }

  const files = fs.readdirSync(instructionsPath).filter(f => f.endsWith('.md'));
  const instructions = [];
  let totalChars = 0;

  for (const file of files) {
    const content = loadInstruction(file);
    if (content) {
      instructions.push({
        file: file,
        length: content.length,
        tokens: Math.ceil(content.length / CHARS_PER_TOKEN)
      });
      totalChars += content.length;
    }
  }

  return {
    instructions: instructions,
    count: instructions.length,
    totalChars: totalChars,
    totalTokens: Math.ceil(totalChars / CHARS_PER_TOKEN),
    averageSize: Math.ceil(totalChars / instructions.length)
  };
}

/**
 * Clear the instruction cache
 */
function clearCache() {
  const cacheSize = instructionCache.size;
  instructionCache.clear();
  return { 
    success: true, 
    message: `Instruction cache cleared (${cacheSize} items removed)` 
  };
}

// Tool definitions
const tools = [
  {
    name: 'get_instructions_for_file',
    description: 'Get language/type-specific instructions for a file with context budget management',
    inputSchema: {
      type: 'object',
      properties: {
        filepath: {
          type: 'string',
          description: 'File path to get instructions for (e.g., "src/UserService.java")'
        },
        maxChars: {
          type: 'number',
          description: 'Maximum characters to return (default: 24000, ~6000 tokens)'
        }
      },
      required: ['filepath']
    }
  },
  {
    name: 'get_contextual_instructions',
    description: 'Get instructions filtered by task type and file type, respecting context budget',
    inputSchema: {
      type: 'object',
      properties: {
        task: {
          type: 'string',
          description: 'Task description (e.g., "implement security", "optimize performance", "write test")'
        },
        fileType: {
          type: 'string',
          description: 'File type (java, groovy, typescript, vue, etc.)'
        },
        contextBudget: {
          type: 'number',
          description: 'Available context in characters (default: 24000)'
        }
      },
      required: ['task', 'fileType']
    }
  },
  {
    name: 'get_reference_docs',
    description: 'Get reference documentation summaries for a topic',
    inputSchema: {
      type: 'object',
      properties: {
        topic: {
          type: 'string',
          description: 'Topic to search (e.g., "i18n", "performance", "architecture")'
        },
        maxChars: {
          type: 'number',
          description: 'Max characters (default: 12000)'
        }
      },
      required: ['topic']
    }
  },
  {
    name: 'get_agent_guide',
    description: 'Get a summary of a specific agent guide',
    inputSchema: {
      type: 'object',
      properties: {
        agentName: {
          type: 'string',
          description: 'Agent name (e.g., "api-agent", "architecture-agent", "moqui-developer-agent")'
        },
        maxChars: {
          type: 'number',
          description: 'Max characters (default: 8000)'
        }
      },
      required: ['agentName']
    }
  },
  {
    name: 'search_instructions',
    description: 'Search instructions by keyword across all files',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: {
          type: 'string',
          description: 'Keyword to search for (e.g., "security", "performance", "entity")'
        }
      },
      required: ['keyword']
    }
  },
  {
    name: 'list_available_instructions',
    description: 'List all available instruction files with metadata',
    inputSchema: {
      type: 'object',
      properties: {}
    }
  },
  {
    name: 'clear_cache',
    description: 'Clear the instruction cache (useful when files are updated)',
    inputSchema: {
      type: 'object',
      properties: {}
    }
  }
];

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case 'get_instructions_for_file': {
        const result = getInstructionsForFile(
          args.filepath,
          args.maxChars || MAX_CHARS
        );
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'get_contextual_instructions': {
        const result = getContextualInstructions(
          args.task,
          args.fileType,
          args.contextBudget || MAX_CHARS
        );
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'get_reference_docs': {
        const result = getReferenceDocs(args.topic, args.maxChars);
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'get_agent_guide': {
        const result = getAgentGuide(args.agentName, args.maxChars);
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'search_instructions': {
        const result = searchInstructions(args.keyword);
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'list_available_instructions': {
        const result = listAvailableInstructions();
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
        };
      }

      case 'clear_cache': {
        const result = clearCache();
        return {
          content: [{ type: 'text', text: JSON.stringify(result, null, 2) }]
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
  console.error('Context-Aware Instructions MCP server running on stdio');
}

main().catch(console.error);
