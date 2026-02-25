package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.returns.ReturnItemLine;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.entity.InventoryReturnEntity;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryReturnRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ReturnServiceImpl} — Story #177:
 * Return Unused Items to Stock with Reason.
 *
 * <p>
 * All tests are intentionally RED: {@code ReturnServiceImpl.returnItemsToStock}
 * currently throws {@link UnsupportedOperationException}. Tests RS1–RS3 fail
 * when
 * the exception propagates uncaught. Tests RS4–RS5 fail because the wrong
 * exception
 * type ({@code UnsupportedOperationException}) is thrown instead of the domain
 * exception each test expects.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0017: HTTP response codes (tested via contract layer)</li>
 * <li>ADR-0018: transactionUserId sourced from security context (service
 * layer)</li>
 * </ul>
 *
 * Issue: #177
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReturnServiceImpl")
class ReturnServiceImplTest {

    @Mock
    private InventoryReturnRepository inventoryReturnRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Captor
    private ArgumentCaptor<InventoryReturnEntity> entityCaptor;

    private Clock clock = Clock.fixed(Instant.parse("2026-02-25T03:00:00Z"), Clock.systemUTC().getZone());

    @BeforeEach
    void setUp() {
        lenient().when(inventoryReturnRepository.save(any(InventoryReturnEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ReturnServiceImpl service() {
        return new ReturnServiceImpl(inventoryReturnRepository, inventoryLedgerEntryRepository, clock);
    }

    private ReturnServiceImpl serviceWithLedgerRepository() {
        return new ReturnServiceImpl(inventoryReturnRepository, inventoryLedgerEntryRepository, clock);
    }

    // ─── RS1: returnItemsToStock — valid request → ReturnResponse fields ─────────

    /**
     * RS1: A valid request must produce a populated {@link ReturnResponse} with
     * a non-null returnId, matching workorderId, correct totalItemsReturned count,
     * and non-null createdAt.
     *
     * <p>
     * RED: {@code ReturnServiceImpl} throws {@code UnsupportedOperationException}
     * before constructing any response.
     *
     * Issue: #177
     */
    @Test
    @DisplayName("valid request returns response with returnId, workorderId, totalItemsReturned, and createdAt populated")
    void returnItemsToStock_validRequest_returnsPopulatedResponse() {
        // Issue #177: RS1 — service must return a properly populated ReturnResponse
        UUID workorderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Leftover brake pads",
                List.of(new ReturnItemLine(skuId, 2)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                eq(skuId.toString()),
                eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION),
                eq(workorderId.toString())))
                .thenReturn(List.of(InventoryLedgerEntry.builder().changeInQuantity(-100).build()));
        when(inventoryLedgerEntryRepository.saveAll(anyList())).thenReturn(List.of(
                InventoryLedgerEntry.builder().ledgerEntryId(UUID.randomUUID()).build()));

        ReturnResponse result = service().returnItemsToStock(request);

        assertThat(result.getReturnId()).isNotNull();
        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getTotalItemsReturned()).isEqualTo(2); // sum of quantityReturned
        assertThat(result.getCreatedAt()).isNotNull();
    }

    // ─── RS2: returnItemsToStock — saves InventoryReturnEntity + lines ───────────

    /**
     * RS2: A two-item request must persist one {@link InventoryReturnEntity} (with
     * two child {@code InventoryReturnLineEntity} lines via cascade) through
     * {@code InventoryReturnRepository.save}.
     *
     * <p>
     * RED: {@code ReturnServiceImpl} throws {@code UnsupportedOperationException}
     * before invoking {@code save}; the subsequent {@code verify} is never reached.
     *
     * Issue: #177
     */
    @Test
    @DisplayName("two-item request saves InventoryReturnEntity containing two lines via cascade")
    void returnItemsToStock_twoItemRequest_savesReturnEntityWithTwoLines() {
        // Issue #177: RS2 — one header entity + one line per returned item must be
        // persisted
        UUID workorderId = UUID.randomUUID();
        UUID skuId1 = UUID.randomUUID();
        UUID skuId2 = UUID.randomUUID();

        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "End of job surplus",
                List.of(new ReturnItemLine(skuId1, 3), new ReturnItemLine(skuId2, 1)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder().changeInQuantity(-100).build()));

        service().returnItemsToStock(request);

        verify(inventoryReturnRepository).save(entityCaptor.capture());
        InventoryReturnEntity captured = entityCaptor.getValue();
        assertThat(captured.getWorkorderId()).isEqualTo(workorderId);
        assertThat(captured.getReturnReason()).isEqualTo("End of job surplus");
        assertThat(captured.getLines()).hasSize(2);
    }

