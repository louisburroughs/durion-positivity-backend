package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * Guide-time defaulting at estimate-item entry (#1569 Phase 1, sourcing plan §6.3 item 1): the
 * guide answer is always snapshotted as the baseline, becomes the quantity only when the writer
 * sent none, and a guide miss with no explicit quantity is a request problem — never a
 * null-quantity row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Estimate item guide-time defaulting (#1569)")
class EstimateItemGuideDefaultingTest {

    private static final Instant NOW = Instant.parse("2026-09-02T09:00:00Z");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc01");
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc02");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc03");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc04");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05");

    private static final LaborTimeDefaultingService.GuideDefault GUIDE = new LaborTimeDefaultingService.GuideDefault(
            new BigDecimal("1.5"), "MOCKGUIDE", "2026-09-01", "EXACT", "WHEEL-OFF", "BRAKE-PAD-FRONT,BRAKE-HW-KIT");

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
    private LocationReferenceService locationReferenceService;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private DocumentClient documentClient;

    @Mock
    private CustomerReferenceService customerReferenceService;

    @Mock
    private VehicleReferenceService vehicleReferenceService;

    @Mock
    private EstimateFactPublisher estimateFactPublisher;

    @Mock
    private ExtProductUomReplicaRepository productUomRepository;

    @Mock
    private LaborTimeDefaultingService laborTimeDefaultingService;

    private EstimateServiceImpl service;

    @BeforeEach
    void setUp() {
        when(productUomRepository.findBasePrecisionScales(any())).thenReturn(List.of());

        service = new EstimateServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                estimateRepository,
                estimateItemRepository,
                estimateSnapshotRepository,
                approvalConfigurationRepository,
                workOrderRepository,
                eventPublisher,
                billingRulesClientService,
                taxClient,
                locationReferenceService,
                peopleAvailabilityLocalService,
                documentClient,
                new ObjectMapper(),
                customerReferenceService,
                vehicleReferenceService,
                estimateFactPublisher,
                new PartQuantityDivisibilityService(productUomRepository),
                laborTimeDefaultingService);

        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        estimate.setStatus(EstimateStatus.DRAFT);
        estimate.setCustomerId(CUSTOMER_ID);
        estimate.setVehicleId(VEHICLE_ID);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));
        when(estimateItemRepository.save(any(EstimateItem.class))).thenAnswer(i -> i.getArgument(0));
        when(laborTimeDefaultingService.lookupGuideTime(any(), any(), any())).thenReturn(Optional.empty());
    }

    private static AddEstimateItemRequest laborRequest(BigDecimal quantity) {
        return AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .quantity(quantity)
                .unitPrice(new BigDecimal("120.00"))
                .serviceId(SERVICE_ID)
                .build();
    }

    private EstimateItem savedItem() {
        ArgumentCaptor<EstimateItem> captor = ArgumentCaptor.forClass(EstimateItem.class);
        verify(estimateItemRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("omitted quantity on a guide-eligible LABOR line is prefilled from the guide hours")
    void omittedQuantityPrefilledFromGuide() {
        when(laborTimeDefaultingService.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID))
                .thenReturn(Optional.of(GUIDE));

        service.addEstimateItem(ESTIMATE_ID, laborRequest(null), "jane.smith");

        assertThat(savedItem().getQuantity()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("an explicit quantity wins over the guide, but the six snapshot columns still persist")
    void explicitQuantityKeptGuideStillSnapshotted() {
        when(laborTimeDefaultingService.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID))
                .thenReturn(Optional.of(GUIDE));

        service.addEstimateItem(ESTIMATE_ID, laborRequest(new BigDecimal("2.0")), "jane.smith");

        EstimateItem saved = savedItem();
        assertThat(saved.getQuantity()).isEqualByComparingTo("2.0");
        assertThat(saved.getGuideHours()).isEqualByComparingTo("1.5");
        assertThat(saved.getGuideSourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(saved.getGuideSourceRevision()).isEqualTo("2026-09-01");
        assertThat(saved.getGuideMatchGrade()).isEqualTo("EXACT");
        assertThat(saved.getGuideOverlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(saved.getGuideIncludedOpCodes()).isEqualTo("BRAKE-PAD-FRONT,BRAKE-HW-KIT");
    }

    @Test
    @DisplayName("all six snapshot columns persist on a guide-prefilled line")
    void snapshotColumnsPersistOnPrefill() {
        when(laborTimeDefaultingService.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID))
                .thenReturn(Optional.of(GUIDE));

        service.addEstimateItem(ESTIMATE_ID, laborRequest(null), "jane.smith");

        EstimateItem saved = savedItem();
        assertThat(saved.getGuideHours()).isEqualByComparingTo("1.5");
        assertThat(saved.getGuideSourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(saved.getGuideSourceRevision()).isEqualTo("2026-09-01");
        assertThat(saved.getGuideMatchGrade()).isEqualTo("EXACT");
        assertThat(saved.getGuideOverlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(saved.getGuideIncludedOpCodes()).isEqualTo("BRAKE-PAD-FRONT,BRAKE-HW-KIT");
    }

    @Test
    @DisplayName("a guide miss with no explicit quantity is rejected — never a null-quantity row")
    void guideMissWithoutQuantityRejected() {
        when(laborTimeDefaultingService.lookupGuideTime(SERVICE_ID, CUSTOMER_ID, VEHICLE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addEstimateItem(ESTIMATE_ID, laborRequest(null), "jane.smith"))
                .isInstanceOf(WorkorderRequestValidationException.class)
                .hasMessageContaining("quantity is required");

        verify(estimateItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("a PART item never consults the guide")
    void partItemNeverConsultsGuide() {
        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .description("Brake pad set")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("45.00"))
                .productId(PRODUCT_ID)
                .build();

        service.addEstimateItem(ESTIMATE_ID, request, "jane.smith");

        verify(laborTimeDefaultingService, never()).lookupGuideTime(any(), any(), any());
        EstimateItem saved = savedItem();
        assertThat(saved.getGuideHours()).isNull();
        assertThat(saved.getGuideSourceCode()).isNull();
    }

    @Test
    @DisplayName("a LABOR item without a serviceId never consults the guide")
    void laborWithoutServiceIdNeverConsultsGuide() {
        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .description("Diagnostic")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("120.00"))
                .build();

        service.addEstimateItem(ESTIMATE_ID, request, "jane.smith");

        verify(laborTimeDefaultingService, never()).lookupGuideTime(any(), any(), any());
        assertThat(savedItem().getGuideHours()).isNull();
    }
}
