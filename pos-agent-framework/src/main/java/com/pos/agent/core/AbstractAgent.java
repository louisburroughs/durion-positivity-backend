package com.pos.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;
import com.pos.agent.framework.model.AgentType;

/**
 * Abstract base class for all agents.
 * Centralizes validation and failure response creation,
 * allowing concrete agents to focus on domain logic.
 * Part of frozen contract specification (REQ-016)
 */
public abstract class AbstractAgent implements Agent {

    private AgentStatus status = AgentStatus.HEALTHY;
    private final List<String> capabilities = new ArrayList<>();
    private final AgentType technicalDomain;
    private static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    /**
     * Creates an agent with the specified domain and capabilities.
     *
     * @param technicalDomain technical domain classification for the agent
     * @param capabilities    capabilities the agent supports; may be null
     */
    public AbstractAgent(AgentType technicalDomain, List<String> capabilities) {
        this.technicalDomain = technicalDomain;
        if (capabilities != null) {
            this.capabilities.addAll(capabilities);
        }
    }

    /**
     * Returns an existing context for the session or creates a new one.
     *
     * @param sessionId unique session identifier
     * @return cached or newly created agent context
     */
    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> DefaultContext.builder().requestId(sessionId).build());
    }

    /** {@inheritDoc} */
    @Override
    public AgentStatus getStatus() {
        return status;
    }

    /**
     * Updates the agent status.
     *
     * @param status new status value
     */
    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isHealthy() {
        return this.status == AgentStatus.HEALTHY;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    /** {@inheritDoc} */
    @Override
    public AgentType getTechnicalDomain() {
        return technicalDomain;
    }

    /**
     * Gets the list of roles required to access this agent.
     * Default implementation returns empty list - override in concrete agents to
     * specify requirements.
     * 
     * @return list of required role names
     */
    @Override
    public List<String> getRequiredRoles() {
        return Collections.emptyList();
    }

    /**
     * Gets the list of permissions required to access this agent.
     * Default implementation returns basic permissions: AGENT_READ, AGENT_WRITE.
     * Override in concrete agents to specify different requirements.
     * 
     * @return list of required permission names
     */
    @Override
    public List<String> getRequiredPermissions() {
        return List.of("AGENT_READ", "AGENT_WRITE");
    }

    /** {@inheritDoc} */
    @Override
    public String generateOutput(String query) {
        String domain = getTechnicalDomain().name().toLowerCase().replace('_', '-');
        return "Agent guidance for: " + query + " in " + domain + " domain";
    }

    /** {@inheritDoc} */
    @Override
    public final AgentResponse processRequest(AgentRequest request) {
        // Validate request
        String validationError = validateRequest(request);
        if (validationError != null) {
            return createFailureResponse(validationError);
        }

        // Delegate to implementation
        return doProcessRequest(request);
    }

    /**
     * Validate the request. Returns null if valid, error message otherwise.
     *
     * @param request the request to validate
     * @return null if valid, error message otherwise
     */
    protected String validateRequest(AgentRequest request) {
        if (request == null) {
            return "Invalid request: request is null";
        }

        if (request.getAgentContext() == null) {
            return "Invalid request: agent context is required";
        }

        return null;
    }

    /**
     * Create a failure response with the given message.
     *
     * @param message error message to include
     * @return failure response
     */
    protected AgentResponse createFailureResponse(String message) {
        return AgentResponse.builder()
                .status(AgentProcessingState.FAILURE)
                .output(message)
                .confidence(0.0)
                .success(false)
                .errorMessage(message)
                .recommendations(Collections.emptyList())
                .build();
    }

    /**
     * Process the request after validation.
     * Subclasses must implement this method to provide their specific logic.
     *
     * @param request the validated request
     * @return the agent response
     */
    protected abstract AgentResponse doProcessRequest(AgentRequest request);

    @Override
    public void updateContext(String sessionId, String guidance) {
        DefaultContext ctx = (DefaultContext) getOrCreateContext(sessionId);
        ctx.getProperties().put("guidance", guidance);
    }

    @Override
    public void removeContext(String sessionId) {
        CONTEXT_MAP.remove(sessionId);
    }
}