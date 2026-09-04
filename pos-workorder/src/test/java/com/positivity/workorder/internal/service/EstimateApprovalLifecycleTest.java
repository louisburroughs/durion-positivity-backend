package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.LineItemApprovalDto;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateSnapshot;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.PurchaseOrderRequiredException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.exception.WorkorderResourceConflictException;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Estimate approval lifecycle: submit for approval (CAP:003 issue #168), selective line-item
 * approval (CAP:003), purchase-order enforcement for commercial accounts (CAP:092 story #98),
 * decline / reopen inside the configured expiry window, approval-window expiration
 * (CAP:003 issue #204), snapshots, PDF rendering, and estimate creation from an appointment.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EstimateServiceImpl approval lifecycle")
class EstimateApprovalLifecycleTest {

    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3311");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3312");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3313");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3314");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3315");
    private static final UUID CONFIG_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3316");
    private static final UUID ITEM_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3317");
    private static final UUID OTHER_ITEM_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3318");
    private static final UUID APPOINTMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3319");
    private static final UUID SNAPSHOT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f331a");

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

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

    private EstimateServiceImpl service;

    @BeforeEach
    void setUp() {
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
                org.mockito.Mockito.mock(PartQuantityDivisibilityService.class),
                // Un-stubbed mock: lookupGuideTime answers Optional.empty(), i.e. "no guide",
                // which keeps every pre-#1569 scenario behaviorally identical.
                org.mockito.Mockito.mock(LaborTimeDefaultingService.class));

        when(estimateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(estimateItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(ESTIMATE_ID))
                .thenReturn(List.of());
        when(estimateSnapshotRepository.save(any())).thenAnswer(invocation -> {
            EstimateSnapshot snapshot = invocation.getArgument(0);
            if (snapshot.getId() == null) {
                // The snapshot id is assigned by the persistence provider and has no setter.
                ReflectionTestUtils.setField(snapshot, "id", SNAPSHOT_ID);
            }
            return snapshot;
        });
        when(billingRulesClientService.isPurchaseOrderRequired(any())).thenReturn(false);
    }

    private Estimate estimate(EstimateStatus status) {
        return Estimate.builder()
                .id(ESTIMATE_ID)
                .estimateNumber("EST-0001")
                .status(status)
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .locationId(LOCATION_ID)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .currencyUomId("USD")
                .build();
    }

    private void givenEstimate(Estimate estimate) {
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));
    }

    private EstimateItem item(UUID id, EstimateItemType type) {
        return EstimateItem.builder()
                .id(id)
                .estimate(new Estimate(ESTIMATE_ID))
                .itemType(type)
                .description(type == EstimateItemType.PART ? "Brake pad set" : "Brake replacement")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("25.00"))
                .lineTotal(new BigDecimal("50.00"))
                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                .build();
    }

    private void givenItems(EstimateItem... items) {
        when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(ESTIMATE_ID))
                .thenReturn(List.of(items));
    }

    private void givenApprovalConfiguration(ApprovalConfiguration configuration) {
        when(approvalConfigurationRepository.findById(CONFIG_ID)).thenReturn(Optional.of(configuration));
    }

    @Nested
    @DisplayName("approveEstimate")
    class ApproveEstimate {

        @Test
        @DisplayName("stamps approval on a PENDING_APPROVAL estimate")
        void approvesPendingEstimate() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));

            EstimateResponse response = service.approveEstimate(ESTIMATE_ID, CUSTOMER_ID);

            assertThat(response.getStatus()).isEqualTo("APPROVED");
            assertThat(response.getApprovedAt()).isEqualTo(NOW_LOCAL);
            assertThat(response.getApprovedBy()).isEqualTo(CUSTOMER_ID);
            verify(estimateFactPublisher).markChanged(ESTIMATE_ID);
        }

        @Test
        @DisplayName("refuses an estimate that is not awaiting approval")
        void rejectsNonPendingEstimate() {
            givenEstimate(estimate(EstimateStatus.DRAFT));

            assertThatThrownBy(() -> service.approveEstimate(ESTIMATE_ID, CUSTOMER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Estimate cannot be approved in current state: DRAFT");
        }

        @Test
        @DisplayName("rejects an unknown estimate")
        void rejectsUnknownEstimate() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveEstimate(ESTIMATE_ID, CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Estimate not found");
        }

        @Test
        @DisplayName("captures the signature payload on the approved estimate")
        void capturesSignature() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));

            EstimateResponse response = service.approveEstimate(
                    ESTIMATE_ID, CUSTOMER_ID, "base64-signature", "image/png", "Jane Smith", "approved in bay");

            assertThat(response.getStatus()).isEqualTo("APPROVED");
            assertThat(response.getSignatureData()).isEqualTo("base64-signature");
            assertThat(response.getSignatureMimeType()).isEqualTo("image/png");
            assertThat(response.getSignerName()).isEqualTo("Jane Smith");
            assertThat(response.getApprovalNotes()).isEqualTo("approved in bay");
        }

        @Test
        @DisplayName("refuses an approval attempted by a different customer")
        void rejectsCustomerMismatch() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));

            assertThatThrownBy(() -> service.approveEstimate(
                            ESTIMATE_ID, OTHER_CUSTOMER_ID, "sig", "image/png", "Someone Else", null))
                    .isInstanceOf(WorkorderResourceConflictException.class)
                    .hasMessageContaining("Customer ID mismatch");
        }

        @Test
        @DisplayName("records the purchase order when the account requires one")
        void recordsPurchaseOrder() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            when(billingRulesClientService.isPurchaseOrderRequired(String.valueOf(CUSTOMER_ID)))
                    .thenReturn(true);

            EstimateResponse response = service.approveEstimate(
                    ESTIMATE_ID, CUSTOMER_ID, "sig", "image/png", "Jane Smith", null, "PO-4711");

            assertThat(response.getPurchaseOrderNumber()).isEqualTo("PO-4711");
            assertThat(response.getStatus()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("refuses approval when a required purchase order is missing or blank")
        void rejectsMissingPurchaseOrder() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            when(billingRulesClientService.isPurchaseOrderRequired(String.valueOf(CUSTOMER_ID)))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.approveEstimate(
                            ESTIMATE_ID, CUSTOMER_ID, "sig", "image/png", "Jane Smith", null, null))
                    .isInstanceOf(PurchaseOrderRequiredException.class)
                    .hasMessage("Purchase order number is required for this customer account");

            assertThatThrownBy(() -> service.approveEstimate(
                            ESTIMATE_ID, CUSTOMER_ID, "sig", "image/png", "Jane Smith", null, "   "))
                    .isInstanceOf(PurchaseOrderRequiredException.class)
                    .hasMessage("Purchase order number is required for this customer account");
            verify(estimateRepository, never()).save(any());
        }

        @Test
        @DisplayName("approves every line item when no selective decisions are supplied")
        void approvesAllItemsByDefault() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            EstimateItem part = item(ITEM_ID, EstimateItemType.PART);
            EstimateItem labor = item(OTHER_ITEM_ID, EstimateItemType.LABOR);
            givenItems(part, labor);

            service.approveEstimate(ESTIMATE_ID, CUSTOMER_ID, "sig", "image/png", "Jane Smith", null);

            assertThat(part.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(part.getApprovalTimestamp()).isEqualTo(NOW_LOCAL);
            assertThat(labor.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        }

        @Test
        @DisplayName("applies selective approvals and declines with a reason")
        void appliesSelectiveApprovals() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            EstimateItem approved = item(ITEM_ID, EstimateItemType.PART);
            EstimateItem declined = item(OTHER_ITEM_ID, EstimateItemType.LABOR);
            givenItems(approved, declined);

            service.approveEstimate(
                    ESTIMATE_ID,
                    CUSTOMER_ID,
                    "sig",
                    "image/png",
                    "Jane Smith",
                    null,
                    null,
                    List.of(
                            LineItemApprovalDto.builder()
                                    .lineItemId(ITEM_ID)
                                    .approved(true)
                                    .notes("go ahead")
                                    .build(),
                            LineItemApprovalDto.builder()
                                    .lineItemId(OTHER_ITEM_ID)
                                    .approved(false)
                                    .rejectionReason("second opinion wanted")
                                    .build()));

            assertThat(approved.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(approved.getApprovalNotes()).isEqualTo("go ahead");
            assertThat(approved.getApprovalTimestamp()).isEqualTo(NOW_LOCAL);

            assertThat(declined.getApprovalStatus()).isEqualTo(ApprovalStatus.DECLINED);
            assertThat(declined.getRejectionReason()).isEqualTo("second opinion wanted");
        }

        @Test
        @DisplayName("implicitly approves items left out of the selective decision list")
        void implicitlyApprovesUndecidedItems() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            EstimateItem decided = item(ITEM_ID, EstimateItemType.PART);
            EstimateItem undecided = item(OTHER_ITEM_ID, EstimateItemType.LABOR);
            givenItems(decided, undecided);

            service.approveEstimate(
                    ESTIMATE_ID,
                    CUSTOMER_ID,
                    "sig",
                    "image/png",
                    "Jane Smith",
                    null,
                    null,
                    List.of(LineItemApprovalDto.builder()
                            .lineItemId(ITEM_ID)
                            .approved(true)
                            .build()));

            assertThat(undecided.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(undecided.getApprovalNotes()).isNull();
        }

        @Test
        @DisplayName("treats an empty selective decision list as approve-all")
        void emptyApprovalListApprovesAll() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            EstimateItem part = item(ITEM_ID, EstimateItemType.PART);
            givenItems(part);

            service.approveEstimate(ESTIMATE_ID, CUSTOMER_ID, "sig", "image/png", "Jane Smith", null, null, List.of());

            assertThat(part.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        }
    }

    @Nested
    @DisplayName("declineEstimate and reopenEstimate")
    class DeclineAndReopen {

        @Test
        @DisplayName("declines and sets the expiry from the approval configuration")
        void declinesWithConfiguredExpiry() {
            Estimate estimate = estimate(EstimateStatus.PENDING_APPROVAL);
            estimate.setApprovalConfigurationId(CONFIG_ID);
            givenEstimate(estimate);
            givenApprovalConfiguration(
                    ApprovalConfiguration.builder().declineExpiryDays(14).build());

            EstimateResponse response = service.declineEstimate(ESTIMATE_ID, "too expensive");

            assertThat(response.getStatus()).isEqualTo("DECLINED");
            assertThat(response.getExpiresAt()).isEqualTo(NOW_LOCAL.plusDays(14));
            assertThat(estimate.getDeclineReason()).isEqualTo("too expensive");
            assertThat(estimate.getDeclinedAt()).isEqualTo(NOW_LOCAL);
            verify(estimateFactPublisher).markChanged(ESTIMATE_ID);
        }

        @Test
        @DisplayName("falls back to the 30-day default when no configuration is linked")
        void declinesWithDefaultExpiry() {
            givenEstimate(estimate(EstimateStatus.DRAFT));

            EstimateResponse response = service.declineEstimate(ESTIMATE_ID, "changed mind");

            assertThat(response.getExpiresAt()).isEqualTo(NOW_LOCAL.plusDays(30));
        }

        @Test
        @DisplayName("refuses to decline an estimate in a terminal state")
        void rejectsUndeclinableEstimate() {
            givenEstimate(estimate(EstimateStatus.EXPIRED));

            assertThatThrownBy(() -> service.declineEstimate(ESTIMATE_ID, "reason"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Estimate cannot be declined in current state: EXPIRED");
        }

        @Test
        @DisplayName("rejects declining an unknown estimate")
        void rejectsUnknownEstimate() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineEstimate(ESTIMATE_ID, "reason"))
                    .isInstanceOf(EstimateNotFoundException.class)
                    .hasMessageContaining("Estimate not found");
        }

        @Test
        @DisplayName("reopens a declined estimate inside the expiry window and clears the decline")
        void reopensWithinWindow() {
            Estimate estimate = estimate(EstimateStatus.DECLINED);
            estimate.setDeclinedAt(NOW_LOCAL.minusDays(3));
            estimate.setDeclineReason("too expensive");
            estimate.setExpiresAt(NOW_LOCAL.plusDays(27));
            givenEstimate(estimate);

            EstimateResponse response = service.reopenEstimate(ESTIMATE_ID);

            assertThat(response.getStatus()).isEqualTo("DRAFT");
            assertThat(response.getExpiresAt()).isNull();
            assertThat(estimate.getDeclinedAt()).isNull();
            assertThat(estimate.getDeclineReason()).isNull();
        }

        @Test
        @DisplayName("refuses to reopen once the expiry window has passed")
        void rejectsReopenAfterWindow() {
            Estimate estimate = estimate(EstimateStatus.DECLINED);
            estimate.setDeclinedAt(NOW_LOCAL.minusDays(45));
            givenEstimate(estimate);

            assertThatThrownBy(() -> service.reopenEstimate(ESTIMATE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("either not declined or expiry period has passed");
        }

        @Test
        @DisplayName("refuses to reopen an estimate that was never declined")
        void rejectsReopenOfNonDeclined() {
            givenEstimate(estimate(EstimateStatus.DRAFT));

            assertThatThrownBy(() -> service.reopenEstimate(ESTIMATE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("either not declined or expiry period has passed");
        }

        @Test
        @DisplayName("fails when the linked approval configuration is missing")
        void failsOnMissingConfiguration() {
            Estimate estimate = estimate(EstimateStatus.DECLINED);
            estimate.setApprovalConfigurationId(CONFIG_ID);
            estimate.setDeclinedAt(NOW_LOCAL.minusDays(1));
            givenEstimate(estimate);
            when(approvalConfigurationRepository.findById(CONFIG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reopenEstimate(ESTIMATE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Approval configuration not found");
        }
    }

    @Nested
    @DisplayName("submitForApproval")
    class SubmitForApproval {

        @BeforeEach
        void seedCompleteEstimate() {
            givenItems(item(ITEM_ID, EstimateItemType.PART));
        }

        @Test
        @DisplayName("snapshots the estimate and moves it to PENDING_APPROVAL")
        void submitsDraft() {
            givenEstimate(estimate(EstimateStatus.DRAFT));

            EstimateResponse response = service.submitForApproval(ESTIMATE_ID, "jane.smith");

            assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(response.getSubmittedAt()).isEqualTo(NOW_LOCAL);
            assertThat(response.getSubmittedBy()).isEqualTo("jane.smith");

            ArgumentCaptor<EstimateSnapshot> captor = ArgumentCaptor.forClass(EstimateSnapshot.class);
            verify(estimateSnapshotRepository).save(captor.capture());
            assertThat(captor.getValue().getCapturedById()).isEqualTo("jane.smith");
            assertThat(captor.getValue().getNotes()).isEqualTo("Submitted for customer approval");
            assertThat(captor.getValue().getSnapshotData()).contains("EST-0001");
        }

        @Test
        @DisplayName("sets the expiry from the configured approval window")
        void appliesApprovalWindow() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setApprovalConfigurationId(CONFIG_ID);
            givenEstimate(estimate);
            givenApprovalConfiguration(
                    ApprovalConfiguration.builder().approvalWindowDays(7).build());

            assertThat(service.submitForApproval(ESTIMATE_ID, "jane.smith").getExpiresAt())
                    .isEqualTo(NOW_LOCAL.plusDays(7));
        }

        @Test
        @DisplayName("leaves the expiry unset when the configuration has no approval window")
        void noApprovalWindowLeavesExpiryUnset() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setApprovalConfigurationId(CONFIG_ID);
            givenEstimate(estimate);
            givenApprovalConfiguration(
                    ApprovalConfiguration.builder().approvalWindowDays(0).build());

            assertThat(service.submitForApproval(ESTIMATE_ID, "jane.smith").getExpiresAt())
                    .isNull();
        }

        @Test
        @DisplayName("refuses an estimate that is not a draft")
        void rejectsNonDraft() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be in DRAFT state, current state: PENDING_APPROVAL");
        }

        @Test
        @DisplayName("rejects an unknown estimate")
        void rejectsUnknownEstimate() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("refuses an estimate with no customer")
        void rejectsMissingCustomer() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setCustomerId(null);
            givenEstimate(estimate);

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot submit estimate - no customer assigned");
        }

        @Test
        @DisplayName("refuses an estimate with no vehicle")
        void rejectsMissingVehicle() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setVehicleId(null);
            givenEstimate(estimate);

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot submit estimate - no vehicle assigned");
        }

        @Test
        @DisplayName("refuses an estimate with no line items")
        void rejectsMissingLineItems() {
            givenEstimate(estimate(EstimateStatus.DRAFT));
            when(estimateItemRepository.findByEstimate_IdAndDeletedFalse(ESTIMATE_ID))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot submit estimate - no line items added");
        }

        @Test
        @DisplayName("refuses an estimate whose totals have not been calculated")
        void rejectsUncalculatedTotals() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setSubtotal(null);
            givenEstimate(estimate);

            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("totals not calculated");

            estimate.setSubtotal(BigDecimal.ZERO);
            assertThatThrownBy(() -> service.submitForApproval(ESTIMATE_ID, "jane.smith"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("totals not calculated");
        }
    }

    @Nested
    @DisplayName("expirePendingApprovals")
    class ExpirePendingApprovals {

        @Test
        @DisplayName("marks every overdue estimate EXPIRED and returns the count")
        void expiresOverdueEstimates() {
            Estimate overdue = estimate(EstimateStatus.PENDING_APPROVAL);
            overdue.setExpiresAt(NOW_LOCAL.minusDays(1));
            when(estimateRepository.findByStatusAndExpiresAtBefore(EstimateStatus.PENDING_APPROVAL, NOW_LOCAL))
                    .thenReturn(List.of(overdue));

            assertThat(service.expirePendingApprovals()).isEqualTo(1);
            assertThat(overdue.getStatus()).isEqualTo(EstimateStatus.EXPIRED);
            assertThat(overdue.getDeclinedAt()).isEqualTo(NOW_LOCAL);
            assertThat(overdue.getDeclineReason()).contains("Approval window expired");
            verify(estimateFactPublisher).markChanged(ESTIMATE_ID);
        }

        @Test
        @DisplayName("returns zero when nothing is overdue")
        void noOverdueEstimates() {
            when(estimateRepository.findByStatusAndExpiresAtBefore(EstimateStatus.PENDING_APPROVAL, NOW_LOCAL))
                    .thenReturn(List.of());

            assertThat(service.expirePendingApprovals()).isZero();
            verify(estimateRepository, never()).save(any());
        }

        @Test
        @DisplayName("keeps going and reports only the successes when one estimate fails to expire")
        void countsOnlySuccessfulExpirations() {
            Estimate healthy = estimate(EstimateStatus.PENDING_APPROVAL);
            Estimate broken = estimate(EstimateStatus.PENDING_APPROVAL);
            broken.setId(null);
            when(estimateRepository.findByStatusAndExpiresAtBefore(EstimateStatus.PENDING_APPROVAL, NOW_LOCAL))
                    .thenReturn(List.of(broken, healthy));
            when(estimateRepository.save(broken)).thenThrow(new IllegalStateException("optimistic lock"));

            assertThat(service.expirePendingApprovals()).isEqualTo(1);
            assertThat(healthy.getStatus()).isEqualTo(EstimateStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("summary, PDF, and snapshots")
    class SummaryPdfAndSnapshots {

        @Test
        @DisplayName("groups the summary line items by type")
        void buildsSummary() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            givenItems(item(ITEM_ID, EstimateItemType.PART), item(OTHER_ITEM_ID, EstimateItemType.LABOR));

            EstimateSummaryResponse summary = service.getEstimateSummary(ESTIMATE_ID);

            assertThat(summary.getEstimateNumber()).isEqualTo("EST-0001");
            assertThat(summary.getPartItems()).hasSize(1);
            assertThat(summary.getLaborItems()).hasSize(1);
        }

        @Test
        @DisplayName("rejects a summary for an unknown estimate")
        void rejectsUnknownSummary() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEstimateSummary(ESTIMATE_ID))
                    .isInstanceOf(EstimateNotFoundException.class)
                    .hasMessageContaining("Estimate not found");
        }

        @Test
        @DisplayName("renders a Markdown document with parts, labor, and totals tables")
        void rendersPdfMarkdown() {
            Estimate estimate = estimate(EstimateStatus.APPROVED);
            estimate.setExpiresAt(NOW_LOCAL.plusDays(10));
            givenEstimate(estimate);
            givenItems(item(ITEM_ID, EstimateItemType.PART), item(OTHER_ITEM_ID, EstimateItemType.LABOR));
            when(documentClient.renderPdf(any())).thenReturn("pdf-bytes".getBytes(StandardCharsets.UTF_8));

            assertThat(service.generateEstimatePdf(ESTIMATE_ID)).asString().isEqualTo("pdf-bytes");

            ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
            verify(documentClient).renderPdf(markdown.capture());
            assertThat(markdown.getValue())
                    .contains("# Estimate EST-0001")
                    .contains("**Status:** APPROVED")
                    .contains("**Expires:**")
                    .contains("## Parts")
                    .contains("Brake pad set")
                    .contains("## Labor")
                    .contains("Brake replacement")
                    .contains("| **Subtotal** | 100.00 |")
                    .contains("| **Tax** | 8.25 |")
                    .contains("| **Total** | 108.25 |")
                    .contains("*Currency: USD*");
        }

        @Test
        @DisplayName("omits empty sections and defaults missing amounts to zero")
        void rendersMarkdownWithoutOptionalSections() {
            Estimate estimate = estimate(EstimateStatus.DRAFT);
            estimate.setExpiresAt(null);
            estimate.setSubtotal(null);
            estimate.setTaxAmount(null);
            estimate.setTotal(null);
            estimate.setCurrencyUomId(null);
            givenEstimate(estimate);
            EstimateItem bare = item(ITEM_ID, EstimateItemType.PART);
            bare.setDescription(null);
            bare.setQuantity(null);
            bare.setUnitPrice(null);
            givenItems(bare);
            when(documentClient.renderPdf(any())).thenReturn(new byte[0]);

            service.generateEstimatePdf(ESTIMATE_ID);

            ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
            verify(documentClient).renderPdf(markdown.capture());
            assertThat(markdown.getValue())
                    .doesNotContain("**Expires:**")
                    .doesNotContain("## Labor")
                    .doesNotContain("*Currency:")
                    .contains("| — | 1 | 0.00 | 0.00 |")
                    .contains("| **Subtotal** | 0.00 |");
        }

        @Test
        @DisplayName("rejects a PDF request for an unknown estimate")
        void rejectsUnknownPdf() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateEstimatePdf(ESTIMATE_ID))
                    .isInstanceOf(EstimateNotFoundException.class);
        }

        @Test
        @DisplayName("serializes the estimate and its items into the snapshot")
        void createsSnapshot() {
            givenEstimate(estimate(EstimateStatus.PENDING_APPROVAL));
            givenItems(item(ITEM_ID, EstimateItemType.PART));

            EstimateSnapshotResponse response =
                    service.createEstimateSnapshot(ESTIMATE_ID, "jane.smith", "before revision");

            assertThat(response.getId()).isEqualTo(SNAPSHOT_ID);
            assertThat(response.getCapturedById()).isEqualTo("jane.smith");
            assertThat(response.getNotes()).isEqualTo("before revision");
            assertThat(response.getStatus()).isEqualTo(EstimateStatus.PENDING_APPROVAL);
            assertThat(response.getSnapshotData()).contains("EST-0001").contains("Brake pad set");
        }

        @Test
        @DisplayName("rejects a snapshot for an unknown estimate")
        void rejectsUnknownSnapshot() {
            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createEstimateSnapshot(ESTIMATE_ID, "jane.smith", null))
                    .isInstanceOf(EstimateNotFoundException.class)
                    .hasMessageContaining("Estimate not found");
        }
    }

    @Nested
    @DisplayName("createEstimateFromAppointment")
    class CreateFromAppointment {

        private CreateEstimateFromAppointmentRequest request() {
            return CreateEstimateFromAppointmentRequest.builder()
                    .appointmentId(APPOINTMENT_ID)
                    .customerId(CUSTOMER_ID)
                    .vehicleId(VEHICLE_ID)
                    .locationId(LOCATION_ID)
                    .idempotencyKey(UUID.randomUUID())
                    .build();
        }

        @Test
        @DisplayName("creates a DRAFT estimate carrying the appointment reference")
        void createsEstimate() {
            when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.empty());
            when(estimateRepository.save(any())).thenAnswer(invocation -> {
                Estimate saved = invocation.getArgument(0);
                saved.setId(ESTIMATE_ID);
                return saved;
            });

            CreateEstimateFromAppointmentResponse response = service.createEstimateFromAppointment(request());

            assertThat(response.isCreated()).isTrue();
            assertThat(response.getEstimateId()).isEqualTo(ESTIMATE_ID);
            assertThat(response.getStatus()).isEqualTo(EstimateStatus.DRAFT.name());
            verify(estimateFactPublisher).markChanged(ESTIMATE_ID);
        }

        @Test
        @DisplayName("returns the existing estimate for a replayed appointment")
        void replaysExistingEstimate() {
            Estimate existing = estimate(EstimateStatus.PENDING_APPROVAL);
            when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.of(existing));

            CreateEstimateFromAppointmentResponse response = service.createEstimateFromAppointment(request());

            assertThat(response.isCreated()).isFalse();
            assertThat(response.getEstimateId()).isEqualTo(ESTIMATE_ID);
            assertThat(response.getStatus()).isEqualTo(EstimateStatus.PENDING_APPROVAL.name());
            verify(estimateRepository, never()).save(any());
        }

        @Test
        @DisplayName("reports DRAFT for a replayed estimate whose status was never set")
        void replaysStatuslessEstimate() {
            Estimate existing = estimate(EstimateStatus.DRAFT);
            existing.setStatus(null);
            when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.of(existing));

            assertThat(service.createEstimateFromAppointment(request()).getStatus())
                    .isEqualTo(EstimateStatus.DRAFT.name());
        }

        @Test
        @DisplayName("requires both an appointment and a customer")
        void requiresIdentifiers() {
            CreateEstimateFromAppointmentRequest noAppointment = request();
            noAppointment.setAppointmentId(null);
            assertThatThrownBy(() -> service.createEstimateFromAppointment(noAppointment))
                    .isInstanceOf(WorkorderRequestValidationException.class)
                    .hasMessage("appointmentId is required");

            CreateEstimateFromAppointmentRequest noCustomer = request();
            noCustomer.setCustomerId(null);
            assertThatThrownBy(() -> service.createEstimateFromAppointment(noCustomer))
                    .isInstanceOf(WorkorderRequestValidationException.class)
                    .hasMessage("customerId is required");
        }
    }

    @Nested
    @DisplayName("reads and configuration lookup")
    class ReadsAndConfiguration {

        @Test
        @DisplayName("maps every estimate list query through the response DTO")
        void listQueries() {
            List<Estimate> estimates = List.of(estimate(EstimateStatus.DRAFT));
            when(estimateRepository.findAll()).thenReturn(estimates);
            when(estimateRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(estimates);
            when(estimateRepository.findByLocationId(LOCATION_ID)).thenReturn(estimates);

            assertThat(service.getAllEstimates())
                    .singleElement()
                    .satisfies(
                            response -> assertThat(response.getEstimateNumber()).isEqualTo("EST-0001"));
            assertThat(service.getEstimatesByCustomer(CUSTOMER_ID)).hasSize(1);
            assertThat(service.getEstimatesByLocation(LOCATION_ID)).hasSize(1);
        }

        @Test
        @DisplayName("returns an estimate by id, or empty when it does not exist")
        void findsById() {
            givenEstimate(estimate(EstimateStatus.DRAFT));
            assertThat(service.getEstimateById(ESTIMATE_ID)).isPresent();

            when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());
            assertThat(service.getEstimateById(ESTIMATE_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns the highest-priority applicable approval configuration")
        void returnsHighestPriorityConfiguration() {
            ApprovalConfiguration first =
                    ApprovalConfiguration.builder().id(CONFIG_ID).priority(10).build();
            when(approvalConfigurationRepository.findApplicableConfigurations(LOCATION_ID, CUSTOMER_ID))
                    .thenReturn(List.of(
                            first, ApprovalConfiguration.builder().priority(1).build()));

            assertThat(service.getApprovalConfiguration(LOCATION_ID, CUSTOMER_ID))
                    .isSameAs(first);
        }

        @Test
        @DisplayName("returns null when no configuration applies")
        void returnsNullConfiguration() {
            when(approvalConfigurationRepository.findApplicableConfigurations(LOCATION_ID, CUSTOMER_ID))
                    .thenReturn(List.of());

            assertThat(service.getApprovalConfiguration(LOCATION_ID, CUSTOMER_ID))
                    .isNull();
        }

        @Test
        @DisplayName("deletes an estimate by id")
        void deletesEstimate() {
            service.deleteEstimate(ESTIMATE_ID);

            verify(estimateRepository).deleteById(ESTIMATE_ID);
        }
    }
}
