package com.positivity.marketing.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.positivity.marketing.internal.service.PromotionOfferPort;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Pins the offer lookup's outcome mapping (Story #1148).
 *
 * <p>The whole value of this client is the distinction between the three outcomes: collapsing
 * "pricing is down" into "this offer does not exist" would tell a marketer their campaign is
 * broken during an outage, and collapsing it the other way would let a campaign advertising a
 * dead offer sail through scheduling. Everything here guards that boundary.
 */
@DisplayName("PriceClient — promotion offer lookup outcomes")
class PriceClientTest {

    private static final UUID OFFER_ID = UUID.fromString("01960005-0000-7000-8000-0000000000a1");
    private static final String OFFER_URL = "http://price/v1/promotions/offers/" + OFFER_ID;

    private MockRestServiceServer server;
    private PriceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PriceClient(builder, "price", "/v1/promotions/offers");
    }

    @Test
    @DisplayName("an existing offer comes back with the status and window a campaign is judged on")
    void foundOffer() {
        server.expect(requestTo(OFFER_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "pricing:promotion:view"))
                .andExpect(header("X-User", "pos-marketing-service"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"promotionOfferId":"%s","promoCode":"SPRING20","status":"ACTIVE",
                                 "startDate":"2026-03-01","endDate":"2026-03-31","discountValue":20.00}
                                """.formatted(OFFER_ID)));

        PromotionOfferPort.OfferLookup lookup = client.findOffer(OFFER_ID);

        assertThat(lookup.outcome()).isEqualTo(PromotionOfferPort.Outcome.FOUND);
        assertThat(lookup.offer()).isNotNull();
        assertThat(lookup.offer().isActive()).isTrue();
        assertThat(lookup.offer().promoCode()).isEqualTo("SPRING20");
        assertThat(lookup.offer().startDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(lookup.offer().endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("a 404 is an authoritative answer that the offer does not exist")
    void missingOffer() {
        server.expect(requestTo(OFFER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findOffer(OFFER_ID).outcome()).isEqualTo(PromotionOfferPort.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("a 5xx says nothing about the offer, so it reports as unavailable rather than missing")
    void serverErrorIsUnavailable() {
        server.expect(requestTo(OFFER_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Reported as NOT_FOUND this would tell the marketer to fix an offer that is fine.
        assertThat(client.findOffer(OFFER_ID).outcome()).isEqualTo(PromotionOfferPort.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("an I/O failure reports as unavailable")
    void ioFailureIsUnavailable() {
        server.expect(requestTo(OFFER_URL)).andRespond(withException(new IOException("connection reset")));

        assertThat(client.findOffer(OFFER_ID).outcome()).isEqualTo(PromotionOfferPort.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a 200 with no body is treated as an outage, not as a missing offer")
    void emptyBodyIsUnavailable() {
        server.expect(requestTo(OFFER_URL))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON));

        assertThat(client.findOffer(OFFER_ID).outcome()).isEqualTo(PromotionOfferPort.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a response that omits the id keeps the id that was asked for")
    void missingIdFallsBackToTheRequestedOne() {
        server.expect(requestTo(OFFER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"DRAFT\"}"));

        PromotionOfferPort.OfferLookup lookup = client.findOffer(OFFER_ID);

        assertThat(lookup.offer()).isNotNull();
        assertThat(lookup.offer().promotionOfferId()).isEqualTo(OFFER_ID);
        assertThat(lookup.offer().isActive()).isFalse();
    }

    @Test
    @DisplayName("an offer with no dates runs on any day")
    void openEndedOfferRunsToday() {
        PromotionOfferPort.PromotionOffer offer =
                new PromotionOfferPort.PromotionOffer(OFFER_ID, "EVERGREEN", "ACTIVE", null, null);

        assertThat(offer.runsOn(LocalDate.of(2026, 8, 14))).isTrue();
    }

    @Test
    @DisplayName("the window is inclusive at both ends, and excludes the days outside it")
    void windowBoundaries() {
        PromotionOfferPort.PromotionOffer offer = new PromotionOfferPort.PromotionOffer(
                OFFER_ID, "SPRING20", "ACTIVE", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(offer.runsOn(LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(offer.runsOn(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(offer.runsOn(LocalDate.of(2026, 2, 28))).isFalse();
        assertThat(offer.runsOn(LocalDate.of(2026, 4, 1))).isFalse();
    }
}
