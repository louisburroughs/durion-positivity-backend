package com.positivity.people.internal.client;

import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.ScopeType;
import com.positivity.people.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.people.internal.client.dto.UserRoleDto;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SecurityServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SecurityServiceClient.class);

    private final RestClient restClient;

    public SecurityServiceClient(@Qualifier("securityServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Get a role by its name to obtain its UUID
     */
    @NonNull
    private Role getRoleByName(@NonNull String roleName) {
        log.debug("Fetching role by name: {}", roleName);

        Role role = restClient.get()
                .uri("/v1/roles/{name}", roleName)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404,
                        (request, response) -> {
                            throw new jakarta.persistence.EntityNotFoundException(
                                    "Role not found with name: " + roleName);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new IllegalStateException("Security service failed while fetching role");
                        })
                .body(Role.class);

        if (role == null) {
            throw new IllegalStateException("Security service returned null response for role: " + roleName);
        }

        return role;
    }

    @NonNull
    public List<RoleDto> getAvailableRoles(@NonNull String scope) {
        log.debug("Fetching available roles for scope: {}", scope);

        List<RoleDto> roles = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles")
                        .queryParam("scopeType", scope)
                        .build())
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400,
                        (request, response) -> {
                            throw new IllegalArgumentException("Invalid scope for role lookup: " + scope);
                        })
                .onStatus(statusCode -> statusCode.value() == 404,
                        (request, response) -> {
                            throw new jakarta.persistence.EntityNotFoundException(
                                    "Roles endpoint not found in security service");
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new IllegalStateException("Security service failed while listing roles");
                        })
                .body(new ParameterizedTypeReference<List<RoleDto>>() {
                });

        if (roles == null) {
            throw new IllegalStateException("Security service returned null response for roles lookup with scope: " + scope);
        }

        return roles;
    }

    @NonNull
    public List<UserRoleDto> getUserRoleAssignments(
            @NonNull String userId,
            Boolean includeHistory,
            LocalDateTime endDate) {
        log.debug("Fetching role assignments for userId: {}, includeHistory: {}, endDate: {}",
                userId, includeHistory, endDate);

        List<UserRoleDto> assignments = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/user/{userId}")
                        .queryParam("includeHistory", includeHistory)
                        .queryParam("endDate", endDate)
                        .build(userId))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400,
                        (request, response) -> {
                            throw new IllegalArgumentException(
                                    "Invalid request while listing assignments for userId: " + userId);
                        })
                .onStatus(statusCode -> statusCode.value() == 404,
                        (request, response) -> {
                            throw new jakarta.persistence.EntityNotFoundException(
                                    "No role assignments found for userId: " + userId);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new IllegalStateException("Security service failed while listing role assignments");
                        })
                .body(new ParameterizedTypeReference<List<UserRoleDto>>() {
                });

        if (assignments == null) {
            throw new IllegalStateException("Security service returned empty response for user role assignments");
        }

        return assignments;
    }

    @NonNull
    public UserRoleDto assignRole(@NonNull UserRoleAssignmentRequest request) {
        log.debug("Assigning role {} to userId: {}", request.getRoleCode(), request.getUserId());

        // First, get the role by name to obtain its UUID
        Role role = getRoleByName(request.getRoleCode());

        // Build the proper RoleAssignmentRequest matching the API contract
        RoleAssignmentRequest apiRequest = new RoleAssignmentRequest(
                java.util.UUID.fromString(request.getUserId()),
                role.getId(),
                request.getLocationId() != null ? ScopeType.LOCATION : ScopeType.GLOBAL,
                request.getLocationId() != null ? java.util.Set.of(request.getLocationId().toString()) : null,
                request.getStartDate() != null ? request.getStartDate().toLocalDate() : null,
                request.getEndDate() != null ? request.getEndDate().toLocalDate() : null
        );

        RoleAssignment assignment = restClient.post()
                .uri("/v1/roles/assignments")
                .body(apiRequest)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400,
                        (httpRequest, httpResponse) -> {
                            throw new IllegalArgumentException(
                                    "Invalid role assignment request for userId: " + request.getUserId());
                        })
                .onStatus(statusCode -> statusCode.value() == 404,
                        (httpRequest, httpResponse) -> {
                            throw new jakarta.persistence.EntityNotFoundException(
                                    "User or role not found for assignment request");
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (httpRequest, httpResponse) -> {
                            throw new IllegalStateException("Security service failed while assigning role");
                        })
                .body(RoleAssignment.class);

        if (assignment == null) {
            throw new IllegalStateException("Security service returned empty response for role assignment creation");
        }

        // Map RoleAssignment to UserRoleDto for backward compatibility
        return UserRoleDto.builder()
                .userId(request.getUserId())
                .roleCode(request.getRoleCode())
                .locationId(request.getLocationId())
                .startDate(assignment.getEffectiveStartDate() != null
                        ? assignment.getEffectiveStartDate().atStartOfDay() : null)
                .endDate(assignment.getEffectiveEndDate() != null
                        ? assignment.getEffectiveEndDate().atStartOfDay() : null)
                .active(assignment.getEffectiveEndDate() == null
                        || assignment.getEffectiveEndDate().isAfter(java.time.LocalDate.now()))
                .build();
    }

    public void revokeRole(@NonNull String userId, @NonNull String roleCode, LocalDateTime endDate) {
        log.debug("Revoking role {} from userId: {} with endDate: {}", roleCode, userId, endDate);

        // First, get the role by name to obtain its UUID
        Role role = getRoleByName(roleCode);

        // Get all current assignments for the user to find the one matching this role
        List<UserRoleDto> assignments = getUserRoleAssignments(userId, false, null);

        // Find the assignment for this specific role
        java.util.UUID assignmentId = assignments.stream()
                .filter(a -> roleCode.equals(a.getRoleCode()))
                .findFirst()
                .map(a -> {
                    // We need to fetch the full assignments with IDs
                    // Unfortunately UserRoleDto doesn't have the assignment ID
                    // So we need to call the raw API endpoint
                    try {
                        List<RoleAssignment> fullAssignments = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v1/roles/assignments/user/{userId}")
                                        .queryParam("includeHistory", false)
                                        .build(userId))
                                .retrieve()
                                .body(new ParameterizedTypeReference<List<RoleAssignment>>() {
                                });

                        if (fullAssignments == null) {
                            throw new IllegalStateException("Security service returned null response");
                        }

                        return fullAssignments.stream()
                                .filter(fa -> role.getId().equals(fa.getRoleId()))
                                .map(RoleAssignment::getId)
                                .findFirst()
                                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                                        "Role assignment not found for userId: " + userId + ", roleCode: " + roleCode));
                    } catch (Exception e) {
                        if (e instanceof jakarta.persistence.EntityNotFoundException) {
                            throw (jakarta.persistence.EntityNotFoundException) e;
                        }
                        throw new IllegalStateException("Failed to find assignment ID", e);
                    }
                })
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Role assignment not found for userId: " + userId + ", roleCode: " + roleCode));

        java.time.LocalDate effectiveEndDate = endDate != null ? endDate.toLocalDate() : java.time.LocalDate.now();

        restClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/{assignmentId}")
                        .queryParam("endDate", effectiveEndDate)
                        .build(assignmentId))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400,
                        (request, response) -> {
                            throw new IllegalArgumentException(
                                    "Invalid role revocation request for userId: " + userId + ", roleCode: "
                                            + roleCode);
                        })
                .onStatus(statusCode -> statusCode.value() == 404,
                        (request, response) -> {
                            throw new jakarta.persistence.EntityNotFoundException(
                                    "Role assignment not found for userId: " + userId + ", roleCode: " + roleCode);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new IllegalStateException("Security service failed while revoking role assignment");
                        })
                .toBodilessEntity();
    }
}