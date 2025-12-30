package com.pos.agent.core;

/**
 * Enum representing the status of an agent response.
 * Part of frozen contract specification (REQ-016)
 */
public enum AgentStatus {
    /**
     * Agent successfully processed the request
     */
    SUCCESS,
    
    /**
     * Agent failed to process the request
     */
    FAILURE,
    
    /**
     * Agent processing was stopped
     */
    STOPPED,
    
    /**
     * Agent processing is pending
     */
    PENDING
}
