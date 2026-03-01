package com.positivity.workorder.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateItemResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.BillingRulesClientService;
import com.positivity.workorder.internal.client.TaxClient;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class EstimateServiceImplTest {

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private EstimateSnapshotRepository estimateSnapshotRepository;

    @Mock
    private ApprovalConfigurationRepository approvalConfigurationRepository;

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BillingRulesClientService billingRulesClientService;

    @Mock
    private TaxClient taxClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private EstimateServiceImpl estimateService;

    private Estimate estimate;
    private UUID customerId;
    private UUID vehicleId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        estimate = Estimate.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .vehicleId(vehicleId)
                .status(EstimateStatus.DRAFT)
                .build();
    }

    @Test
    void searchEstimates_noFilters_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Estimate> pagedEstimates = new PageImpl<>(List.of(estimate), pageable, 1);
        when(estimateRepository.findAll(any(Pageable.class))).thenReturn(pagedEstimates);

        Page<EstimateSummaryResponse> result = estimateService.searchEstimates(null, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(estimate.getId(), result.getContent().get(0).getId());
    }

    @Test
    void searchEstimates_byCustomerId_returnsMatching() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Estimate> pagedEstimates = new PageImpl<>(List.of(estimate), pageable, 1);
        when(estimateRepository.findByCustomerId(any(UUID.class), any(Pageable.class))).thenReturn(pagedEstimates);

        Page<EstimateSummaryResponse> result = estimateService.searchEstimates(customerId, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(customerId, result.getContent().get(0).getCustomerId());
    }

    @Test
    void searchEstimates_byVehicleId_returnsMatching() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Estimate> pagedEstimates = new PageImpl<>(List.of(estimate), pageable, 1);
        when(estimateRepository.findByVehicleId(any(UUID.class), any(Pageable.class))).thenReturn(pagedEstimates);

        Page<EstimateSummaryResponse> result = estimateService.searchEstimates(null, vehicleId, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(vehicleId, result.getContent().get(0).getVehicleId());
    }

    @Test
    void searchEstimates_byCustomerIdAndVehicleId_returnsMatching() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Estimate> pagedEstimates = new PageImpl<>(List.of(estimate), pageable, 1);
        when(estimateRepository.findByCustomerIdAndVehicleId(any(UUID.class), any(UUID.class), any(Pageable.class)))
                .thenReturn(pagedEstimates);

        Page<EstimateSummaryResponse> result = estimateService.searchEstimates(customerId, vehicleId, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(customerId, result.getContent().get(0).getCustomerId());
        assertEquals(vehicleId, result.getContent().get(0).getVehicleId());
    }

    @Test
    void createEstimate_validRequest_createsEstimate() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        String username = "testuser";
        CreateEstimateRequest request = new CreateEstimateRequest(customerId, vehicleId, null, null, null);
        ApprovalConfiguration config = ApprovalConfiguration.builder().id(UUID.randomUUID()).build();
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any())).thenReturn(List.of(config));
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(i -> i.getArgument(0));

        EstimateResponse result = estimateService.createEstimate(request, username);

        assertEquals(customerId, result.getCustomerId());
        assertEquals(vehicleId, result.getVehicleId());
        assertEquals(EstimateStatus.DRAFT.toString(), result.getStatus());
    }

    @Test
    void createEstimate_missingCustomerId_throwsException() {
        CreateEstimateRequest request = new CreateEstimateRequest(null, UUID.randomUUID(), null, null, null);
        String username = "testuser";

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> estimateService.createEstimate(request, username));
        assertEquals("customerId is required", exception.getMessage());
    }

    @Test
    void createEstimate_missingVehicleId_throwsException() {
        CreateEstimateRequest request = new CreateEstimateRequest(UUID.randomUUID(), null, null, null, null);
        String username = "testuser";

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> estimateService.createEstimate(request, username));
        assertEquals("vehicleId is required", exception.getMessage());
    }

    @Test
    void approveEstimate_valid_approvesEstimate() {
        estimate.setStatus(EstimateStatus.PENDING_APPROVAL);
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(i -> i.getArgument(0));

        EstimateResponse result = estimateService.approveEstimate(estimate.getId(), customerId);

        assertEquals(EstimateStatus.APPROVED.toString(), result.getStatus());
    }

    @Test
    void declineEstimate_valid_declinesEstimate() {
        estimate.setStatus(EstimateStatus.PENDING_APPROVAL);
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(i -> i.getArgument(0));
        ApprovalConfiguration config = ApprovalConfiguration.builder().id(UUID.randomUUID()).declineExpiryDays(30)
                .build();
        // when(approvalConfigurationRepository.findById(any())).thenReturn(Optional.empty());

        EstimateResponse result = estimateService.declineEstimate(estimate.getId(), "reason");

        assertEquals(EstimateStatus.DECLINED.toString(), result.getStatus());
    }

    @Test
    void addEstimateItem_valid_addsItem() {
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
        AddEstimateItemRequest request = new AddEstimateItemRequest(EstimateItemType.PART, "description",
                BigDecimal.ONE, BigDecimal.TEN, "taxCode", UUID.randomUUID(), null);

        EstimateItemResponse result = estimateService.addEstimateItem(estimate.getId(), request, "testuser");

        assertEquals("description", result.getDescription());
    }

    @Test
    void updateEstimateItem_valid_updatesItem() {
        EstimateItem item = EstimateItem.builder().id(UUID.randomUUID()).estimateId(estimate.getId()).build();
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.findByIdAndEstimateIdAndDeletedFalse(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(item));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
        UpdateEstimateItemRequest request = new UpdateEstimateItemRequest("new description", null, null, null);

        EstimateItemResponse result = estimateService.updateEstimateItem(estimate.getId(), item.getId(), request);

        assertEquals("new description", result.getDescription());
    }

    @Test
    void deleteEstimateItem_valid_deletesItem() {
        EstimateItem item = EstimateItem.builder().id(UUID.randomUUID()).estimateId(estimate.getId()).build();
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.findByIdAndEstimateIdAndDeletedFalse(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(item));

        estimateService.deleteEstimateItem(estimate.getId(), item.getId());

        assertEquals(true, item.getDeleted());
    }
}
