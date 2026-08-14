package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.OutboxEventWriter;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.SupplierPriceEntryEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportChunkEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportEntity;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportChunkRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
@DisplayName("supplier.events.v1 → append-only supplier price entries (#1308)")
class SupplierPriceCatalogEventsListenerTest {

    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SupplierPriceEntryRepository priceEntryRepository;

    @Mock
    private SupplierPriceImportRepository priceImportRepository;

    @Mock
    private SupplierPriceImportChunkRepository priceImportChunkRepository;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private SupplierPriceCatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierPriceCatalogEventsListener(
                CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                priceEntryRepository,
                priceImportRepository,
                priceImportChunkRepository,
                outboxEventWriter);
        when(priceEntryRepository.save(any(SupplierPriceEntryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(priceImportRepository.save(any(SupplierPriceImportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(priceImportRepository.findById(MANIFEST_ID)).thenReturn(Optional.empty());
        when(priceImportChunkRepository.save(any(SupplierPriceImportChunkEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static String chunkEvent(String eventId, int sequence, int chunkCount, int lines) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("""
                    {"productId":"%s","matchMethod":"EAN","articleEan":"352870999908%d","supplierArticleCode":"99990%d",
                     "xReferenceCode":null,"suggestedRetailPrice":"120.00","grossPrice":"100.00","netPrice":"90.00",
                     "taxRate":"25","recyclingFee":"3.50","effectiveFrom":"2026-02-01","positionNumber":%d}""".formatted(PRODUCT_ID, i, i, i + 1));
        }
        return """
                {"eventId":"%s","eventType":"supplier.pricecatalog.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-08-14T09:00:00Z",
                 "sourceService":"pos-supplier",
                 "payload":{"importManifestId":"%s","vendorProfileId":"%s","supplierRef":"michelin-eu",
                   "chunkSequence":%d,"chunkCount":%d,"sourceDocumentId":"PRICAT-1","sourceDocumentDate":"2026-01-30",
                   "buyerAccountNumber":"30012456","countryCode":"SE","currency":"SEK","protocolVersion":"B4_0",
                   "fetchedAt":"2026-08-14T08:00:00Z","lines":[%s]}}
                """.formatted(eventId, MANIFEST_ID, sequence, MANIFEST_ID, PROFILE_ID, sequence, chunkCount, items);
    }

    private static String completionEvent(String eventId, int chunkCount, int linesMatched, String status) {
        return """
                {"eventId":"%s","eventType":"supplier.pricecatalog.import.completed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-08-14T09:05:00Z",
                 "sourceService":"pos-supplier",
                 "payload":{"importManifestId":"%s","vendorProfileId":"%s","supplierRef":"michelin-eu",
                   "status":"%s","linesFetched":%d,"linesMatched":%d,"linesUnmatched":0,"linesDuplicate":0,
                   "chunkCount":%d,"chunkSize":500,"contentChecksum":"abc123","sourceDocumentId":"PRICAT-1",
                   "sourceDocumentDate":"2026-01-30","buyerAccountNumber":"30012456","countryCode":"SE",
                   "currency":"SEK","fetchedAt":"2026-08-14T08:00:00Z","completedAt":"2026-08-14T09:05:00Z"}}
                """.formatted(
                        eventId,
                        MANIFEST_ID,
                        chunkCount + 1,
                        MANIFEST_ID,
                        PROFILE_ID,
                        status,
                        linesMatched,
                        linesMatched,
                        chunkCount);
    }

    private SupplierPriceImportEntity capturedTracker() {
        ArgumentCaptor<SupplierPriceImportEntity> captor = ArgumentCaptor.forClass(SupplierPriceImportEntity.class);
        verify(priceImportRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("applying chunks")
    class Chunks {

        @Test
        void writesOnePriceEntryPerLineWithItsMarketScopeAndSource() {
            listener.onSupplierEvent(chunkEvent("e-1", 1, 1, 2));

            ArgumentCaptor<SupplierPriceEntryEntity> captor = ArgumentCaptor.forClass(SupplierPriceEntryEntity.class);
            verify(priceEntryRepository, times(2)).save(captor.capture());
            SupplierPriceEntryEntity entry = captor.getAllValues().getFirst();
            assertThat(entry.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(entry.getVendorProfileId()).isEqualTo(PROFILE_ID);
            assertThat(entry.getCountryCode()).isEqualTo("SE");
            assertThat(entry.getCurrency()).isEqualTo("SEK");
            assertThat(entry.getBuyerAccountNumber()).isEqualTo("30012456");
            assertThat(entry.getNetPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(entry.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(entry.getSourceDocumentId()).isEqualTo("PRICAT-1");
            assertThat(entry.getImportManifestId()).isEqualTo(MANIFEST_ID);
        }

        @Test
        void countsChunksAndLinesOnTheImportTracker() {
            listener.onSupplierEvent(chunkEvent("e-1", 1, 3, 2));

            SupplierPriceImportEntity tracker = capturedTracker();
            assertThat(tracker.getChunksApplied()).isEqualTo(1);
            assertThat(tracker.getLinesApplied()).isEqualTo(2);
            assertThat(tracker.getExpectedChunkCount()).isEqualTo(3);
            assertThat(tracker.getStatus()).isEqualTo(SupplierPriceImportEntity.STATUS_APPLYING);
        }

        @Test
        void appliesAChunkOnlyOnceWhenItIsRedelivered() {
            when(processedEventRepository.existsById("e-1")).thenReturn(true);

            listener.onSupplierEvent(chunkEvent("e-1", 1, 1, 2));

            verify(priceEntryRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void recordsEveryAppliedEventForTheOwnersManifest() {
            listener.onSupplierEvent(chunkEvent("e-1", 1, 1, 1));

            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }
    }

    @Nested
    @DisplayName("completeness")
    class Completeness {

        @Test
        void marksAnImportCompleteWhenEveryDeclaredChunkArrived() {
            when(priceImportRepository.findById(MANIFEST_ID))
                    .thenReturn(Optional.of(SupplierPriceImportEntity.builder()
                            .importManifestId(MANIFEST_ID)
                            .vendorProfileId(PROFILE_ID)
                            .chunksApplied(2)
                            .linesApplied(4)
                            .status(SupplierPriceImportEntity.STATUS_APPLYING)
                            .build()));

            listener.onSupplierEvent(completionEvent("e-done", 2, 4, "COMPLETED"));

            assertThat(capturedTracker().getStatus()).isEqualTo(SupplierPriceImportEntity.STATUS_COMPLETE);
            verify(outboxEventWriter, never()).publish(any(), any());
        }

        @Test
        void requestsAReEmitOnTheCommandTopicWhenChunksAreMissing() {
            when(priceImportRepository.findById(MANIFEST_ID))
                    .thenReturn(Optional.of(SupplierPriceImportEntity.builder()
                            .importManifestId(MANIFEST_ID)
                            .vendorProfileId(PROFILE_ID)
                            .chunksApplied(1)
                            .linesApplied(2)
                            .status(SupplierPriceImportEntity.STATUS_APPLYING)
                            .build()));

            listener.onSupplierEvent(completionEvent("e-done", 3, 6, "COMPLETED"));

            assertThat(capturedTracker().getStatus()).isEqualTo(SupplierPriceImportEntity.STATUS_INCOMPLETE);

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
            verify(outboxEventWriter).publish(eq("supplier.commands.v1"), captor.capture());
            assertThat(captor.getValue().eventType()).isEqualTo(SupplierPriceCatalogRepublishRequestedV1.EVENT_TYPE);
            SupplierPriceCatalogRepublishRequestedV1 request =
                    (SupplierPriceCatalogRepublishRequestedV1) captor.getValue().payload();
            assertThat(request.importManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(request.chunksApplied()).isEqualTo(1);
            assertThat(request.expectedChunks()).isEqualTo(3);
            assertThat(request.requestedBy()).isEqualTo("pos-catalog");
        }

        @Test
        void flipsAnIncompleteImportToCompleteWhenTheMissingChunkArrives() {
            // The real recovery path: completion was processed first and reported a gap, then the
            // re-emitted chunk lands and closes it.
            when(priceImportRepository.findById(MANIFEST_ID))
                    .thenReturn(Optional.of(SupplierPriceImportEntity.builder()
                            .importManifestId(MANIFEST_ID)
                            .vendorProfileId(PROFILE_ID)
                            .chunksApplied(2)
                            .linesApplied(4)
                            .expectedChunkCount(3)
                            .completedAt(Instant.parse("2026-08-14T09:05:00Z"))
                            .status(SupplierPriceImportEntity.STATUS_INCOMPLETE)
                            .build()));

            listener.onSupplierEvent(chunkEvent("e-replay", 3, 3, 2));

            assertThat(capturedTracker().getStatus()).isEqualTo(SupplierPriceImportEntity.STATUS_COMPLETE);
            assertThat(capturedTracker().getChunksApplied()).isEqualTo(3);
        }

        @Test
        void appliesAReEmittedChunkOnlyOnceEvenUnderANewEventId() {
            // A re-emit republishes the same chunk as a NEW event, so the eventId guard does not
            // fire. Without the applied-chunk log this would duplicate every line in the chunk and
            // inflate the counter — potentially declaring an import complete that still has a hole.
            when(priceImportRepository.findById(MANIFEST_ID))
                    .thenReturn(Optional.of(SupplierPriceImportEntity.builder()
                            .importManifestId(MANIFEST_ID)
                            .vendorProfileId(PROFILE_ID)
                            .chunksApplied(1)
                            .linesApplied(2)
                            .expectedChunkCount(3)
                            .status(SupplierPriceImportEntity.STATUS_APPLYING)
                            .build()));
            when(priceImportChunkRepository.existsByImportManifestIdAndChunkSequence(MANIFEST_ID, 1))
                    .thenReturn(true);

            listener.onSupplierEvent(chunkEvent("e-different-id", 1, 3, 2));

            verify(priceEntryRepository, never()).save(any());
            verify(priceImportChunkRepository, never()).save(any());
            assertThat(capturedTracker().getChunksApplied()).isEqualTo(1);
            // Still recorded as processed: the event was delivered and deliberately not applied.
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        void recordsEachAppliedChunkSoARedeliveryCanBeRecognised() {
            listener.onSupplierEvent(chunkEvent("e-1", 2, 3, 2));

            ArgumentCaptor<SupplierPriceImportChunkEntity> captor =
                    ArgumentCaptor.forClass(SupplierPriceImportChunkEntity.class);
            verify(priceImportChunkRepository).save(captor.capture());
            assertThat(captor.getValue().getImportManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(captor.getValue().getChunkSequence()).isEqualTo(2);
            assertThat(captor.getValue().getLinesApplied()).isEqualTo(2);
        }

        @Test
        void appliesNothingAndRemovesNothingForAnEmptyImport() {
            listener.onSupplierEvent(completionEvent("e-empty", 0, 0, "EMPTY"));

            verify(priceEntryRepository, never()).save(any());
            assertThat(capturedTracker().getStatus()).isEqualTo(SupplierPriceImportEntity.STATUS_COMPLETE);
            verify(outboxEventWriter, never()).publish(any(), any());
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        void ignoresUnrelatedSupplierEventsButRecordsThem() {
            listener.onSupplierEvent("""
                    {"eventId":"e-other","eventType":"supplier.order.confirmed","aggregateVersion":1,"payload":{}}
                    """);

            verify(priceEntryRepository, never()).save(any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        void ignoresAnEventWithoutAnEventId() {
            listener.onSupplierEvent("""
                    {"eventType":"supplier.pricecatalog.updated","payload":{}}
                    """);

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void ignoresAnUnparsableMessage() {
            listener.onSupplierEvent("not json");

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void rethrowsTransientDatabaseErrorsSoTheContainerRetries() {
            when(priceEntryRepository.save(any(SupplierPriceEntryEntity.class)))
                    .thenThrow(new QueryTimeoutException("db busy"));

            assertThatThrownBy(() -> listener.onSupplierEvent(chunkEvent("e-1", 1, 1, 1)))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(processedEventRepository, never()).save(any());
        }
    }
}
