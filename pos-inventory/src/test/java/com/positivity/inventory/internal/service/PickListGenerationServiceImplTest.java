package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.picklist.GeneratePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingDecision;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pick-task location suggestion through the sourcing strategy engine
 * (odoo-parity H1/H2, issue #1037): the engine's first ordered candidate
 * becomes {@code suggestedLocationId} and the decision's effective strategy
 * is recorded as {@code sourcingReason} ("why this bin").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PickListGenerationServiceImpl")
class PickListGenerationServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WO_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PRODUCT_ID = UUID.fromString("01960003-0000-7000-8000-0000000000aa");
    private static final UUID LOC_BEST = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID LOC_OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Mock
    private PickListRepository pickListRepository;

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private SourcingStrategyService sourcingStrategyService;

    @Captor
    private ArgumentCaptor<PickTaskEntity> taskCaptor;

    @Captor
    private ArgumentCaptor<SourcingSelection> selectionCaptor;

    private PickListGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PickListGenerationServiceImpl(
                pickListRepository,
                pickTaskRepository,
                reservationRepository,
                stockSummaryRepository,
                sourcingStrategyService,
                mock(InventoryFactPublisher.class));

        when(pickListRepository.save(any())).thenAnswer(invocation -> {
            PickListEntity entity = invocation.getArgument(0);
            entity.setPickListId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
            return entity;
        });
        when(pickTaskRepository.save(any())).thenAnswer(invocation -> {
            PickTaskEntity entity = invocation.getArgument(0);
            entity.setPickTaskId(UUID.fromString("00000000-0000-0000-0000-000000000098"));
            return entity;
        });
        when(stockSummaryRepository.findByStockItemId(any())).thenReturn(List.of());
        when(sourcingStrategyService.orderCandidates(any()))
                .thenReturn(new SourcingDecision(SourcingStrategy.FIFO, SourcingStrategy.FIFO, List.of()));
    }

    private GeneratePickListRequest requestWithOneLine(String sku) {
        return new GeneratePickListRequest(
                WORKORDER_ID, null, 5, List.of(new GeneratePickListRequest.PickLineItem(WO_LINE_ID, null, sku, 4)));
    }

    @Test
    @DisplayName("suggestion recording: engine's first candidate becomes suggestedLocationId, reason is recorded")
    void generatePickList_recordsSuggestionAndSourcingReason() {
        when(stockSummaryRepository.findByStockItemId(PRODUCT_ID.toString()))
                .thenReturn(List.of(
                        summary(LOC_OTHER, 8, 1),
                        summary(LOC_BEST, 5, 0),
                        summary(null, 3, 0), // null-location row is never a candidate
                        summary(UUID.fromString("00000000-0000-0000-0000-0000000000cc"), 2, 2))); // 0 available
        when(sourcingStrategyService.orderCandidates(any()))
                .thenReturn(new SourcingDecision(
                        SourcingStrategy.PROXIMITY,
                        SourcingStrategy.PROXIMITY,
                        List.of(new SourcingCandidate(LOC_BEST, 5L), new SourcingCandidate(LOC_OTHER, 7L))));

        PickListResponse response = service.generatePickList(requestWithOneLine(PRODUCT_ID.toString()));

        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(response.getStatus()).isEqualTo(PickListStatus.DRAFT);

        verify(pickTaskRepository).save(taskCaptor.capture());
        PickTaskEntity task = taskCaptor.getValue();
        assertThat(task.getSuggestedLocationId()).isEqualTo(LOC_BEST);
        assertThat(task.getSourcingReason()).isEqualTo(SourcingStrategy.PROXIMITY);
        assertThat(task.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(task.getSku()).isEqualTo(PRODUCT_ID.toString());
        assertThat(task.getQuantityRequired()).isEqualTo(4);
        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.PENDING);
        assertThat(task.getWorkorderLineId()).isEqualTo(WO_LINE_ID);

        // Only positive-available, located summary rows become candidates.
        verify(sourcingStrategyService).orderCandidates(selectionCaptor.capture());
        assertThat(selectionCaptor.getValue().candidates())
                .extracting(SourcingCandidate::locationId)
                .containsExactlyInAnyOrder(LOC_BEST, LOC_OTHER);
    }

    @Test
    @DisplayName("no available stock → no suggestion and no sourcing reason")
    void generatePickList_withoutStock_leavesSuggestionEmpty() {
        service.generatePickList(requestWithOneLine(PRODUCT_ID.toString()));

        verify(pickTaskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSuggestedLocationId()).isNull();
        assertThat(taskCaptor.getValue().getSourcingReason()).isNull();
    }

    @Test
    @DisplayName("free-text SKU resolves productId through the line's reservation")
    void generatePickList_freeTextSku_resolvesProductIdViaReservation() {
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID))
                .thenReturn(Optional.of(ReservationEntity.builder()
                        .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000042"))
                        .workorderLineId(WO_LINE_ID)
                        .stockItemId(PRODUCT_ID)
                        .build()));

        service.generatePickList(requestWithOneLine("SKU-10042"));

        verify(pickTaskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(taskCaptor.getValue().getSku()).isEqualTo("SKU-10042");
    }

    @Test
    @DisplayName("free-text SKU without a reservation is rejected")
    void generatePickList_freeTextSkuWithoutReservation_throws() {
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.empty());

        GeneratePickListRequest request = requestWithOneLine("SKU-10042");
        assertThatThrownBy(() -> service.generatePickList(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-10042");
    }

    @Test
    @DisplayName("getPickList returns the list or throws ResourceNotFoundException")
    void getPickList_returnsOrThrows() {
        UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000031");
        when(pickListRepository.findById(pickListId))
                .thenReturn(Optional.of(PickListEntity.builder()
                        .pickListId(pickListId)
                        .workorderId(WORKORDER_ID)
                        .status(PickListStatus.DRAFT)
                        .priority(1)
                        .build()));

        assertThat(service.getPickList(pickListId).getPickListId()).isEqualTo(pickListId);

        UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000032");
        when(pickListRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPickList(missing)).isInstanceOf(ResourceNotFoundException.class);
    }

    private InventoryStockSummary summary(UUID locationId, long onHand, long allocated) {
        return InventoryStockSummary.builder()
                .stockItemId(PRODUCT_ID.toString())
                .locationId(locationId)
                .onHand(onHand)
                .allocated(allocated)
                .build();
    }
}
