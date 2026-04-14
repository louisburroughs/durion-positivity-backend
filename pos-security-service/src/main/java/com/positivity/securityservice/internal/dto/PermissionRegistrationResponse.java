package com.positivity.securityservice.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for permission registration
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionRegistrationResponse {
    private boolean success;
    private String message;
    private int totalPermissions;
    private int registeredPermissions;
    private int updatedPermissions;
    private int skippedPermissions;
    private List<String> errors;
}
