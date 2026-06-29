package com.positivity.workorder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.workorder.internal.client.LocationClient;
import com.positivity.workorder.internal.client.PeopleLocationClient;
import com.positivity.workorder.internal.client.TaxClient;
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
import com.positivity.workorder.internal.service.EstimateServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private LocationClient locationClient;

    @Mock
    private PeopleLocationClient peopleLocationClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private EstimateServiceImpl estimateService;

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final UUID ESTIMATE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONFIG_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ITEM_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID LOCAL_CUSTOMER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID LOCAL_VEHICLE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private Estimate estimate;
    private UUID customerId;
    private UUID vehicleId;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(estimateService, "clock", clock);

        customerId = CUSTOMER_ID;
        vehicleId = VEHICLE_ID;
        estimate = Estimate.builder()
                .id(ESTIMATE_ID)
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
        when(estimateRepository.findByCustomerId(any(UUID.class), any(Pageable.class)))
                .thenReturn(pagedEstimates);

        Page<EstimateSummaryResponse> result = estimateService.searchEstimates(customerId, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(customerId, result.getContent().get(0).getCustomerId());
    }

    @Test
    void searchEstimates_byVehicleId_returnsMatching() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Estimate> pagedEstimates = new PageImpl<>(List.of(estimate), pageable, 1);
        when(estimateRepository.findByVehicleId(any(UUID.class), any(Pageable.class)))
                .thenReturn(pagedEstimates);

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
        String username = "testuser";
        CreateEstimateRequest request = CreateEstimateRequest.builder()
                .customerId(LOCAL_CUSTOMER_ID)
                .vehicleId(LOCAL_VEHICLE_ID)
                .locationId(UUID.fromString("01960003-0000-7000-8000-000000000001"))
                .build();
        ApprovalConfiguration config =
                ApprovalConfiguration.builder().id(CONFIG_ID).build();
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any()))
                .thenReturn(List.of(config));
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(i -> i.getArgument(0));

        EstimateResponse result = estimateService.createEstimate(request, username);

        assertEquals(LOCAL_CUSTOMER_ID, result.getCustomerId());
        assertEquals(LOCAL_VEHICLE_ID, result.getVehicleId());
        assertEquals(EstimateStatus.DRAFT.toString(), result.getStatus());
    }

    @Test
    void calculateEstimateTaxesAndTotals_sendsResolvedShopLocationAddressToTax() {
        UUID locationId = UUID.fromString("01960003-0000-7000-8000-000000000001");
        Estimate draft = Estimate.builder()
                .id(ESTIMATE_ID)
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .locationId(locationId)
                .status(EstimateStatus.DRAFT)
                .build();
        EstimateItem item = EstimateItem.builder()
                .id(ITEM_ID)
                .estimate(draft)
                .itemType(EstimateItemType.PART)
                .description("Brake pad")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("25.00"))
                .build();

        TaxAddress resolved = TaxAddress.builder()
                .countryCode("US")
                .regionCode("WA")
                .city("Seattle")
                .postalCode("98101")
                .line1("123 Pike St")
                .build();

        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(draft));
        when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(ESTIMATE_ID))
                .thenReturn(List.of(item));
        when(locationClient.resolveTaxAddress(locationId)).thenReturn(resolved);
        when(taxClient.calculateTax(any(TaxCalculationRequest.class)))
                .thenReturn(TaxCalculationResponse.builder()
                        .subtotal(new BigDecimal("50.00"))
                        .totalTax(new BigDecimal("4.50"))
                        .total(new BigDecimal("54.50"))
                        .testMode(false)
                        .build());
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(i -> i.getArgument(0));

        estimateService.calculateEstimateTaxesAndTotals(ESTIMATE_ID, "testuser");

        ArgumentCaptor<TaxCalculationRequest> captor = ArgumentCaptor.forClass(TaxCalculationRequest.class);
        verify(taxClient).calculateTax(captor.capture());
        TaxAddress sent = captor.getValue().getDestinationAddress();
        assertEquals("US", sent.getCountryCode());
        assertEquals("WA", sent.getRegionCode());
        assertEquals("98101", sent.getPostalCode());
        assertEquals("Seattle", sent.getCity());
    }

    @Test
    void createEstimate_noLocationAndNoPrimaryLocation_throwsException() {
        // No request location and the creator has no primary location to derive one from:
        // reject rather than fabricating a placeholder location that has no staffing.
        CreateEstimateRequest request = CreateEstimateRequest.builder()
                .customerId(LOCAL_CUSTOMER_ID)
                .vehicleId(LOCAL_VEHICLE_ID)
                .build();
        when(peopleLocationClient.resolveCurrentUserPrimaryLocation()).thenReturn(java.util.Optional.empty());

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> estimateService.createEstimate(request, "testuser"));
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("locationId is required"));
    }

    @Test
    void createEstimate_missingCustomerId_throwsException() {
        CreateEstimateRequest request =
                CreateEstimateRequest.builder().vehicleId(LOCAL_VEHICLE_ID).build();
        String username = "testuser";

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> estimateService.createEstimate(request, username));
        assertEquals("customerId is required", exception.getMessage());
    }

    @Test
    void createEstimate_missingVehicleId_throwsException() {
        CreateEstimateRequest request =
                CreateEstimateRequest.builder().customerId(LOCAL_CUSTOMER_ID).build();
        String username = "testuser";

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> estimateService.createEstimate(request, username));
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

        EstimateResponse result = estimateService.declineEstimate(estimate.getId(), "reason");

        assertEquals(EstimateStatus.DECLINED.toString(), result.getStatus());
    }

    @Test
    void addEstimateItem_valid_addsItem() {
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
        AddEstimateItemRequest request = new AddEstimateItemRequest(
                EstimateItemType.PART,
                "description",
                BigDecimal.ONE,
                BigDecimal.TEN,
                "taxCode",
                LOCAL_VEHICLE_ID,
                null);

        EstimateItemResponse result = estimateService.addEstimateItem(estimate.getId(), request, "testuser");

        assertEquals("description", result.getDescription());
    }

    @Test
    void updateEstimateItem_valid_updatesItem() {
        EstimateItem item =
                EstimateItem.builder().id(ITEM_ID).estimate(estimate).build();
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.findByIdAndEstimate_IdAndDeletedFalse(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(item));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
        UpdateEstimateItemRequest request = new UpdateEstimateItemRequest("new description", null, null, null);

        EstimateItemResponse result = estimateService.updateEstimateItem(estimate.getId(), item.getId(), request);

        assertEquals("new description", result.getDescription());
    }

    @Test
    void deleteEstimateItem_valid_deletesItem() {
        EstimateItem item =
                EstimateItem.builder().id(ITEM_ID).estimate(estimate).build();
        when(estimateRepository.findById(any(UUID.class))).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.findByIdAndEstimate_IdAndDeletedFalse(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(item));

        estimateService.deleteEstimateItem(estimate.getId(), item.getId());

        assertEquals(true, item.getDeleted());
    }
}
