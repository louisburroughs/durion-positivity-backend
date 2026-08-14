package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotChunk;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotReceipt;
import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotChunkRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotReceiptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the supplier.events.v1 stock-report listener (CAP-322, #1312).
 *
 * <p>Covers what the story's acceptance criteria name: idempotent replay, the three-way quantity
 * distinction, supersession that never zeroes an omitted article, chunk-gap visibility, and
 * transient database errors rethrown for container retry.
 */
class SupplierStockHintEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SNAPSHOT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000005a1");
    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-0000000005b2");
    private static final Instant VENDOR_AS_OF = Instant.parse("2026-08-14T06:00:00Z");
    private static final Instant FETCHED_AT = Instant.parse("2026-08-14T06:05:00Z");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final SupplierStockHintRepository hints = mock(SupplierStockHintRepository.class);
    private final SupplierStockSnapshotReceiptRepository receipts = mock(SupplierStockSnapshotReceiptRepository.class);
    private final SupplierStockSnapshotChunkRepository chunks = mock(SupplierStockSnapshotChunkRepository.class);

    private SupplierStockHintEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierStockHintEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, hints, receipts, chunks);
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** One chunk of a stock report, with the lines given verbatim. */
    private static String chunkEvent(
            String eventId, int chunkSequence, int chunkCount, int linesReported, String... lines) {
        return chunkEvent(
                eventId, chunkSequence, chunkCount, linesReported, "\"%s\"".formatted(VENDOR_AS_OF), FETCHED_AT, lines);
    }

    private static String chunkEvent(
            String eventId,
            int chunkSequence,
            int chunkCount,
            int linesReported,
            String snapshotAsOfJson,
            Instant fetchedAt,
            String... lines) {
        return """
                {"eventId":"%s","eventType":"supplier.stockreport.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"snapshotId":"%s","vendorProfileId":"%s","supplierRef":"ediwheel-b21",
                            "chunkSequence":%d,"chunkCount":%d,"documentId":"DOC-1",
                            "buyerAccountNumber":"ACCT-1","countryCode":null,
                            "snapshotAsOf":%s,"fetchedAt":"%s","linesReported":%d,
                            "lines":[%s]}}
                """.formatted(
                        eventId,
                        SNAPSHOT_ID,
                        chunkSequence,
                        SNAPSHOT_ID,
                        VENDOR_ID,
                        chunkSequence,
                        chunkCount,
                        snapshotAsOfJson,
                        fetchedAt,
                        linesReported,
                        String.join(",", lines));
    }

    private static String line(String ean, String supplierCode, String buyerCode, String quantityJson) {
        return """
                {"articleEan":%s,"supplierArticleCode":%s,"buyersArticleId":%s,
                 "description":"Tyre 205/55R16","availableQuantity":%s}
                """.formatted(json(ean), json(supplierCode), json(buyerCode), quantityJson);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static SupplierStockHint storedHint(String ean, Integer quantity, Instant fetchedAt) {
        return SupplierStockHint.builder()
                .hintId(UUID.fromString("018f0000-0000-7000-8000-0000000005c3"))
                .vendorProfileId(VENDOR_ID)
                .identityKind(SupplierHintIdentityKind.EAN)
                .identityValue(ean)
                .articleEan(ean)
                .availableQuantity(quantity)
                .resolutionStatus(SupplierHintResolutionStatus.PENDING)
                .sourceSnapshotId(UUID.fromString("018f0000-0000-7000-8000-0000000005d4"))
                .sourceChunkSequence(1)
                .asOfSource(SupplierHintAsOfSource.VENDOR)
                .snapshotAsOf(fetchedAt.minusSeconds(300))
                .fetchedAt(fetchedAt)
                .firstSeenAt(fetchedAt)
                .build();
    }

    // ---------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a redelivered event is not applied twice")
    void replayed_event_is_skipped() {
        when(processedEvents.existsById("evt-1")).thenReturn(true);

        listener.onSupplierEvent(chunkEvent("evt-1", 1, 1, 1, line("4012345678901", "V-1", "B-1", "12")));

        verifyNoInteractions(hints, chunks, receipts);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("a chunk re-emitted under a fresh eventId is not applied twice")
    void reemitted_chunk_is_skipped() {
        when(chunks.existsBySnapshotIdAndChunkSequence(SNAPSHOT_ID, 1)).thenReturn(true);

        listener.onSupplierEvent(chunkEvent("evt-2", 1, 1, 1, line("4012345678901", "V-1", "B-1", "12")));

        // The processed_events guard cannot catch this — the applied-chunk log is what does.
        verify(hints, never()).save(any());
        verify(chunks, never()).save(any());
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("an event carrying no eventId is skipped without being recorded")
    void event_without_id_is_skipped() {
        listener.onSupplierEvent("{\"eventType\":\"supplier.stockreport.updated\",\"payload\":{}}");

        verifyNoInteractions(hints, chunks, receipts);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("a PRICAT event on the shared topic is recorded but not applied")
    void unrelated_supplier_event_is_recorded_only() {
        listener.onSupplierEvent("""
                {"eventId":"evt-3","eventType":"supplier.pricecatalog.updated","schemaVersion":1,
                 "payload":{"importManifestId":"018f0000-0000-7000-8000-0000000005e5"}}
                """);

        verifyNoInteractions(hints, chunks, receipts);
        // Recorded so a redelivery does not walk the dispatch path again.
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    // ---------------------------------------------------------------------
    // The three-way quantity distinction
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("explicit zero, unstated quantity and absence stay three distinct things")
    void three_way_quantity_distinction_is_preserved() {
        listener.onSupplierEvent(chunkEvent(
                "evt-4",
                1,
                1,
                3,
                line("4012345678901", "V-1", "B-1", "12"),
                line("4012345678902", "V-2", "B-2", "0"),
                line("4012345678903", "V-3", "B-3", "null")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints, times(3)).save(saved.capture());
        List<SupplierStockHint> written = saved.getAllValues();

        assertThat(written.get(0).getAvailableQuantity()).isEqualTo(12);
        // The vendor explicitly reporting none is information, kept as a stated zero.
        assertThat(written.get(1).getAvailableQuantity()).isZero();
        // The vendor listing the article and saying nothing about quantity is NOT zero.
        assertThat(written.get(2).getAvailableQuantity()).isNull();
        // The fourth case — an article the vendor never mentioned — is the absence of a row, so
        // exactly three rows were written and nothing was invented for anything else.
        assertThat(written).hasSize(3);
    }

    @Test
    @DisplayName("a snapshot omitting a previously reported article leaves its hint standing")
    void omitted_article_is_not_zeroed() {
        // The vendor previously reported 4012345678901; this snapshot mentions only ...902.
        listener.onSupplierEvent(chunkEvent("evt-5", 1, 1, 1, line("4012345678902", "V-2", "B-2", "3")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints, times(1)).save(saved.capture());
        assertThat(saved.getValue().getIdentityValue()).isEqualTo("4012345678902");
        // Nothing looked up or wrote the omitted article: its previous hint stands with its own
        // asOf, and its age is what tells a caller how much to trust it.
        verify(hints, never())
                .findByVendorProfileIdAndIdentityKindAndIdentityValue(
                        VENDOR_ID, SupplierHintIdentityKind.EAN, "4012345678901");
    }

    // ---------------------------------------------------------------------
    // Supersession
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a newer snapshot supersedes the previous hint in place")
    void newer_snapshot_supersedes() {
        SupplierStockHint existing = storedHint("4012345678901", 5, FETCHED_AT.minusSeconds(3600));
        when(hints.findByVendorProfileIdAndIdentityKindAndIdentityValue(
                        VENDOR_ID, SupplierHintIdentityKind.EAN, "4012345678901"))
                .thenReturn(Optional.of(existing));

        listener.onSupplierEvent(chunkEvent("evt-6", 1, 1, 1, line("4012345678901", "V-1", "B-1", "2")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints).save(saved.capture());
        assertThat(saved.getValue().getAvailableQuantity()).isEqualTo(2);
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(saved.getValue().getSourceSnapshotId()).isEqualTo(SNAPSHOT_ID);
        // first_seen_at belongs to the first sighting, not to the latest snapshot.
        assertThat(saved.getValue().getFirstSeenAt()).isEqualTo(FETCHED_AT.minusSeconds(3600));
    }

    @Test
    @DisplayName("a chunk of an older snapshot arriving late does not overwrite a newer hint")
    void older_snapshot_arriving_late_is_ignored() {
        SupplierStockHint existing = storedHint("4012345678901", 9, FETCHED_AT.plusSeconds(3600));
        when(hints.findByVendorProfileIdAndIdentityKindAndIdentityValue(
                        VENDOR_ID, SupplierHintIdentityKind.EAN, "4012345678901"))
                .thenReturn(Optional.of(existing));

        listener.onSupplierEvent(chunkEvent("evt-7", 1, 1, 1, line("4012345678901", "V-1", "B-1", "2")));

        verify(hints, never()).save(any());
        assertThat(existing.getAvailableQuantity()).isEqualTo(9);
        // The chunk itself is still logged: it did arrive, and completeness counts it.
        verify(chunks).save(any(SupplierStockSnapshotChunk.class));
    }

    // ---------------------------------------------------------------------
    // Completeness
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a snapshot missing a chunk stays incomplete and says which chunks arrived")
    void chunk_gap_is_visible() {
        listener.onSupplierEvent(chunkEvent("evt-8", 1, 3, 3, line("4012345678901", "V-1", "B-1", "1")));

        ArgumentCaptor<SupplierStockSnapshotReceipt> receipt =
                ArgumentCaptor.forClass(SupplierStockSnapshotReceipt.class);
        verify(receipts).save(receipt.capture());
        assertThat(receipt.getValue().getChunkCount()).isEqualTo(3);
        assertThat(receipt.getValue().getChunksApplied()).isEqualTo(1);
        assertThat(receipt.getValue().isComplete()).isFalse();

        ArgumentCaptor<SupplierStockSnapshotChunk> chunk = ArgumentCaptor.forClass(SupplierStockSnapshotChunk.class);
        verify(chunks).save(chunk.capture());
        assertThat(chunk.getValue().getChunkSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("a snapshot completes only once every promised chunk has arrived")
    void receipt_completes_on_the_last_chunk() {
        SupplierStockSnapshotReceipt existing = SupplierStockSnapshotReceipt.builder()
                .snapshotId(SNAPSHOT_ID)
                .vendorProfileId(VENDOR_ID)
                .chunkCount(2)
                .chunksApplied(1)
                .linesReported(2)
                .linesApplied(1)
                .asOfSource(SupplierHintAsOfSource.VENDOR)
                .fetchedAt(FETCHED_AT)
                .complete(false)
                .firstChunkAt(FETCHED_AT)
                .lastChunkAt(FETCHED_AT)
                .build();
        when(receipts.findBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(existing));

        listener.onSupplierEvent(chunkEvent("evt-9", 2, 2, 2, line("4012345678902", "V-2", "B-2", "4")));

        ArgumentCaptor<SupplierStockSnapshotReceipt> receipt =
                ArgumentCaptor.forClass(SupplierStockSnapshotReceipt.class);
        verify(receipts).save(receipt.capture());
        assertThat(receipt.getValue().getChunksApplied()).isEqualTo(2);
        assertThat(receipt.getValue().isComplete()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Timestamps, identity and resolution state
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the vendor's own asOf is retained, and its absence is recorded rather than hidden")
    void as_of_source_records_whose_clock_is_in_play() {
        listener.onSupplierEvent(chunkEvent("evt-10", 1, 1, 1, line("4012345678901", "V-1", "B-1", "1")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints).save(saved.capture());
        assertThat(saved.getValue().getSnapshotAsOf()).isEqualTo(VENDOR_AS_OF);
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(saved.getValue().getAsOfSource()).isEqualTo(SupplierHintAsOfSource.VENDOR);
        assertThat(saved.getValue().effectiveAsOf()).isEqualTo(VENDOR_AS_OF);
    }

    @Test
    @DisplayName("a vendor stating no time leaves the age measured on our fetch clock, marked as such")
    void missing_vendor_as_of_falls_back_to_fetch_clock() {
        listener.onSupplierEvent(
                chunkEvent("evt-11", 1, 1, 1, "null", FETCHED_AT, line("4012345678901", "V-1", "B-1", "1")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints).save(saved.capture());
        assertThat(saved.getValue().getSnapshotAsOf()).isNull();
        assertThat(saved.getValue().getAsOfSource()).isEqualTo(SupplierHintAsOfSource.FETCH);
        assertThat(saved.getValue().effectiveAsOf()).isEqualTo(FETCHED_AT);
    }

    @Test
    @DisplayName("identity is elected EAN first, then our code, then the vendor's")
    void identity_election_follows_producer_precedence() {
        listener.onSupplierEvent(chunkEvent(
                "evt-12",
                1,
                1,
                3,
                line("4012345678901", "V-1", "B-1", "1"),
                line(null, "V-2", "B-2", "1"),
                line(null, "V-3", null, "1")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints, times(3)).save(saved.capture());
        List<SupplierStockHint> written = saved.getAllValues();
        assertThat(written.get(0).getIdentityKind()).isEqualTo(SupplierHintIdentityKind.EAN);
        assertThat(written.get(0).getIdentityValue()).isEqualTo("4012345678901");
        assertThat(written.get(1).getIdentityKind()).isEqualTo(SupplierHintIdentityKind.BUYER_ARTICLE);
        assertThat(written.get(1).getIdentityValue()).isEqualTo("B-2");
        assertThat(written.get(2).getIdentityKind()).isEqualTo(SupplierHintIdentityKind.SUPPLIER_ARTICLE);
        assertThat(written.get(2).getIdentityValue()).isEqualTo("V-3");
        // Every identifier the vendor stated is kept, whichever one was elected.
        assertThat(written.get(0).getSupplierArticleCode()).isEqualTo("V-1");
        assertThat(written.get(0).getBuyersArticleId()).isEqualTo("B-1");
    }

    @Test
    @DisplayName("only an article with an EAN is queued for resolution")
    void resolution_status_reflects_what_can_be_matched() {
        listener.onSupplierEvent(
                chunkEvent("evt-13", 1, 1, 2, line("4012345678901", "V-1", "B-1", "1"), line(null, "V-2", "B-2", "1")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.PENDING);
        // No EAN means nothing deterministic to match on; it is not queued and not guessed at.
        assertThat(saved.getAllValues().get(1).getResolutionStatus())
                .isEqualTo(SupplierHintResolutionStatus.NOT_RESOLVABLE);
    }

    @Test
    @DisplayName("a resolved hint keeps its product across snapshots while its EAN is unchanged")
    void resolved_hint_survives_supersession() {
        SupplierStockHint existing = storedHint("4012345678901", 5, FETCHED_AT.minusSeconds(3600));
        existing.setResolutionStatus(SupplierHintResolutionStatus.RESOLVED);
        existing.setResolvedProductId(UUID.fromString("018f0000-0000-7000-8000-0000000005f6"));
        when(hints.findByVendorProfileIdAndIdentityKindAndIdentityValue(
                        VENDOR_ID, SupplierHintIdentityKind.EAN, "4012345678901"))
                .thenReturn(Optional.of(existing));

        listener.onSupplierEvent(chunkEvent("evt-14", 1, 1, 1, line("4012345678901", "V-1", "B-1", "7")));

        ArgumentCaptor<SupplierStockHint> saved = ArgumentCaptor.forClass(SupplierStockHint.class);
        verify(hints).save(saved.capture());
        assertThat(saved.getValue().getResolutionStatus()).isEqualTo(SupplierHintResolutionStatus.RESOLVED);
        assertThat(saved.getValue().getResolvedProductId())
                .isEqualTo(UUID.fromString("018f0000-0000-7000-8000-0000000005f6"));
    }

    // ---------------------------------------------------------------------
    // Error policy
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("transient database errors are rethrown for container retry")
    void transient_errors_are_rethrown() {
        when(hints.save(any())).thenThrow(new QueryTimeoutException("statement timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onSupplierEvent(
                        chunkEvent("evt-15", 1, 1, 1, line("4012345678901", "V-1", "B-1", "1"))));

        // Not recorded as processed: the retry has to be able to apply it.
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("a malformed payload is recorded as processed rather than retried forever")
    void malformed_payload_is_swallowed() {
        listener.onSupplierEvent("""
                {"eventId":"evt-16","eventType":"supplier.stockreport.updated","schemaVersion":1,
                 "payload":{"snapshotId":"not-a-uuid"}}
                """);

        verify(hints, never()).save(any());
        verify(processedEvents).save(any(ProcessedEvent.class));
    }
}
