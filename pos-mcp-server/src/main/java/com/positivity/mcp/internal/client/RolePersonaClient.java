package com.positivity.mcp.internal.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.service.RolePersonaSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST adapter for {@code pos-security-service}'s role persona endpoints (#1613).
 *
 * <p>Follows the {@link RoleDefaultPermissionsClient} precedent from #782: a load-balanced
 * {@code RestClient} to {@code http://security-service}, asserting the required authority through
 * {@code X-User} / {@code X-Authorities} because the sync has no caller JWT, and swallowing every
 * failure. An empty result leaves the previous snapshot serving traffic, so a sync outage degrades
 * persona freshness and nothing else.
 */
@Component
public class RolePersonaClient implements RolePersonaSource {

    private static final Logger log = LoggerFactory.getLogger(RolePersonaClient.class);
    private static final String SERVICE_ACTOR = "pos-mcp-server";
    private static final String REQUIRED_AUTHORITY = "security:role:view";

    private final RestClient restClient;

    public RolePersonaClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${mcp.security-service.base-url:http://security-service}") String baseUrl) {
        this.restClient = loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public @NonNull Optional<RolePersonaSnapshotData> fetchAll() {
        try {
            RolePersonasResponse response = restClient
                    .get()
                    .uri("/v1/roles/personas")
                    .header("X-User", SERVICE_ACTOR)
                    .header("X-Authorities", REQUIRED_AUTHORITY)
                    .retrieve()
                    .body(RolePersonasResponse.class);
            if (response == null || response.roles() == null) {
                log.warn("Role persona fetch returned no body");
                return Optional.empty();
            }
            List<RolePersona> personas =
                    response.roles().stream().map(RolePersonaClient::toPersona).toList();
            // A missing timestamp would make snapshot age unreadable, so fall back to receipt time:
            // slightly optimistic, but bounded by how often the sync runs.
            return Optional.of(new RolePersonaSnapshotData(
                    response.generatedAt() == null ? Instant.now() : response.generatedAt(), personas));
        } catch (RuntimeException exception) {
            log.warn("Failed to fetch role personas: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads the single-role lookup rather than a dedicated persona endpoint: {@code RoleDto} carries
     * the persona fields, so one endpoint fewer has to exist for the on-miss path to work.
     */
    @Override
    public @NonNull Optional<RolePersona> fetchOne(@NonNull String roleName) {
        try {
            RoleView role = restClient
                    .get()
                    .uri("/v1/roles/by-name/{name}", roleName)
                    .header("X-User", SERVICE_ACTOR)
                    .header("X-Authorities", REQUIRED_AUTHORITY)
                    .retrieve()
                    .body(RoleView.class);
            if (role == null || role.name() == null) {
                return Optional.empty();
            }
            return Optional.of(new RolePersona(
                    role.name(),
                    role.description(),
                    role.personaTitle(),
                    role.personaFocus(),
                    role.personaTone(),
                    role.mcpPersonaRank(),
                    role.mcpPersonaEligible()));
        } catch (RuntimeException exception) {
            // Includes the 404 for a role that does not exist, which is an ordinary outcome here:
            // a caller can hold an authority for a role this service has never heard of.
            log.warn("Failed to fetch role persona for role={}: {}", roleName, exception.getMessage());
            return Optional.empty();
        }
    }

    private static RolePersona toPersona(PersonaView view) {
        return new RolePersona(
                view.name(),
                view.description(),
                view.personaTitle(),
                view.personaFocus(),
                view.personaTone(),
                view.mcpPersonaRank(),
                view.mcpPersonaEligible());
    }

    /** Mirror of {@code pos-security-service}'s RolePersonasResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RolePersonasResponse(
            @Nullable Instant generatedAt, @Nullable List<PersonaView> roles) {}

    /** Mirror of {@code pos-security-service}'s RolePersonaDto. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PersonaView(
            String name,
            @Nullable String description,
            @Nullable String personaTitle,
            @Nullable String personaFocus,
            @Nullable String personaTone,
            @Nullable Short mcpPersonaRank,
            boolean mcpPersonaEligible) {}

    /** The subset of {@code RoleDto} the on-miss path needs. Other fields are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleView(
            @Nullable String name,
            @Nullable String description,
            @Nullable String personaTitle,
            @Nullable String personaFocus,
            @Nullable String personaTone,
            @Nullable Short mcpPersonaRank,
            boolean mcpPersonaEligible) {}
}
