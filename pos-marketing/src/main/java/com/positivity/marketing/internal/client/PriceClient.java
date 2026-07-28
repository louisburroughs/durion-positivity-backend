package com.positivity.marketing.internal.client;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Offer validation against pos-price (Story #1148).
 *
 * <p>Checked at schedule time, not at create time: an offer that is ACTIVE while a marketer is
 * drafting can expire before the campaign goes out, and a message promoting a dead offer is
 * worse than no message at all.
 */
@Slf4j
@Component
public class PriceClient {

    public record OfferSummary(UUID offerId, String status) {

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    private final RestClient restClient;
    private final String basePath;

    public PriceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.price.service-id:price}") String serviceId,
            @Value("${pos.price.base-path:/v1}") String basePath) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
        this.basePath = basePath;
    }

    /**
     * Whether the offer exists and is currently ACTIVE.
     *
     * <p>Returns {@code false} rather than throwing when pos-price is unreachable: refusing to
     * schedule is the safe outcome, and it is reported to the caller as a validation failure
     * naming the offer, not as an opaque 500.
     */
    public boolean isOfferActive(@NonNull UUID offerId) {
        try {
            OfferSummary offer = restClient
                    .get()
                    .uri(basePath + "/offers/{offerId}", offerId)
                    .retrieve()
                    .body(OfferSummary.class);
            return offer != null && offer.isActive();
        } catch (RestClientException ex) {
            log.warn("Unable to validate offer {} against pos-price: {}", offerId, ex.getMessage());
            return false;
        }
    }
}
