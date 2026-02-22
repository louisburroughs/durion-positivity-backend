package com.positivity.securityservice.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Authorization decision response payload.
 *
 * Issue: #42
 */
@Data
@AllArgsConstructor
public class AuthorizationDecisionResponse {
    private String decision;
}
