package com.positivity.people.internal.client;

import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.people.internal.client.dto.UserRoleDto;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SecurityServiceClient {

    private final RestClient restClient;

    public SecurityServiceClient(@Qualifier("securityServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @NonNull
    public List<RoleDto> getAvailableRoles(@NonNull String scope) {
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

        return roles != null ? roles : List.of();
    }

    @NonNull
    public List<UserRoleDto> getUserRoleAssignments(
            @NonNull String userId,
            Boolean includeHistory,
            LocalDateTime endDate) {
        List<UserRoleDto> assignments = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/user-roles")
                        .queryParam("userId", userId)
                        .queryParam("includeHistory", includeHistory)
                        .queryParam("endDate", endDate)
                        .build())
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

        return assignments != null ? assignments : List.of();
    }

    @NonNull
    public UserRoleDto assignRole(@NonNull UserRoleAssignmentRequest request) {
        UserRoleDto assignment = restClient.post()
                .uri("/v1/user-roles")
                .body(request)
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
                .body(UserRoleDto.class);

        if (assignment == null) {
            throw new IllegalStateException("Security service returned empty response for role assignment creation");
        }

        return assignment;
    }

    public void revokeRole(@NonNull String userId, @NonNull String roleCode, LocalDateTime endDate) {
        restClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/user-roles/{userId}/{roleCode}")
                        .queryParam("endDate", endDate)
                        .build(userId, roleCode))
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