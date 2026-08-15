package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.DispatchTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ReceiveTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ShortCloseTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.dto.transfer.TransferQuantityLineRequest;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.TransferOrder;
import com.positivity.inventory.internal.entity.TransferOrderLine;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.enums.TransferShortCloseDisposition;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.TransferLocationNotEligibleException;
import com.positivity.inventory.internal.exception.TransferOrderNotFoundException;
import com.positivity.inventory.internal.exception.TransferQuantityExceededException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.data.domain.Sort;

/**
 * Transfer-order lifecycle (odoo-parity C1/C3, #1035/#1039): site eligibility gates every
 * movement, dispatch and receive post conserving ledger pairs, and short-close resolves the
 * in-transit remainder as either a loss or a return to the source.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransferOrderServiceImpl")
class TransferOrderServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a01");
    private static final UUID SOURCE_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a02");
    private static final UUID DESTINATION_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a03");
    private static final UUID SOURCE_BIN = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a04");
    private static final UUID LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a05");
    private static final UUID LOT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1a06");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String SKU = "SKU-1";

    @Mock
    private TransferOrderRepository transferOrderRepository;

    @Mock
    private LocationRefRepository locationRefRepository;

    @Mock
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    @Mock
    private InventoryLotOutboundService lotOutboundService;

    private TransferOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService(false);
        stubEligibleSite(SOURCE_SITE);
        stubEligibleSite(DESTINATION_SITE);
        when(transferOrderRepository.save(any())).thenAnswer(invocation -> {
            TransferOrder order = invocation.getArgument(0);
            if (order.getTransferOrderId() == null) {
                order.setTransferOrderId(ORDER_ID);
            }
            return order;
        });
        when(ledgerPostingService.postAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(anyString(), any(UUID.class)))
                .thenReturn(50);
        when(storageLocationRepository.findById(any())).thenReturn(Optional.empty());
        when(lotOutboundService.resolveOutboundLot(anyString(), any())).thenReturn(null);
    }

    private TransferOrderServiceImpl newService(boolean approvalRequired) {
        return new TransferOrderServiceImpl(
                transferOrderRepository,
                locationRefRepository,
                storageLocationRepository,
                ledgerPostingService,
                ledgerRepository,
                inventoryFactPublisher,
                lotOutboundService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                approvalRequired);
    }

    private void stubEligibleSite(UUID siteId) {
        stubSite(siteId, "ACTIVE");
    }

    private void stubSite(UUID siteId, String status) {
        LocationRefEntity site = new LocationRefEntity();
        site.setLocationId(siteId);
        site.setStatus(status);
        when(locationRefRepository.findByLocationId(siteId)).thenReturn(Optional.of(site));
    }

    private static CreateTransferOrderRequest createRequest() {
        return CreateTransferOrderRequest.builder()
                .sourceLocationId(SOURCE_SITE)
                .destinationLocationId(DESTINATION_SITE)
                .notes("restock")
                .lines(List.of(TransferOrderLineRequest.builder()
                        .sku(SKU)
                        .requestedQty(10)
                        .build()))
                .build();
    }

    private TransferOrder order(TransferOrderStatus status, int requested, int dispatched, int received) {
        TransferOrderLine line = TransferOrderLine.builder()
                .lineId(LINE_ID)
                .lineNumber(1)
                .sku(SKU)
                .requestedQty(requested)
                .dispatchedQty(dispatched)
                .receivedQty(received)
                .build();
        TransferOrder order = TransferOrder.builder()
                .transferOrderId(ORDER_ID)
                .sourceLocationId(SOURCE_SITE)
                .destinationLocationId(DESTINATION_SITE)
                .status(status)
                .lines(new ArrayList<>())
                .build();
        order.addLine(line);
        when(transferOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    @SuppressWarnings("unchecked")
    private List<InventoryLedgerEntry> postedEntries() {
        ArgumentCaptor<List<InventoryLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerPostingService).postAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("createTransferOrder")
    class CreateTransferOrder {

        @Test
        void opensADraftOrderWithNumberedLinesAndPublishesTheFact() {
            TransferOrderResponse response = service.createTransferOrder(createRequest());

            assertThat(response.getStatus()).isEqualTo(TransferOrderStatus.DRAFT);
            assertThat(response.getSourceLocationId()).isEqualTo(SOURCE_SITE);
            assertThat(response.getLines()).hasSize(1);
            assertThat(response.getLines().get(0).getLineNumber()).isEqualTo(1);
            assertThat(response.getLines().get(0).getRequestedQty()).isEqualTo(10);
            assertThat(response.getNotes()).isEqualTo("restock");
            assertThat(response.getCreatedBy()).isEqualTo("system");

            ArgumentCaptor<TransferOrderUpdatedV1> fact = ArgumentCaptor.forClass(TransferOrderUpdatedV1.class);
            verify(inventoryFactPublisher).recordTransferOrderUpdated(fact.capture());
            assertThat(fact.getValue().status()).isEqualTo("DRAFT");
            assertThat(fact.getValue().lines()).hasSize(1);
        }

        @Test
        void refusesATransferFromASiteToItself() {
            CreateTransferOrderRequest request = CreateTransferOrderRequest.builder()
                    .sourceLocationId(SOURCE_SITE)
                    .destinationLocationId(SOURCE_SITE)
                    .lines(List.of())
                    .build();

            assertThatThrownBy(() -> service.createTransferOrder(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be different sites");
        }

        @Test
        void refusesAnUnknownSite() {
            when(locationRefRepository.findByLocationId(SOURCE_SITE)).thenReturn(Optional.empty());
            CreateTransferOrderRequest request = createRequest();

            assertThatThrownBy(() -> service.createTransferOrder(request))
                    .isInstanceOf(LocationNotFoundException.class);
        }

        @Test
        void refusesASiteThatIsNotActive() {
            stubSite(DESTINATION_SITE, "INACTIVE");
            CreateTransferOrderRequest request = createRequest();

            assertThatThrownBy(() -> service.createTransferOrder(request))
                    .isInstanceOf(TransferLocationNotEligibleException.class);
        }

        @Test
        void refusesABinThatBelongsToADifferentSite() {
            ExtStorageLocationReplica bin = new ExtStorageLocationReplica();
            bin.setStorageLocationId(SOURCE_BIN);
            bin.setSiteId(DESTINATION_SITE);
            when(storageLocationRepository.findById(SOURCE_BIN)).thenReturn(Optional.of(bin));
            CreateTransferOrderRequest request = CreateTransferOrderRequest.builder()
                    .sourceLocationId(SOURCE_SITE)
                    .sourceStorageLocationId(SOURCE_BIN)
                    .destinationLocationId(DESTINATION_SITE)
                    .lines(List.of())
                    .build();

            assertThatThrownBy(() -> service.createTransferOrder(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belongs to site");
        }

        @Test
        void acceptsABinTheReplicaDoesNotKnowYet() {
            CreateTransferOrderRequest request = CreateTransferOrderRequest.builder()
                    .sourceLocationId(SOURCE_SITE)
                    .sourceStorageLocationId(SOURCE_BIN)
                    .destinationLocationId(DESTINATION_SITE)
                    .lines(List.of(TransferOrderLineRequest.builder()
                            .sku(SKU)
                            .requestedQty(10)
                            .build()))
                    .build();

            assertThat(service.createTransferOrder(request).getSourceStorageLocationId())
                    .isEqualTo(SOURCE_BIN);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void readsOneOrderById() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);

            assertThat(service.getTransferOrder(ORDER_ID).getTransferOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        void failsForAnUnknownOrder() {
            when(transferOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTransferOrder(ORDER_ID))
                    .isInstanceOf(TransferOrderNotFoundException.class);
        }

        @Test
        void listsOrdersNewestFirstWithEveryFilterApplied() {
            TransferOrder existing = TransferOrder.builder()
                    .transferOrderId(ORDER_ID)
                    .sourceLocationId(SOURCE_SITE)
                    .destinationLocationId(DESTINATION_SITE)
                    .status(TransferOrderStatus.DRAFT)
                    .lines(new ArrayList<>())
                    .build();
            when(transferOrderRepository.findAll(
                            any(org.springframework.data.jpa.domain.Specification.class), any(Sort.class)))
                    .thenReturn(List.of(existing));

            assertThat(service.listTransferOrders(TransferOrderStatus.DRAFT, SOURCE_SITE, DESTINATION_SITE))
                    .hasSize(1);
        }

        @Test
        void listsOrdersWithNoFiltersAtAll() {
            when(transferOrderRepository.findAll(
                            any(org.springframework.data.jpa.domain.Specification.class), any(Sort.class)))
                    .thenReturn(List.of());

            assertThat(service.listTransferOrders(null, null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("approve and cancel")
    class ApproveAndCancel {

        @Test
        void refusesToApproveWhenTheApprovalStepIsDisabled() {
            assertThatThrownBy(() -> service.approveTransferOrder(ORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("approval step is disabled");
        }

        @Test
        void approvesADraftOrderWhenTheStepIsEnabled() {
            TransferOrderServiceImpl approving = newService(true);
            TransferOrder draft = order(TransferOrderStatus.DRAFT, 10, 0, 0);

            TransferOrderResponse response = approving.approveTransferOrder(ORDER_ID);

            assertThat(draft.getStatus()).isEqualTo(TransferOrderStatus.APPROVED);
            assertThat(response.getApprovedBy()).isEqualTo("system");
        }

        @Test
        void refusesToApproveAnOrderThatIsNoLongerADraft() {
            TransferOrderServiceImpl approving = newService(true);
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            assertThatThrownBy(() -> approving.approveTransferOrder(ORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot approve");
        }

        @Test
        void cancelsAnOrderThatHasNotMovedStock() {
            TransferOrder draft = order(TransferOrderStatus.DRAFT, 10, 0, 0);

            assertThat(service.cancelTransferOrder(ORDER_ID).getStatus()).isEqualTo(TransferOrderStatus.CANCELLED);
            assertThat(draft.getStatus()).isEqualTo(TransferOrderStatus.CANCELLED);
        }

        @Test
        void refusesToCancelOnceStockHasBeenDispatched() {
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            assertThatThrownBy(() -> service.cancelTransferOrder(ORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("short-close");
        }
    }

    @Nested
    @DisplayName("dispatchTransferOrder")
    class DispatchTransferOrder {

        @Test
        void postsATransferOutForTheFullRequestedQuantityByDefault() {
            TransferOrder draft = order(TransferOrderStatus.DRAFT, 10, 0, 0);

            TransferOrderResponse response = service.dispatchTransferOrder(
                    ORDER_ID, DispatchTransferOrderRequest.builder().build());

            List<InventoryLedgerEntry> entries = postedEntries();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_OUT);
            assertThat(entries.get(0).getChangeInQuantity()).isEqualTo(-10);
            assertThat(entries.get(0).getQuantityAfter()).isEqualTo(40);
            assertThat(entries.get(0).getLocationId()).isEqualTo(SOURCE_SITE);
            assertThat(entries.get(0).getToLocationId()).isEqualTo(DESTINATION_SITE);
            assertThat(entries.get(0).getSourceTransactionId()).isEqualTo(ORDER_ID.toString());
            assertThat(draft.getLines().get(0).getDispatchedQty()).isEqualTo(10);
            assertThat(response.getStatus()).isEqualTo(TransferOrderStatus.DISPATCHED);
            verify(inventoryFactPublisher).markEntries(entries);
        }

        @Test
        void dispatchesAnExplicitPartialQuantity() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);

            service.dispatchTransferOrder(
                    ORDER_ID,
                    DispatchTransferOrderRequest.builder()
                            .lines(List.of(TransferQuantityLineRequest.builder()
                                    .lineId(LINE_ID)
                                    .quantity(4)
                                    .build()))
                            .build());

            assertThat(postedEntries().get(0).getChangeInQuantity()).isEqualTo(-4);
        }

        @Test
        void pinsTheResolvedLotOnTheLineSoBothEndsPostTheSameLot() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            when(lotOutboundService.resolveOutboundLot(SKU, "LOT-A")).thenReturn(LOT_ID);

            TransferOrderResponse response = service.dispatchTransferOrder(
                    ORDER_ID,
                    DispatchTransferOrderRequest.builder()
                            .lines(List.of(TransferQuantityLineRequest.builder()
                                    .lineId(LINE_ID)
                                    .quantity(10)
                                    .lotNumber("LOT-A")
                                    .build()))
                            .build());

            assertThat(response.getLines().get(0).getLotId()).isEqualTo(LOT_ID);
            assertThat(response.getLines().get(0).getLotNumber()).isEqualTo("LOT-A");
            assertThat(postedEntries().get(0).getLotId()).isEqualTo(LOT_ID);
        }

        @Test
        void refusesToDispatchMoreThanWasRequested() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            DispatchTransferOrderRequest request = DispatchTransferOrderRequest.builder()
                    .lines(List.of(TransferQuantityLineRequest.builder()
                            .lineId(LINE_ID)
                            .quantity(11)
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.dispatchTransferOrder(ORDER_ID, request))
                    .isInstanceOf(TransferQuantityExceededException.class);
            verify(ledgerPostingService, never()).postAll(any());
        }

        @Test
        void refusesAnUnknownLineIdInTheRequest() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            DispatchTransferOrderRequest request = DispatchTransferOrderRequest.builder()
                    .lines(List.of(TransferQuantityLineRequest.builder()
                            .lineId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1aff"))
                            .quantity(1)
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.dispatchTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown transfer order line");
        }

        @Test
        void refusesADuplicateLineIdInTheRequest() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            TransferQuantityLineRequest line = TransferQuantityLineRequest.builder()
                    .lineId(LINE_ID)
                    .quantity(1)
                    .build();
            DispatchTransferOrderRequest request = DispatchTransferOrderRequest.builder()
                    .lines(List.of(line, line))
                    .build();

            assertThatThrownBy(() -> service.dispatchTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate transfer order line");
        }

        @Test
        void refusesToDispatchAnOrderInTheWrongStatus() {
            order(TransferOrderStatus.RECEIVED, 10, 10, 10);
            DispatchTransferOrderRequest request =
                    DispatchTransferOrderRequest.builder().build();

            assertThatThrownBy(() -> service.dispatchTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot dispatch");
        }

        @Test
        void tellsTheCallerToApproveFirstWhenTheApprovalStepIsOn() {
            TransferOrderServiceImpl approving = newService(true);
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            DispatchTransferOrderRequest request =
                    DispatchTransferOrderRequest.builder().build();

            assertThatThrownBy(() -> approving.dispatchTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("approval is required before dispatch");
        }

        @Test
        void postsAgainstTheBinWhenTheOrderPinsOne() {
            TransferOrder draft = order(TransferOrderStatus.DRAFT, 10, 0, 0);
            draft.setSourceStorageLocationId(SOURCE_BIN);

            service.dispatchTransferOrder(
                    ORDER_ID, DispatchTransferOrderRequest.builder().build());

            assertThat(postedEntries().get(0).getLocationId()).isEqualTo(SOURCE_BIN);
        }
    }

    @Nested
    @DisplayName("receiveTransferOrder")
    class ReceiveTransferOrder {

        @Test
        void postsATransferInForTheWholeOutstandingQuantity() {
            TransferOrder dispatched = order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            TransferOrderResponse response = service.receiveTransferOrder(
                    ORDER_ID, ReceiveTransferOrderRequest.builder().build());

            List<InventoryLedgerEntry> entries = postedEntries();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
            assertThat(entries.get(0).getChangeInQuantity()).isEqualTo(10);
            assertThat(entries.get(0).getLocationId()).isEqualTo(DESTINATION_SITE);
            assertThat(dispatched.getLines().get(0).getReceivedQty()).isEqualTo(10);
            assertThat(response.getStatus()).isEqualTo(TransferOrderStatus.RECEIVED);
        }

        @Test
        void marksThePartialReceiptWithoutClosingTheOrder() {
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            TransferOrderResponse response = service.receiveTransferOrder(
                    ORDER_ID,
                    ReceiveTransferOrderRequest.builder()
                            .lines(List.of(TransferQuantityLineRequest.builder()
                                    .lineId(LINE_ID)
                                    .quantity(6)
                                    .build()))
                            .build());

            assertThat(response.getStatus()).isEqualTo(TransferOrderStatus.PARTIALLY_RECEIVED);
            assertThat(response.getLines().get(0).getReceivedQty()).isEqualTo(6);
        }

        @Test
        void postsNothingForALineWithNothingLeftToReceive() {
            order(TransferOrderStatus.PARTIALLY_RECEIVED, 10, 10, 10);

            service.receiveTransferOrder(
                    ORDER_ID, ReceiveTransferOrderRequest.builder().build());

            assertThat(postedEntries()).isEmpty();
        }

        @Test
        void refusesToReceiveMoreThanIsInTransit() {
            order(TransferOrderStatus.DISPATCHED, 10, 10, 8);
            ReceiveTransferOrderRequest request = ReceiveTransferOrderRequest.builder()
                    .lines(List.of(TransferQuantityLineRequest.builder()
                            .lineId(LINE_ID)
                            .quantity(3)
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.receiveTransferOrder(ORDER_ID, request))
                    .isInstanceOf(TransferQuantityExceededException.class);
        }

        @Test
        void refusesToReceiveAnOrderThatWasNeverDispatched() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            ReceiveTransferOrderRequest request =
                    ReceiveTransferOrderRequest.builder().build();

            assertThatThrownBy(() -> service.receiveTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot receive");
        }
    }

    @Nested
    @DisplayName("shortCloseTransferOrder")
    class ShortCloseTransferOrder {

        private ShortCloseTransferOrderRequest shortClose(TransferShortCloseDisposition disposition) {
            return ShortCloseTransferOrderRequest.builder()
                    .disposition(disposition)
                    .reason("carrier loss")
                    .notes("claim filed")
                    .build();
        }

        @Test
        void writesOffALostRemainderAsAConstructiveArrivalPairedWithAScrap() {
            when(ledgerRepository.findLatestUnitCostByStockItemAndEventType(any(), any(), any()))
                    .thenReturn(List.of(new BigDecimal("12.50")));
            TransferOrder dispatched = order(TransferOrderStatus.DISPATCHED, 10, 10, 4);

            TransferOrderResponse response = service.shortCloseTransferOrder(
                    ORDER_ID, shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT));

            List<InventoryLedgerEntry> entries = postedEntries();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
            assertThat(entries.get(0).getChangeInQuantity()).isEqualTo(6);
            assertThat(entries.get(1).getEventType()).isEqualTo(InventoryLedgerEventType.SCRAP_OUT);
            assertThat(entries.get(1).getChangeInQuantity()).isEqualTo(-6);
            assertThat(entries.get(1).getReasonCode()).isEqualTo(ScrapReasonCode.LOST.name());
            assertThat(entries.get(1).getUnitCost()).isEqualByComparingTo("12.50");
            assertThat(entries.get(1).getNotes()).isEqualTo("claim filed");
            assertThat(dispatched.getShortClosedAt()).isEqualTo(NOW);
            assertThat(response.getStatus()).isEqualTo(TransferOrderStatus.SHORT_CLOSED);
            assertThat(response.getShortCloseReason()).isEqualTo("carrier loss");
        }

        @Test
        void fallsBackToTheLatestKnownUnitCostWhenThereIsNoGoodsReceipt() {
            when(ledgerRepository.findLatestUnitCostByStockItemAndEventType(any(), any(), any()))
                    .thenReturn(List.of());
            when(ledgerRepository.findLatestUnitCostByStockItem(any(), any()))
                    .thenReturn(List.of(new BigDecimal("9.00")));
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            service.shortCloseTransferOrder(ORDER_ID, shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT));

            assertThat(postedEntries().get(1).getUnitCost()).isEqualByComparingTo("9.00");
        }

        @Test
        void leavesTheScrapCostUnsetWhenTheSkuHasNoCostHistory() {
            when(ledgerRepository.findLatestUnitCostByStockItemAndEventType(any(), any(), any()))
                    .thenReturn(List.of());
            when(ledgerRepository.findLatestUnitCostByStockItem(any(), any())).thenReturn(List.of());
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            service.shortCloseTransferOrder(ORDER_ID, shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT));

            assertThat(postedEntries().get(1).getUnitCost()).isNull();
        }

        @Test
        void returnsTheRemainderToTheSourceAsAThreeEntryShape() {
            order(TransferOrderStatus.PARTIALLY_RECEIVED, 10, 10, 7);

            service.shortCloseTransferOrder(ORDER_ID, shortClose(TransferShortCloseDisposition.RETURNED_TO_SOURCE));

            List<InventoryLedgerEntry> entries = postedEntries();
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
            assertThat(entries.get(0).getLocationId()).isEqualTo(DESTINATION_SITE);
            assertThat(entries.get(1).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_OUT);
            assertThat(entries.get(1).getLocationId()).isEqualTo(DESTINATION_SITE);
            assertThat(entries.get(1).getToLocationId()).isEqualTo(SOURCE_SITE);
            assertThat(entries.get(2).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
            assertThat(entries.get(2).getLocationId()).isEqualTo(SOURCE_SITE);
            assertThat(entries.get(2).getChangeInQuantity()).isEqualTo(3);
        }

        @Test
        void revalidatesTheSourceOnlyForAReturn() {
            stubSite(SOURCE_SITE, "INACTIVE");
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);
            ShortCloseTransferOrderRequest request = shortClose(TransferShortCloseDisposition.RETURNED_TO_SOURCE);

            assertThatThrownBy(() -> service.shortCloseTransferOrder(ORDER_ID, request))
                    .isInstanceOf(TransferLocationNotEligibleException.class);
        }

        @Test
        void resolvesALossEvenWhenTheSourceHasSinceBeenDeactivated() {
            stubSite(SOURCE_SITE, "INACTIVE");
            order(TransferOrderStatus.DISPATCHED, 10, 10, 0);

            assertThat(service.shortCloseTransferOrder(
                                    ORDER_ID, shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT))
                            .getStatus())
                    .isEqualTo(TransferOrderStatus.SHORT_CLOSED);
        }

        @Test
        void refusesToShortCloseAnOrderWithNothingOutstanding() {
            order(TransferOrderStatus.DISPATCHED, 10, 10, 10);
            ShortCloseTransferOrderRequest request = shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT);

            assertThatThrownBy(() -> service.shortCloseTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no outstanding in-transit remainder");
        }

        @Test
        void refusesToShortCloseAnOrderThatWasNeverDispatched() {
            order(TransferOrderStatus.DRAFT, 10, 0, 0);
            ShortCloseTransferOrderRequest request = shortClose(TransferShortCloseDisposition.LOST_IN_TRANSIT);

            assertThatThrownBy(() -> service.shortCloseTransferOrder(ORDER_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot short-close");
        }
    }
}
