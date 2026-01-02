package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.framework.audit.AuditTrailManager;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.impl.StoryValidationAgent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages agent lifecycle and request processing.
 * Handles security validation, routing, and response generation.
 */
public class AgentManager implements AgentRegistry {
    private final AuditTrailManager auditTrailManager;
    private final List<Agent> registeredAgents;

    public AgentManager() {
        this(new AuditTrailManager());
    }

    public AgentManager(AuditTrailManager auditTrailManager) {
        this.auditTrailManager = auditTrailManager;
        this.registeredAgents = new ArrayList<>();

        // Register default agents
        registerAgent(new StoryValidationAgent());
    }

    /**
     * Register an agent with the manager.
     *
     * @param agent The agent to register
     */
    public void registerAgent(Agent agent) {
        registeredAgents.add(agent);
    }

    /**
     * Processes an agent request with security validation.
     *
     * @param request the agent request to process
     * @return the agent response
     */
    public AgentResponse processRequest(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String userId = SecurityValidation.getInstance().extractUserId(request);
        String agentType = request.getAgentContext().getAgentDomain();
        boolean success = false;

        try {
            // Validate security context
            if (request.getSecurityContext() != null) {
                if (!SecurityValidation.getInstance().validateSecurityContext(request.getSecurityContext())) {
                    // Record failed authentication attempt
                    recordAuditEntry(agentType, userId, "AUTHENTICATION_FAILED", false);

                    return AgentResponse.builder()
                            .success(false)
                            .errorMessage("Authentication failed: Invalid security credentials")
                            .processingTimeMs(System.currentTimeMillis() - startTime)
                            .build();
                }

            }

            // Try to find a registered agent that can handle the request
            // TODO implement real agent activity and routing logic
            Agent handlingAgent = findAgentForRequest(request);

            // Check role-based authorization using agent's declared requirements
            if (handlingAgent != null && !SecurityValidation.getInstance().validateAuthorization(request, handlingAgent)) {
                // Record failed authorization attempt
                recordAuditEntry(agentType, userId, "AUTHORIZATION_FAILED", false);

                return AgentResponse.builder()
                        .success(false)
                        .errorMessage("Authorization failed: Insufficient permissions for " + agentType)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            if (handlingAgent != null) {
                // Delegate to the registered agent
                AgentResponse response = handlingAgent.processRequest(request);
                success = response.isSuccess();

                // Record request processing
                recordAuditEntry(agentType, userId,
                        success ? "REQUEST_PROCESSED" : "REQUEST_FAILED", success);

                return response;
            }

            // Fallback to default processing if no agent handles it
            try {
                Thread.sleep(1); // Simulate minimal processing time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long processingTime = System.currentTimeMillis() - startTime;

            // Generate output based on request type
            String output = generateOutput(request);
            success = true;

            // Record successful request processing
            recordAuditEntry(agentType, userId, "REQUEST_PROCESSED", true);

            return AgentResponse.builder()
                    .success(true)
                    .status("SUCCESS")
                    .output(output)
                    .confidence(0.95)
                    .processingTimeMs(processingTime)
                    .build();
        } catch (Exception e) {
            // Record failed request processing
            recordAuditEntry(agentType, userId, "REQUEST_FAILED", false);
            throw e;
        }
    }

    /**
     * Find an agent that can handle the given request.
     * For StoryValidationAgent, checks if it can handle the request
     * based on activation conditions.
     *
     * @param request The request to find an agent for
     * @return The agent that can handle the request, or null if none found
     */
    private Agent findAgentForRequest(AgentRequest request) {
        for (Agent agent : registeredAgents) {
            // Special handling for StoryValidationAgent
            if (agent instanceof StoryValidationAgent) {
                // Always delegate story domain requests to StoryValidationAgent
                // The agent will determine if it can handle based on activation conditions
                if (request.getAgentContext() instanceof com.pos.agent.context.AgentContext) {
                    com.pos.agent.context.AgentContext ctx = (com.pos.agent.context.AgentContext) request
                            .getAgentContext();
                    if ("story".equals(ctx.getAgentDomain())) {
                        return agent;
                    }
                }
            }
        }
        return null;
    }

    // TODO: Performance Optimization Step 2 - Optimize audit trail recording
    // Make audit recording asynchronous using a queue (e.g., BlockingQueue or
    // Disruptor)
    // to avoid blocking request processing. Consider batching multiple audit
    // entries
    // and writing them in bulk. Make audit recording configurable/optional for
    // performance tests.
    private void recordAuditEntry(String agentType, String userId, String action, boolean success) {
        AuditTrailManager.AuditEntry entry = new AuditTrailManager.AuditEntry(
                agentType,
                userId,
                action,
                success);
        auditTrailManager.recordAuditEntry(entry);
    }

    public AuditTrailManager getAuditTrailManager() {
        return auditTrailManager;
    }

    
    private String generateOutput(AgentRequest request, Agent agent) {
        //TODO Generate a real query from the request context

        String query = "general request";

        // Try to extract query from context
        AgentContext contextObj = request.getAgentContext();
        if (contextObj != null) {

            // For documentation requests, try to extract meaningful info from AgentContext
            // The context contains "focus" property which hints at the request type
            query = "documentation synchronization request";

        }

        return agent.generateOutput(query);
      
    }


   

    @Override
    public boolean unregisterAgent(Agent agent) {
        registeredAgents.remove(agent);
        return true;
    }

    @Override
    public void clearAgents() {
        registeredAgents.clear();
    }

    @Override
    public List<Agent> listAgents() {
        return Collections.unmodifiableList(registeredAgents);
    }

    @Override
    public CompletableFuture<AgentResponse> consultBestAgent(AgentRequest request) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'consultBestAgent'");
    }

    @Override
    public RegistryHealthStatus getHealthStatus() {
        return new RegistryHealthStatus(registeredAgents.size(),
                registeredAgents.stream().filter(Agent::isHealthy).count());
    }

    @Override
    public List<Agent> getAgentsWithCapabilities(Set<String> capabilities) {
        return registeredAgents.stream()
                .filter(agent -> {
                    List<String> agentCapabilities = agent.getCapabilities();
                    return agentCapabilities != null &&
                            agentCapabilities.stream().anyMatch(capabilities::contains);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getAgentsForTechnicalDomain(String technicalDomain) {
        return registeredAgents.stream()
                .filter(agent -> {
                    AgentType agentTechnicalDomain = agent.getTechnicalDomain();
                    return agentTechnicalDomain != null && agentTechnicalDomain.equalsIgnoreCase(technicalDomain);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getAgentsForAgentType(AgentType searchAgentType) {
        return registeredAgents.stream()
                .filter(agent -> {
                    AgentType agentTechnicalDomain = agent.getTechnicalDomain();
                    return agentTechnicalDomain != null && agentTechnicalDomain.equals(searchAgentType);
                })
                .collect(Collectors.toList());
    }

}
