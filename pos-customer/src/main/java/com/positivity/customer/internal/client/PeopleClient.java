package com.positivity.customer.internal.client;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for resolving/creating canonical person records in pos-people.
 */
@Slf4j
@Component
public class PeopleClient {

    private final RestClient restClient;
    private final boolean allowLocalFallback;

    @Autowired
    public PeopleClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.people.service-id:people}") String serviceId,
            @Value("${pos.people.allow-local-fallback:false}") boolean allowLocalFallback) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
        this.allowLocalFallback = allowLocalFallback;
        log.info("PeopleClient initialized with serviceId: {}", serviceId);
    }

    /** Package-private constructor for unit tests — accepts a pre-built RestClient.Builder. */
    PeopleClient(RestClient.Builder restClientBuilder, String serviceId) {
        this(restClientBuilder, serviceId, false);
    }

    @NonNull
    public UUID resolveOrCreatePersonId(String email, String phone, String lastName, String firstName) {
        ResolvePersonRequest request = new ResolvePersonRequest();
        request.setEmail(email);
        request.setPhone(phone);
        request.setLastName(lastName);
        request.setFirstName(firstName);

        try {
            ResolvePersonResponse response = restClient
                    .post()
                    .uri("/v1/people/resolve")
                    .header("X-User", "pos-customer")
                    .header("X-Authorities", "people:person:create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ResolvePersonResponse.class);

            if (response == null || response.getPersonId() == null) {
                throw new IllegalStateException("pos-people resolve response missing personId");
            }
            return response.getPersonId();
        } catch (Exception exception) {
            if (allowLocalFallback) {
                UUID fallback = UUIDv7Generator.generate();
                log.warn(
                        "Falling back to generated personId={} because pos-people resolve failed: {}",
                        fallback,
                        exception.getMessage());
                return fallback;
            }
            throw exception;
        }
    }

    /**
     * Batch-resolve canonical person identities from pos-people (the source of
     * truth, ADR-0015 I2). Unknown ids are simply absent from the returned map.
     *
     * @param personIds the canonical person ids to fetch
     * @return map of personId to identity for ids that exist in pos-people
     */
    @NonNull
    public Map<UUID, PersonIdentity> fetchPersonIdentities(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        PersonView[] people = restClient
                .post()
                .uri("/v1/people/by-ids")
                .header("X-User", "pos-customer")
                .header("X-Authorities", "people:person:view")
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.copyOf(personIds))
                .retrieve()
                .body(PersonView[].class);

        if (people == null) {
            return Map.of();
        }
        Map<UUID, PersonIdentity> result = new HashMap<>();
        for (PersonView p : people) {
            if (p.getId() != null) {
                result.put(
                        p.getId(),
                        new PersonIdentity(p.getId(), p.getFirstName(), p.getLastName(), p.getPrimaryEmail()));
            }
        }
        return result;
    }

    /** Canonical person identity sourced from pos-people. */
    public record PersonIdentity(UUID id, String firstName, String lastName, String primaryEmail) {
        public String displayName() {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ResolvePersonRequest {
        private String email;
        private String phone;
        private String lastName;
        private String firstName;
        private Integer threshold;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ResolvePersonResponse {
        private UUID personId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class PersonView {
        private UUID id;
        private String firstName;
        private String lastName;
        private String primaryEmail;
    }
}
