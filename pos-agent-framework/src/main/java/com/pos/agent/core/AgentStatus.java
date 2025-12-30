package com.pos.agent.core;

/**
 * Status enumeration for agent responses.
 * Represents the outcome of agent request processing.
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
     * Agent processing was stopped/cancelled
     */
    STOPPED,
    
    /**
     * Agent request is pending/in progress
     */
    PENDING
}
