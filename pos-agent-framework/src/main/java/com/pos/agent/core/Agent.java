package com.pos.agent.core;

import java.util.List;

import com.pos.agent.context.AgentContext;
import com.pos.agent.framework.model.AgentType;

/**
 * Base interface for all agents in the POS Agent Framework.
 * <p>
 * Defines the frozen contract (REQ-016) that all agents must implement.
 * This interface provides the core capabilities for request processing,
 * health monitoring, capability discovery, and security authorization.
 * <p>
 * Agents following this contract are guaranteed to work with the
 * {@link AgentManager} and the broader agent ecosystem.
 * 
 * @see AbstractAgent
 * @see AgentManager
 * @since 1.0
 */
public interface Agent {
    /**
     * Processes an agent request and returns a response.
     * <p>
     * This is the primary method for agent interaction. Implementations
     * should validate the request, execute the requested operation,
     * and return an appropriate response with success/failure status.
     * 
     * @param request the request to process, must not be null
     * @return the agent response containing status, output, and recommendations
     * @see AgentRequest
     * @see AgentResponse
     */
    AgentResponse processRequest(AgentRequest request);

    /**
     * Gets the current operational status of the agent.
     * <p>
     * The status indicates whether the agent is operational, degraded,
     * or unavailable for processing requests.
     * 
     * @return the current agent status
     * @see AgentStatus
     */
    AgentStatus getStatus();

    /**
     * Checks if the agent is in a healthy operational state.
     * <p>
     * A healthy agent is ready to process requests. This is typically
     * used for health checks and monitoring.
     * 
     * @return {@code true} if the agent is healthy and operational,
     *         {@code false} otherwise
     */
    boolean isHealthy();

    /**
     * Gets the list of capabilities this agent supports.
     * <p>
     * Capabilities describe the specific operations and features
     * this agent can perform, such as "api-documentation",
     * "security-scanning", or "deployment-automation".
     * 
     * @return unmodifiable list of capability identifiers
     */
    List<String> getCapabilities();

    /**
     * Gets the technical domain this agent operates in.
     * <p>
     * The technical domain categorizes the agent's area of expertise,
     * such as DOCUMENTATION, CICD_PIPELINE, or EVENT_DRIVEN_ARCHITECTURE.
     * 
     * @return the technical domain identifier
     * @see AgentType
     */
    AgentType getTechnicalDomain();

    /**
     * Gets the list of roles required to access this agent.
     * <p>
     * Role-based access control (RBAC) determines which users can
     * invoke this agent. Default implementations return an empty list
     * indicating no specific role requirements.
     * 
     * @return list of required role names (e.g., "ADMIN", "DEVELOPER")
     * @see SecurityContext
     */
    List<String> getRequiredRoles();

    /**
     * Gets the list of permissions required to access this agent.
     * <p>
     * Permission-based access control provides fine-grained authorization.
     * Default implementations typically require "AGENT_READ" and "AGENT_WRITE".
     * 
     * @return list of required permission names (e.g., "AGENT_READ",
     *         "CONFIG_MANAGE")
     * @see SecurityContext
     */
    List<String> getRequiredPermissions();

    /**
     * Generates output based on a query string.
     * <p>
     * This method provides domain-specific guidance and recommendations
     * based on the provided query. Concrete agents override this to
     * provide specialized output for their technical domain.
     * 
     * @param query the query string describing the request
     * @return generated output string with guidance and recommendations
     */
    String generateOutput(String query);

    /**
     * Gets or creates an agent context for the specified session.
     * <p>
     * Agent contexts maintain state across multiple interactions
     * within a session, enabling more contextual and relevant responses.
     * 
     * @param sessionId the session identifier
     * @return the agent context for the session
     * @see AgentContext
     */
    AgentContext getOrCreateContext(String sessionId);
}
