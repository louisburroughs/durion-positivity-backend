package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link WorkorderServiceImpl} — CRM reference ID propagation
 * path (Story #93 CAP-094).
 *
 * <p>
 * Covers the two null-safety branches added in Story #93:
 * <ol>
 * <li>When {@code estimateId == null}: workorder is created with empty CRM
 * fields (no estimate to propagate from).</li>
 * <li>When {@code estimateId} is provided: CRM IDs are propagated from the
 * estimate to the workorder.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderServiceImpl — CRM Reference ID Propagation Unit Tests (CAP-094 Story #93)")
class WorkorderServiceImplCrmPropagationTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;

    @Mock
    private WorkorderStateMachine stateMachine;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private PartQuantityDivisibilityService partQuantityDivisibilityService;

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.WorkorderFactPublisher workorderFactPublisher;

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.PromotedWorkorderDemandPublisher promotedWorkorderDemandPublisher;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    private static final UUID CUSTOMER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ESTIMATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String CRM_PARTY_ID = "01952f4e-crm0-7000-8000-party00001";
    private static final String CRM_VEHICLE_ID = "01952f4e-crm0-7000-8000-vehicle0001";
    private static final String CRM_CONTACT_ID = "01952f4e-crm0-7000-8000-contact0001";

    @BeforeEach
    void stubCustomerRequirements() {
        // Stub the customer-party replica to allow workorder creation
        when(extCustomerPartyReplicaRepository.findById(any()))
                .thenReturn(
                        java.util.Optional.of(com.positivity.workorder.internal.entity.ExtCustomerPartyReplica.builder()
                                .requirementsMet(true)
                                .build()));

        // Default workorderRepository.save() behaviour: return the Workorder passed in
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> {
            Workorder w = inv.getArgument(0);
            // Simulate a persisted state (no id assignment needed for these tests)
            return w;
        });
    }

    // =====================================================================
    // Null estimateId path — no estimate, CRM fields default to empty
    // =====================================================================

    @Test
    @DisplayName("createWorkorder(null, customerId) — workorder has null crmPartyId and crmVehicleId")
    void createWorkorder_nullEstimateId_workorderHasNullCrmPartyIdAndVehicleId() {
        WorkorderResponse result = workorderService.createWorkorder(null, CUSTOMER_ID);

        assertThat(result.getCrmPartyId()).isNull();
        assertThat(result.getCrmVehicleId()).isNull();
    }

    @Test
    @DisplayName("createWorkorder(null, customerId) — workorder has empty crmContactIds (not null)")
    void createWorkorder_nullEstimateId_workorderHasEmptyCrmContactIds() {
        WorkorderResponse result = workorderService.createWorkorder(null, CUSTOMER_ID);

        assertThat(result.getCrmContactIds()).isNotNull().isEmpty();
    }

    // =====================================================================
    // Non-null estimateId path — CRM IDs propagated from estimate
    // =====================================================================

    @Test
    @DisplayName("createWorkorder(estimateId, customerId) — workorder receives crmPartyId from estimate")
    void createWorkorder_withEstimateId_workorderHasCrmPartyIdFromEstimate() {
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(workorderCaptor.getValue().getCrmPartyId()).isEqualTo(CRM_PARTY_ID);
    }

    @Test
    @DisplayName("createWorkorder(estimateId, customerId) — workorder receives crmVehicleId from estimate")
    void createWorkorder_withEstimateId_workorderHasCrmVehicleIdFromEstimate() {
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(workorderCaptor.getValue().getCrmVehicleId()).isEqualTo(CRM_VEHICLE_ID);
    }

    @Test
    @DisplayName("createWorkorder(estimateId, customerId) — workorder receives crmContactIds from estimate")
    void createWorkorder_withEstimateId_workorderHasCrmContactIdsFromEstimate() {
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(workorderCaptor.getValue().getCrmContactIds()).containsExactly(CRM_CONTACT_ID);
    }

    @Test
    @DisplayName("createWorkorder(estimateId, customerId) — crmContactIds is a defensive copy (mutation-safe)")
    void createWorkorder_withEstimateId_crmContactIdsIsDefensiveCopy() {
        List<String> originalContactIds = new java.util.ArrayList<>(List.of(CRM_CONTACT_ID));
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, originalContactIds);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        // Mutating the original list must not affect the saved workorder's list
        originalContactIds.add("extra-id");
        assertThat(workorderCaptor.getValue().getCrmContactIds())
                .doesNotContain("extra-id")
                .hasSize(1);
    }

    @Test
    @DisplayName(
            "createWorkorder(estimateId, customerId) — empty crmContactIds on estimate yields empty list in workorder")
    void createWorkorder_withEstimateId_emptyCrmContactIds_workorderHasEmptyList() {
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of());
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(workorderCaptor.getValue().getCrmContactIds()).isNotNull().isEmpty();
    }

    // =====================================================================
    // shop_id / location_id resolution
    // =====================================================================

    @Test
    @DisplayName("createWorkorder(estimateId, ...) — shopId and locationId come from the estimate's location")
    void createWorkorder_withEstimateId_shopIdAndLocationIdFromEstimateLocation() {
        UUID estimateLocation = UUID.fromString("30000000-0000-0000-0000-000000000001");
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of());
        estimate.setLocationId(estimateLocation);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(workorderCaptor.getValue().getShopId()).isEqualTo(estimateLocation);
        assertThat(workorderCaptor.getValue().getLocationId()).isEqualTo(estimateLocation);
    }

    @Test
    @DisplayName("createWorkorder(null, ...) — estimate-less falls back to the creator's primary location")
    void createWorkorder_nullEstimateId_shopIdFromPrimaryLocation() {
        UUID primaryLocation = UUID.fromString("30000000-0000-0000-0000-000000000002");
        when(peopleAvailabilityLocalService.resolveCurrentUserPrimaryLocation())
                .thenReturn(Optional.of(primaryLocation));

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        WorkorderResponse result = workorderService.createWorkorder(null, CUSTOMER_ID);

        assertThat(result.getShopId()).isEqualTo(primaryLocation);
        assertThat(workorderCaptor.getValue().getLocationId()).isEqualTo(primaryLocation);
    }

    @Test
    @DisplayName("createWorkorder(null, ...) — no primary location leaves shopId null (no sentinel shop)")
    void createWorkorder_nullEstimateId_noPrimaryLocation_shopIdNull() {
        when(peopleAvailabilityLocalService.resolveCurrentUserPrimaryLocation()).thenReturn(Optional.empty());

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        when(workorderRepository.save(workorderCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        WorkorderResponse result = workorderService.createWorkorder(null, CUSTOMER_ID);

        assertThat(result.getShopId()).isNull();
        assertThat(workorderCaptor.getValue().getLocationId()).isNull();
    }

    // =====================================================================
    // uomCode snapshot at promotion (ADR-0055 stage 3, #1415)
    // =====================================================================

    @Test
    @DisplayName("createWorkorder(estimateId, ...) — a PART item's uomCode is snapshotted onto the promoted "
            + "WorkorderPart, the same way quantity is")
    void createWorkorder_withEstimateId_partItemUomCodeIsSnapshotted() {
        UUID productId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of());
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        EstimateItem partItem = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.PART)
                .description("Parker Hydraulic Hose")
                .quantity(new BigDecimal("3.5"))
                .uomCode("FT")
                .unitPrice(BigDecimal.TEN)
                .productId(productId)
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("tech")
                .build();
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of(partItem));

        ArgumentCaptor<List<WorkorderPart>> partsCaptor = ArgumentCaptor.forClass(List.class);
        when(workorderPartRepository.saveAll(partsCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(partsCaptor.getValue()).hasSize(1);
        WorkorderPart promoted = partsCaptor.getValue().get(0);
        assertThat(promoted.getUomCode()).isEqualTo("FT");
        assertThat(promoted.getQuantity()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("createWorkorder(estimateId, ...) — a PART item with no uomCode promotes a null uomCode "
            + "(base unit, unchanged from pre-#1415 behavior)")
    void createWorkorder_withEstimateId_partItemWithoutUomCodePromotesNull() {
        UUID productId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        Estimate estimate = buildEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of());
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));

        EstimateItem partItem = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.PART)
                .description("Brake pad")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.TEN)
                .productId(productId)
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("tech")
                .build();
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of(partItem));

        ArgumentCaptor<List<WorkorderPart>> partsCaptor = ArgumentCaptor.forClass(List.class);
        when(workorderPartRepository.saveAll(partsCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        assertThat(partsCaptor.getValue()).hasSize(1);
        assertThat(partsCaptor.getValue().get(0).getUomCode()).isNull();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static Estimate buildEstimateWithCrmFields(
            String crmPartyId, String crmVehicleId, List<String> crmContactIds) {
        return Estimate.builder()
                .id(ESTIMATE_ID)
                .customerId(CUSTOMER_ID)
                .crmPartyId(crmPartyId)
                .crmVehicleId(crmVehicleId)
                .crmContactIds(crmContactIds)
                .build();
    }
}
