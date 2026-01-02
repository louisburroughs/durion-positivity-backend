# Agent Interface Documentation

## Overview

The `Agent` interface defines the frozen contract (REQ-016) for all agents in the POS (Positivity) Agent Framework. It establishes the core capabilities and behaviors that all agent implementations must follow, ensuring compatibility with the `AgentManager` and the broader agent ecosystem.

## Package Information

- **Package:** `com.pos.agent.core`
- **Module:** `pos-agent-framework`
- **Version:** 1.0
- **Status:** Frozen Contract (REQ-016)

## Purpose

The `Agent` interface serves as the contract that all agents must implement to:
1. Process incoming requests and generate responses
2. Report operational status and health
3. Advertise capabilities and technical domains
4. Enforce security through role-based and permission-based access control
5. Maintain session-specific context across multiple interactions

## Core Methods

### Request Processing

#### `AgentResponse processRequest(AgentRequest request)`

The primary method for agent interaction.

- **Parameters:**
  - `request` (AgentRequest): The request to process, must not be null
  
- **Returns:**
  - `AgentResponse`: The agent response containing status, output, and recommendations
  
- **Behavior:**
  - Implementations must validate the request
  - Execute the requested operation
  - Return an appropriate response with success/failure status
  
- **Related Types:**
  - `AgentRequest` - Input request structure
  - `AgentResponse` - Output response structure

---

### Health and Status Monitoring

#### `AgentStatus getStatus()`

Returns the current operational status of the agent.

- **Returns:**
  - `AgentStatus`: The current agent status (operational, degraded, or unavailable)
  
- **Use Cases:**
  - Monitoring and alerting systems
  - Agent lifecycle management
  - Load balancing and failover

#### `boolean isHealthy()`

Checks if the agent is in a healthy operational state.

- **Returns:**
  - `true` if the agent is healthy and operational
  - `false` otherwise
  
- **Use Cases:**
  - Pre-request health checks
  - Health check endpoints
  - Service mesh integration

---

### Capability Discovery

#### `List<String> getCapabilities()`

Gets the list of capabilities this agent supports.

- **Returns:**
  - Unmodifiable list of capability identifiers
  
- **Capability Examples:**
  - `"api-documentation"` - API documentation generation
  - `"security-scanning"` - Security analysis and vulnerability detection
  - `"deployment-automation"` - Automated deployment and orchestration
  - `"code-review"` - Code quality and review analysis
  
- **Use Cases:**
  - Dynamic agent discovery
  - Request routing to appropriate agents
  - UI/UX capability display

#### `AgentType getTechnicalDomain()`

Gets the technical domain this agent operates in.

- **Returns:**
  - `AgentType`: The technical domain identifier
  
- **Domain Examples:**
  - `DOCUMENTATION` - Documentation-focused agents
  - `CICD_PIPELINE` - CI/CD and deployment agents
  - `EVENT_DRIVEN_ARCHITECTURE` - Event-driven system agents
  - `SECURITY` - Security and compliance agents
  
- **Use Cases:**
  - Categorizing agents by expertise area
  - Contextual request routing
  - Specialized agent selection

---

### Security and Access Control

#### `List<String> getRequiredRoles()`

Gets the list of roles required to access this agent.

- **Returns:**
  - List of required role names
  
- **Role Examples:**
  - `"ADMIN"` - Administrator access
  - `"DEVELOPER"` - Developer-level access
  - `"VIEWER"` - Read-only access
  
- **Default Behavior:**
  - Default implementations return an empty list (no specific role requirements)
  
- **Security Model:**
  - Role-Based Access Control (RBAC)
  - Checked before request processing
  
- **Related:**
  - `SecurityContext` - Security context implementation

#### `List<String> getRequiredPermissions()`

Gets the list of permissions required to access this agent.

- **Returns:**
  - List of required permission names
  
- **Permission Examples:**
  - `"AGENT_READ"` - Permission to read agent data
  - `"AGENT_WRITE"` - Permission to modify agent data
  - `"CONFIG_MANAGE"` - Permission to manage configuration
  - `"SECURITY_AUDIT"` - Permission for security auditing
  
- **Default Behavior:**
  - Default implementations typically require `"AGENT_READ"` and `"AGENT_WRITE"`
  
- **Security Model:**
  - Fine-grained Permission-Based Access Control (PBAC)
  - More granular than role-based access
  
- **Related:**
  - `SecurityContext` - Security context implementation

---

### Output Generation

#### `String generateOutput(String query)`

Generates output based on a query string.

- **Parameters:**
  - `query` (String): The query string describing the request
  
- **Returns:**
  - Generated output string with guidance and recommendations
  
- **Behavior:**
  - Provides domain-specific guidance and recommendations
  - Concrete agents override this to provide specialized output
  - Output is tailored to the agent's technical domain
  
- **Use Cases:**
  - Query-based response generation
  - Domain-specific analysis and recommendations
  - Extended context beyond structured request/response

---

### Session and Context Management

#### `AgentContext getOrCreateContext(String sessionId)`

Gets or creates an agent context for the specified session.

- **Parameters:**
  - `sessionId` (String): The session identifier
  
- **Returns:**
  - `AgentContext`: The agent context for the session
  
- **Behavior:**
  - If context exists, returns the existing context
  - If context doesn't exist, creates and returns a new one
  - Maintains state across multiple interactions within a session
  
- **Use Cases:**
  - Multi-turn conversations with context awareness
  - State persistence across requests
  - Session-specific agent configuration

#### `void updateContext(String sessionId, String guidance)`

Updates the agent context for a session with new guidance.

