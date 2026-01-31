package com.positivity.people.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Registers people permissions with pos-security-service at startup.
 */
@Component
public class PeoplePermissionRegistration extends PermissionRegistrationSupport {

    public PeoplePermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "people", "pos-people", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Employee Management
                PermissionDefinition.of("people:employee:view", "View employee records"),
                PermissionDefinition.of("people:employee:create", "Create employee records"),
                PermissionDefinition.of("people:employee:edit", "Edit employee records"),
                PermissionDefinition.of("people:employee:deactivate", "Deactivate employee records"),

                // Role Assignment
                PermissionDefinition.of("people:role:view", "View role assignments"),
                PermissionDefinition.of("people:role:assign", "Assign roles to employees"),
                PermissionDefinition.of("people:role:revoke", "Revoke roles from employees"),

                // Skill Management
                PermissionDefinition.of("people:skill:view", "View employee skills"),
                PermissionDefinition.of("people:skill:assign", "Assign skills to employees"),
                PermissionDefinition.of("people:skill:edit", "Edit skill assignments"));
    }
}
