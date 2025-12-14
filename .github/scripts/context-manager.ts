/**
 * Context Manager - Intelligent token budget management for MCP servers
 * 
 * Prevents context overload by:
 * - Allocating token budgets by document type
 * - Prioritizing relevant content based on task
 * - Truncating intelligently to fit constraints
 * - Estimating token usage accurately
 */

interface ContextConfig {
  totalBudget: number;        // Total tokens available
  instructionsPercent: number; // % allocated to instructions (default: 40%)
  docsPercent: number;        // % allocated to docs (default: 30%)
  agentPercent: number;       // % allocated to agent guides (default: 20%)
  responsePercent: number;    // % reserved for response (default: 10%)
}

interface ContextAllocation {
  instructions: number;
  docs: number;
  agent: number;
  reserved: number;
  total: number;
}

interface ContentPriority {
  type: 'instructions' | 'docs' | 'agent';
  priority: number; // 1 = highest, 3 = lowest
}

interface TruncationResult {
  content: string;
  original: {
    length: number;
    tokens: number;
  };
  truncated: {
    length: number;
    tokens: number;
  };
  wasTruncated: boolean;
  percentRetained: number;
}

/**
 * Manages token budget and context allocation for MCP operations
 */
export class ContextManager {
  private config: ContextConfig;
  private allocation: ContextAllocation;
  private readonly CHARS_PER_TOKEN = 4;

  constructor(config: Partial<ContextConfig> = {}) {
    this.config = {
      totalBudget: config.totalBudget ?? 6000,
      instructionsPercent: config.instructionsPercent ?? 40,
      docsPercent: config.docsPercent ?? 30,
      agentPercent: config.agentPercent ?? 20,
      responsePercent: config.responsePercent ?? 10,
    };

    // Validate percentages add up to 100
    const total = this.config.instructionsPercent + 
                  this.config.docsPercent + 
                  this.config.agentPercent + 
                  this.config.responsePercent;
    
    if (Math.abs(total - 100) > 1) {
      console.warn(`Context allocation percentages total ${total}%, adjusting...`);
      // Auto-normalize percentages
      const scale = 100 / total;
      this.config.instructionsPercent *= scale;
      this.config.docsPercent *= scale;
      this.config.agentPercent *= scale;
      this.config.responsePercent *= scale;
    }

    this.calculateAllocation();
  }

  /**
   * Calculate token allocation based on configuration
   */
  private calculateAllocation(): void {
    const total = this.config.totalBudget;
    this.allocation = {
      instructions: Math.floor(total * (this.config.instructionsPercent / 100)),
      docs: Math.floor(total * (this.config.docsPercent / 100)),
      agent: Math.floor(total * (this.config.agentPercent / 100)),
      reserved: Math.floor(total * (this.config.responsePercent / 100)),
      total: total,
    };
  }

  /**
   * Get the current allocation breakdown
   */
  getAllocation(): ContextAllocation {
    return { ...this.allocation };
  }

  /**
   * Get budget for specific context type
   */
  getBudgetFor(type: 'instructions' | 'docs' | 'agent'): number {
    return this.allocation[type];
  }

  /**
   * Get remaining budget after usage
   */
  getRemaining(used: number, type: 'instructions' | 'docs' | 'agent'): number {
    const budget = this.allocation[type];
    return Math.max(0, budget - used);
  }

  /**
   * Get token count from character count
   */
  estimateTokens(content: string): number {
    return Math.ceil(content.length / this.CHARS_PER_TOKEN);
  }

  /**
   * Get character limit from token budget
   */
  getCharLimit(tokens: number): number {
    return tokens * this.CHARS_PER_TOKEN;
  }

  /**
   * Check if content fits within budget
   */
  fits(content: string, budget: number): boolean {
    return this.estimateTokens(content) <= budget;
  }

  /**
   * Truncate content intelligently to fit budget
   * Tries to preserve sentence boundaries when possible
   */
  truncate(content: string, budget: number): TruncationResult {
    const originalTokens = this.estimateTokens(content);
    const maxChars = this.getCharLimit(budget);

    if (content.length <= maxChars) {
      return {
        content: content,
        original: { length: content.length, tokens: originalTokens },
        truncated: { length: content.length, tokens: originalTokens },
        wasTruncated: false,
        percentRetained: 100,
      };
    }

    // Find a good truncation point (end of sentence)
    let truncated = content.substring(0, maxChars);
    
    // Try to find the last sentence boundary
    const lastPeriod = truncated.lastIndexOf('.');
    const lastNewline = truncated.lastIndexOf('\n');
    const lastHeading = truncated.lastIndexOf('#');
    
    const lastGoodPoint = Math.max(lastPeriod, lastNewline, lastHeading);
    
    if (lastGoodPoint > maxChars * 0.7) {
      // If we found a good boundary in the last 30%, use it
      truncated = content.substring(0, lastGoodPoint + 1);
    }

    const truncatedTokens = this.estimateTokens(truncated);
    const percentRetained = Math.round((truncated.length / content.length) * 100);

    return {
      content: truncated + '\n\n[... content truncated to fit context budget ...]',
      original: { length: content.length, tokens: originalTokens },
      truncated: { length: truncated.length, tokens: truncatedTokens },
      wasTruncated: true,
      percentRetained: percentRetained,
    };
  }

