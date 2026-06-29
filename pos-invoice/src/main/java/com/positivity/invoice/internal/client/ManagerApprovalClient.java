package com.positivity.invoice.internal.client;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound client backing manager-approval elevation. Resolves an employee number to an
 * active person (pos-people) and verifies that person holds the finalize-override
 * authority (pos-security).
 *
 * <p>Service-to-service authority is asserted via trusted {@code X-User} /
 * {@code X-Authorities} headers, mirroring {@link CustomerReferenceClient}; the calling
 * end user's own authorities are intentionally not propagated for these lookups.
 */
@Component
public class ManagerApprovalClient {

    private static final Logger log = LoggerFactory.getLogger(ManagerApprovalClient.class);
    private static final String SERVICE_ACTOR = "pos-invoice";

    private final RestClient peopleRestClient;
    private final RestClient securityRestClient;

    public ManagerApprovalClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.people.base-url:http://pos-people:8080/v1/people}") String peopleBaseUrl,
            @Value("${invoice.security.base-url:http://pos-security-service:8080/v1/users}") String securityBaseUrl) {
        this.peopleRestClient = restClientBuilder.baseUrl(peopleBaseUrl).build();
        this.securityRestClient = restClientBuilder.baseUrl(securityBaseUrl).build();
    }

    /**
     * Resolve an employee number to its person id, but only when the employee is in an
     * ACTIVE employment status.
     *
     * @return the person id of the active employee, or empty when unknown/inactive
     */
    @NonNull
    public Optional<UUID> resolveActivePersonId(@NonNull String employeeNumber) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = peopleRestClient
                    .get()
                    .uri("/employees/by-number/{employeeNumber}", employeeNumber)
                    .header("X-User", SERVICE_ACTOR)
                    .header("X-Authorities", "people:employee:view")
                    .retrieve()
                    .body(Map.class);

            if (body == null || !Boolean.TRUE.equals(body.get("active"))) {
                return Optional.empty();
            }
            Object personId = body.get("personId");
            return personId != null ? Optional.of(UUID.fromString(personId.toString())) : Optional.empty();
        } catch (Exception ex) {
            log.debug("Unable to resolve employee number to active person: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Verify the user backing {@code personId} holds {@code invoice:finalize:override}.
     *
     * @return true when the security service returns an allow decision
     */
    public boolean personHoldsFinalizeOverride(@NonNull UUID personId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = securityRestClient
                    .get()
                    .uri(
                            "/authorization/person-decision?personId={personId}&permission={permission}",
                            personId,
                            "invoice:finalize:override")
                    .header("X-User", SERVICE_ACTOR)
                    .header("X-Authorities", "security:authorization:decide")
                    .retrieve()
                    .body(Map.class);

            return body != null && "allow".equalsIgnoreCase(String.valueOf(body.get("decision")));
        } catch (Exception ex) {
            log.debug("Unable to evaluate finalize-override authority for person: {}", ex.getMessage());
            return false;
        }
    }
}
