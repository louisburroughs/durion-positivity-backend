package com.positivity.securityservice.internal.enums;

/**
 * Defines the scope type for role assignments.
 * GLOBAL: Permission applies to all resources regardless of location
 * LOCATION: Permission applies only to resources in specified locations
 */
public enum ScopeType {
    GLOBAL,
    LOCATION
}
