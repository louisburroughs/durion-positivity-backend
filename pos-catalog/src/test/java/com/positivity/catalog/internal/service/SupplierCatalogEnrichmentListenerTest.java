package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogEnrichmentProperties;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.enums.TreadDesignSource;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignMatchCandidateRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("supplier.events.v1 -> tread design enrichment (CAP-324 #1352, confidence tiers #1645)")
class SupplierCatalogEnrichmentListenerTest {

    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
    private static final UUID DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");
    private static final UUID RIVAL_DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b04");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneOffset.UTC);

    /**
     * Thresholds are deliberately loose in most of these tests: the subject here is which candidate
     * the listener is <em>allowed</em> to attach, not where the score cut-offs sit — those belong to
     * {@link TreadDesignMatcherTest}. A low auto threshold makes every plausible candidate AUTO-tier
     * so the attachment rules are the only thing being exercised.
     */
    private static final CatalogEnrichmentProperties LOOSE_THRESHOLDS =
            new CatalogEnrichmentProperties(0.10, 0.05, null);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private TreadDesignRepository treadDesignRepository;

    @Mock
    private TreadDesignTextRepository treadDesignTextRepository;

    @Mock
    private TreadDesignImageRepository treadDesignImageRepository;

    @Mock
    private TreadDesignMatchCandidateRepository treadDesignMatchCandidateRepository;

    @Mock
    private SupplierPriceEntryRepository supplierPriceEntryRepository;

    @Mock
    private ProductRepository productRepository;

    private SupplierCatalogEnrichmentListener listener;

