package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.tenancy.TenantIterator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Goods receipt copy backfill (#2750)")
class GoodsReceiptReplicaBackfillTest {

    private static final UUID RECEIPT_A = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final UUID RECEIPT_B = UUID.fromString("019a0000-0000-7000-8000-00000000d002");
    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantIterator tenants = mock(TenantIterator.class);
    private final AccountingEventRepository events = mock(AccountingEventRepository.class);
    private final ExtInventoryGoodsReceiptRepository receipts = mock(ExtInventoryGoodsReceiptRepository.class);
    private final GoodsReceiptReplica replica = mock(GoodsReceiptReplica.class);
    private final GoodsReceiptAccrualPostingService assessor = mock(GoodsReceiptAccrualPostingService.class);
    private GoodsReceiptReplicaBackfill backfill;

    @BeforeEach
    void setUp() {
        backfill = new GoodsReceiptReplicaBackfill(
                tenants, events, receipts, replica, assessor, objectMapper, mock(PlatformTransactionManager.class));
        when(assessor.assess(any())).thenReturn(new GoodsReceiptAccrualPostingService.Assessment.NothingToPost());
        when(events.findGoodsReceiptFactsWithoutCopy(any(), any(), eq(GoodsReceiptReplicaBackfill.SLICE_SIZE)))
                .thenReturn(List.of());
        when(events.findByEventTypeAndDomainKeyIdOrderByReceivedAtAscEventIdAsc(any(), any()))
                .thenReturn(List.of());
    }

    private AccountingEvent stored(UUID receiptId, Instant receivedAt, Map<String, Object> overrides) {
        GoodsReceiptRecordedV1 fact = new GoodsReceiptRecordedV1(
                receiptId,
                "GR-" + receiptId.toString().substring(30),
                PO,
                null,
                40_000L,
                Instant.parse("2026-10-01T09:00:00Z"),
                List.of(),
                "USD");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new LinkedHashMap<>(objectMapper.convertValue(fact, Map.class));
        payload.putAll(overrides);
        AccountingEvent event = new AccountingEvent();
        event.setEventId(UUID.randomUUID());
        event.setReceivedAt(receivedAt);
        event.setPayload(payload);
        return event;
    }

    private AccountingEvent stored(UUID receiptId) {
        return stored(receiptId, T0, Map.of());
    }

    private void firstSlice(AccountingEvent... stored) {
        when(events.findGoodsReceiptFactsWithoutCopy(eq(Instant.EPOCH), any(), eq(GoodsReceiptReplicaBackfill.SLICE_SIZE)))
                .thenReturn(List.of(stored));
    }

    @Test
    @DisplayName("a stored fact with no copy row is replayed through the writer")
    void replaysFactsWithoutARow() {
        firstSlice(stored(RECEIPT_A), stored(RECEIPT_B));

        assertThat(backfill.backfillTenant()).isEqualTo(2);

        verify(replica, times(2)).recorded(any());
    }

    @Test
    @DisplayName("a row this run did not create (the live consumer's, even one made mid-run) is never overwritten")
    void rowThatIsNotThisRunsIsKept() {
        when(receipts.existsById(RECEIPT_A)).thenReturn(true);
        firstSlice(stored(RECEIPT_A), stored(RECEIPT_B));

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        ArgumentCaptor<GoodsReceiptRecordedV1> copied = ArgumentCaptor.forClass(GoodsReceiptRecordedV1.class);
        verify(replica).recorded(copied.capture());
        assertThat(copied.getValue().receiptId()).isEqualTo(RECEIPT_B);
    }

    @Test
    @DisplayName("an unreadable stored payload and a malformed fact are skipped; the rest is still copied")
    void badFactsAreSkipped() {
        AccountingEvent unreadable = stored(RECEIPT_A, T0, Map.of("occurredAt", "not a time"));
        AccountingEvent malformed = stored(RECEIPT_B, T0, Map.of());
        when(assessor.assess(any()))
                .thenReturn(new GoodsReceiptAccrualPostingService.Assessment.Malformed("lines do not sum"))
                .thenReturn(new GoodsReceiptAccrualPostingService.Assessment.NothingToPost());
        UUID good = UUID.fromString("019a0000-0000-7000-8000-00000000d003");
        firstSlice(unreadable, malformed, stored(good));

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        verify(replica, times(1)).recorded(any());
    }

    @Test
    @DisplayName("a receipt's whole stored history is applied oldest first, once, even when it spans the slice")
    void historyIsAppliedOnce() {
        AccountingEvent first = stored(RECEIPT_A, T0, Map.of());
        AccountingEvent corrected = stored(RECEIPT_A, T0.plusSeconds(60), Map.of("totalAccruedAmountMinor", 50_000));
        when(events.findByEventTypeAndDomainKeyIdOrderByReceivedAtAscEventIdAsc(
                        GoodsReceiptRecordedV1.EVENT_TYPE, RECEIPT_A.toString()))
                .thenReturn(List.of(first, corrected));
        // Both facts are in the slice: the second must not be applied again after the history.
        firstSlice(first, corrected);

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        ArgumentCaptor<GoodsReceiptRecordedV1> applied = ArgumentCaptor.forClass(GoodsReceiptRecordedV1.class);
        verify(replica, times(2)).recorded(applied.capture());
        assertThat(applied.getAllValues())
                .extracting(GoodsReceiptRecordedV1::totalAccruedAmountMinor)
                .containsExactly(40_000L, 50_000L);
    }

    @Test
    @DisplayName("slices are read by keyset until one comes back short; every receipt of every slice is copied")
    void multipleSlicesAreConsumed() {
        int size = GoodsReceiptReplicaBackfill.SLICE_SIZE;
        List<AccountingEvent> page1 = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            page1.add(stored(UUID.randomUUID(), T0.plusSeconds(i), Map.of()));
        }
        List<AccountingEvent> page2 = List.of(
                stored(UUID.randomUUID(), T0.plusSeconds(size), Map.of()),
                stored(UUID.randomUUID(), T0.plusSeconds(size + 1), Map.of()),
                stored(UUID.randomUUID(), T0.plusSeconds(size + 2), Map.of()));
        AccountingEvent lastOfPage1 = page1.get(size - 1);
        firstSlice(page1.toArray(AccountingEvent[]::new));
        when(events.findGoodsReceiptFactsWithoutCopy(
                        eq(lastOfPage1.getReceivedAt()), eq(lastOfPage1.getEventId()), eq(size)))
                .thenReturn(page2);

        assertThat(backfill.backfillTenant()).isEqualTo(size + 3);

        verify(replica, times(size + 3)).recorded(any());
        verify(events, times(2)).findGoodsReceiptFactsWithoutCopy(any(), any(), eq(size));
    }

    @Test
    @DisplayName("a failure for one tenant is swallowed and the run never throws, so startup is never blocked")
    void failuresAreSwallowed() {
        doAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    work.accept(UUID.randomUUID());
                    work.accept(UUID.randomUUID());
                    return new TenantIterator.Sweep(2, false);
                })
                .when(tenants)
                .sweep(any());
        when(events.findGoodsReceiptFactsWithoutCopy(any(), any(), eq(GoodsReceiptReplicaBackfill.SLICE_SIZE)))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(backfill.backfill()).isZero();
        backfill.runSafely();

        verify(replica, never()).recorded(any());
    }

    @Test
    @DisplayName("runSafely swallows a failure of the tenant iteration itself")
    void runSwallowsIterationFailure() {
        when(tenants.sweep(any())).thenThrow(new IllegalStateException("registry down"));

        backfill.runSafely();
    }
}
