package com.positivity.people.internal.client;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.ScopeType;
import com.positivity.people.internal.client.dto.User;
import com.positivity.people.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.people.internal.client.dto.UserRoleDto;

@Component
public class SecurityServiceClient {

        private static final Logger log = LoggerFactory.getLogger(SecurityServiceClient.class);

        private final RestClient restClient;

        public SecurityServiceClient(@Qualifier("securityServiceRestClient") RestClient restClient) {
                this.restClient = restClient;
        }

        @NonNull
        public Optional<User> getUserByUsername(@NonNull String username) {
                log.debug("Fetching user by username: {}", username);

                List<User> users = restClient.get()
                                .uri("/v1/users")
                                .retrieve()
                                .onStatus(statusCode -> statusCode.value() == 400,
                                                (request, response) -> {
                                                        throw new IllegalArgumentException(
                                                                        "Invalid username for security lookup: "
                                                                                        + username);
                                                })
                                .onStatus(statusCode -> statusCode.value() == 401 || statusCode.value() == 403,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Not authorized to query users in security service",
                                                                        statusCode);
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while fetching users",
                                                                        statusCode);
                                                })
                                .body(new ParameterizedTypeReference<List<User>>() {
                                });

                if (users == null) {
                        throw new IllegalStateException(
                                        "Security service returned null response for user lookup");
                }

                return users.stream()
                                .filter(user -> user.getUsername() != null
                                                && username.equalsIgnoreCase(user.getUsername()))
                                .findFirst();
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
                                                                        "Role not found in security service: "
                                                                                        + roleName);
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while fetching role",
                                                                        statusCode);
                                                })
                                .body(Role.class);

                if (role == null) {
                        throw new IllegalStateException(
                                        "Security service returned null response for role: " + roleName);
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
                                                        throw new IllegalArgumentException(
                                                                        "Invalid scope for role lookup: " + scope);
                                                })
                                .onStatus(statusCode -> statusCode.value() == 404,
                                                (request, response) -> {
                                                        throw new jakarta.persistence.EntityNotFoundException(
                                                                        "Roles endpoint not found in security service");
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while listing roles",
                                                                        statusCode);
                                                })
                                .body(new ParameterizedTypeReference<List<RoleDto>>() {
                                });

                if (roles == null) {
                        throw new IllegalStateException(
                                        "Security service returned null response for roles lookup with scope: "
                                                        + scope);
                }

                return roles;
        }

        @NonNull
        public List<UserRoleDto> getUserRoleAssignments(
                        @NonNull UUID userId,
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
                                                                        "Invalid request while listing assignments for userId: "
                                                                                        + userId);
                                                })
                                .onStatus(statusCode -> statusCode.value() == 404,
                                                (request, response) -> {
                                                        throw new jakarta.persistence.EntityNotFoundException(
                                                                        "No role assignments found for userId: "
                                                                                        + userId);
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while listing role assignments",
                                                                        statusCode);
                                                })
                                .body(new ParameterizedTypeReference<List<UserRoleDto>>() {
                                });

                if (assignments == null) {
                        throw new IllegalStateException(
                                        "Security service returned empty response for user role assignments");
                }

                return assignments;
        }

        @NonNull
        public UserRoleDto assignRole(@NonNull UserRoleAssignmentRequest request) {
                log.debug("Assigning role {} to userId: {}", request.getRoleCode(), request.getUserId());

                // First, get the role by name to obtain its UUID
                Role role = getRoleByName(request.getRoleCode());

                // Parse and validate userId
                UUID userIdUuid;
                try {
                        userIdUuid = request.getUserId();
                } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid userId format: " + request.getUserId(), e);
                }

                // Build the proper RoleAssignmentRequest matching the API contract
                Set<String> scopeLocationIds = request.getLocationIds() != null && !request.getLocationIds().isEmpty()
                                ? request.getLocationIds().stream().map(UUID::toString)
                                                .collect(java.util.stream.Collectors.toSet())
                                : request.getLocationId() != null ? Set.of(request.getLocationId().toString()) : null;
                RoleAssignmentRequest apiRequest = new RoleAssignmentRequest(
                                userIdUuid,
                                role.getId(),
                                scopeLocationIds != null ? ScopeType.LOCATION : ScopeType.GLOBAL,
                                scopeLocationIds,
                                request.getStartDate() != null ? request.getStartDate().toLocalDate() : null,
                                request.getEndDate() != null ? request.getEndDate().toLocalDate() : null);

                RoleAssignment assignment = restClient.post()
                                .uri("/v1/roles/assignments")
                                .body(apiRequest)
                                .retrieve()
                                .onStatus(statusCode -> statusCode.value() == 400,
                                                (httpRequest, httpResponse) -> {
                                                        throw new IllegalArgumentException(
                                                                        "Invalid role assignment request for userId: "
                                                                                        + request.getUserId());
                                                })
                                .onStatus(statusCode -> statusCode.value() == 404,
                                                (httpRequest, httpResponse) -> {
                                                        throw new jakarta.persistence.EntityNotFoundException(
                                                                        "User or role not found for assignment request");
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (httpRequest, httpResponse) -> {
                                                        int statusCode = httpResponse.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while assigning role",
                                                                        statusCode);
                                                })
                                .body(RoleAssignment.class);

                if (assignment == null) {
                        throw new IllegalStateException(
                                        "Security service returned empty response for role assignment creation");
                }

                // Map RoleAssignment to UserRoleDto for backward compatibility
                return mapToUserRoleDto(assignment, request.getRoleCode());
        }

        public void revokeRole(@NonNull UUID userId, @NonNull String roleCode, LocalDateTime endDate) {
                log.debug("Revoking role {} from userId: {} with endDate: {}", roleCode, userId, endDate);

                // First, get the role by name to obtain its UUID
                Role role = getRoleByName(roleCode);

                // Get all assignments for the user with full RoleAssignment objects

                List<RoleAssignment> fullAssignments = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/v1/roles/assignments/user/{userId}")
                                                .queryParam("includeHistory", true)
                                                .build(userId))
                                .retrieve()
                                .onStatus(statusCode -> statusCode.value() == 404,
                                                (request, response) -> {
                                                        throw new jakarta.persistence.EntityNotFoundException(
                                                                        "No role assignments found for userId: "
                                                                                        + userId);
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while listing role assignments in revokeRole",
                                                                        statusCode);
                                                })
                                .body(new ParameterizedTypeReference<List<RoleAssignment>>() {
                                });

                if (fullAssignments == null) {
                        throw new IllegalStateException("Security service returned null response");
                }

                java.time.LocalDate today = java.time.LocalDate.now();
                List<RoleAssignment> currentAndFutureAssignments = fullAssignments.stream()
                                .filter(assignment -> assignment.getEffectiveEndDate() == null
                                                || !assignment.getEffectiveEndDate().isBefore(today))
                                .toList();

                // Find the assignment for this specific role
                java.util.UUID assignmentId = currentAndFutureAssignments.stream()
                                .filter(fa -> role.getId().equals(fa.getRole().getId()))
                                .map(RoleAssignment::getId)
                                .findFirst()
                                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                                                "Role assignment not found for userId: " + userId + ", roleCode: "
                                                                + roleCode));

                java.time.LocalDate revocationDate = endDate != null ? endDate.toLocalDate()
                                : java.time.LocalDate.now();

                restClient.delete()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/v1/roles/assignments/{assignmentId}")
                                                .queryParam("endDate", revocationDate)
                                                .build(assignmentId))
                                .retrieve()
                                .onStatus(statusCode -> statusCode.value() == 400,
                                                (request, response) -> {
                                                        throw new IllegalArgumentException(
                                                                        "Invalid role revocation request for userId: "
                                                                                        + userId + ", roleCode: "
                                                                                        + roleCode);
                                                })
                                .onStatus(statusCode -> statusCode.value() == 404,
                                                (request, response) -> {
                                                        throw new jakarta.persistence.EntityNotFoundException(
                                                                        "Role assignment not found for userId: "
                                                                                        + userId + ", roleCode: "
                                                                                        + roleCode);
                                                })
                                .onStatus(HttpStatusCode::is5xxServerError,
                                                (request, response) -> {
                                                        int statusCode = response.getStatusCode().value();
                                                        throw new SecurityServiceException(
                                                                        "Security service failed while revoking role assignment",
                                                                        statusCode);
                                                })
                                .toBodilessEntity();
        }

        /**
         * Helper method to map RoleAssignment to UserRoleDto.
         *
         * Note: If a RoleAssignment contains multiple scopeLocationIds, only the first
         * one
         * is mapped to UserRoleDto.locationId. The remaining IDs are ignored.
         */
        private UserRoleDto mapToUserRoleDto(RoleAssignment assignment, String roleCode) {
                UUID locationId = null;
                if (assignment.getScopeType() == ScopeType.LOCATION
                                && assignment.getScopeLocationIds() != null
                                && !assignment.getScopeLocationIds().isEmpty()) {
                        if (assignment.getScopeLocationIds().size() > 1) {
                                // Note: The API supports multiple scopeLocationIds, but UserRoleDto currently
                                // exposes
                                // a single locationId.
                                // Log to make this limitation visible when multi-location assignments are
                                // encountered.
                                UUID userId = assignment.getUser() != null ? assignment.getUser().getId() : null;
                                log.warn("RoleAssignment {} for user {} and role {} has multiple scopeLocationIds; only the first will be used.",
                                                assignment.getId(), userId, roleCode);
                        }

                        locationId = assignment.getScopeLocationIds().stream().findFirst()
                                        .orElseThrow(() -> new SecurityServiceException(
                                                        "Empty location ID set in role assignment",
                                                        502));
                }

                return UserRoleDto.builder()
                                .userId(assignment.getUser().getId().toString())
                                .roleCode(roleCode)
                                .locationId(locationId)
                                .startDate(assignment.getEffectiveStartDate())
                                .endDate(assignment.getEffectiveEndDate())
                                .active(isAssignmentActive(assignment))
                                .build();
        }

        /**
         * Helper method to determine if an assignment is currently active
         */
        private boolean isAssignmentActive(RoleAssignment assignment) {
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate startDate = assignment.getEffectiveStartDate();
                java.time.LocalDate endDate = assignment.getEffectiveEndDate();

                // Active if started and not yet ended
                boolean hasStarted = startDate == null || !startDate.isAfter(today);
                boolean hasNotEnded = endDate == null || today.isBefore(endDate);

                return hasStarted && hasNotEnded;
        }
}
