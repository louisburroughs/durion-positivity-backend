package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.TreadDesignCandidateDto;
import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignResolveRequest;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.enums.TreadDesignResolutionAction;
import com.positivity.catalog.internal.enums.TreadDesignSource;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignMatchCandidateRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TreadDesignServiceImpl — enrichment read and review surface (CAP-324 #1352, #1645)")
class TreadDesignServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");
    private static final UUID OTHER_DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c03");
    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c04");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TreadDesignRepository treadDesignRepository;

    @Mock
    private TreadDesignTextRepository treadDesignTextRepository;

    @Mock
    private TreadDesignImageRepository treadDesignImageRepository;

    @Mock
    private TreadDesignMatchCandidateRepository treadDesignMatchCandidateRepository;

    private TreadDesignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TreadDesignServiceImpl(
                CLOCK,
                productRepository,
                treadDesignRepository,
                treadDesignTextRepository,
                treadDesignImageRepository,
                treadDesignMatchCandidateRepository);
        when(treadDesignRepository.save(any(TreadDesignEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TreadDesignEntity design() {
        return TreadDesignEntity.builder()
                .id(DESIGN_ID)
                .vendorProfileId(VENDOR_PROFILE_ID)
                .supplierRef("michelin-eu")
                .vendorVariantId("VAR-1")
                .brand("Michelin")
                .treadDesign("Pilot Sport 4S")
                .hasUnresolvedImages(false)
                .matchState(TreadDesignMatchState.REVIEW)
                .matchStateAt(Instant.parse("2026-08-18T09:00:00Z"))
                .updatedAt(Instant.parse("2026-08-18T09:00:00Z"))
                .build();
    }

    private static ProductEntity product(UUID id, UUID treadDesignId, TreadDesignSource source) {
        ProductEntity product = new ProductEntity();
        product.setId(id);
        product.setTreadDesignId(treadDesignId);
        product.setTreadDesignSource(source);
        return product;
    }

    @Nested
    @DisplayName("the product-scoped read")
    class ForProduct {

        @Test
        @DisplayName("a product with no matched design returns empty rather than throwing")
        void unmatchedProductReturnsEmpty() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(PRODUCT_ID, null, null)));

            assertThat(service.findForProduct(PRODUCT_ID)).isEmpty();
        }

        @Test
        @DisplayName("a matched product returns the design's texts and images, vendor-supplied")
        void matchedProductReturnsEnrichment() {
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(PRODUCT_ID, DESIGN_ID, TreadDesignSource.AUTO)));
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(treadDesignTextRepository.findByTreadDesignId(DESIGN_ID))
                    .thenReturn(List.of(TreadDesignTextEntity.builder()
                            .treadDesignId(DESIGN_ID)
                            .languageCode("en-US")
                            .name("Pilot Sport 4S")
                            .build()));
            when(treadDesignImageRepository.findByTreadDesignId(DESIGN_ID))
                    .thenReturn(List.of(TreadDesignImageEntity.builder()
                            .treadDesignId(DESIGN_ID)
                            .imageId(42L)
                            .unresolved(false)
                            .build()));

            Optional<TreadDesignDto> result = service.findForProduct(PRODUCT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().brand()).isEqualTo("Michelin");
            assertThat(result.get().texts())
                    .singleElement()
                    .satisfies(text -> assertThat(text.languageCode()).isEqualTo("en-US"));
            assertThat(result.get().images())
                    .singleElement()
                    .satisfies(image -> assertThat(image.imageId()).isEqualTo(42L));
            // This read answers "what did the vendor say about this product", not "what else might
            // it have been" — the near misses belong to the review surface.
            assertThat(result.get().candidates()).isEmpty();
        }

        @Test
        @DisplayName("a product that does not exist returns empty rather than throwing")
        void missingProductReturnsEmpty() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThat(service.findForProduct(PRODUCT_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the review worklist")
    class Worklist {

        @Test
        @DisplayName("the requested states and vendor filter reach the repository unchanged")
        void passesFiltersThrough() {
            when(treadDesignRepository.findForReview(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(design())));

            assertThat(service.findForReview(
                                    List.of(TreadDesignMatchState.REVIEW), VENDOR_PROFILE_ID, PageRequest.of(0, 50))
                            .getContent())
                    .hasSize(1);

            verify(treadDesignRepository)
                    .findForReview(eq(List.of(TreadDesignMatchState.REVIEW)), eq(VENDOR_PROFILE_ID), any());
        }

        @Test
        @DisplayName("rows carry the review state and the scored candidates a reviewer has to judge")
        void rowsCarryStateAndCandidates() {
            when(treadDesignRepository.findForReview(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(design())));
            when(treadDesignMatchCandidateRepository.findByTreadDesignIdInOrderByScoreDesc(List.of(DESIGN_ID)))
                    .thenReturn(List.of(candidate(PRODUCT_ID, "0.7400", MatchTier.REVIEW)));

            TreadDesignDto row = service.findForReview(
                            List.of(TreadDesignMatchState.REVIEW), null, PageRequest.of(0, 50))
                    .getContent()
                    .getFirst();

            assertThat(row.matchState()).isEqualTo(TreadDesignMatchState.REVIEW);
            assertThat(row.matchStateAt()).isEqualTo(Instant.parse("2026-08-18T09:00:00Z"));
            assertThat(row.candidates()).singleElement().satisfies(shown -> {
                assertThat(shown.productId()).isEqualTo(PRODUCT_ID);
                assertThat(shown.score()).isEqualByComparingTo("0.7400");
                assertThat(shown.tier()).isEqualTo(MatchTier.REVIEW);
            });
        }

        @Test
        @DisplayName("an empty page does not go looking for candidates of nothing")
        void emptyPageSkipsTheDetailQueries() {
            when(treadDesignRepository.findForReview(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            assertThat(service.findForReview(List.of(TreadDesignMatchState.UNMATCHED), null, PageRequest.of(0, 50)))
                    .isEmpty();

            verify(treadDesignMatchCandidateRepository, never()).findByTreadDesignIdInOrderByScoreDesc(any());
        }

        @Test
        @DisplayName("a worklist row's candidates are bounded to the top 20, however many are persisted "
                + "(read-time bound only -- storage stays unbounded for AUTO tier, #1645)")
        void rowCandidatesAreBoundedToTheWorklistCap() {
            when(treadDesignRepository.findForReview(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(design())));
            List<TreadDesignMatchCandidateEntity> stored = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                // Already best-first, as the query itself returns (see the service javadoc).
                stored.add(candidate(UUID.randomUUID(), String.format("0.%04d", 9900 - (i * 10)), MatchTier.REVIEW));
            }
            when(treadDesignMatchCandidateRepository.findByTreadDesignIdInOrderByScoreDesc(List.of(DESIGN_ID)))
                    .thenReturn(stored);

            TreadDesignDto row = service.findForReview(
                            List.of(TreadDesignMatchState.REVIEW), null, PageRequest.of(0, 50))
                    .getContent()
                    .getFirst();

            assertThat(row.candidates()).hasSize(20);
            assertThat(row.candidates().stream()
                            .map(TreadDesignCandidateDto::productId)
                            .toList())
                    .isEqualTo(stored.subList(0, 20).stream()
                            .map(TreadDesignMatchCandidateEntity::getProductId)
                            .toList());
        }
    }

    @Nested
    @DisplayName("listing one design's candidates")
    class Candidates {

        @Test
        @DisplayName("an unknown design is a 404, not an empty list")
        void unknownDesignIsNotFound() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findCandidates(DESIGN_ID)).isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        @DisplayName("a design nothing resembled returns an empty list, which is a real answer")
        void knownDesignWithoutCandidatesReturnsEmpty() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(treadDesignMatchCandidateRepository.findByTreadDesignIdOrderByScoreDesc(DESIGN_ID))
                    .thenReturn(List.of());

            assertThat(service.findCandidates(DESIGN_ID)).isEmpty();
        }

        @Test
        @DisplayName("findCandidates returns every scored candidate, unbounded by the worklist read-time cap")
        void returnsEveryScoredCandidateUnboundedByTheWorklistCap() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            List<TreadDesignMatchCandidateEntity> stored = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                stored.add(candidate(UUID.randomUUID(), String.format("0.%04d", 9900 - (i * 10)), MatchTier.REVIEW));
            }
            when(treadDesignMatchCandidateRepository.findByTreadDesignIdOrderByScoreDesc(DESIGN_ID))
                    .thenReturn(stored);

            assertThat(service.findCandidates(DESIGN_ID)).hasSize(25);
        }
    }

    @Nested
    @DisplayName("resolving a design")
    class Resolve {

        @Test
        @DisplayName("ATTACH marks each product MANUAL so no later automatic pass may re-point it")
        void attachMarksProductsManual() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(PRODUCT_ID, null, null)));

            TreadDesignDto result = service.resolve(
                    DESIGN_ID,
                    new TreadDesignResolveRequest(
                            TreadDesignResolutionAction.ATTACH, List.of(PRODUCT_ID), "vendor confirmed", null),
                    "reviewer@example.com");

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getTreadDesignId()).isEqualTo(DESIGN_ID);
            assertThat(captor.getValue().getTreadDesignSource()).isEqualTo(TreadDesignSource.MANUAL);
            assertThat(result.matchState()).isEqualTo(TreadDesignMatchState.MATCHED);
            assertThat(result.resolvedBy()).isEqualTo("reviewer@example.com");
            assertThat(result.resolutionNote()).isEqualTo("vendor confirmed");
            assertThat(result.matchStateAt()).isEqualTo(CLOCK.instant());
        }

        @Test
        @DisplayName("ATTACH replaces an automatic attachment without ceremony — that is what review is for")
        void attachOverridesAnAutomaticAttachment() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(PRODUCT_ID, OTHER_DESIGN_ID, TreadDesignSource.AUTO)));

            service.resolve(
                    DESIGN_ID,
                    new TreadDesignResolveRequest(TreadDesignResolutionAction.ATTACH, List.of(PRODUCT_ID), null, null),
                    "reviewer@example.com");

            ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getTreadDesignId()).isEqualTo(DESIGN_ID);
        }

        @Test
        @DisplayName("ATTACH to a product another design holds by a person's decision is a conflict")
        void attachToAnotherManualAttachmentConflicts() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(productRepository.findById(PRODUCT_ID))
                    .thenReturn(Optional.of(product(PRODUCT_ID, OTHER_DESIGN_ID, TreadDesignSource.MANUAL)));

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(
                                    TreadDesignResolutionAction.ATTACH, List.of(PRODUCT_ID), null, null),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogBusinessRuleException.class);
        }

        @Test
        @DisplayName("ATTACH with no products is refused rather than reporting success having attached nothing")
        void attachWithoutProductsIsInvalid() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(TreadDesignResolutionAction.ATTACH, List.of(), null, null),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogValidationException.class);
        }

        @Test
        @DisplayName("ATTACH to a product that does not exist is a 404")
        void attachToUnknownProductIsNotFound() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(
                                    TreadDesignResolutionAction.ATTACH, List.of(PRODUCT_ID), null, null),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        @DisplayName("REJECT records the ruling and detaches nothing a person attached earlier")
        void rejectDetachesNothing() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));

            TreadDesignDto result = service.resolve(
                    DESIGN_ID,
                    new TreadDesignResolveRequest(TreadDesignResolutionAction.REJECT, null, "none of these", null),
                    "reviewer@example.com");

            assertThat(result.matchState()).isEqualTo(TreadDesignMatchState.REJECTED);
            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("re-resolving to the same state leaves matchStateAt untouched, only the note moves")
        void reResolvingToTheSameStateLeavesMatchStateAtUntouched() {
            TreadDesignEntity alreadyRejected = design();
            alreadyRejected.setMatchState(TreadDesignMatchState.REJECTED);
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(alreadyRejected));

            TreadDesignDto result = service.resolve(
                    DESIGN_ID,
                    new TreadDesignResolveRequest(TreadDesignResolutionAction.REJECT, null, "still not it", null),
                    "reviewer@example.com");

            assertThat(result.matchState()).isEqualTo(TreadDesignMatchState.REJECTED);
            // The decision did not move (REJECTED -> REJECTED), so the worklist must not re-age it —
            // only the note and resolver, which did change, are expected to move.
            assertThat(result.matchStateAt()).isEqualTo(Instant.parse("2026-08-18T09:00:00Z"));
            assertThat(result.resolutionNote()).isEqualTo("still not it");
        }

        @Test
        @DisplayName("REJECT carrying products is refused — the fields do not go together")
        void rejectWithProductsIsInvalid() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(
                                    TreadDesignResolutionAction.REJECT, List.of(PRODUCT_ID), null, null),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogValidationException.class);
        }

        @Test
        @DisplayName("DEFER stores the date the design should come back")
        void deferStoresTheDate() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
            Instant until = Instant.parse("2026-10-01T00:00:00Z");

            TreadDesignDto result = service.resolve(
                    DESIGN_ID,
                    new TreadDesignResolveRequest(TreadDesignResolutionAction.DEFER, null, "waiting on vendor", until),
                    "reviewer@example.com");

            assertThat(result.matchState()).isEqualTo(TreadDesignMatchState.DEFERRED);
            assertThat(result.deferUntil()).isEqualTo(until);
        }

        @Test
        @DisplayName("a deferUntil on an ATTACH is refused rather than silently ignored")
        void attachWithDeferUntilIsInvalid() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(
                                    TreadDesignResolutionAction.ATTACH,
                                    List.of(PRODUCT_ID),
                                    null,
                                    Instant.parse("2026-10-01T00:00:00Z")),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogValidationException.class);
        }

        @Test
        @DisplayName("resolving an unknown design is a 404")
        void unknownDesignIsNotFound() {
            when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(
                            DESIGN_ID,
                            new TreadDesignResolveRequest(TreadDesignResolutionAction.REJECT, null, null, null),
                            "reviewer@example.com"))
                    .isInstanceOf(CatalogNotFoundException.class);
        }
    }

    private static TreadDesignMatchCandidateEntity candidate(UUID productId, String score, MatchTier tier) {
        return TreadDesignMatchCandidateEntity.builder()
                .treadDesignId(DESIGN_ID)
                .productId(productId)
                .score(new BigDecimal(score))
                .tier(tier)
                .build();
    }
}
