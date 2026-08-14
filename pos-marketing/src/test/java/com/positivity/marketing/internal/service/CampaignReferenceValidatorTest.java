package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.entity.Campaign;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CampaignReferenceValidator}.
 *
 * <p>The behaviour worth pinning is what each answer from pricing means for the campaign:
 *
 * <ul>
 *   <li><b>An expired offer blocks, and so does an unreachable pricing service</b> — but with
 *       different messages. A marketer told "your offer is wrong" during an outage goes
 *       looking for a data problem that does not exist.
 *   <li><b>Status and calendar window are separate checks.</b> Pricing stores EXPIRED as a
 *       status something has to set, so an offer whose end date passed can still read ACTIVE.
 *   <li><b>An absent reference is not a problem.</b> Both fields are optional; validation
 *       must not turn "no offer" into "invalid campaign".
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignReferenceValidator — offer and catalog references")
class CampaignReferenceValidatorTest {

    private static final UUID OFFER_ID = UUID.fromString("01960005-0000-7000-8000-0000000000b1");
    private static final Instant TODAY = Instant.parse("2026-03-15T09:00:00Z");

    @Mock
    private PromotionOfferPort promotionOfferPort;

    private CampaignReferenceValidator validator() {
        return new CampaignReferenceValidator(Clock.fixed(TODAY, ZoneOffset.UTC), promotionOfferPort);
    }

    private static Campaign campaign(UUID offerId, String catalogFocusRef) {
        return Campaign.builder()
                .campaignId(UUID.randomUUID())
                .promotionOfferId(offerId)
                .catalogFocusRef(catalogFocusRef)
                .build();
    }

    private void pricingAnswers(String status, LocalDate start, LocalDate end) {
        when(promotionOfferPort.findOffer(OFFER_ID))
                .thenReturn(PromotionOfferPort.OfferLookup.found(
                        new PromotionOfferPort.PromotionOffer(OFFER_ID, "SPRING20", status, start, end)));
    }

    @Nested
    @DisplayName("promotion offer")
    class PromotionOffer {

        @Test
        @DisplayName("an active offer inside its window is no problem at all")
        void activeAndRunning() {
            pricingAnswers("ACTIVE", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

            assertThat(validator().problems(campaign(OFFER_ID, null))).isEmpty();
        }

        @Test
        @DisplayName("an offer that is not ACTIVE blocks, naming the status it is in")
        void inactiveOfferBlocks() {
            pricingAnswers("DRAFT", null, null);

            assertThat(validator().problems(campaign(OFFER_ID, null)))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("is DRAFT, not ACTIVE");
        }

        @Test
        @DisplayName("an ACTIVE offer whose window has passed still blocks")
        void staleWindowBlocksEvenWhenActive() {
            // Pricing stores EXPIRED as a status something has to set, so an offer whose end
            // date passed can still read ACTIVE. The campaign would advertise nothing.
            pricingAnswers("ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));

            assertThat(validator().problems(campaign(OFFER_ID, null)))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("does not include today")
                    .contains("2026-01-01 to 2026-02-28");
        }

        @Test
        @DisplayName("an ACTIVE offer that has not started yet blocks")
        void futureWindowBlocks() {
            pricingAnswers("ACTIVE", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

            assertThat(validator().problems(campaign(OFFER_ID, null))).hasSize(1);
        }

        @Test
        @DisplayName("an offer pricing has never heard of blocks as nonexistent")
        void unknownOfferBlocks() {
            when(promotionOfferPort.findOffer(OFFER_ID)).thenReturn(PromotionOfferPort.OfferLookup.notFound());

            assertThat(validator().problems(campaign(OFFER_ID, null)))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("does not exist");
        }

        @Test
        @DisplayName("an unreachable pricing service blocks, but says so rather than blaming the offer")
        void unavailablePricingBlocksWithItsOwnMessage() {
            when(promotionOfferPort.findOffer(OFFER_ID)).thenReturn(PromotionOfferPort.OfferLookup.unavailable());

            assertThat(validator().problems(campaign(OFFER_ID, null)))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("could not be verified")
                    .contains("pricing is unavailable")
                    .doesNotContain("does not exist");
        }

        @Test
        @DisplayName("a campaign with no offer bound is never asked about")
        void noOfferIsNoQuestion() {
            assertThat(validator().problems(campaign(null, null))).isEmpty();
            verifyNoInteractions(promotionOfferPort);
        }
    }

    @Nested
    @DisplayName("catalog focus reference")
    class CatalogFocus {

        @Test
        @DisplayName("accepts a well-formed reference")
        void wellFormedReference() {
            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .isEmpty();
        }

        @Test
        @DisplayName("rejects a bare name and says what to write instead")
        void bareNameRejected() {
            assertThat(validator().problems(campaign(null, "alignment")))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("is not a catalog reference")
                    .contains("product, sku, service, category");
        }

        @Test
        @DisplayName("a blank reference is absence, not a malformed value")
        void blankIsAbsent() {
            assertThat(validator().problems(campaign(null, "   "))).isEmpty();
        }
    }

    @Test
    @DisplayName("gathers both references' problems rather than stopping at the first")
    void gathersEveryProblem() {
        when(promotionOfferPort.findOffer(OFFER_ID)).thenReturn(PromotionOfferPort.OfferLookup.notFound());

        // A marketer fixing a campaign wants the whole list, not a game of whack-a-mole.
        assertThat(validator().problems(campaign(OFFER_ID, "alignment"))).hasSize(2);
    }
}