    @BeforeEach
    void setUp() {
        listener = listenerWith(LOOSE_THRESHOLDS);
        when(treadDesignRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.empty());
        when(treadDesignRepository.save(any(TreadDesignEntity.class))).thenAnswer(inv -> {
            TreadDesignEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(DESIGN_ID);
            }
            return entity;
        });
        when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(any()))
                .thenReturn(List.of());
        when(treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(any(), any(), any()))
                .thenReturn(List.of());
        when(productRepository.findByTreadDesignId(any())).thenReturn(List.of());
        when(productRepository.existsByTreadDesignIdAndTreadDesignSource(any(), any()))
                .thenReturn(false);
    }

    private SupplierCatalogEnrichmentListener listenerWith(CatalogEnrichmentProperties properties) {
        return new SupplierCatalogEnrichmentListener(
                CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                treadDesignRepository,
                treadDesignTextRepository,
                treadDesignImageRepository,
                treadDesignMatchCandidateRepository,
                supplierPriceEntryRepository,
                productRepository,
                new TreadDesignMatcher(properties, new BrandNormalizer(properties)));
    }

    /** A product the sample event's design plainly describes: same brand, same design name. */
    private static ProductEntity michelinProduct(UUID id) {
        return michelinProductNamed(id, "Michelin Pilot Sport 4S 245/40R18");
    }

    /** Same brand as {@link #michelinProduct}, a caller-chosen name -- used to steer the score. */
    private static ProductEntity michelinProductNamed(UUID id, String name) {
        ProductEntity product = new ProductEntity();
        product.setId(id);
        product.setManufacturerBrand("Michelin");
        product.setName(name);
        return product;
    }

    /** A deterministic, distinct UUID per index -- used to build candidate sets larger than the cap. */
    private static UUID productId(int index) {
        return UUID.fromString(String.format("018f0a1b-2c3d-7e4f-8a9b-%012x", 0x600000 + index));
    }

    private static String enrichmentEvent(
            String eventId, String vendorVariantId, String contentHash, boolean unresolvedImage) {
        return """
                {"eventId":"%s","eventType":"supplier.catalog.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-08-18T08:59:00Z",
                 "sourceService":"pos-supplier",
                 "payload":{"vendorProfileId":"%s","supplierRef":"michelin-eu","vendorVariantId":"%s",
                   "brand":"Michelin","treadDesign":"Pilot Sport 4S","treadDesign2":null,
                   "productName":"Michelin Pilot Sport 4S","vehicleType":"passenger","seasonality":"summer",
                   "contentHash":"%s",
                   "texts":[{"languageCode":"en-US","name":"Pilot Sport 4S","description":"High performance",
                     "footNotes":null}],
                   "images":[{"imageType":"HERO","imageId":%s,"contentHash":%s,
                     "sourceUri":"https://vendor.example/img.jpg","unresolved":%s}],
                   "occurredAt":"2026-08-18T08:59:00Z"}}
                """.formatted(
                        eventId,
                        VENDOR_PROFILE_ID,
                        VENDOR_PROFILE_ID,
                        vendorVariantId,
                        contentHash,
                        unresolvedImage ? "null" : "42",
                        unresolvedImage ? "null" : "\"abc123\"",
                        unresolvedImage);
    }

    @Nested
    @DisplayName("applying an enrichment")
    class Applying {

        @Test
        void appliesAWellFormedEnrichmentAndRecordsProcessed() {
            listener.onSupplierEvent(enrichmentEvent("e-1", "VAR-1", "hash-1", false));

            ArgumentCaptor<TreadDesignEntity> designCaptor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(designCaptor.capture());
            TreadDesignEntity saved = designCaptor.getValue();
            assertThat(saved.getVendorProfileId()).isEqualTo(VENDOR_PROFILE_ID);
            assertThat(saved.getVendorVariantId()).isEqualTo("VAR-1");
            assertThat(saved.getBrand()).isEqualTo("Michelin");
            assertThat(saved.getTreadDesign()).isEqualTo("Pilot Sport 4S");
            assertThat(saved.getContentHash()).isEqualTo("hash-1");
            assertThat(saved.isHasUnresolvedImages()).isFalse();

            verify(treadDesignTextRepository).deleteByTreadDesignId(DESIGN_ID);
            ArgumentCaptor<TreadDesignTextEntity> textCaptor = ArgumentCaptor.forClass(TreadDesignTextEntity.class);
            verify(treadDesignTextRepository).save(textCaptor.capture());
            assertThat(textCaptor.getValue().getLanguageCode()).isEqualTo("en-US");
            assertThat(textCaptor.getValue().getDescription()).isEqualTo("High performance");

            verify(treadDesignImageRepository).deleteByTreadDesignId(DESIGN_ID);
            ArgumentCaptor<TreadDesignImageEntity> imageCaptor = ArgumentCaptor.forClass(TreadDesignImageEntity.class);
            verify(treadDesignImageRepository).save(imageCaptor.capture());
            assertThat(imageCaptor.getValue().getImageId()).isEqualTo(42L);
            assertThat(imageCaptor.getValue().isUnresolved()).isFalse();

            ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(processedCaptor.capture());
            assertThat(processedCaptor.getValue().getEventId()).isEqualTo("e-1");
        }

        @Test
        void treatsAnUnresolvedImageAsNotYetRatherThanAbsent() {
            listener.onSupplierEvent(enrichmentEvent("e-2", "VAR-2", "hash-1", true));

            ArgumentCaptor<TreadDesignEntity> designCaptor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(designCaptor.capture());
            assertThat(designCaptor.getValue().isHasUnresolvedImages()).isTrue();

            ArgumentCaptor<TreadDesignImageEntity> imageCaptor = ArgumentCaptor.forClass(TreadDesignImageEntity.class);
            verify(treadDesignImageRepository).save(imageCaptor.capture());
            assertThat(imageCaptor.getValue().isUnresolved()).isTrue();
            assertThat(imageCaptor.getValue().getImageId()).isNull();
            // The record is still applied whole -- a missing picture never drops the rest.
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }
    }

    @Nested
    @DisplayName("redelivery and content staleness")
    class Redelivery {

        @Test
        void redeliveredEventIdIsANoOp() {
            when(processedEventRepository.existsById("e-3")).thenReturn(true);

            listener.onSupplierEvent(enrichmentEvent("e-3", "VAR-1", "hash-1", false));

            verify(treadDesignRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void unchangedContentHashOnANewEventIdIsANoOpButStillRecordsTheEvent() {
            when(treadDesignRepository.findByVendorProfileIdAndVendorVariantId(VENDOR_PROFILE_ID, "VAR-1"))
                    .thenReturn(Optional.of(TreadDesignEntity.builder()
                            .id(DESIGN_ID)
                            .vendorProfileId(VENDOR_PROFILE_ID)
                            .vendorVariantId("VAR-1")
                            .contentHash("hash-1")
                            .build()));

            // A republication of unchanged content still arrives as a new event and is legitimately
            // processed -- the domain-level action is a no-op, but the delivery itself is not.
            listener.onSupplierEvent(enrichmentEvent("e-4", "VAR-1", "hash-1", false));

            verify(treadDesignRepository, never()).save(any());
            verify(treadDesignTextRepository, never()).save(any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        void changedContentHashIsAppliedLastWriteWins() {
            when(treadDesignRepository.findByVendorProfileIdAndVendorVariantId(VENDOR_PROFILE_ID, "VAR-1"))
                    .thenReturn(Optional.of(TreadDesignEntity.builder()
                            .id(DESIGN_ID)
                            .vendorProfileId(VENDOR_PROFILE_ID)
                            .vendorVariantId("VAR-1")
                            .contentHash("old-hash")
                            .build()));

            listener.onSupplierEvent(enrichmentEvent("e-5", "VAR-1", "new-hash", false));

            ArgumentCaptor<TreadDesignEntity> captor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getContentHash()).isEqualTo("new-hash");
        }
    }

    @Nested
    @DisplayName("re-matching when the vendor changes what it published")
    class Rematching {

        private TreadDesignEntity existing(TreadDesignMatchState state) {
            return TreadDesignEntity.builder()
                    .id(DESIGN_ID)
                    .vendorProfileId(VENDOR_PROFILE_ID)
                    .vendorVariantId("VAR-1")
                    .contentHash("old-hash")
                    .matchState(state)
                    .matchStateAt(Instant.parse("2026-08-01T00:00:00Z"))
                    .build();
        }

        private void existingDesignIs(TreadDesignMatchState state) {
            when(treadDesignRepository.findByVendorProfileIdAndVendorVariantId(VENDOR_PROFILE_ID, "VAR-1"))
                    .thenReturn(Optional.of(existing(state)));
            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(List.of(PRODUCT_ID));
            when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(michelinProduct(PRODUCT_ID)));
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(
                value = TreadDesignMatchState.class,
                names = {"UNMATCHED", "REVIEW", "DEFERRED", "REJECTED"})
        @DisplayName("an undecided or rejected design re-enters matching — the vendor changed its words")
        void changedContentReRunsMatching(TreadDesignMatchState state) {
            existingDesignIs(state);

            listener.onSupplierEvent(enrichmentEvent("e-20-" + state, "VAR-1", "new-hash", false));

            // A REJECTED design re-enters deliberately: the rejection was of what the vendor said,
            // and the vendor has now said something different.
            verify(productRepository).save(any(ProductEntity.class));
        }

        @Test
        @DisplayName("a design a person attached by hand is left alone, however the content changes")
        void manuallyMatchedDesignsAreNotReMatched() {
            existingDesignIs(TreadDesignMatchState.MATCHED);
            when(productRepository.existsByTreadDesignIdAndTreadDesignSource(DESIGN_ID, TreadDesignSource.MANUAL))
                    .thenReturn(true);

            listener.onSupplierEvent(enrichmentEvent("e-21", "VAR-1", "new-hash", false));

            verify(productRepository, never()).save(any());
            verify(treadDesignMatchCandidateRepository, never()).deleteByTreadDesignId(any());
            // The content itself is still applied — only the matching is left alone.
            verify(treadDesignRepository).save(any(TreadDesignEntity.class));
        }

        @Test
        @DisplayName("a matched design holding only automatic attachments is re-evaluated")
        void automaticallyMatchedDesignsAreReEvaluated() {
            existingDesignIs(TreadDesignMatchState.MATCHED);
            when(productRepository.existsByTreadDesignIdAndTreadDesignSource(DESIGN_ID, TreadDesignSource.MANUAL))
                    .thenReturn(false);

            listener.onSupplierEvent(enrichmentEvent("e-22", "VAR-1", "new-hash", false));

            verify(productRepository).save(any(ProductEntity.class));
        }

        @Test
        @DisplayName("re-matching to the same decision state leaves matchStateAt untouched, so the worklist "
                + "does not re-age on a vendor re-publication or re-match that lands back on the same state")
        void reMatchingToTheSameStateLeavesMatchStateAtUntouched() {
            // High auto threshold, low review floor: the candidate scores below AUTO but above REVIEW,
            // so the design lands on REVIEW both before and after this re-match.
            listener = listenerWith(new CatalogEnrichmentProperties(0.99, 0.05, null));
            existingDesignIs(TreadDesignMatchState.REVIEW);

            listener.onSupplierEvent(enrichmentEvent("e-23", "VAR-1", "new-hash", false));

            ArgumentCaptor<TreadDesignEntity> captor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            TreadDesignEntity saved = captor.getValue();
            assertThat(saved.getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW);
            assertThat(saved.getMatchStateAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        }

        @Test
        @DisplayName("a re-match that actually moves the decision ages matchStateAt to now")
        void reMatchingToADifferentStateUpdatesMatchStateAt() {
            existingDesignIs(TreadDesignMatchState.UNMATCHED);

            listener.onSupplierEvent(enrichmentEvent("e-24", "VAR-1", "new-hash", false));

            ArgumentCaptor<TreadDesignEntity> captor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            TreadDesignEntity saved = captor.getValue();
            assertThat(saved.getMatchState()).isEqualTo(TreadDesignMatchState.MATCHED);
            assertThat(saved.getMatchStateAt()).isEqualTo(CLOCK.instant());
        }
    }

    @Nested
    @DisplayName("matching to products")
    class Matching {

        private void vendorHasPriced(ProductEntity... products) {
            List<UUID> ids =
                    java.util.Arrays.stream(products).map(ProductEntity::getId).toList();
            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(ids);
            when(productRepository.findAllById(ids)).thenReturn(List.of(products));
        }

        private TreadDesignEntity savedDesign() {
            ArgumentCaptor<TreadDesignEntity> captor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            return captor.getValue();
        }

        @Test
        void aDesignThatMatchesNothingIsParkedForReviewNotAnError() {
            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(List.of());

            listener.onSupplierEvent(enrichmentEvent("e-6", "VAR-1", "hash-1", false));

            verify(productRepository, never()).findAllById(any());
            verify(productRepository, never()).save(any());
            // Still applied and recorded -- an unmatched design is an ordinary outcome.
            assertThat(savedDesign().getMatchState()).isEqualTo(TreadDesignMatchState.UNMATCHED);
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        void aMatchedCandidateGetsTheDesignIdAndNothingElseChanges() {
            ProductEntity candidate = michelinProduct(PRODUCT_ID);
            candidate.setSku("SKU-KEEP");
            candidate.setManufacturerPartNumber("MPN-KEEP");
            vendorHasPriced(candidate);

            listener.onSupplierEvent(enrichmentEvent("e-7", "VAR-1", "hash-1", false));

            ArgumentCaptor<ProductEntity> productCaptor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(productCaptor.capture());
            assertThat(productCaptor.getValue().getTreadDesignId()).isEqualTo(DESIGN_ID);
            assertThat(productCaptor.getValue().getTreadDesignSource()).isEqualTo(TreadDesignSource.AUTO);
            // Product identity and structure are untouched -- only the association was written.
            assertThat(productCaptor.getValue().getSku()).isEqualTo("SKU-KEEP");
            assertThat(productCaptor.getValue().getManufacturerPartNumber()).isEqualTo("MPN-KEEP");
            assertThat(savedDesign().getMatchState()).isEqualTo(TreadDesignMatchState.MATCHED);
        }

        @Test
        void anUnrelatedCandidateIsNotMatched() {
            ProductEntity unrelated = new ProductEntity();
            unrelated.setId(PRODUCT_ID);
            unrelated.setManufacturerBrand("Continental");
            unrelated.setName("Continental ExtremeContact");
            vendorHasPriced(unrelated);

            listener.onSupplierEvent(enrichmentEvent("e-8", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
            assertThat(savedDesign().getMatchState()).isEqualTo(TreadDesignMatchState.UNMATCHED);
        }

        @Test
        @DisplayName("what the matcher saw is recorded, so a reviewer can judge it instead of re-running it")
        void scoredCandidatesAreRecorded() {
            vendorHasPriced(michelinProduct(PRODUCT_ID));

            listener.onSupplierEvent(enrichmentEvent("e-12", "VAR-1", "hash-1", false));

            verify(treadDesignMatchCandidateRepository).deleteByTreadDesignId(DESIGN_ID);
            ArgumentCaptor<TreadDesignMatchCandidateEntity> captor =
                    ArgumentCaptor.forClass(TreadDesignMatchCandidateEntity.class);
            verify(treadDesignMatchCandidateRepository).save(captor.capture());
            assertThat(captor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(captor.getValue().getTier()).isEqualTo(MatchTier.AUTO);
            assertThat(captor.getValue().getScore()).isGreaterThan(java.math.BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a candidate below the auto threshold is parked for review, never attached")
        void belowAutoIsParkedForReview() {
            listener = listenerWith(new CatalogEnrichmentProperties(0.99, 0.05, null));
            vendorHasPriced(michelinProduct(PRODUCT_ID));

            listener.onSupplierEvent(enrichmentEvent("e-13", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
            assertThat(savedDesign().getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW);
        }

        @Test
        @DisplayName("a product two designs claim at auto tier is attached to neither, and both are parked")
        void ambiguousClaimsParkBothDesigns() {
            vendorHasPriced(michelinProduct(PRODUCT_ID));
            when(treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                            PRODUCT_ID, MatchTier.AUTO, DESIGN_ID))
                    .thenReturn(List.of(TreadDesignMatchCandidateEntity.builder()
                            .treadDesignId(RIVAL_DESIGN_ID)
                            .productId(PRODUCT_ID)
                            .tier(MatchTier.AUTO)
                            .build()));
            when(treadDesignRepository.findById(RIVAL_DESIGN_ID))
                    .thenReturn(Optional.of(TreadDesignEntity.builder()
                            .id(RIVAL_DESIGN_ID)
                            .matchState(TreadDesignMatchState.MATCHED)
                            .build()));

            listener.onSupplierEvent(enrichmentEvent("e-14", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
            ArgumentCaptor<TreadDesignEntity> captor = ArgumentCaptor.forClass(TreadDesignEntity.class);
            verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues())
                    .filteredOn(saved -> RIVAL_DESIGN_ID.equals(saved.getId()))
                    .singleElement()
                    .satisfies(rival -> assertThat(rival.getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW));
            assertThat(captor.getAllValues())
                    .filteredOn(saved -> DESIGN_ID.equals(saved.getId()))
                    .last()
                    .satisfies(mine -> assertThat(mine.getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW));
        }

        @Test
        @DisplayName("a product a person attached is never re-pointed by an automatic pass")
        void manualAttachmentsAreNeverOverwritten() {
            ProductEntity manual = michelinProduct(PRODUCT_ID);
            manual.setTreadDesignId(RIVAL_DESIGN_ID);
            manual.setTreadDesignSource(TreadDesignSource.MANUAL);
            vendorHasPriced(manual);

            listener.onSupplierEvent(enrichmentEvent("e-15", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
            assertThat(manual.getTreadDesignId()).isEqualTo(RIVAL_DESIGN_ID);
            assertThat(manual.getTreadDesignSource()).isEqualTo(TreadDesignSource.MANUAL);
            assertThat(savedDesign().getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW);
        }

        @Test
        @DisplayName("an automatic attachment this design no longer scores is cleared rather than left stale")
        void staleAutoAttachmentsAreCleared() {
            ProductEntity stale = new ProductEntity();
            stale.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b09"));
            stale.setTreadDesignId(DESIGN_ID);
            stale.setTreadDesignSource(TreadDesignSource.AUTO);
            when(productRepository.findByTreadDesignId(DESIGN_ID)).thenReturn(List.of(stale));

            listener.onSupplierEvent(enrichmentEvent("e-16", "VAR-1", "hash-1", false));

            assertThat(stale.getTreadDesignId()).isNull();
            assertThat(stale.getTreadDesignSource()).isNull();
            verify(productRepository).save(stale);
        }

        @Test
        @DisplayName("a rival's stale AUTO attachment on the disputed product is cleared when ambiguity fires")
        void rivalAutoAttachmentIsClearedWhenAmbiguityFires() {
            ProductEntity disputed = michelinProduct(PRODUCT_ID);
            disputed.setTreadDesignId(RIVAL_DESIGN_ID);
            disputed.setTreadDesignSource(TreadDesignSource.AUTO);
            vendorHasPriced(disputed);
            when(treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                            PRODUCT_ID, MatchTier.AUTO, DESIGN_ID))
                    .thenReturn(List.of(TreadDesignMatchCandidateEntity.builder()
                            .treadDesignId(RIVAL_DESIGN_ID)
                            .productId(PRODUCT_ID)
                            .tier(MatchTier.AUTO)
                            .build()));
            when(treadDesignRepository.findById(RIVAL_DESIGN_ID))
                    .thenReturn(Optional.of(TreadDesignEntity.builder()
                            .id(RIVAL_DESIGN_ID)
                            .matchState(TreadDesignMatchState.MATCHED)
                            .build()));

            listener.onSupplierEvent(enrichmentEvent("e-17", "VAR-1", "hash-1", false));

            verify(productRepository).save(disputed);
            assertThat(disputed.getTreadDesignId()).isNull();
            assertThat(disputed.getTreadDesignSource()).isNull();
        }

        @Test
        @DisplayName("a MANUAL attachment on the disputed product is left untouched even when a rival claims it")
        void manualAttachmentIsNotClearedEvenWhenARivalClaimsTheSameProduct() {
            ProductEntity disputed = michelinProduct(PRODUCT_ID);
            disputed.setTreadDesignId(RIVAL_DESIGN_ID);
            disputed.setTreadDesignSource(TreadDesignSource.MANUAL);
            vendorHasPriced(disputed);
            // Primed as if ambiguity would fire -- proving the MANUAL guard, not an absence of rivals,
            // is what keeps this product untouched.
            when(treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                            PRODUCT_ID, MatchTier.AUTO, DESIGN_ID))
                    .thenReturn(List.of(TreadDesignMatchCandidateEntity.builder()
                            .treadDesignId(RIVAL_DESIGN_ID)
                            .productId(PRODUCT_ID)
                            .tier(MatchTier.AUTO)
                            .build()));

            listener.onSupplierEvent(enrichmentEvent("e-18", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
            assertThat(disputed.getTreadDesignId()).isEqualTo(RIVAL_DESIGN_ID);
            assertThat(disputed.getTreadDesignSource()).isEqualTo(TreadDesignSource.MANUAL);
        }

        @Nested
        @DisplayName("candidate persistence: AUTO rows are never capped, only REVIEW rows are (ADR-0060 §4)")
        class CandidatePersistenceCap {

            @Test
            @DisplayName("every AUTO-tier candidate is persisted, however many clear the auto threshold")
            void everyAutoTierCandidateIsPersistedUncapped() {
                List<ProductEntity> candidates = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    candidates.add(michelinProduct(productId(i)));
                }
                vendorHasPriced(candidates.toArray(new ProductEntity[0]));

                listener.onSupplierEvent(enrichmentEvent("e-40", "VAR-1", "hash-40", false));

                ArgumentCaptor<TreadDesignMatchCandidateEntity> candidateCaptor =
                        ArgumentCaptor.forClass(TreadDesignMatchCandidateEntity.class);
                verify(treadDesignMatchCandidateRepository, org.mockito.Mockito.times(25))
                        .save(candidateCaptor.capture());
                assertThat(candidateCaptor.getAllValues())
                        .hasSize(25)
                        .allSatisfy(saved -> assertThat(saved.getTier()).isEqualTo(MatchTier.AUTO));

                ArgumentCaptor<ProductEntity> productCaptor = ArgumentCaptor.forClass(ProductEntity.class);
                verify(productRepository, org.mockito.Mockito.times(25)).save(productCaptor.capture());
                Set<UUID> attachedIds = productCaptor.getAllValues().stream()
                        .map(ProductEntity::getId)
                        .collect(Collectors.toSet());
                Set<UUID> candidateRowIds = candidateCaptor.getAllValues().stream()
                        .map(TreadDesignMatchCandidateEntity::getProductId)
                        .collect(Collectors.toSet());
                // Every attached product has a persisted candidate row explaining the attachment, and
                // every AUTO row corresponds to a product that was actually attached.
                assertThat(attachedIds).hasSize(25).isEqualTo(candidateRowIds);
            }

            @Test
            @DisplayName("a rival claiming a candidate beyond the review cap is still caught -- AUTO rows "
                    + "are never capped, so the row it needs to see is still there")
            void rivalClaimBeyondTheReviewCapStillTriggersAmbiguity() {
                List<ProductEntity> candidates = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    candidates.add(michelinProduct(productId(i)));
                }
                vendorHasPriced(candidates.toArray(new ProductEntity[0]));

                listener.onSupplierEvent(enrichmentEvent("e-41", "VAR-1", "hash-41", false));

                ArgumentCaptor<TreadDesignMatchCandidateEntity> candidateCaptor =
                        ArgumentCaptor.forClass(TreadDesignMatchCandidateEntity.class);
                verify(treadDesignMatchCandidateRepository, org.mockito.Mockito.times(25))
                        .save(candidateCaptor.capture());
                // Position 21 (index 20) sits beyond the REVIEW-tier cap of 20 -- under the old
                // capped-everything behaviour this row would never have been persisted.
                TreadDesignMatchCandidateEntity beyondCap =
                        candidateCaptor.getAllValues().get(20);
                assertThat(beyondCap.getTier()).isEqualTo(MatchTier.AUTO);
                UUID disputedProductId = beyondCap.getProductId();

                // A rival design's later event also claims exactly that product at AUTO tier.
                when(treadDesignRepository.save(any(TreadDesignEntity.class))).thenAnswer(inv -> {
                    TreadDesignEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId("VAR-RIVAL".equals(entity.getVendorVariantId()) ? RIVAL_DESIGN_ID : DESIGN_ID);
                    }
                    return entity;
                });
                when(treadDesignRepository.findById(DESIGN_ID))
                        .thenReturn(Optional.of(TreadDesignEntity.builder()
                                .id(DESIGN_ID)
                                .matchState(TreadDesignMatchState.MATCHED)
                                .build()));
                when(treadDesignMatchCandidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                                disputedProductId, MatchTier.AUTO, RIVAL_DESIGN_ID))
                        .thenReturn(List.of(TreadDesignMatchCandidateEntity.builder()
                                .treadDesignId(DESIGN_ID)
                                .productId(disputedProductId)
                                .tier(MatchTier.AUTO)
                                .build()));
                when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                        .thenReturn(List.of(disputedProductId));
                when(productRepository.findAllById(List.of(disputedProductId)))
                        .thenReturn(List.of(michelinProduct(disputedProductId)));

                listener.onSupplierEvent(enrichmentEvent("e-42", "VAR-RIVAL", "hash-42", false));

                ArgumentCaptor<TreadDesignEntity> designCaptor = ArgumentCaptor.forClass(TreadDesignEntity.class);
                verify(treadDesignRepository, org.mockito.Mockito.atLeastOnce()).save(designCaptor.capture());
                assertThat(designCaptor.getAllValues())
                        .filteredOn(saved -> DESIGN_ID.equals(saved.getId()))
                        .last()
                        .satisfies(first -> assertThat(first.getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW));
                assertThat(designCaptor.getAllValues())
                        .filteredOn(saved -> RIVAL_DESIGN_ID.equals(saved.getId()))
                        .last()
                        .satisfies(rival -> assertThat(rival.getMatchState()).isEqualTo(TreadDesignMatchState.REVIEW));
                // Parked -- attached to neither design.
                verify(productRepository, never())
                        .save(argThat(p ->
                                disputedProductId.equals(p.getId()) && RIVAL_DESIGN_ID.equals(p.getTreadDesignId())));
            }

            @Test
            @DisplayName("REVIEW-tier candidates beyond the cap are dropped, best score first")
            void reviewTierCandidatesAreCappedBestScoreFirst() {
                listener = listenerWith(new CatalogEnrichmentProperties(0.90, 0.01, null));
                // A lower-scoring group, deliberately listed FIRST, so a naive input-order cap would
                // keep them instead of the higher-scoring group listed after them.
                List<ProductEntity> lowScoring = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    lowScoring.add(michelinProductNamed(
                            productId(100 + i), "Michelin Pilot Sport 4S zzqxvkwjbf1235690zzqxvkwjbf1235690"));
                }
                List<ProductEntity> highScoring = new ArrayList<>();
                for (int i = 0; i < 15; i++) {
                    highScoring.add(michelinProductNamed(productId(200 + i), "Michelin Pilot Sport 4S"));
                }
                List<ProductEntity> ordered = new ArrayList<>();
                ordered.addAll(lowScoring);
                ordered.addAll(highScoring);
                vendorHasPriced(ordered.toArray(new ProductEntity[0]));

                listener.onSupplierEvent(enrichmentEvent("e-43", "VAR-1", "hash-43", false));

                ArgumentCaptor<TreadDesignMatchCandidateEntity> candidateCaptor =
                        ArgumentCaptor.forClass(TreadDesignMatchCandidateEntity.class);
                verify(treadDesignMatchCandidateRepository, org.mockito.Mockito.times(20))
                        .save(candidateCaptor.capture());
                assertThat(candidateCaptor.getAllValues())
                        .allSatisfy(saved -> assertThat(saved.getTier()).isEqualTo(MatchTier.REVIEW));

                Set<UUID> persistedIds = candidateCaptor.getAllValues().stream()
                        .map(TreadDesignMatchCandidateEntity::getProductId)
                        .collect(Collectors.toSet());
                Set<UUID> highIds =
                        highScoring.stream().map(ProductEntity::getId).collect(Collectors.toSet());
                // All 15 higher-scoring candidates survive despite being listed after the
                // lower-scoring ones -- the cap ranks by score, not by input position.
                assertThat(persistedIds).hasSize(20).containsAll(highIds);
                Set<UUID> survivingLowIds = new HashSet<>(persistedIds);
                survivingLowIds.removeAll(highIds);
                Set<UUID> expectedSurvivingLowIds = lowScoring.subList(0, 5).stream()
                        .map(ProductEntity::getId)
                        .collect(Collectors.toSet());
                // Only the best 5 of the 10 lower-scoring candidates fit the remaining cap headroom.
                assertThat(survivingLowIds).isEqualTo(expectedSurvivingLowIds);
                verify(productRepository, never()).save(any());
            }
        }
    }

    @Nested
    @DisplayName("the shared consumer contract")
    class Contract {

        @Test
        void skipsAnUnrelatedEventTypeWithoutRecordingIt() {
            listener.onSupplierEvent("""
                    {"eventId":"e-9","eventType":"supplier.pricecatalog.updated","aggregateVersion":1,"payload":{}}
                    """);

            verify(treadDesignRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void ignoresAnEventWithoutAnEventId() {
            listener.onSupplierEvent("""
                    {"eventType":"supplier.catalog.updated","payload":{}}
                    """);

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void ignoresAnUnparsableMessage() {
            listener.onSupplierEvent("not json");

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void swallowsAMalformedPayloadButLeavesItUnrecorded() {
            listener.onSupplierEvent("""
                    {"eventId":"e-10","eventType":"supplier.catalog.updated","aggregateVersion":0,
                     "payload":{"vendorVariantId":"VAR-1"}}
                    """);

            verify(treadDesignRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void rethrowsATransientDatabaseErrorSoTheContainerRetries() {
            when(treadDesignRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                    .thenThrow(new QueryTimeoutException("db busy"));

            assertThatThrownBy(() -> listener.onSupplierEvent(enrichmentEvent("e-11", "VAR-1", "hash-1", false)))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(processedEventRepository, never()).save(any());
        }
    }
}
