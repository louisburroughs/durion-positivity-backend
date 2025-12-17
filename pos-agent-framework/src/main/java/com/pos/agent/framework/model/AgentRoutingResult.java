package com.pos.agent.framework.model;

/**
 * Result of agent routing operation containing the selected agent,
 * routing reason, and success/failure status.
 */
public class AgentRoutingResult {
    
    private final boolean success;
    private final String selectedAgent;
    private final String targetService;
    private final String routingReason;
    private final String errorMessage;
    
    private AgentRoutingResult(boolean success, String selectedAgent, String targetService, 
                              String routingReason, String errorMessage) {
        this.success = success;
        this.selectedAgent = selectedAgent;
        this.targetService = targetService;
        this.routingReason = routingReason;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates a successful routing result.
     */
    public static AgentRoutingResult success(String selectedAgent, String targetService, String routingReason) {
        return new AgentRoutingResult(true, selectedAgent, targetService, routingReason, null);
    }
    
    /**
     * Creates a failed routing result.
     */
    public static AgentRoutingResult failure(String errorMessage, String targetService) {
        return new AgentRoutingResult(false, null, targetService, null, errorMessage);
    }
    
    // Getters
    public boolean isSuccess() { return success; }
    public String getSelectedAgent() { return selectedAgent; }
    public String getTargetService() { return targetService; }
    public String getRoutingReason() { return routingReason; }
    public String getErrorMessage() { return errorMessage; }
    
    @Override
    public String toString() {
        if (success) {
            return String.format("AgentRoutingResult{success=true, agent='%s', service='%s', reason='%s'}", 
                               selectedAgent, targetService, routingReason);
        } else {
            return String.format("AgentRoutingResult{success=false, service='%s', error='%s'}", 
                               targetService, errorMessage);
        }
    }
}
