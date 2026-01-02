package com.pos.agent.core;

/**
 * Permission constants for agent framework authorization.
 * These permissions are used in SecurityContext for role-based access control.
 * Organized by category: Core Agent, Service, Configuration, Audit, and
 * Specialized Operations.
 */
public enum Permissions {
    // Core Agent Operations
    AGENT_READ,
    AGENT_WRITE,
    AGENT_EXECUTE,
    AGENT_DELETE,
    AGENT_ADMIN,

    // Service Operations
    SERVICE_READ,
    SERVICE_WRITE,
    SERVICE_INTEGRATION,

    // Configuration & Secrets Management
    CONFIG_MANAGE,
    SECRETS_MANAGE,

    // Audit & Compliance
    AUDIT_READ,
    AUDIT_MANAGE,

    // Specialized Operations
    SECURITY_VALIDATE,
    PERFORMANCE_TEST,
    DOMAIN_ACCESS,
    DATASTORE_ACCESS,
    QUALITY_ASSESS,
    TESTING_GUIDE,
    COLLABORATION_PROCESS,
    SERVICE_MAP,
    FALLBACK_SELECT
}
