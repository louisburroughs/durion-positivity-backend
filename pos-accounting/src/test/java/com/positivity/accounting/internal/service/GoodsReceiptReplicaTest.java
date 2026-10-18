package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Goods receipt replica (#2750)")
class GoodsReceiptReplicaTest {

    private static final UUID RECEIPT = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final Instant FIRST = Instant.parse("2026-10-01T09:00:00Z");

    private final ExtInventoryGoodsReceiptRepository repository = mock(ExtInventoryGoodsReceiptRepository.class);
    private final Map<UUID, ExtInventoryGoodsReceipt> rows = new LinkedHashMap<>();
    private GoodsReceiptReplica replica;

    @BeforeEach
    void setUp() {
        replica = new GoodsReceiptReplica(repository);
        when(repository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(repository.save(any())).thenAnswer(invocation -> {
            ExtInventoryGoodsReceipt row = invocation.getArgument(0);
            rows.put(row.getGoodsReceiptId(), row);
            return row;
        });
    }

    private static GoodsReceiptRecordedV1 fact(
            String number, long amount, String currency, Instant occurredAt, UUID purchaseOrderId) {
        return new GoodsReceiptRecordedV1(RECEIPT, number, purchaseOrderId, null, amount, occurredAt, List.of(), currency);
    }

    @Test
    @DisplayName("the first fact inserts the receipt with its occurredAt as the received time")
    void firstFactInserts() {
        replica.recorded(fact("GR-1", 120_000L, "USD", FIRST, PO));

        ExtInventoryGoodsReceipt row = rows.get(RECEIPT);
        assertThat(row.getReceiptNumber()).isEqualTo("GR-1");
        assertThat(row.getPurchaseOrderId()).isEqualTo(PO);
        assertThat(row.getAccruedAmountMinor()).isEqualTo(120_000L);
        assertThat(row.getCurrencyCode()).isEqualTo("USD");
        assertThat(row.getReceivedAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a corrected re-emit updates number, order, amount and currency and keeps the first received time")
    void reEmitKeepsReceivedAt() {
        replica.recorded(fact("GR-1", 120_000L, "USD", FIRST, PO));
        UUID otherOrder = UUID.fromString("019a0000-0000-7000-8000-00000000b002");

        replica.recorded(fact("GR-1A", 130_000L, "EUR", FIRST.plusSeconds(86_400), otherOrder));

        assertThat(rows).hasSize(1);
        ExtInventoryGoodsReceipt row = rows.get(RECEIPT);
        assertThat(row.getReceiptNumber()).isEqualTo("GR-1A");
        assertThat(row.getPurchaseOrderId()).isEqualTo(otherOrder);
        assertThat(row.getAccruedAmountMinor()).isEqualTo(130_000L);
        assertThat(row.getCurrencyCode()).isEqualTo("EUR");
        assertThat(row.getReceivedAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("a fact with no receipt number or currency stores nulls, never a default")
    void nullsAreKept() {
        replica.recorded(fact(null, 0L, null, FIRST, PO));

        ExtInventoryGoodsReceipt row = rows.get(RECEIPT);
        assertThat(row.getReceiptNumber()).isNull();
        assertThat(row.getCurrencyCode()).isNull();
        assertThat(row.getAccruedAmountMinor()).isZero();
    }
}