- **Parameters:**
  - `sessionId` (String): The session identifier
  - `guidance` (String): The guidance information to update
  
- **Behavior:**
  - Updates existing context with new guidance data
  - May throw exception if session doesn't exist
  
- **Use Cases:**
  - Refining agent behavior based on feedback
  - Progressive context updates during conversation
  - Learning and adaptation within a session

#### `void removeContext(String sessionId)`

Removes the agent context for the specified session.

- **Parameters:**
  - `sessionId` (String): The session identifier
  
- **Behavior:**
  - Deletes the context associated with the session
  - Subsequent requests will create a new context
  
- **Use Cases:**
  - Session cleanup and resource management
  - Explicit session termination
  - Privacy and data retention compliance

---

## Related Types and Interfaces

### Core Types

| Type | Location | Purpose |
|------|----------|---------|
| `AgentRequest` | `com.pos.agent.core` | Input request structure |
| `AgentResponse` | `com.pos.agent.core` | Output response structure |
| `AgentStatus` | `com.pos.agent.core` | Agent status enumeration |
| `AgentContext` | `com.pos.agent.context` | Session context management |
| `AgentType` | `com.pos.agent.framework.model` | Technical domain enumeration |
| `SecurityContext` | `com.pos.agent.security` | Security context for RBAC/PBAC |

### Related Interfaces

| Interface | Purpose |
|-----------|---------|
| `AbstractAgent` | Base implementation class |
| `AgentManager` | Agent lifecycle and request routing management |

---

## Implementation Patterns

### Basic Implementation

```java
public class MyAgent implements Agent {
    
    @Override
    public AgentResponse processRequest(AgentRequest request) {
        // Validate request
        // Execute operation
        // Return response
        return new AgentResponse(...);
    }
    
    @Override
    public AgentStatus getStatus() {
        return AgentStatus.OPERATIONAL;
    }
    
    @Override
    public boolean isHealthy() {
        return getStatus() == AgentStatus.OPERATIONAL;
    }
    
    @Override
    public List<String> getCapabilities() {
        return List.of("capability-1", "capability-2");
    }
    
    @Override
    public AgentType getTechnicalDomain() {
        return AgentType.DOCUMENTATION;
    }
    
    @Override
    public List<String> getRequiredRoles() {
        return List.of("DEVELOPER", "ADMIN");
    }
    
    @Override
    public List<String> getRequiredPermissions() {
        return List.of("AGENT_READ", "AGENT_WRITE");
    }
    
    // ... additional implementations
}
```

### With Context Management

```java
public class ContextAwareAgent implements Agent {
    private Map<String, AgentContext> contexts = new ConcurrentHashMap<>();
    
    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return contexts.computeIfAbsent(
            sessionId, 
            k -> new AgentContext(sessionId)
        );
    }
    
    @Override
    public void updateContext(String sessionId, String guidance) {
        AgentContext context = contexts.get(sessionId);
        if (context != null) {
            context.updateGuidance(guidance);
        }
    }
    
    @Override
    public void removeContext(String sessionId) {
        contexts.remove(sessionId);
    }
}
```

---

## Security Considerations

### Access Control Layers

1. **Role-Based Access Control (RBAC)**
   - Coarse-grained authorization
   - Determined by `getRequiredRoles()`
   - User must have at least one required role

2. **Permission-Based Access Control (PBAC)**
   - Fine-grained authorization
   - Determined by `getRequiredPermissions()`
   - User must have all required permissions

3. **Request Validation**
   - Implement in `processRequest()`
   - Validate request parameters
   - Check for malicious input

### Best Practices

- Always return empty list if no roles/permissions required
- Default to most restrictive configuration
- Document security assumptions in implementation
- Use `SecurityContext` for validation
- Log access control decisions

---

## Status Codes

Agent status values guide operational decisions:

| Status | Meaning | Action |
|--------|---------|--------|
| `OPERATIONAL` | Agent is healthy and ready | Accept requests normally |
| `DEGRADED` | Agent is operational but limited | Route to other agents if available |
| `UNAVAILABLE` | Agent cannot process requests | Reject requests, use fallback |

---

## Best Practices

1. **Request Validation**
   - Always validate `AgentRequest` before processing
   - Check required fields and constraints
   - Return error `AgentResponse` for invalid requests

2. **Health Monitoring**
   - Implement meaningful `getStatus()` that reflects actual health
   - `isHealthy()` should align with `getStatus()`
   - Consider dependencies and resource availability

3. **Capability Declaration**
   - Be accurate about supported capabilities
   - Update capabilities when agent is modified
   - Use consistent capability naming

4. **Context Management**
   - Clean up contexts when sessions end
   - Set reasonable TTL/expiration for contexts
   - Avoid memory leaks from orphaned contexts

5. **Security**
   - Always specify required roles and permissions
   - Validate security context in `processRequest()`
   - Log security-related decisions

6. **Error Handling**
   - Provide meaningful error messages in responses
   - Distinguish between client and server errors
   - Include remediation guidance where possible

---

## Versioning

- **Current Version:** 1.0
- **API Contract:** Frozen (REQ-016)
- **Breaking Changes:** Not allowed without major version bump
- **Deprecation:** Use `@Deprecated` annotation with guidance

---

## See Also

- [AbstractAgent Documentation](./ABSTRACT_AGENT_DOCUMENTATION.md)
- [AgentManager Documentation](./AGENT_MANAGER_DOCUMENTATION.md)
- [AgentContext Documentation](./AGENT_CONTEXT_DOCUMENTATION.md)
- REQ-016: Agent Framework Frozen Contract
