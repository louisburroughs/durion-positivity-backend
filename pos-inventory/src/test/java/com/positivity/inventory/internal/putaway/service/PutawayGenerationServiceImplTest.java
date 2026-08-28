package com.positivity.inventory.internal.putaway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayLineItemRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoPutawayRuleMatchException;
import com.positivity.inventory.internal.exception.ReceiptNotStagedException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.ProximitySourcingStrategy;
import com.positivity.inventory.internal.service.PutawayRuleMatcher;
import com.positivity.inventory.internal.service.SkuCategoryLookup;
import com.positivity.inventory.internal.service.SkuCategoryLookup.SkuCategoryRef;
import com.positivity.inventory.internal.service.StagingLocationResolver;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PutawayGenerationServiceImplTest {
    private static final UUID DEST_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DEST_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID STAGING_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TIRES_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000001");
    private static final UUID FLUIDS_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000007");
    private static final UUID TIRE_PRODUCT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID OIL_PRODUCT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004");

    @Mock
    private PutawayRuleRepository putawayRuleRepository;

    @Mock
    private PutawayTaskRepository putawayTaskRepository;

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @Mock
    private ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    @Mock
    private ProximitySourcingStrategy proximitySourcingStrategy;

    @Mock
    private PutawayValidationService putawayValidationService;

    @Mock
    private StagingLocationResolver stagingLocationResolver;

    @Mock
    private SkuCategoryLookup skuCategoryLookup;

    private PutawayGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        PutawayDestinationResolver destinationResolver = new PutawayDestinationResolver(
                putawayTaskRepository,
                extStorageLocationReplicaRepository,
                proximitySourcingStrategy,
                putawayValidationService);
        service = new PutawayGenerationServiceImpl(
                new PutawayRuleMatcher(putawayRuleRepository, skuCategoryLookup),
                putawayTaskRepository,
                goodsReceiptRepository,
                destinationResolver,
                stagingLocationResolver,
                putawayValidationService);
        lenient()
                .when(skuCategoryLookup.categoryRefOfAll(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of());
        lenient()
                .when(goodsReceiptRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(receipt(inv.getArgument(0))));
        lenient().when(stagingLocationResolver.resolveStagingLocationId()).thenReturn(STAGING_LOCATION);
    }

    @Test
    void generateTasksForReceipt_withRules_createsUnassignedTask() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(anyRule(DEST_A)));
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(1);
        PutawayTaskResponse response = responses.get(0);
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.UNASSIGNED.toString());
        assertThat(response.getSuggestedDestinationLocationId()).isEqualTo(DEST_A);
        assertThat(response.getSourceLocationId()).isEqualTo(STAGING_LOCATION);
    }

    @Test
    void generateTasksForReceipt_lastUsedRule_suggestsMostRecentlyUsedBin() {
        UUID lastUsedBin = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();
        PutawayRule rule = PutawayRule.builder()
                .priority(1)
                .matchType(PutawayRuleMatchType.ANY)
                .destinationLocationId(DEST_A)
                .destinationStrategy(com.positivity.inventory.internal.enums.PutawayDestinationStrategy.LAST_USED)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(rule));
        when(putawayTaskRepository
                        .findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
                                any(UUID.class), eq(PutawayTaskStatus.COMPLETED)))
                .thenReturn(Optional.of(PutawayTask.builder()
                        .actualDestinationLocationId(lastUsedBin)
                        .status(PutawayTaskStatus.COMPLETED)
                        .build()));
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSuggestedDestinationLocationId()).isEqualTo(lastUsedBin);
        assertThat(responses.get(0).getFallbackReason()).isNull();
    }

    @Test
    void generateTasksForReceipt_lastUsedRuleNeverUsed_fallsBackToFixedWithReason() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();
        PutawayRule rule = PutawayRule.builder()
                .priority(1)
                .matchType(PutawayRuleMatchType.ANY)
                .destinationLocationId(DEST_A)
                .destinationStrategy(com.positivity.inventory.internal.enums.PutawayDestinationStrategy.LAST_USED)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(rule));
        when(putawayTaskRepository
                        .findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
                                any(UUID.class), eq(PutawayTaskStatus.COMPLETED)))
                .thenReturn(Optional.empty());
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSuggestedDestinationLocationId()).isEqualTo(DEST_A);
        assertThat(responses.get(0).getFallbackReason())
                .isEqualTo(com.positivity.inventory.internal.enums.PutawayFallbackReason.UNAVAILABLE.name());
    }

    @Test
    void generateTasksForReceipt_noRules_raisesAConfigurationErrorRatherThanRoutingAtAFakeBin() {
        // Pre-#1514 this produced a task pointing at a hardcoded
        // 00000000-0000-0000-0000-000000000001 "default location" that no environment has, so the
        // failure surfaced later at execution against a bin that does not exist.
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(NoPutawayRuleMatchException.class);

        verify(putawayTaskRepository, never()).saveAll(anyList());
    }

    @Test
    void generateTasksForReceipt_multipleLines_resolvesADestinationPerLine() {
        // The regression that proves the old findAll...get(0) bug is gone: one receipt, two lines,
        // two different bins because the lines are different kinds of thing.
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(receiptId.toString())
                .lineItems(List.of(
                        PutawayLineItemRequest.builder()
                                .productId(TIRE_PRODUCT.toString())
                                .quantity(4)
                                .build(),
                        PutawayLineItemRequest.builder()
                                .productId(OIL_PRODUCT.toString())
                                .quantity(12)
                                .build()))
                .build();

        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(categoryRule(1, TIRES_CATEGORY, DEST_A), categoryRule(2, FLUIDS_CATEGORY, DEST_B)));
        when(skuCategoryLookup.categoryRefOfAll(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of(
                        TIRE_PRODUCT.toString(),
                        new SkuCategoryRef(TIRES_CATEGORY, "Tires & Wheels", null, null),
                        OIL_PRODUCT.toString(),
                        new SkuCategoryRef(FLUIDS_CATEGORY, "Fluids & Chemicals", null, null)));
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses)
                .extracting(PutawayTaskResponse::getSuggestedDestinationLocationId)
                .containsExactly(DEST_A, DEST_B);
    }

    @Test
    void generateTasksForReceipt_brandNewUncategorisedSku_landsViaTheAnyRule() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(TIRE_PRODUCT.toString())
                .quantity(5)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(categoryRule(1, FLUIDS_CATEGORY, DEST_B), anyRule(DEST_A)));
        // The replica has never heard of this product.
        when(skuCategoryLookup.categoryRefOfAll(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of());
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.generateTasksForReceipt(request))
                .singleElement()
                .extracting(PutawayTaskResponse::getSuggestedDestinationLocationId)
                .isEqualTo(DEST_A);
    }

    private static PutawayRule anyRule(UUID destination) {
        return PutawayRule.builder()
                .ruleId(UUID.randomUUID())
                .priority(100)
                .matchType(PutawayRuleMatchType.ANY)
                .destinationLocationId(destination)
                .isEnabled(true)
                .build();
    }

    private static PutawayRule categoryRule(int priority, UUID categoryId, UUID destination) {
        return PutawayRule.builder()
                .ruleId(UUID.randomUUID())
                .priority(priority)
                .matchType(PutawayRuleMatchType.CATEGORY)
                .matchValue(categoryId.toString())
                .destinationLocationId(destination)
                .isEnabled(true)
                .build();
    }

    @Test
    void generateTasksForReceipt_withLineItems_createsTaskPerLineItem() {
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(receiptId.toString())
                .lineItems(List.of(
                        PutawayLineItemRequest.builder()
                                .productId(productId1.toString())
                                .quantity(5)
                                .build(),
                        PutawayLineItemRequest.builder()
                                .productId(productId2.toString())
                                .quantity(3)
                                .build()))
                .build();

        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(anyRule(DEST_A)));
        when(putawayTaskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(2);
        assertThat(responses)
                .extracting(PutawayTaskResponse::getProductId)
                .containsExactly(productId1.toString(), productId2.toString());
        assertThat(responses).extracting(PutawayTaskResponse::getQuantity).containsExactly(5, 3);
        assertThat(responses)
                .extracting(PutawayTaskResponse::getSourceReceiptId)
                .containsExactly(receiptId.toString(), receiptId.toString());
    }

    @Test
    void generateTasksForReceipt_withLineItemsAndLegacyFields_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .lineItems(List.of(PutawayLineItemRequest.builder()
                        .productId(UUID.fromString("00000000-0000-0000-0000-000000000001")
                                .toString())
                        .quantity(2)
                        .build()))
                .build();

        // No rule stub: line-item parsing runs before the rule lookup, so this never reaches it.
        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Provide either lineItems or productId/quantity, not both");
    }

    @Test
    void getAvailableTasks_returnsUnassignedTasks() {
        PutawayTask task = new PutawayTask();
        task.setTaskId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        task.setStatus(PutawayTaskStatus.UNASSIGNED);
        when(putawayTaskRepository.findByStatusIn(List.of(PutawayTaskStatus.UNASSIGNED)))
                .thenReturn(List.of(task));

        List<PutawayTaskResponse> responses = service.getAvailableTasks(null, null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getTaskId()).isEqualTo(task.getTaskId().toString());
    }

    @Test
    void claimTask_validTask_updatesStatusAndAssignee() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String userId = "user-123";
        PutawayTask task = new PutawayTask();
        task.setTaskId(taskId);
        task.setStatus(PutawayTaskStatus.UNASSIGNED);

        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(putawayTaskRepository.save(any(PutawayTask.class))).thenAnswer(inv -> inv.getArgument(0));

        PutawayTaskResponse response = service.claimTask(taskId.toString(), userId);

        assertThat(response.getTaskId()).isEqualTo(taskId.toString());
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.ASSIGNED.toString());
        assertThat(response.getAssigneeId()).isEqualTo(userId);
    }

    @Test
    void claimTask_invalidTask_throwsTaskNotFoundException() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String taskIdString = taskId.toString();
        String userId = "user-123";
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claimTask(taskIdString, userId)).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getTasksByReceiptId_returnsFilteredList() {
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PutawayTask task = new PutawayTask();
        task.setTaskId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        task.setSourceReceipt(receipt(receiptId));

        when(putawayTaskRepository.findBySourceReceipt_ReceiptId(receiptId)).thenReturn(List.of(task));

        List<PutawayTaskResponse> responses = service.getTasksByReceiptId(receiptId.toString());

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSourceReceiptId()).isEqualTo(receiptId.toString());
    }

    @Test
    void generateTasksForReceipt_withInvalidUuid_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId("invalid-uuid")
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceReceiptId must be a valid UUID");
    }

    @Test
    void generateTasksForReceipt_withNullUuid_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(null)
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceReceiptId is required");
    }

    @Test
    void generateTasksForReceipt_withNoLineItemsAndNoLegacyFields_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .build();

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Either lineItems or productId/quantity is required");
    }

    @Test
    void generateTasksForReceipt_receiptNotAtStagingLocation_throwsReceiptNotStagedException() {
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID directOnHandLocation = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
        GoodsReceiptEntity receipt = new GoodsReceiptEntity();
        receipt.setReceiptId(receiptId);
        receipt.setLocationId(directOnHandLocation);
        when(goodsReceiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(receiptId.toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(ReceiptNotStagedException.class)
                .hasMessageContaining(directOnHandLocation.toString())
                .hasMessageContaining(STAGING_LOCATION.toString());
    }

    @Test
    void generateTasksForReceipt_destinationInvalidForSku_throwsLocationNotValidForSkuException() {
        GeneratePutawayTasksRequest request = GeneratePutawayTasksRequest.builder()
                .sourceReceiptId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .productId(
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .quantity(5)
                .build();
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(anyRule(DEST_A)));
        doThrow(new LocationNotValidForSkuException(
                        DEST_A, "sku", "OIL_STORAGE does not accept catalog class Tires & Wheels"))
                .when(putawayValidationService)
                .validateLocationCompatibility(any(UUID.class), any(String.class));

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(LocationNotValidForSkuException.class);

        verify(putawayTaskRepository, never()).saveAll(anyList());
    }

    private GoodsReceiptEntity receipt(UUID receiptId) {
        GoodsReceiptEntity receipt = new GoodsReceiptEntity();
        receipt.setReceiptId(receiptId);
        receipt.setLocationId(STAGING_LOCATION);
        return receipt;
    }
}
