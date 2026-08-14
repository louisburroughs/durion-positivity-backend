package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PRICAT re-publication on consumer request (ADR-0044 §4)")
class PriceCatalogRepublisherTest {

    private static final UUID IMPORT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final Instant FETCHED_AT = Instant.parse("2026-08-10T06:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-10T06:05:00Z");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private PriceCatalogImportRepository importRepository;

    @Mock
    private PriceCatalogEntryRepository entryRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private PriceCatalogRepublisher republisher;

    @BeforeEach
    void setUp() {
        republisher = new PriceCatalogRepublisher(importRepository, entryRepository, outboxWriter, CLOCK);
        ReflectionTestUtils.setField(republisher, "maxAttempts", 3);
        ReflectionTestUtils.setField(republisher, "cooldown", Duration.ofMinutes(10));
        when(importRepository.save(any(PriceCatalogImportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PriceCatalogImportEntity manifest(PriceCatalogImportStatus status, int chunkCount) {
        return PriceCatalogImportEntity.builder()
                .importManifestId(IMPORT_ID)
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .protocolFamily(ProtocolFamily.EDIWHEEL_B)
                .protocolVersion("B4_0")
                .status(status)
                .sourceDocumentId("PRICAT-1")
                .sourceDocumentDate(LocalDate.of(2026, 8, 9))
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .fetchedAt(FETCHED_AT)
                .completedAt(COMPLETED_AT)
                .linesFetched(3)
                .linesMatched(3)
                .chunkCount(chunkCount)
                .chunkSize(2)
                .contentChecksum("abc123")
                .correlationId("corr-1")
                .build();
    }

    private static PriceCatalogEntryEntity entry(int position, int chunkSequence) {
        return PriceCatalogEntryEntity.builder()
                .entryId(UUID.randomUUID())
                .importManifestId(IMPORT_ID)
                .vendorProfileId(PROFILE_ID)
                .positionNumber(position)
                .chunkSequence(chunkSequence)
                .articleEan("352870999908" + position)
                .supplierArticleCode("9999" + position)
                .matchedProductId(PRODUCT_ID)
                .matchMethod(PriceCatalogMatchMethod.EAN)
                .netPrice(new BigDecimal("90.00"))
                .effectiveFrom(LocalDate.of(2026, 8, 1))
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .fetchedAt(FETCHED_AT)
                .build();
    }

    private static SupplierPriceCatalogRepublishRequestedV1 request() {
        return new SupplierPriceCatalogRepublishRequestedV1(
                IMPORT_ID, PROFILE_ID, 1, 2, "pos-catalog", "applied 1 of 2 chunks");
    }

    private void stageTwoChunks() {
        when(entryRepository.findDistinctChunkSequences(IMPORT_ID)).thenReturn(List.of(1, 2));
        when(entryRepository.findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(IMPORT_ID, 1))
                .thenReturn(List.of(entry(1, 1), entry(2, 1)));
        when(entryRepository.findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(IMPORT_ID, 2))
                .thenReturn(List.of(entry(3, 2)));
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> capturedEvents() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, org.mockito.Mockito.atLeastOnce()).publish(eq("supplier.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void reEmitsEveryChunkAndTheCompletionThatClosesThem() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        int chunks = republisher.republish(request());

        assertThat(chunks).isEqualTo(2);
        List<DomainEventEnvelope<?>> events = capturedEvents();
        assertThat(events).hasSize(3);
        // The whole import, not just the chunk the consumer is missing: the request names the
        // import because the consumer cannot know WHICH chunks it never received.
        assertThat(events.subList(0, 2))
                .allSatisfy(e -> assertThat(e.eventType()).isEqualTo(SupplierPriceCatalogUpdatedV1.EVENT_TYPE));
        assertThat(events.getLast().eventType()).isEqualTo(SupplierPriceCatalogImportCompletedV1.EVENT_TYPE);
    }

    @Test
    void reproducesTheOriginalChunkBoundariesTheConsumerDeduplicatesOn() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        republisher.republish(request());

        // Chunk membership comes from the sequence recorded at staging, not from re-chunking the
        // import now. Re-chunking could move a line across a boundary, and the consumer — which
        // skips sequences it already applied — would then lose it permanently.
        List<DomainEventEnvelope<?>> events = capturedEvents();
        SupplierPriceCatalogUpdatedV1 first =
                (SupplierPriceCatalogUpdatedV1) events.get(0).payload();
        SupplierPriceCatalogUpdatedV1 second =
                (SupplierPriceCatalogUpdatedV1) events.get(1).payload();
        assertThat(first.chunkSequence()).isEqualTo(1);
        assertThat(first.lines()).hasSize(2);
        assertThat(second.chunkSequence()).isEqualTo(2);
        assertThat(second.lines()).hasSize(1);
        assertThat(second.lines().getFirst().positionNumber()).isEqualTo(3);
    }

    @Test
    void readsOneChunkAtATimeRatherThanTheWholeImport() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        republisher.republish(request());

        // A country-wide catalogue is tens of thousands of lines; loading all of them to re-emit a
        // recovery would make the recovery the outage.
        verify(entryRepository).findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(IMPORT_ID, 1);
        verify(entryRepository).findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(IMPORT_ID, 2);
        verify(entryRepository, never()).findByImportManifestIdOrderByPositionNumberAsc(any(UUID.class));
    }

    @Test
    void restatesWhenTheImportCompletedRatherThanWhenItWasReEmitted() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        republisher.republish(request());

        SupplierPriceCatalogImportCompletedV1 completion = (SupplierPriceCatalogImportCompletedV1)
                capturedEvents().getLast().payload();
        // A re-emit re-delivers a fact; it does not claim the fact happened now. Stamping "now"
        // would let a re-emitted old import outrank a newer one downstream.
        assertThat(completion.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(completion.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(completion.contentChecksum()).isEqualTo("abc123");
    }

    @Test
    void mintsNewEventIdsSoTheConsumersEventIdGuardCannotSwallowTheRecovery() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        republisher.republish(request());

        List<DomainEventEnvelope<?>> events = capturedEvents();
        assertThat(events.stream().map(DomainEventEnvelope::eventId).distinct()).hasSize(events.size());
        assertThat(events).allSatisfy(e -> assertThat(e.aggregateId()).isEqualTo(IMPORT_ID));
    }

    @Test
    void recordsTheAttemptSoRepeatRequestsAreBounded() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        stageTwoChunks();

        republisher.republish(request());

        ArgumentCaptor<PriceCatalogImportEntity> captor = ArgumentCaptor.forClass(PriceCatalogImportEntity.class);
        verify(importRepository).save(captor.capture());
        assertThat(captor.getValue().getRepublishCount()).isEqualTo(1);
        assertThat(captor.getValue().getLastRepublishedAt()).isEqualTo(NOW);
    }

    @Test
    void refusesOnceTheAttemptCapIsReached() {
        PriceCatalogImportEntity exhausted = manifest(PriceCatalogImportStatus.COMPLETED, 2);
        exhausted.setRepublishCount(3);
        exhausted.setLastRepublishedAt(NOW.minus(Duration.ofDays(1)));
        when(importRepository.findById(IMPORT_ID)).thenReturn(Optional.of(exhausted));

        assertThat(republisher.republish(request())).isZero();

        // A consumer still short after three full re-emits is not short because of the producer.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void collapsesABurstOfRequestsForTheSameImportIntoOneReEmit() {
        PriceCatalogImportEntity recent = manifest(PriceCatalogImportStatus.COMPLETED, 2);
        recent.setRepublishCount(1);
        recent.setLastRepublishedAt(NOW.minus(Duration.ofMinutes(2)));
        when(importRepository.findById(IMPORT_ID)).thenReturn(Optional.of(recent));

        assertThat(republisher.republish(request())).isZero();

        // The first re-emit may not even have drained the outbox yet; answering again would
        // multiply the catalogue on the topic without changing anything the consumer sees.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void servesAgainOnceTheCooldownHasPassed() {
        PriceCatalogImportEntity cooled = manifest(PriceCatalogImportStatus.COMPLETED, 2);
        cooled.setRepublishCount(1);
        cooled.setLastRepublishedAt(NOW.minus(Duration.ofMinutes(30)));
        when(importRepository.findById(IMPORT_ID)).thenReturn(Optional.of(cooled));
        stageTwoChunks();

        assertThat(republisher.republish(request())).isEqualTo(2);
    }

    @Test
    void refusesAnImportThatWasNeverStagedHereRatherThanFailingTheConsumer() {
        when(importRepository.findById(IMPORT_ID)).thenReturn(Optional.empty());

        assertThat(republisher.republish(request())).isZero();

        verifyNoInteractions(outboxWriter);
    }

    @Test
    void refusesWhenTheRequestNamesAProfileThatDoesNotOwnTheImport() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        SupplierPriceCatalogRepublishRequestedV1 wrongProfile = new SupplierPriceCatalogRepublishRequestedV1(
                IMPORT_ID, UUID.randomUUID(), 1, 2, "pos-catalog", "applied 1 of 2 chunks");

        assertThat(republisher.republish(wrongProfile)).isZero();

        // Re-emitting one vendor's prices in answer to a request about another is a leak, not a
        // recovery.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void refusesAFailedImportBecauseItStagedNoLinesToReEmit() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.FAILED, 0)));

        assertThat(republisher.republish(request())).isZero();

        verifyNoInteractions(outboxWriter);
        verify(entryRepository, never())
                .findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(any(UUID.class), anyInt());
    }

    @Test
    void refusesTheWholeReEmitWhenTheStagedLinesNoLongerCoverEveryDeclaredChunk() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        when(entryRepository.findDistinctChunkSequences(IMPORT_ID)).thenReturn(List.of(1));

        assertThat(republisher.republish(request())).isZero();

        // Publishing the chunks that DO exist would satisfy the consumer's chunk count while lines
        // stayed missing — a visible gap turned into an invisible one. Checked before anything is
        // queued, so the refusal is a logged no-op rather than a rollback part-way through.
        verifyNoInteractions(outboxWriter);
        verify(entryRepository, never())
                .findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(any(UUID.class), anyInt());
        verify(importRepository, never()).save(any(PriceCatalogImportEntity.class));
    }

    @Test
    void refusesWhenTheStagedSequencesAreNotTheOnesTheManifestDeclared() {
        when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(manifest(PriceCatalogImportStatus.COMPLETED, 2)));
        // The right NUMBER of chunks under the wrong sequence numbers. Counting alone would pass
        // this and then re-emit chunk 2 twice while chunk 1 never arrived.
        when(entryRepository.findDistinctChunkSequences(IMPORT_ID)).thenReturn(List.of(2, 3));

        assertThat(republisher.republish(request())).isZero();

        verifyNoInteractions(outboxWriter);
    }
}
