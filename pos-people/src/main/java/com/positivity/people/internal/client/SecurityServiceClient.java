package com.positivity.people.internal.client;

import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class SecurityServiceClient {

    private final RestClient restClient;

    public SecurityServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security-service.base-url:http://localhost:8086}") String securityServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(securityServiceBaseUrl).build();
    }

    @NonNull
    public List<Role> getAllRoles() {
        List<Role> roles = restClient.get()
                .uri("/v1/roles")
                .retrieve()
                .body(new ParameterizedTypeReference<List<Role>>() {
                });

        return roles != null ? roles : List.of();
    }

    @NonNull
    public List<RoleAssignment> getAssignmentsForUser(@NonNull UUID userId, boolean includeHistory) {
        List<RoleAssignment> assignments = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/user/{userId}")
                        .queryParam("includeHistory", includeHistory)
                        .build(userId))
                .retrieve()
                .body(new ParameterizedTypeReference<List<RoleAssignment>>() {
                });

        return assignments != null ? assignments : List.of();
    }

    @NonNull
    public RoleAssignment createRoleAssignment(@NonNull RoleAssignmentRequest request) {
        RoleAssignment assignment = restClient.post()
                .uri("/v1/roles/assignments")
                .body(request)
                .retrieve()
                .body(RoleAssignment.class);

        if (assignment == null) {
            throw new IllegalStateException("Security service returned empty response for role assignment creation");
        }

        return assignment;
    }

    public void revokeRoleAssignment(@NonNull UUID assignmentId, @NonNull LocalDate endDate) {
        restClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/roles/assignments/{assignmentId}")
                        .queryParam("endDate", endDate)
                        .build(assignmentId))
                .retrieve()
                .toBodilessEntity();
    }
}