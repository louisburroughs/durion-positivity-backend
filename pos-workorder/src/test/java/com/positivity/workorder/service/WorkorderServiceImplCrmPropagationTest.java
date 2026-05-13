package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.CustomerValidationClient;
import com.positivity.workorder.internal.client.ShopmgrOperationalContextClient;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.WorkorderServiceImpl;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
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
    private CustomerValidationClient customerValidationClient;

    @Mock
    private WorkorderStateMachine stateMachine;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @Mock
    private ShopmgrOperationalContextClient shopmgrClient;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    private static final UUID CUSTOMER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ESTIMATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String CRM_PARTY_ID = "01952f4e-crm0-7000-8000-party00001";
    private static final String CRM_VEHICLE_ID = "01952f4e-crm0-7000-8000-vehicle0001";
    private static final String CRM_CONTACT_ID = "01952f4e-crm0-7000-8000-contact0001";

    @BeforeEach
    void stubCustomerRequirements() {
        // Stub CustomerValidationClient to allow workorder creation
        when(customerValidationClient.checkRequirementsMet(any())).thenReturn(true);

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
        Workorder result = workorderService.createWorkorder(null, CUSTOMER_ID);

        assertThat(result.getCrmPartyId()).isNull();
        assertThat(result.getCrmVehicleId()).isNull();
    }

    @Test
    @DisplayName("createWorkorder(null, customerId) — workorder has empty crmContactIds (not null)")
    void createWorkorder_nullEstimateId_workorderHasEmptyCrmContactIds() {
        Workorder result = workorderService.createWorkorder(null, CUSTOMER_ID);

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
    // Helpers
    // =====================================================================

    private static Estimate buildEstimateWithCrmFields(
            String crmPartyId, String crmVehicleId, List<String> crmContactIds) {
        return Estimate.builder()
                .customerId(CUSTOMER_ID)
                .crmPartyId(crmPartyId)
                .crmVehicleId(crmVehicleId)
                .crmContactIds(crmContactIds)
                .build();
    }
}
