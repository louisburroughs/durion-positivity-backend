package com.positivity.securityservice.service;

import java.util.Set;

public interface RoleAuthorityService {

    String ROLE_PREFIX = "ROLE_";
    // CRM roles
    String ROLE_CSR = "CSR";
    String ROLE_FLEET_MANAGER = "FLEET_MANAGER";
    String ROLE_GENERAL_MANAGER = "GENERAL_MANAGER";
    String ROLE_MANAGER = "MANAGER";
    // Accounting roles
    String ROLE_GL_ANALYST = "GL_ANALYST";
    String ROLE_AP_CLERK = "AP_CLERK";
    String ROLE_ACCOUNTANT = "ACCOUNTANT";
    String ROLE_CONTROLLER = "CONTROLLER";
    // Common admin role
    String ROLE_ADMIN = "ADMIN";

    /**
     * Expand roles to authorities, including ROLE_* and domain permission strings.
     * Supports CRM roles (CSR, FLEET_MANAGER) and Accounting roles (GL_ANALYST,
     * AP_CLERK, etc.).
     */
    Set<String> expandRolesToAuthorities(Set<String> roles);

}
