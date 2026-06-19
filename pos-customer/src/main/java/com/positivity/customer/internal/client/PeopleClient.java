package com.positivity.customer.internal.client;

import com.fasterxml.jackson.annotation.JsonProperty;
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
                result.put(p.getId(), toIdentity(p));
            }
        }
        return result;
    }

    /**
     * Text-search canonical persons in pos-people (source of truth, ADR-0015 I2).
     * Delegates to {@code GET /v1/people?q=}, which matches firstName, lastName,
     * primaryEmail and username case-insensitively. Resilient: returns an empty
     * list if pos-people is unreachable.
     *
     * @param q the search term
     * @return matching canonical identities (empty if blank query or on failure)
     */
    @NonNull
    public List<PersonIdentity> searchPersons(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        try {
            PersonView[] people = restClient
                    .get()
                    .uri(uriBuilder ->
                            uriBuilder.path("/v1/people").queryParam("q", q).build())
                    .header("X-User", "pos-customer")
                    .header("X-Authorities", "people:person:view")
                    .retrieve()
                    .body(PersonView[].class);
            if (people == null) {
                return List.of();
            }
            List<PersonIdentity> result = new java.util.ArrayList<>();
            for (PersonView p : people) {
                if (p.getId() != null) {
                    result.add(toIdentity(p));
                }
            }
            return result;
        } catch (Exception exception) {
            log.warn("pos-people search failed for q={}: {}", q, exception.getMessage());
            return List.of();
        }
    }

    private PersonIdentity toIdentity(PersonView p) {
        List<ContactPoint> points = p.getContactPoints() == null
                ? List.of()
                : p.getContactPoints().stream()
                        .map(cp -> new ContactPoint(cp.getContactType(), cp.getValue(), cp.isPrimary()))
                        .toList();
        return new PersonIdentity(p.getId(), p.getFirstName(), p.getLastName(), p.getPrimaryEmail(), points);
    }

    /**
     * Write a person's contact points to pos-people (sole source of truth, ADR-0015 I2;
     * issue #684 removed the local copy). Best-effort: failures are logged and swallowed
     * so a transient pos-people outage does not fail person creation — the canonical link
     * is still persisted and contacts can be re-mirrored later.
     *
     * @param personId the canonical person id
     * @param contactPoints the contact points to persist (replaces existing)
     */
    public void setContactPoints(@NonNull UUID personId, @NonNull List<ContactPointUpsert> contactPoints) {
        try {
            restClient
                    .put()
                    .uri("/v1/people/{personId}/contact-points", personId)
                    .header("X-User", "pos-customer")
                    .header("X-Authorities", "people:person:edit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(contactPoints)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exception) {
            log.warn(
                    "Failed to mirror contact points to pos-people for personId={}: {}",
                    personId,
                    exception.getMessage());
        }
    }

    /** Contact point to upsert into pos-people. Serializes the flag as "primary". */
    public record ContactPointUpsert(String contactType, String value, boolean primary) {}

    /**
     * Resilient variant of {@link #fetchPersonIdentities} for display read paths:
     * if pos-people is unreachable, returns an empty map instead of throwing, so
     * callers degrade names/contacts to null rather than failing the request (there
     * is no local fallback copy after issue #684). Do not use for reconciliation,
     * which must distinguish "unreachable" from "not found".
     *
     * @param personIds the canonical person ids to fetch
     * @return identities by id, or an empty map if pos-people could not be reached
     */
    @NonNull
    public Map<UUID, PersonIdentity> fetchPersonIdentitiesQuietly(@NonNull Collection<UUID> personIds) {
        try {
            return fetchPersonIdentities(personIds);
        } catch (Exception exception) {
            log.warn("pos-people identity fetch failed; falling back to local names: {}", exception.getMessage());
            return Map.of();
        }
    }

    /** Canonical person identity sourced from pos-people. */
    public record PersonIdentity(
            UUID id, String firstName, String lastName, String primaryEmail, List<ContactPoint> contactPoints) {
        public String displayName() {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }

        @NonNull
        public List<ContactPoint> contactPoints() {
            return contactPoints != null ? contactPoints : List.of();
        }

        /** Email values from typed contact points (falls back to primaryEmail). */
        @NonNull
        public List<String> emails() {
            List<String> fromPoints = contactPoints().stream()
                    .filter(cp -> "EMAIL".equals(cp.contactType()))
                    .map(ContactPoint::value)
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
            if (!fromPoints.isEmpty()) {
                return fromPoints;
            }
            return primaryEmail != null && !primaryEmail.isBlank() ? List.of(primaryEmail) : List.of();
        }

        /** Phone values from typed contact points (any PHONE_* type). */
        @NonNull
        public List<String> phones() {
            return contactPoints().stream()
                    .filter(cp -> cp.contactType() != null && cp.contactType().startsWith("PHONE"))
                    .map(ContactPoint::value)
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
        }
    }

    /** Typed contact point sourced from pos-people. */
    public record ContactPoint(String contactType, String value, boolean isPrimary) {}

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
        private List<ContactPointView> contactPoints;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ContactPointView {
        private String contactType;
        private String value;

        // pos-people serializes this flag as JSON "primary" (its isPrimary() getter);
        // map it explicitly so deserialization does not bind null to a primitive boolean.
        @JsonProperty("primary")
        private boolean isPrimary;
    }
}
