package com.positivity.peoplecontact.internal.client;

import com.positivity.peoplecontact.internal.client.dto.Role;
import com.positivity.peoplecontact.internal.client.dto.RoleAssignment;
import com.positivity.peoplecontact.internal.client.dto.RoleAssignmentRequest;
import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.User;
import com.positivity.peoplecontact.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.SecurityServiceContractException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SecurityServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SecurityServiceClient.class);

    private final Clock clock;

    private final RestClient restClient;

    public SecurityServiceClient(@Qualifier("securityServiceRestClient") RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @NonNull
    public Optional<User> getUserByUsername(@NonNull String username) {
        log.debug("Fetching user by username: {}", username);

        List<User> users = restClient
                .get()
                .uri("/v1/users")
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400, (request, response) -> {
                    // GET /v1/users sends no query parameter — username is never transmitted and
                    // filtered client-side below — so a 400 here cannot be caused by the
                    // username argument. It means this call's shape no longer matches what
                    // pos-security-service expects: a contract/version drift, not caller error.
                    throw new SecurityServiceContractException(
                            "pos-security-service rejected GET /v1/users as malformed, but this request carries "
                                    + "no caller-supplied value (looking up username=" + username + "); likely a "
                                    + "request-shape contract drift with pos-security-service");
                })
                .onStatus(statusCode -> statusCode.value() == 401 || statusCode.value() == 403, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException("Not authorized to query users in security service", statusCode);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException("Security service failed while fetching users", statusCode);
                })
                .body(new ParameterizedTypeReference<List<User>>() {});

        if (users == null) {
            throw new IllegalStateException("Security service returned null response for user lookup");
        }

        return users.stream()
                .filter(user -> user.getUsername() != null && username.equalsIgnoreCase(user.getUsername()))
                .findFirst();
    }

    /**
     * Get a role by its name to obtain its UUID
     */
    @NonNull
    private Role getRoleByName(@NonNull String roleName) {
        log.debug("Fetching role by name: {}", roleName);

        Role role = restClient
                .get()
                .uri("/v1/roles/by-name/{name}", roleName)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404, (request, response) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "Role not found in security service: " + roleName);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException("Security service failed while fetching role", statusCode);
                })
                .body(Role.class);

        if (role == null) {
            throw new IllegalStateException("Security service returned null response for role: " + roleName);
        }

        return role;
    }

    /**
     * List the whole role catalog from pos-security-service.
     *
     * <p>{@code GET /v1/roles} takes no parameters: there are no filters and no paging. This
     * call used to send a {@code scopeType} query parameter that {@code RoleController.getAllRoles()}
     * has never declared, so Spring dropped it and the same unfiltered catalog came back for
     * every value — which is why calling this once per "scope" returned each role once per call.
     */
    @NonNull
    public List<RoleDto> getAvailableRoles() {
        log.debug("Fetching the available role catalog");

        List<RoleDto> roles = restClient
                .get()
                .uri("/v1/roles")
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404, (request, response) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "Roles endpoint not found in security service");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException("Security service failed while listing roles", statusCode);
                })
                .body(new ParameterizedTypeReference<List<RoleDto>>() {});

        if (roles == null) {
            throw new IllegalStateException("Security service returned null response for the role catalog");
        }

        return roles;
    }

    @NonNull
    public List<UserRoleDto> getUserRoleAssignments(
            @NonNull UUID userId, Boolean includeHistory, LocalDateTime endDate) {
        log.debug(
                "Fetching role assignments for userId: {}, includeHistory: {}, endDate: {}",
                userId,
                includeHistory,
                endDate);

        List<UserRoleDto> assignments = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/user/{userId}")
                        .queryParam("includeHistory", includeHistory)
                        .queryParam("endDate", endDate)
                        .build(userId))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400, (request, response) -> {
                    throw new PeopleContactValidationException(
                            "Invalid request while listing assignments for userId: " + userId);
                })
                .onStatus(statusCode -> statusCode.value() == 404, (request, response) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "No role assignments found for userId: " + userId);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException(
                            "Security service failed while listing role assignments", statusCode);
                })
                .body(new ParameterizedTypeReference<List<UserRoleDto>>() {});

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

        // Parse and validate userId
        UUID userIdUuid;
        try {
            userIdUuid = request.getUserId();
        } catch (IllegalArgumentException e) {
            throw new PeopleContactValidationException("Invalid userId format: " + request.getUserId(), e);
        }

        // Under ADR-0061 the assignment is a user-to-role link over an effective window and
        // nothing else; the dates go across as LocalDateTime because pos-security-service cannot
        // widen a date-only value into one and answers 400 instead.
        RoleAssignmentRequest apiRequest =
                new RoleAssignmentRequest(userIdUuid, role.getId(), request.getStartDate(), request.getEndDate());

        RoleAssignment assignment = restClient
                .post()
                .uri("/v1/roles/assignments")
                .body(apiRequest)
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400, (httpRequest, httpResponse) -> {
                    throw new PeopleContactValidationException(
                            "Invalid role assignment request for userId: " + request.getUserId());
                })
                .onStatus(statusCode -> statusCode.value() == 404, (httpRequest, httpResponse) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "User or role not found for assignment request");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (httpRequest, httpResponse) -> {
                    int statusCode = httpResponse.getStatusCode().value();
                    throw new SecurityServiceException("Security service failed while assigning role", statusCode);
                })
                .body(RoleAssignment.class);

        if (assignment == null) {
            throw new IllegalStateException("Security service returned empty response for role assignment creation");
        }

        // Map RoleAssignment to UserRoleDto for backward compatibility
        return mapToUserRoleDto(assignment, request.getRoleCode());
    }

    public void revokeRole(@NonNull UUID userId, @NonNull String roleCode, LocalDateTime endDate) {
        log.debug("Revoking role {} from userId: {} with endDate: {}", roleCode, userId, endDate);

        // First, get the role by name to obtain its UUID
        Role role = getRoleByName(roleCode);

        // Get all assignments for the user with full RoleAssignment objects

        List<RoleAssignment> fullAssignments = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/user/{userId}")
                        .queryParam("includeHistory", true)
                        .build(userId))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 404, (request, response) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "No role assignments found for userId: " + userId);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException(
                            "Security service failed while listing role assignments in revokeRole", statusCode);
                })
                .body(new ParameterizedTypeReference<List<RoleAssignment>>() {});

        if (fullAssignments == null) {
            throw new IllegalStateException("Security service returned null response");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<RoleAssignment> currentAndFutureAssignments = fullAssignments.stream()
                .filter(assignment -> assignment.getEffectiveEndDate() == null
                        || !assignment.getEffectiveEndDate().isBefore(now))
                .toList();

        // Find the assignment for this specific role. The role arrives as a flat roleId, not as a
        // nested role object.
        java.util.UUID assignmentId = currentAndFutureAssignments.stream()
                .filter(fa -> role.getId().equals(fa.getRoleId()))
                .map(RoleAssignment::getId)
                .findFirst()
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Role assignment not found for userId: " + userId + ", roleCode: " + roleCode));

        // revokeRoleAssignment binds endDate as an ISO LocalDateTime; a date-only value does not
        // bind and comes back as a 400.
        LocalDateTime revocationDate = endDate != null ? endDate : now;

        restClient
                .delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/{assignmentId}")
                        .queryParam("endDate", revocationDate)
                        .build(assignmentId))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400, (request, response) -> {
                    throw new PeopleContactValidationException(
                            "Invalid role revocation request for userId: " + userId + ", roleCode: " + roleCode);
                })
                .onStatus(statusCode -> statusCode.value() == 404, (request, response) -> {
                    throw new jakarta.persistence.EntityNotFoundException(
                            "Role assignment not found for userId: " + userId + ", roleCode: " + roleCode);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    throw new SecurityServiceException(
                            "Security service failed while revoking role assignment", statusCode);
                })
                .toBodilessEntity();
    }

    /**
     * Map the security service's assignment response onto this module's caller-facing shape.
     *
     * <p>{@code userId} arrives flat on the response; the role code cannot be read back off it
     * (only a {@code roleId} is returned), so the code the caller asked for is carried through.
     * A response missing the {@code userId} its contract declares REQUIRED is a downstream
     * defect, not an assignment belonging to nobody, so it fails rather than mapping to null.
     */
    private UserRoleDto mapToUserRoleDto(RoleAssignment assignment, String roleCode) {
        UUID userId = assignment.getUserId();
        if (userId == null) {
            throw new SecurityServiceException(
                    "Security service returned a role assignment without a userId: " + assignment.getId(), 502);
        }

        return UserRoleDto.builder()
                .userId(userId.toString())
                .roleCode(roleCode)
                .startDate(assignment.getEffectiveStartDate())
                .endDate(assignment.getEffectiveEndDate())
                .active(isAssignmentActive(assignment))
                .build();
    }

    /**
     * Helper method to determine if an assignment is currently active
     */
    private boolean isAssignmentActive(RoleAssignment assignment) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime startDate = assignment.getEffectiveStartDate();
        LocalDateTime endDate = assignment.getEffectiveEndDate();

        // Active if started and not yet ended. The effective window is start-inclusive and
        // end-exclusive, as pos-security-service declares it.
        boolean hasStarted = startDate == null || !startDate.isAfter(now);
        boolean hasNotEnded = endDate == null || now.isBefore(endDate);

        return hasStarted && hasNotEnded;
    }
}
