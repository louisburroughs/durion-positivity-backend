package com.pos.agent.core;

/**
 * Mixin interface for loop-breaker functionality (REQ-017)
 * Enforces iteration limits and pattern detection
 */
public interface LoopBreakerMixin {
    
    /**
     * Reset iteration counter for new request
     */
    void resetIterationCounter();
    
    /**
     * Increment iteration counter
     */
    void incrementIterationCounter();
    
    /**
     * Get current iteration count
     */
    int getIterationCount();
    
    /**
     * Get maximum allowed iterations
     */
    int getMaxIterations();
}
