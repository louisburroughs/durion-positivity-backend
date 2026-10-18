package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.tenancy.TenantIterator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Goods receipt copy backfill (#2750)")
class GoodsReceiptReplicaBackfillTest {

    private static final UUID RECEIPT_A = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final UUID RECEIPT_B = UUID.fromString("019a0000-0000-7000-8000-00000000d002");
    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");

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
        when(receipts.findAll()).thenReturn(List.of());
    }

    private AccountingEvent stored(UUID receiptId, Map<String, Object> overrides) {
        GoodsReceiptRecordedV1 fact = new GoodsReceiptRecordedV1(
                receiptId,
                "GR-" + receiptId.toString().substring(35),
                PO,
                null,
                40_000L,
                Instant.parse("2026-10-01T09:00:00Z"),
                List.of(),
                "USD");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new java.util.LinkedHashMap<>(objectMapper.convertValue(fact, Map.class));
        payload.putAll(overrides);
        AccountingEvent event = new AccountingEvent();
        event.setPayload(payload);
        return event;
    }

    private void storedFacts(AccountingEvent... stored) {
        when(events.findByEventTypeOrderByReceivedAtAscEventIdAsc(
                        org.mockito.ArgumentMatchers.eq(GoodsReceiptRecordedV1.EVENT_TYPE), any()))
                .thenReturn(new SliceImpl<>(List.of(stored), PageRequest.of(0, 200), false));
    }

    @Test
    @DisplayName("a stored fact with no copy row is replayed through the writer")
    void replaysFactsWithoutARow() {
        storedFacts(stored(RECEIPT_A, Map.of()), stored(RECEIPT_B, Map.of()));

        assertThat(backfill.backfillTenant()).isEqualTo(2);

        verify(replica, org.mockito.Mockito.times(2)).recorded(any());
    }

    @Test
    @DisplayName("a receipt that already has a copy row is left alone")
    void existingRowIsKept() {
        ExtInventoryGoodsReceipt existing = new ExtInventoryGoodsReceipt();
        existing.setGoodsReceiptId(RECEIPT_A);
        when(receipts.findAll()).thenReturn(List.of(existing));
        storedFacts(stored(RECEIPT_A, Map.of()), stored(RECEIPT_B, Map.of()));

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        verify(replica, org.mockito.Mockito.times(1)).recorded(any());
    }

    @Test
    @DisplayName("an unreadable stored payload and a malformed fact are skipped; the rest is still copied")
    void badFactsAreSkipped() {
        AccountingEvent unreadable = stored(RECEIPT_A, Map.of("occurredAt", "not a time"));
        AccountingEvent malformed = stored(RECEIPT_B, Map.of());
        when(assessor.assess(any()))
                .thenReturn(new GoodsReceiptAccrualPostingService.Assessment.Malformed("lines do not sum"))
                .thenReturn(new GoodsReceiptAccrualPostingService.Assessment.NothingToPost());
        UUID good = UUID.fromString("019a0000-0000-7000-8000-00000000d003");
        storedFacts(unreadable, malformed, stored(good, Map.of()));

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        verify(replica, org.mockito.Mockito.times(1)).recorded(any());
    }

    @Test
    @DisplayName("a corrected re-emit later in the same run is applied after the fact it corrects")
    void laterFactForTheSameReceiptIsApplied() {
        storedFacts(stored(RECEIPT_A, Map.of()), stored(RECEIPT_A, Map.of("totalAccruedAmountMinor", 50_000)));

        assertThat(backfill.backfillTenant()).isEqualTo(1);

        verify(replica, org.mockito.Mockito.times(2)).recorded(any());
    }

    @Test
    @DisplayName("a failure for one tenant is swallowed and the run never throws, so startup is never blocked")
    void failuresAreSwallowed() {
        doAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    work.accept(UUID.randomUUID());
                    work.accept(UUID.randomUUID());
                    return 2;
                })
                .when(tenants)
                .forEachActiveTenant(any());
        when(receipts.findAll()).thenThrow(new IllegalStateException("db down"));

        assertThat(backfill.backfill()).isZero();
        backfill.run(null);

        verify(replica, never()).recorded(any());
    }

    @Test
    @DisplayName("run() swallows a failure of the tenant iteration itself")
    void runSwallowsIterationFailure() {
        doThrow(new IllegalStateException("registry down")).when(tenants).forEachActiveTenant(any());

        backfill.run(null);
    }
}
