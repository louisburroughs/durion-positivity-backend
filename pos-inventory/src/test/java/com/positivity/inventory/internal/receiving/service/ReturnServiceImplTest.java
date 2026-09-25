package com.positivity.inventory.internal.receiving.service;

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
import com.positivity.inventory.internal.entity.InventoryReturnEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryReturnLineRepository;
import com.positivity.inventory.internal.repository.InventoryReturnRepository;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private InventoryReturnRepository inventoryReturnRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private InventoryReturnLineRepository inventoryReturnLineRepository;

    @Mock
    private ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;

    @Mock
    private BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Captor
    private ArgumentCaptor<InventoryReturnEntity> entityCaptor;

    private Clock clock = Clock.fixed(Instant.parse("2026-02-25T03:00:00Z"), TEST_CLOCK.getZone());

    @BeforeEach
    void setUp() {
        lenient()
                .when(inventoryReturnRepository.save(any(InventoryReturnEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ReturnServiceImpl service() {
        return new ReturnServiceImpl(
                inventoryReturnRepository,
                inventoryReturnLineRepository,
                inventoryLedgerEntryRepository,
                extWorkorderPartReplicaRepository,
                ledgerPostingService,
                org.mockito.Mockito.mock(com.positivity.inventory.internal.service.InventoryFactPublisher.class),
                new com.positivity.inventory.internal.service.DocumentQuantityConverter(
                        org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class)),
                baseUnitOfMeasureResolver,
                clock,
                new com.positivity.inventory.internal.service.QuantityScaleGuard(org.mockito.Mockito.mock(
                        com.positivity.inventory.internal.service.UomConversionService.class)));
    }

    private ReturnServiceImpl serviceWithLedgerRepository() {
        return new ReturnServiceImpl(
                inventoryReturnRepository,
                inventoryReturnLineRepository,
                inventoryLedgerEntryRepository,
                extWorkorderPartReplicaRepository,
                ledgerPostingService,
                org.mockito.Mockito.mock(com.positivity.inventory.internal.service.InventoryFactPublisher.class),
                new com.positivity.inventory.internal.service.DocumentQuantityConverter(
                        org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class)),
                baseUnitOfMeasureResolver,
                clock,
                new com.positivity.inventory.internal.service.QuantityScaleGuard(org.mockito.Mockito.mock(
                        com.positivity.inventory.internal.service.UomConversionService.class)));
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
    @DisplayName(
            "valid request returns response with returnId, workorderId, totalItemsReturned, and createdAt populated")
    void returnItemsToStock_validRequest_returnsPopulatedResponse() {
        // Issue #177: RS1 — service must return a properly populated ReturnResponse
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Leftover brake pads",
                List.of(new ReturnItemLine(skuId, new BigDecimal("2"), null, null)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        eq(skuId.toString()),
                        eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION),
                        eq(workorderId.toString())))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .changeInQuantity(new BigDecimal("-100"))
                        .build()));
        when(ledgerPostingService.postAll(anyList()))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .build()));

        ReturnResponse result = service().returnItemsToStock(request);

        assertThat(result.getReturnId()).isNotNull();
        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getTotalItemsReturned()).isEqualByComparingTo("2"); // sum of quantityReturned
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
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "End of job surplus",
                List.of(
                        new ReturnItemLine(skuId1, new BigDecimal("3"), null, null),
                        new ReturnItemLine(skuId2, new BigDecimal("1"), null, null)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .changeInQuantity(new BigDecimal("-100"))
                        .build()));

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
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Surplus parts",
                List.of(
                        new ReturnItemLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                new BigDecimal("1"),
                                null,
                                null),
                        new ReturnItemLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                new BigDecimal("2"),
                                null,
                                null)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .changeInQuantity(new BigDecimal("-100"))
                        .build()));
        when(ledgerPostingService.postAll(anyList()))
                .thenReturn(List.of(
                        InventoryLedgerEntry.builder()
                                .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build(),
                        InventoryLedgerEntry.builder()
                                .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build()));

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
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                null,
                List.of(new ReturnItemLine(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("1"), null, null)));

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
        UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Over-return attempt",
                List.of(new ReturnItemLine(skuId, new BigDecimal("999"), null, null)));

        // RED: UnsupportedOperationException is thrown, not
        // ReturnQuantityExceededException
        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(ReturnQuantityExceededException.class)
                .hasMessageContaining(skuId.toString());
    }

    @Test
    @DisplayName("ledger-enabled path sums returned quantities and filters null ledger IDs")
    void returnItemsToStock_withLedgerRepository_sumsQuantitiesAndFiltersNullLedgerIds() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "  Completed workorder return  ",
                List.of(
                        new ReturnItemLine(skuId1, new BigDecimal("3"), null, null),
                        new ReturnItemLine(skuId2, new BigDecimal("1"), null, null)));

        InventoryReturnEntity saved = InventoryReturnEntity.builder()
                .returnId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderId(workorderId)
                .returnReason("Completed workorder return")
                .totalItemsReturned(new BigDecimal("4"))
                .createdAt(Instant.parse("2026-02-25T03:00:00Z"))
                .build();
        when(inventoryReturnRepository.save(entityCaptor.capture())).thenReturn(saved);

        InventoryLedgerEntry consumedOne = InventoryLedgerEntry.builder()
                .changeInQuantity(new BigDecimal("-5"))
                .build();
        InventoryLedgerEntry consumedTwo = InventoryLedgerEntry.builder()
                .changeInQuantity(new BigDecimal("2"))
                .build();
        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        anyString(), eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION), eq(workorderId.toString())))
                .thenReturn(List.of(consumedOne, consumedTwo));

        UUID firstLedgerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        InventoryLedgerEntry savedLedgerOne =
                InventoryLedgerEntry.builder().ledgerEntryId(firstLedgerId).build();
        InventoryLedgerEntry savedLedgerTwo =
                InventoryLedgerEntry.builder().ledgerEntryId(null).build();
        when(ledgerPostingService.postAll(anyList())).thenReturn(List.of(savedLedgerOne, savedLedgerTwo));

        ReturnResponse result = serviceWithLedgerRepository().returnItemsToStock(request);

        assertThat(result.getTotalItemsReturned()).isEqualByComparingTo("4");
        assertThat(result.getReturnReason()).isEqualTo("Completed workorder return");
        assertThat(result.getLedgerEntryIds()).containsExactly(firstLedgerId);

        InventoryReturnEntity captured = entityCaptor.getValue();
        assertThat(captured.getTotalItemsReturned()).isEqualByComparingTo("4");
        assertThat(captured.getReturnReason()).isEqualTo("Completed workorder return");
    }

    @Test
    @DisplayName("ledger-enabled path throws when requested return exceeds consumed")
    void returnItemsToStock_withLedgerRepository_returnExceedsConsumed_throwsException() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId, "Return attempt", List.of(new ReturnItemLine(skuId, new BigDecimal("3"), null, null)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        eq(skuId.toString()),
                        eq(InventoryLedgerEventType.WORKORDER_CONSUMPTION),
                        eq(workorderId.toString())))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .changeInQuantity(new BigDecimal("1"))
                        .build()));

        assertThatThrownBy(() -> serviceWithLedgerRepository().returnItemsToStock(request))
                .isInstanceOf(ReturnQuantityExceededException.class)
                .hasMessageContaining(skuId.toString());
    }

    @Test
    @DisplayName("save returning null fails fast")
    void returnItemsToStock_saveReturnsNull_throwsNullPointerException() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReturnItemsRequest request = new ReturnItemsRequest(
                workorderId,
                "Fallback return",
                List.of(new ReturnItemLine(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("1"), null, null)));

        when(inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        anyString(), any(), anyString()))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .changeInQuantity(new BigDecimal("-100"))
                        .build()));
        when(inventoryReturnRepository.save(any(InventoryReturnEntity.class))).thenReturn(null);

        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inventoryReturnRepository.save(...) returned null");
    }

    @Test
    @DisplayName("non-positive quantity throws IllegalArgumentException")
    void returnItemsToStock_nonPositiveQuantity_throwsIllegalArgumentException() {
        ReturnItemsRequest request = new ReturnItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Invalid return",
                List.of(new ReturnItemLine(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("0"), null, null)));

        assertThatThrownBy(() -> service().returnItemsToStock(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantityReturned must be positive");
    }

    // ─── #2206: listReturnableItems / listReturnReasonCodes / submitToStock (real impl) ──

    @Test
    @DisplayName("listReturnReasonCodes returns the CAP-218 closed set")
    void listReturnReasonCodes_returnsClosedSet() {
        List<String> codes = service().listReturnReasonCodes().stream()
                .map(com.positivity.inventory.internal.dto.returns.ReasonCodeDto::getCode)
                .toList();

        assertThat(codes).containsExactlyInAnyOrder("NOT_NEEDED", "WRONG_PART", "CUSTOMER_REFUSED");
    }

    @Test
    @DisplayName("listReturnableItems derives quantityReturnable from consumed minus already returned")
    void listReturnableItems_derivesReturnableFromConsumedMinusReturned() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000012");

        com.positivity.inventory.internal.entity.ExtWorkorderPartReplica partLine =
                com.positivity.inventory.internal.entity.ExtWorkorderPartReplica.builder()
                        .workorderLineId(workorderLineId)
                        .workorderId(workorderId)
                        .productEntityId(productId)
                        .quantity(new BigDecimal("5"))
                        .build();
        when(extWorkorderPartReplicaRepository.findByWorkorderId(workorderId)).thenReturn(List.of(partLine));
        when(inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                        workorderId, InventoryLedgerEventType.WORKORDER_CONSUMPTION))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .workorderId(workorderId)
                        .workorderLineId(workorderLineId)
                        .changeInQuantity(new BigDecimal("-5"))
                        .build()));
        when(inventoryReturnLineRepository.findByWorkorderLineIdIn(List.of(workorderLineId)))
                .thenReturn(List.of(com.positivity.inventory.internal.entity.InventoryReturnLineEntity.builder()
                        .workorderLineId(workorderLineId)
                        .quantityReturned(new BigDecimal("2"))
                        .build()));
        when(baseUnitOfMeasureResolver.resolveAll(any())).thenReturn(java.util.Map.of(productId, "EACH"));

        List<com.positivity.inventory.internal.dto.returns.ReturnableItemDto> result =
                service().listReturnableItems(workorderId);

        assertThat(result).hasSize(1);
        com.positivity.inventory.internal.dto.returns.ReturnableItemDto item = result.get(0);
        assertThat(item.getItemId()).isEqualTo(workorderLineId);
        assertThat(item.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(item.getSku()).isEqualTo(productId.toString());
        assertThat(item.getUom()).isEqualTo("EACH");
        assertThat(item.getQuantityReturnable()).isEqualTo(3); // 5 consumed - 2 returned
    }

    @Test
    @DisplayName("listReturnableItems returns empty list for a workorder with no part lines")
    void listReturnableItems_noPartLines_returnsEmptyList() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        when(extWorkorderPartReplicaRepository.findByWorkorderId(workorderId)).thenReturn(List.of());

        assertThat(service().listReturnableItems(workorderId)).isEmpty();
    }

    @Test
    @DisplayName("submitToStock posts a RETURN_TO_STOCK ledger entry carrying workorderId/workorderLineId")
    void submitToStock_validLine_postsLedgerEntryAndPersistsReturn() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000031");
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000032");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000033");

        com.positivity.inventory.internal.entity.ExtWorkorderPartReplica partLine =
                com.positivity.inventory.internal.entity.ExtWorkorderPartReplica.builder()
                        .workorderLineId(workorderLineId)
                        .workorderId(workorderId)
                        .productEntityId(productId)
                        .build();
        when(extWorkorderPartReplicaRepository.findAllById(List.of(workorderLineId)))
                .thenReturn(List.of(partLine));
        when(inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                        workorderId, InventoryLedgerEventType.WORKORDER_CONSUMPTION))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .workorderId(workorderId)
                        .workorderLineId(workorderLineId)
                        .changeInQuantity(new BigDecimal("-3"))
                        .build()));
        when(inventoryReturnLineRepository.findByWorkorderLineIdIn(List.of(workorderLineId)))
                .thenReturn(List.of());
        when(ledgerPostingService.postAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest request =
                com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest.builder()
                        .workorderId(workorderId)
                        .lines(List.of(com.positivity.inventory.internal.dto.returns.ReturnLineDto.builder()
                                .itemId(workorderLineId)
                                .quantity(2)
                                .reasonCode("NOT_NEEDED")
                                .locationId(locationId)
                                .build()))
                        .build();

        com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto result =
                service().submitToStock(request);

        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getProcessedLines()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("SUBMITTED");

        org.mockito.ArgumentCaptor<List<InventoryLedgerEntry>> ledgerCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ledgerPostingService).postAll(ledgerCaptor.capture());
        InventoryLedgerEntry posted = ledgerCaptor.getValue().get(0);
        assertThat(posted.getEventType()).isEqualTo(InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(posted.getWorkorderId()).isEqualTo(workorderId);
        assertThat(posted.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(posted.getChangeInQuantity()).isEqualByComparingTo("2");

        verify(inventoryReturnRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getLines()).hasSize(1);
        assertThat(entityCaptor.getValue().getLines().get(0).getWorkorderLineId())
                .isEqualTo(workorderLineId);
    }

    @Test
    @DisplayName("submitToStock rejects a quantity exceeding what remains returnable with 422 RETURN_QUANTITY_EXCEEDED")
    void submitToStock_quantityExceedsReturnable_throwsReturnQuantityExceeded() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000041");
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000042");

        com.positivity.inventory.internal.entity.ExtWorkorderPartReplica partLine =
                com.positivity.inventory.internal.entity.ExtWorkorderPartReplica.builder()
                        .workorderLineId(workorderLineId)
                        .workorderId(workorderId)
                        .productEntityId(productId)
                        .build();
        when(extWorkorderPartReplicaRepository.findAllById(List.of(workorderLineId)))
                .thenReturn(List.of(partLine));
        when(inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                        workorderId, InventoryLedgerEventType.WORKORDER_CONSUMPTION))
                .thenReturn(List.of(InventoryLedgerEntry.builder()
                        .workorderId(workorderId)
                        .workorderLineId(workorderLineId)
                        .changeInQuantity(new BigDecimal("-1"))
                        .build()));
        when(inventoryReturnLineRepository.findByWorkorderLineIdIn(List.of(workorderLineId)))
                .thenReturn(List.of());

        com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest request =
                com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest.builder()
                        .workorderId(workorderId)
                        .lines(List.of(com.positivity.inventory.internal.dto.returns.ReturnLineDto.builder()
                                .itemId(workorderLineId)
                                .quantity(5)
                                .reasonCode("WRONG_PART")
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000043"))
                                .build()))
                        .build();

        assertThatThrownBy(() -> service().submitToStock(request)).isInstanceOf(ReturnQuantityExceededException.class);
    }

    @Test
    @DisplayName("submitToStock 404s when a line's itemId does not name a work order part line")
    void submitToStock_unknownWorkorderLine_throwsResourceNotFound() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000050");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000051");
        when(extWorkorderPartReplicaRepository.findAllById(List.of(workorderLineId)))
                .thenReturn(List.of());

        com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest request =
                com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest.builder()
                        .workorderId(workorderId)
                        .lines(List.of(com.positivity.inventory.internal.dto.returns.ReturnLineDto.builder()
                                .itemId(workorderLineId)
                                .quantity(1)
                                .reasonCode("CUSTOMER_REFUSED")
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000052"))
                                .build()))
                        .build();

        assertThatThrownBy(() -> service().submitToStock(request))
                .isInstanceOf(com.positivity.inventory.internal.exception.ResourceNotFoundException.class);
    }
}
