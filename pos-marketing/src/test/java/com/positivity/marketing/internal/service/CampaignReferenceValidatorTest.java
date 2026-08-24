package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import com.positivity.marketing.internal.repository.ExtCatalogReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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

    private static final UUID SERVICE_ID = UUID.fromString("01960005-0000-7000-8000-0000000000c1");
    private static final UUID CATEGORY_ID = UUID.fromString("01960005-0000-7000-8000-0000000000c2");

    @Mock
    private PromotionOfferPort promotionOfferPort;

    @Mock
    private ExtCatalogReplicaRepository catalogReplicaRepository;

    private CampaignReferenceValidator validator() {
        return new CampaignReferenceValidator(
                Clock.fixed(TODAY, ZoneOffset.UTC), promotionOfferPort, catalogReplicaRepository);
    }

    private static ExtCatalogReplica row(CatalogItemKind kind, boolean active) {
        return ExtCatalogReplica.builder()
                .catalogItemId(SERVICE_ID)
                .itemKind(kind)
                .name("alignment")
                .active(active)
                .aggregateVersion(1L)
                .updatedAt(TODAY)
                .build();
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

    /**
     * The reference is checked twice over: grammar first, because a bare name is the mistake
     * marketers actually make, then resolution against the replica, because {@code
     * service:alignment} is perfectly well-formed and still points at nothing if that service was
     * never created.
     *
     * <p>The three answers are deliberately distinct. "Not a catalog reference" tells the marketer
     * what to type; "not known to this module yet" says the platform cannot find it — which, from
     * here, covers both a typo and a replica that has not caught up; "no longer active" says it was
     * found and has been retired, which is a different fix entirely.
     */
    @Nested
    @DisplayName("catalog focus reference")
    class CatalogFocus {

        @Test
        @DisplayName("a service the replica knows and holds active resolves")
        void knownServiceResolves() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of(row(CatalogItemKind.SERVICE, true)));

            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a reference written as an id resolves by id, not by name")
        void referenceByIdResolves() {
            when(catalogReplicaRepository.findByItemKindAndCatalogItemId(CatalogItemKind.SERVICE, SERVICE_ID))
                    .thenReturn(List.of(row(CatalogItemKind.SERVICE, true)));

            assertThat(validator().problems(campaign(null, "service:" + SERVICE_ID)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a well-formed reference to nothing blocks, the way an unknown segment does")
        void unresolvableReferenceBlocks() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of());
            // The replica knows services; it just does not know this one.
            when(catalogReplicaRepository.countByItemKind(CatalogItemKind.SERVICE))
                    .thenReturn(12L);

            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("service:alignment")
                    .contains("is not known to this module yet");
        }

        @Test
        @DisplayName("a retired item blocks with its own message, not as if it never existed")
        void retiredItemBlocksDistinctly() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of(row(CatalogItemKind.SERVICE, false)));

            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("no longer active")
                    .doesNotContain("not known to this module");
        }

        @Test
        @DisplayName("one active match is enough when a name matches several rows")
        void oneActiveMatchIsEnough() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of(row(CatalogItemKind.SERVICE, false), row(CatalogItemKind.SERVICE, true)));

            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .isEmpty();
        }

        @Test
        @DisplayName("sku: resolves against product rows, since a sku is a product attribute")
        void skuResolvesAgainstProducts() {
            when(catalogReplicaRepository.findBySkuIgnoreCase("SKU-1"))
                    .thenReturn(List.of(row(CatalogItemKind.PRODUCT, true)));

            assertThat(validator().problems(campaign(null, "sku:SKU-1"))).isEmpty();
        }

        @Test
        @DisplayName("category: resolves by id when written as one")
        void categoryByIdResolves() {
            when(catalogReplicaRepository.findByCategoryId(CATEGORY_ID))
                    .thenReturn(List.of(row(CatalogItemKind.PRODUCT, true)));

            assertThat(validator().problems(campaign(null, "category:" + CATEGORY_ID)))
                    .isEmpty();
        }

        @Test
        @DisplayName("category: resolves by name through the products that carry it")
        void categoryByNameResolves() {
            when(catalogReplicaRepository.findByCategoryIgnoreCase("Tires"))
                    .thenReturn(List.of(row(CatalogItemKind.PRODUCT, true)));

            assertThat(validator().problems(campaign(null, "category:Tires"))).isEmpty();
        }

        @Test
        @DisplayName("product: looks at products, so a service of the same name does not answer for one")
        void productKindDoesNotMatchServices() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.PRODUCT, "alignment"))
                    .thenReturn(List.of());
            when(catalogReplicaRepository.countByItemKind(CatalogItemKind.PRODUCT))
                    .thenReturn(400L);

            assertThat(validator().problems(campaign(null, "product:alignment")))
                    .hasSize(1);
        }

        @Test
        @DisplayName("stands down for a kind the replica holds nothing of, rather than blocking everything")
        void coldReplicaDoesNotBlock() {
            // No service ever reached this module: the feed is not provisioned, or nobody has
            // edited a service since it was. Either way the replica cannot answer, and turning
            // that into a scheduling blocker would be a check nobody could satisfy.
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of());
            when(catalogReplicaRepository.countByItemKind(CatalogItemKind.SERVICE))
                    .thenReturn(0L);

            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .isEmpty();
        }

        @Test
        @DisplayName("cold is judged per kind: replicated products still hold their own references to account")
        void coldnessIsPerKind() {
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.SERVICE, "alignment"))
                    .thenReturn(List.of());
            when(catalogReplicaRepository.countByItemKind(CatalogItemKind.SERVICE))
                    .thenReturn(0L);
            when(catalogReplicaRepository.findByItemKindAndNameIgnoreCase(CatalogItemKind.PRODUCT, "alignment"))
                    .thenReturn(List.of());
            when(catalogReplicaRepository.countByItemKind(CatalogItemKind.PRODUCT))
                    .thenReturn(400L);

            // The product and service replays are separate operator calls, so one kind being
            // replicated says nothing about whether the other ever arrived.
            assertThat(validator().problems(campaign(null, "service:alignment")))
                    .isEmpty();
            assertThat(validator().problems(campaign(null, "product:alignment")))
                    .hasSize(1);
        }

        @Test
        @DisplayName("rejects a bare name and says what to write instead")
        void bareNameRejected() {
            assertThat(validator().problems(campaign(null, "alignment")))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("is not a catalog reference")
                    .contains("product, sku, service, category");
            // Grammar is checked before the replica is asked anything.
            verifyNoInteractions(catalogReplicaRepository);
        }

        @Test
        @DisplayName("a blank reference is absence, not a malformed value")
        void blankIsAbsent() {
            assertThat(validator().problems(campaign(null, "   "))).isEmpty();
            verifyNoInteractions(catalogReplicaRepository);
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
