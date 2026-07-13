package com.positivity.invoice.internal.client;

import java.util.Map;
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

    private final RestClient securityRestClient;

    public ManagerApprovalClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.security.base-url:http://pos-security-service:8080/v1/users}") String securityBaseUrl) {
        this.securityRestClient = restClientBuilder.baseUrl(securityBaseUrl).build();
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
