package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("supplier.events.v1 -> tread design enrichment (CAP-324 #1352)")
class SupplierCatalogEnrichmentListenerTest {

    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
    private static final UUID DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private TreadDesignRepository treadDesignRepository;

    @Mock
    private TreadDesignTextRepository treadDesignTextRepository;

    @Mock
    private TreadDesignImageRepository treadDesignImageRepository;

    @Mock
    private SupplierPriceEntryRepository supplierPriceEntryRepository;

    @Mock
    private ProductRepository productRepository;

    private SupplierCatalogEnrichmentListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierCatalogEnrichmentListener(
                CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                treadDesignRepository,
                treadDesignTextRepository,
                treadDesignImageRepository,
                supplierPriceEntryRepository,
                productRepository,
                new TreadDesignMatcher());
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
            verify(treadDesignRepository).save(designCaptor.capture());
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
            verify(treadDesignRepository).save(designCaptor.capture());
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
            verify(treadDesignRepository).save(captor.capture());
            assertThat(captor.getValue().getContentHash()).isEqualTo("new-hash");
        }
    }

    @Nested
    @DisplayName("matching to products")
    class Matching {

        @Test
        void aDesignThatMatchesNothingIsParkedForReviewNotAnError() {
            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(List.of());

            listener.onSupplierEvent(enrichmentEvent("e-6", "VAR-1", "hash-1", false));

            verify(productRepository, never()).findAllById(any());
            verify(productRepository, never()).save(any());
            // Still applied and recorded -- an unmatched design is an ordinary outcome.
            verify(treadDesignRepository).save(any(TreadDesignEntity.class));
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        void aMatchedCandidateGetsTheDesignIdAndNothingElseChanges() {
            ProductEntity candidate = new ProductEntity();
            candidate.setId(PRODUCT_ID);
            candidate.setManufacturerBrand("Michelin");
            candidate.setName("Michelin Pilot Sport 4S 245/40R18");
            candidate.setSku("SKU-KEEP");
            candidate.setManufacturerPartNumber("MPN-KEEP");

            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(List.of(PRODUCT_ID));
            when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(candidate));

            listener.onSupplierEvent(enrichmentEvent("e-7", "VAR-1", "hash-1", false));

            ArgumentCaptor<ProductEntity> productCaptor = ArgumentCaptor.forClass(ProductEntity.class);
            verify(productRepository).save(productCaptor.capture());
            assertThat(productCaptor.getValue().getTreadDesignId()).isEqualTo(DESIGN_ID);
            // Product identity and structure are untouched -- only the association was written.
            assertThat(productCaptor.getValue().getSku()).isEqualTo("SKU-KEEP");
            assertThat(productCaptor.getValue().getManufacturerPartNumber()).isEqualTo("MPN-KEEP");
        }

        @Test
        void anUnrelatedCandidateIsNotMatched() {
            ProductEntity unrelated = new ProductEntity();
            unrelated.setId(PRODUCT_ID);
            unrelated.setManufacturerBrand("Continental");
            unrelated.setName("Continental ExtremeContact");

            when(supplierPriceEntryRepository.findDistinctProductIdsByVendorProfileId(VENDOR_PROFILE_ID))
                    .thenReturn(List.of(PRODUCT_ID));
            when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(unrelated));

            listener.onSupplierEvent(enrichmentEvent("e-8", "VAR-1", "hash-1", false));

            verify(productRepository, never()).save(any());
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