    // ─── RS3: returnItemsToStock — creates RETURN_TO_STOCK ledger entry per item ─

    /**
     * RS3: A two-item request must create one {@code RETURN_TO_STOCK} ledger entry
     * for
     * each returned item and include the resulting ledger entry IDs in the
     * response.
     *
     * <p>
     * RED: {@code ReturnServiceImpl} throws {@code UnsupportedOperationException}
     * before constructing any ledger entries; {@code getLedgerEntryIds()} is never
     * reached.
     *
     * Issue: #177
     */
    @Test
    @DisplayName("two-item request populates response with two non-null RETURN_TO_STOCK ledger entry IDs")
    void returnItemsToStock_twoItemRequest_returnsTwoLedgerEntryIds() {
        // Issue #177: RS3 — one RETURN_TO_STOCK ledger entry per line; IDs surfaced in
        // response
        UUID workorderId = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Surplus parts",
                List.of(
                        new ReturnItemLine(UUID.randomUUID(), 1),
                        new ReturnItemLine(UUID.randomUUID(), 2)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder().changeInQuantity(-100).build()));
        when(inventoryLedgerEntryRepository.saveAll(anyList())).thenReturn(List.of(
                InventoryLedgerEntry.builder().ledgerEntryId(UUID.randomUUID()).build(),
                InventoryLedgerEntry.builder().ledgerEntryId(UUID.randomUUID()).build()));

        ReturnResponse result = service().returnItemsToStock(request);

