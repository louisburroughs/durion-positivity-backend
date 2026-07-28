package com.positivity.marketing.internal.client;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read access to pos-customer over the gateway (Stories #1148/#1149).
 *
 * <p>Integration with CRM is REST plus {@code customer.events.v1} only — pos-marketing has no
 * compile-time dependency on pos-customer (plan §0.1), so the response shapes are declared
 * here as local records rather than shared DTOs.
 *
 * <p>Failures surface as {@link CustomerUnavailableException} rather than being swallowed. A
 * segment that silently resolves to zero recipients because CRM was down looks exactly like a
 * campaign that legitimately matched nobody, and the operator would never know the difference.
 */
@Slf4j
@Component
public class CustomerClient {

    /** Segment membership: counts plus a masked sample, never the full recipient list. */
    public record SegmentResolution(
            UUID segmentId,
            String audienceType,
            long totalMatched,
            @Nullable Long eligibleCount,
            boolean truncated) {}

    /** One party's send eligibility on a channel, after consent, account gate, and suppression. */
    public record ConsentDecision(
            UUID partyId,
            String channel,
            boolean allowed,
            String reason,
            @Nullable UUID governingPartyId) {}

    public record SegmentSummary(UUID segmentId, String name, String audienceType, String type, boolean active) {}

    /** Thrown when CRM cannot be reached or answers with an error. */
    public static class CustomerUnavailableException extends RuntimeException {
        public CustomerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient restClient;
    private final String basePath;

    public CustomerClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.service-id:customer}") String serviceId,
            @Value("${pos.customer.base-path:/v1/crm}") String basePath) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
        this.basePath = basePath;
    }

    public @NonNull SegmentSummary getSegment(@NonNull UUID segmentId) {
        try {
            SegmentSummary summary = restClient
                    .get()
                    .uri(basePath + "/segments/{segmentId}", segmentId)
                    .retrieve()
                    .body(SegmentSummary.class);
            if (summary == null) {
                throw new CustomerUnavailableException("Empty segment response for " + segmentId, null);
            }
            return summary;
        } catch (RestClientException ex) {
            throw new CustomerUnavailableException("Unable to read segment " + segmentId + " from pos-customer", ex);
        }
    }

    /**
     * Resolve a segment to counts. {@code channel} makes CRM also report how many matches are
     * actually sendable, so the preview reflects consent and suppression without this module
     * re-deriving either.
     */
    public @NonNull SegmentResolution resolveSegment(@NonNull UUID segmentId, @Nullable String channel) {
        try {
            SegmentResolution resolution = restClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .path(basePath + "/segments/{segmentId}/resolve")
                            .queryParamIfPresent("channel", java.util.Optional.ofNullable(channel))
                            .queryParam("sampleSize", 0)
                            .build(segmentId))
                    .retrieve()
                    .body(SegmentResolution.class);
            if (resolution == null) {
                throw new CustomerUnavailableException("Empty resolve response for segment " + segmentId, null);
            }
            return resolution;
        } catch (RestClientException ex) {
            throw new CustomerUnavailableException("Unable to resolve segment " + segmentId + " in pos-customer", ex);
        }
    }

    /** Send eligibility for one party, re-checked at dispatch time (Story #1149). */
    public @NonNull ConsentDecision resolveEligibility(@NonNull UUID partyId, @NonNull String channel) {
        try {
            ConsentDecision decision = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(basePath + "/parties/{partyId}/marketing-eligibility")
                            .queryParam("channel", channel)
                            .build(partyId))
                    .retrieve()
                    .body(ConsentDecision.class);
            if (decision == null) {
                throw new CustomerUnavailableException("Empty eligibility response for party " + partyId, null);
            }
            return decision;
        } catch (RestClientException ex) {
            throw new CustomerUnavailableException("Unable to resolve marketing eligibility for party " + partyId, ex);
        }
    }

    /**
     * Party ids matching a segment, used by the send orchestrator to materialize an audience.
     *
     * <p>Returns opaque identifiers only — no names, addresses, or contact details. The
     * recipient's actual address is resolved by the platform sender under the FI-2 contract,
     * so this module never holds one.
     */
    public @NonNull List<UUID> resolveSegmentPartyIds(@NonNull UUID segmentId) {
        try {
            UUID[] partyIds = restClient
                    .post()
                    .uri(basePath + "/segments/{segmentId}/resolve/party-ids", segmentId)
                    .retrieve()
                    .body(UUID[].class);
            return partyIds != null ? List.of(partyIds) : List.of();
        } catch (RestClientException ex) {
            throw new CustomerUnavailableException(
                    "Unable to read segment membership for " + segmentId + " from pos-customer", ex);
        }
    }
}
