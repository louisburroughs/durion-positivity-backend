package com.positivity.inventory.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the per-line counter-sale posting guard (order parity story H2, #1079): a
 * replayed order line (same event type + sourceTransactionId) must not double-decrement stock.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CounterSaleIssuePoster — parity story H2")
class CounterSaleIssuePosterTest {

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private InventoryLedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private CounterSaleIssuePoster poster;

    private static InventoryLedgerEntry entry(String sourceTransactionId) {
        return InventoryLedgerEntry.builder()
                .stockItemId("SKU-1")
                .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                .changeInQuantity(new BigDecimal("-1"))
                .quantityAfter(new BigDecimal("0"))
                .sourceTransactionId(sourceTransactionId)
                .build();
    }

    @Test
    @DisplayName("CSP-001: first posting for a line goes through")
    void firstPosting_goesThrough() {
        when(ledgerEntryRepository.existsByEventTypeAndSourceTransactionId(any(), any()))
                .thenReturn(false);

        poster.post(entry(UUID.randomUUID().toString()));

        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    @DisplayName("CSP-002: replayed line (same sourceTransactionId) is skipped")
    void replayedLine_skipped() {
        when(ledgerEntryRepository.existsByEventTypeAndSourceTransactionId(any(), any()))
                .thenReturn(true);

        poster.post(entry(UUID.randomUUID().toString()));

        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }
}
