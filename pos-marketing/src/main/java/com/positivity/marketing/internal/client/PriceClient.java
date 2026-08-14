package com.positivity.marketing.internal.client;

import com.positivity.marketing.internal.service.PromotionOfferPort;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads a promotion offer from the pricing service so a campaign can be stopped from
 * advertising one that is not running (Story #1148).
 *
 * <p>This is the module's one synchronous domain read, and it is permitted because pricing is
 * an ADR-0044 §1 utility rather than a peer domain. It is also the right shape for the
 * question: offer status changes on its own schedule with no fact to replicate from, and the
 * answer is only needed at two discrete moments — previewing a campaign and scheduling one —
 * rather than continuously.
 *
 * <p>The three outcomes are kept distinct on purpose. "No such offer" and "an offer that has
 * expired" are the marketer's mistake and must block scheduling; "pricing did not answer" is
 * an outage, and reporting it as an invalid campaign would send someone hunting for a data
 * problem that does not exist.
 */
@Slf4j
@Component
public class PriceClient implements PromotionOfferPort {

    /**
     * Service identity for the gateway-header trust model: downstream services accept
     * {@code X-Authorities} because they are only reachable behind the gateway.
     */
    private static final String SERVICE_USER = "pos-marketing-service";

    private static final String AUTHORITIES = "pricing:promotion:view";

    private final RestClient restClient;
    private final String basePath;

    public PriceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.price.service-id:price}") String serviceId,
            @Value("${pos.price.promotion-offers-base-path:/v1/promotions/offers}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull OfferLookup findOffer(@NonNull UUID promotionOfferId) {
        try {
            PromotionOfferWire wire = restClient
                    .get()
                    .uri(basePath + "/{promotionOfferId}", promotionOfferId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(PromotionOfferWire.class);
            if (wire == null) {
                // A 200 with no body is not an authoritative "does not exist"; treat it as an
                // outage rather than condemning the campaign on it.
                log.warn("Promotion offer {} returned an empty body", promotionOfferId);
                return OfferLookup.unavailable();
            }
            return OfferLookup.found(new PromotionOffer(
                    wire.promotionOfferId() != null ? wire.promotionOfferId() : promotionOfferId,
                    wire.promoCode(),
                    wire.status(),
                    wire.startDate(),
                    wire.endDate()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return OfferLookup.notFound();
            }
            log.warn(
                    "Promotion offer lookup failed for {}: HTTP {}",
                    promotionOfferId,
                    e.getStatusCode().value());
            return OfferLookup.unavailable();
        } catch (RestClientException e) {
            log.warn("Pricing unreachable for promotion offer {}: {}", promotionOfferId, e.getMessage());
            return OfferLookup.unavailable();
        } catch (IllegalStateException e) {
            // Discovery has no instance registered — the same class of answer as an unreachable
            // one, and it surfaces as IllegalStateException rather than a RestClientException
            // because it fails before any request is made.
            log.warn("No pricing instance available for promotion offer {}: {}", promotionOfferId, e.getMessage());
            return OfferLookup.unavailable();
        }
    }

    /** Wire shape of the pricing response; unknown properties are ignored. */
    private record PromotionOfferWire(
            @Nullable UUID promotionOfferId,
            @Nullable String promoCode,
            @Nullable String status,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate) {}
}