        assertThat(result.getLedgerEntryIds()).hasSize(2).doesNotContainNull();
    }

    // ─── RS4: returnItemsToStock — null returnReason → exception ─────────────────

    /**
     * RS4: Passing a {@code null} returnReason directly to the service (bypassing
     * Bean Validation) must raise a {@link NullPointerException} or
     * {@link IllegalArgumentException}.
     *
     * <p>
     * RED: {@code ReturnServiceImpl} throws {@code UnsupportedOperationException},
     * which is not an instance of either expected type.
     *
     * Issue: #177
     */
    @Test
    @DisplayName("null returnReason throws NullPointerException or IllegalArgumentException")
    void returnItemsToStock_nullReturnReason_throwsNullOrIllegalArgument() {
        // Issue #177: RS4 — runtime guard required; @NotBlank may not run outside MVC
        // layer
        ReturnItemsRequest request = new ReturnItemsRequest(
                UUID.randomUUID(),
                null,
                List.of(new ReturnItemLine(UUID.randomUUID(), 1)));

        // RED: UnsupportedOperationException is thrown — not in isInstanceOfAny set
        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }

    // ─── RS5: returnItemsToStock — quantity > consumedQuantity → exception
    // ────────

    /**
     * RS5: When a returned quantity exceeds the previously consumed quantity for a
     * given
     * SKU, the service must throw {@link ReturnQuantityExceededException}.
     *
     * <p>
     * RED: {@code ReturnServiceImpl} throws {@code UnsupportedOperationException},
     * which is not a {@code ReturnQuantityExceededException}.
     *
     * Issue: #177
     */
    @Test
    @DisplayName("return quantity exceeding consumed quantity throws ReturnQuantityExceededException")
    void returnItemsToStock_quantityExceedsConsumed_throwsReturnQuantityExceededException() {
        // Issue #177: RS5 — over-return must be caught before persisting
        UUID skuId = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                UUID.randomUUID(),
                "Over-return attempt",
                List.of(new ReturnItemLine(skuId, 999)));

        // RED: UnsupportedOperationException is thrown, not
        // ReturnQuantityExceededException
        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(ReturnQuantityExceededException.class)
                .hasMessageContaining(skuId.toString());
    }

    @Test
    @DisplayName("ledger-enabled path sums returned quantities and filters null ledger IDs")
    void returnItemsToStock_withLedgerRepository_sumsQuantitiesAndFiltersNullLedgerIds() {
        UUID workorderId = UUID.randomUUID();
        UUID skuId1 = UUID.randomUUID();
        UUID skuId2 = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "  Completed workorder return  ",
                List.of(new ReturnItemLine(skuId1, 3), new ReturnItemLine(skuId2, 1)));

        InventoryReturnEntity saved = InventoryReturnEntity.builder()
                .returnId(UUID.randomUUID())
                .workorderId(workorderId)
                .returnReason("Completed workorder return")
                .totalItemsReturned(4)
                .createdAt(Instant.parse("2026-02-25T03:00:00Z"))
                .build();
        when(inventoryReturnRepository.save(entityCaptor.capture())).thenReturn(saved);

        InventoryLedgerEntry consumedOne = InventoryLedgerEntry.builder().changeInQuantity(-5).build();
        InventoryLedgerEntry consumedTwo = InventoryLedgerEntry.builder().changeInQuantity(2).build();
        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                anyString(),
                eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION),
                eq(workorderId.toString())))
                .thenReturn(List.of(consumedOne, consumedTwo));

        UUID firstLedgerId = UUID.randomUUID();
        InventoryLedgerEntry savedLedgerOne = InventoryLedgerEntry.builder().ledgerEntryId(firstLedgerId).build();
        InventoryLedgerEntry savedLedgerTwo = InventoryLedgerEntry.builder().ledgerEntryId(null).build();
        when(inventoryLedgerEntryRepository.saveAll(anyList())).thenReturn(List.of(savedLedgerOne, savedLedgerTwo));

        ReturnResponse result = serviceWithLedgerRepository().returnItemsToStock(request);

        assertThat(result.getTotalItemsReturned()).isEqualTo(4);
        assertThat(result.getReturnReason()).isEqualTo("Completed workorder return");
        assertThat(result.getLedgerEntryIds()).containsExactly(firstLedgerId);

        InventoryReturnEntity captured = entityCaptor.getValue();
        assertThat(captured.getTotalItemsReturned()).isEqualTo(4);
        assertThat(captured.getReturnReason()).isEqualTo("Completed workorder return");
    }

    @Test
    @DisplayName("ledger-enabled path throws when requested return exceeds consumed")
    void returnItemsToStock_withLedgerRepository_returnExceedsConsumed_throwsException() {
        UUID workorderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Return attempt",
                List.of(new ReturnItemLine(skuId, 3)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                eq(skuId.toString()),
                eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION),
                eq(workorderId.toString())))
                .thenReturn(List.of(InventoryLedgerEntry.builder().changeInQuantity(1).build()));

        assertThatThrownBy(() -> serviceWithLedgerRepository().returnItemsToStock(request))
                .isInstanceOf(ReturnQuantityExceededException.class)
                .hasMessageContaining(skuId.toString());
    }

    @Test
    @DisplayName("save returning null fails fast")
    void returnItemsToStock_saveReturnsNull_throwsNullPointerException() {
        UUID workorderId = UUID.randomUUID();
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Fallback return",
                List.of(new ReturnItemLine(UUID.randomUUID(), 1)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder().changeInQuantity(-100).build()));
        when(inventoryReturnRepository.save(any(InventoryReturnEntity.class))).thenReturn(null);

        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inventoryReturnRepository.save(...) returned null");
    }

    @Test
    @DisplayName("non-positive quantity throws IllegalArgumentException")
    void returnItemsToStock_nonPositiveQuantity_throwsIllegalArgumentException() {
        ReturnItemsRequest request = new ReturnItemsRequest(
                UUID.randomUUID(),
                "Invalid return",
                List.of(new ReturnItemLine(UUID.randomUUID(), 0)));

        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantityReturned must be positive");
    }
}
