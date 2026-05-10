package com.positivity.mcp.internal.service;

import java.util.List;

/**
 * Shared default prompt constants. Package-private — not part of the public
 * service API.
 */
final class SystemPromptDefaults {

    static final String DEFAULT_PROMPT_NAME = "default";

    // SQL-seeded security roles from pos-security-service
    // (R__seed_reference_security.sql)
    static final String ROLE_ADMIN_PROMPT_NAME = "ROLE_ADMIN";
    static final String ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME = "ROLE_SYSTEM_ADMINISTRATOR";
    static final String ROLE_ACCOUNT_MANAGER_PROMPT_NAME = "ROLE_ACCOUNT_MANAGER";
    static final String ROLE_ACCOUNTING_ASSOCIATE_PROMPT_NAME = "ROLE_ACCOUNTING_ASSOCIATE";
    static final String ROLE_LOCATION_MANAGER_PROMPT_NAME = "ROLE_LOCATION_MANAGER";
    static final String ROLE_SERVICE_ADVISOR_PROMPT_NAME = "ROLE_SERVICE_ADVISOR";
    static final String ROLE_DISPATCHER_PROMPT_NAME = "ROLE_DISPATCHER";
    static final String ROLE_TECHNICIAN_PROMPT_NAME = "ROLE_TECHNICIAN";
    static final String ROLE_CUSTOMER_PROMPT_NAME = "ROLE_CUSTOMER";
    static final String ROLE_SELF_SERVICE_CUSTOMER_PROMPT_NAME = "ROLE_SELF_SERVICE_CUSTOMER";

    static final List<String> MCP_ROLE_PRIORITY = List.of(
            ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME,
            ROLE_ADMIN_PROMPT_NAME,
            ROLE_LOCATION_MANAGER_PROMPT_NAME,
            ROLE_ACCOUNT_MANAGER_PROMPT_NAME,
            ROLE_ACCOUNTING_ASSOCIATE_PROMPT_NAME,
            ROLE_SERVICE_ADVISOR_PROMPT_NAME,
            ROLE_DISPATCHER_PROMPT_NAME,
            ROLE_TECHNICIAN_PROMPT_NAME,
            ROLE_CUSTOMER_PROMPT_NAME,
            ROLE_SELF_SERVICE_CUSTOMER_PROMPT_NAME);

    static final String DEFAULT_PROMPT_TEXT = "You are a concise POS assistant for Positivity. Answer general conversation directly. "
            + "Do not invent business data.";

    private SystemPromptDefaults() {
    }
}
