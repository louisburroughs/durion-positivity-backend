package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.EstimateServiceImpl;
import com.positivity.workorder.internal.service.PeopleAvailabilityLocalService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link EstimateServiceImpl} — CRM reference ID mapping path
 * (Story #93 CAP-094).
 *
 * <p>
 * Covers the null-safety branch added in Story #93:
 * {@code request.getCrmContactIds() != null ? new ArrayList<>(...) : new ArrayList<>()}
 *
 * <p>
 * Verifies that:
 * <ul>
 * <li>When {@code crmContactIds} is {@code null}, the saved estimate receives
 * an empty list.</li>
 * <li>When {@code crmContactIds} is provided, the saved estimate receives a
 * defensive copy.</li>
 * <li>CRM string fields ({@code crmPartyId}, {@code crmVehicleId}) are mapped
 * directly.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EstimateServiceImpl — CRM Reference ID Mapping Unit Tests (CAP-094 Story #93)")
class EstimateServiceImplCrmPropagationTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

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
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private DocumentClient documentClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private EstimateServiceImpl estimateService;

    private static final UUID CUSTOMER_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("22000000-0000-0000-0000-000000000002");
    private static final UUID LOCATION_ID = UUID.fromString("33000000-0000-0000-0000-000000000003");
    private static final String CRM_PARTY_ID = "01952f4e-crm0-7000-8000-est-party001";
    private static final String CRM_VEHICLE_ID = "01952f4e-crm0-7000-8000-est-veh0001";
    private static final String CRM_CONTACT_ID = "01952f4e-crm0-7000-8000-est-cont001";

    @BeforeEach
    void stubCommonDependencies() {
        // Approval configurations: return empty list so default config is used
        when(approvalConfigurationRepository.findApplicableConfigurations(any(), any()))
                .thenReturn(Collections.emptyList());

        // Estimate number uniqueness check: first candidate is not taken
        when(estimateRepository.existsByLocationIdAndEstimateNumber(any(), anyString()))
                .thenReturn(false);

        // estimateRepository.save: return the passed-in estimate (simulate persistence)
        when(estimateRepository.save(any(Estimate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // null crmContactIds — defensive empty-list branch
    // =====================================================================

    @Test
    @DisplayName("createEstimate: null crmContactIds in request → saved estimate has empty crmContactIds")
    void createEstimate_nullCrmContactIds_savesEstimateWithEmptyCrmContactIds() {
        CreateEstimateRequest request = buildBaseRequest().crmContactIds(null).build();

        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        when(estimateRepository.save(estimateCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        estimateService.createEstimate(request, "test-user");

        assertThat(estimateCaptor.getValue().getCrmContactIds())
                .as("crmContactIds should be an empty list, never null, even when request field is null")
                .isNotNull()
                .isEmpty();
    }

    // =====================================================================
    // Non-null crmContactIds — defensive copy branch
    // =====================================================================

    @Test
    @DisplayName("createEstimate: crmContactIds provided → saved estimate has same contact IDs")
    void createEstimate_withCrmContactIds_savesEstimateWithContactIds() {
        CreateEstimateRequest request =
                buildBaseRequest().crmContactIds(List.of(CRM_CONTACT_ID)).build();

        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        when(estimateRepository.save(estimateCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        estimateService.createEstimate(request, "test-user");

        assertThat(estimateCaptor.getValue().getCrmContactIds()).containsExactly(CRM_CONTACT_ID);
    }

    @Test
    @DisplayName(
            "createEstimate: crmContactIds is a defensive copy — mutations to source list don't affect saved estimate")
    void createEstimate_withCrmContactIds_savesDefensiveCopy() {
        List<String> mutableContactIds = new java.util.ArrayList<>(List.of(CRM_CONTACT_ID));
        CreateEstimateRequest request =
                buildBaseRequest().crmContactIds(mutableContactIds).build();

        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        when(estimateRepository.save(estimateCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        estimateService.createEstimate(request, "test-user");

        mutableContactIds.add("extra-contact");
        assertThat(estimateCaptor.getValue().getCrmContactIds())
                .doesNotContain("extra-contact")
                .hasSize(1);
    }

    // =====================================================================
    // CRM string field mapping
    // =====================================================================

    @Test
    @DisplayName("createEstimate: crmPartyId is mapped directly to the saved estimate")
    void createEstimate_withCrmPartyId_savesEstimateWithCrmPartyId() {
        CreateEstimateRequest request = buildBaseRequest().build();

        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        when(estimateRepository.save(estimateCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        estimateService.createEstimate(request, "test-user");

        assertThat(estimateCaptor.getValue().getCrmPartyId()).isEqualTo(CRM_PARTY_ID);
    }

    @Test
    @DisplayName("createEstimate: crmVehicleId is mapped directly to the saved estimate")
    void createEstimate_withCrmVehicleId_savesEstimateWithCrmVehicleId() {
        CreateEstimateRequest request = buildBaseRequest().build();

        ArgumentCaptor<Estimate> estimateCaptor = ArgumentCaptor.forClass(Estimate.class);
        when(estimateRepository.save(estimateCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        estimateService.createEstimate(request, "test-user");

        assertThat(estimateCaptor.getValue().getCrmVehicleId()).isEqualTo(CRM_VEHICLE_ID);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private CreateEstimateRequest.CreateEstimateRequestBuilder buildBaseRequest() {
        return CreateEstimateRequest.builder()
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .locationId(LOCATION_ID)
                .subtotal(new BigDecimal("500.00"))
                .taxAmount(new BigDecimal("50.00"))
                .total(new BigDecimal("550.00"))
                .crmPartyId(CRM_PARTY_ID)
                .crmVehicleId(CRM_VEHICLE_ID)
                .crmContactIds(List.of(CRM_CONTACT_ID));
    }
}