  /**
   * Suggest content priority based on task description
   * Returns array of content types in priority order
   */
  suggestPriority(task: string): ContentPriority[] {
    const taskLower = task.toLowerCase();
    const priorities: ContentPriority[] = [];

    // Default: instructions > docs > agent
    let instructionsPriority = 1;
    let docsPriority = 2;
    let agentPriority = 3;

    // Adjust based on task keywords
    if (taskLower.includes('api') || taskLower.includes('rest') || taskLower.includes('endpoint')) {
      agentPriority = 1; // Agent expertise is most valuable
      instructionsPriority = 2;
      docsPriority = 3;
    }

    if (taskLower.includes('security') || taskLower.includes('auth') || taskLower.includes('owasp')) {
      instructionsPriority = 1; // Instructions have security guidelines
      docsPriority = 2;
      agentPriority = 3;
    }

    if (taskLower.includes('performance') || taskLower.includes('optimize')) {
      docsPriority = 1; // Documentation has optimization patterns
      instructionsPriority = 2;
      agentPriority = 3;
    }

    if (taskLower.includes('test') || taskLower.includes('spec')) {
      instructionsPriority = 1; // Testing guidelines
      docsPriority = 2;
      agentPriority = 3;
    }

    if (taskLower.includes('architecture') || taskLower.includes('design')) {
      agentPriority = 1; // Architect agent knows design
      docsPriority = 2;
      instructionsPriority = 3;
    }

    priorities.push(
      { type: 'instructions', priority: instructionsPriority },
      { type: 'docs', priority: docsPriority },
      { type: 'agent', priority: agentPriority }
    );

    return priorities.sort((a, b) => a.priority - b.priority);
  }

  /**
   * Allocate budget based on priority
   * Returns allocation optimized for the task
   */
  allocateByPriority(task: string): ContextAllocation {
    const priorities = this.suggestPriority(task);
    const totalAvailable = this.allocation.total - this.allocation.reserved;

    // Allocate more to higher priority items
    const allocation: ContextAllocation = {
      instructions: 0,
      docs: 0,
      agent: 0,
      reserved: this.allocation.reserved,
      total: this.allocation.total,
    };

    // Weight distribution: 50% to highest, 35% to middle, 15% to lowest
    const weights = [0.5, 0.35, 0.15];

    priorities.forEach((priority, index) => {
      const amount = Math.floor(totalAvailable * weights[index]);
      allocation[priority.type] = amount;
    });

    return allocation;
  }

  /**
   * Get summary of context usage
   */
  getSummary(): object {
    return {
      config: this.config,
      allocation: this.allocation,
      breakdown: {
        instructions: `${this.config.instructionsPercent}% (${this.allocation.instructions} tokens)`,
        docs: `${this.config.docsPercent}% (${this.allocation.docs} tokens)`,
        agent: `${this.config.agentPercent}% (${this.allocation.agent} tokens)`,
        reserved: `${this.config.responsePercent}% (${this.allocation.reserved} tokens)`,
      },
      charLimits: {
        instructions: this.getCharLimit(this.allocation.instructions),
        docs: this.getCharLimit(this.allocation.docs),
        agent: this.getCharLimit(this.allocation.agent),
      },
    };
  }
}

/**
 * Preset configurations for different scenarios
 */
export const PRESETS = {
  // Default balanced approach
  BALANCED: {
    instructionsPercent: 40,
    docsPercent: 30,
    agentPercent: 20,
    responsePercent: 10,
  },

  // Heavy documentation focus
  DOCUMENTATION: {
    instructionsPercent: 30,
    docsPercent: 50,
    agentPercent: 10,
    responsePercent: 10,
  },

  // Agent-heavy for complex guidance
  AGENT_FOCUSED: {
    instructionsPercent: 20,
    docsPercent: 20,
    agentPercent: 50,
    responsePercent: 10,
  },

  // Instruction-heavy for learning/implementation
  LEARNING: {
    instructionsPercent: 60,
    docsPercent: 20,
    agentPercent: 10,
    responsePercent: 10,
  },

  // Minimal context, maximize response
  RESPONSE_FOCUSED: {
    instructionsPercent: 20,
    docsPercent: 20,
    agentPercent: 20,
    responsePercent: 40,
  },

  // High token budget (8000 tokens)
  GENEROUS: {
    instructionsPercent: 40,
    docsPercent: 30,
    agentPercent: 20,
    responsePercent: 10,
  },

  // Low token budget (2000 tokens)
  MINIMAL: {
    instructionsPercent: 40,
    docsPercent: 30,
    agentPercent: 20,
    responsePercent: 10,
  },
};

/**
 * Example usage
 */
export function exampleUsage(): void {
  // Create manager with default budget
  const manager = new ContextManager({ totalBudget: 6000 });

  // Get allocation
  console.log('Default allocation:', manager.getAllocation());

  // Estimate tokens
  const content = 'This is a test string to estimate tokens.';
  console.log(`Content "${content}" = ${manager.estimateTokens(content)} tokens`);

  // Check if content fits
  const budget = manager.getBudgetFor('instructions');
  console.log(`Fits in budget of ${budget}? ${manager.fits(content, budget)}`);

  // Get truncation
  const longContent = 'A'.repeat(50000); // 50KB of text
  const truncated = manager.truncate(longContent, budget);
  console.log('Truncation result:', {
    wasTruncated: truncated.wasTruncated,
    originalTokens: truncated.original.tokens,
    truncatedTokens: truncated.truncated.tokens,
    percentRetained: truncated.percentRetained,
  });

  // Get priority for a task
  const task = 'Implement security authentication for API';
  const priority = manager.suggestPriority(task);
  console.log(`Priority for "${task}":`, priority);

  // Get optimized allocation
  const optimized = manager.allocateByPriority(task);
  console.log('Optimized allocation:', optimized);

  // Get summary
  console.log('Context summary:', manager.getSummary());
}

export default ContextManager;
