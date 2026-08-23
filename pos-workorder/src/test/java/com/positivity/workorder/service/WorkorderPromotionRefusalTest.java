package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.PartQuantityDivisibilityService;
import com.positivity.workorder.internal.service.PeopleAvailabilityLocalService;
import com.positivity.workorder.internal.service.PromotedWorkorderDemandPublisher;
import com.positivity.workorder.internal.service.WorkorderFactPublisher;
import com.positivity.workorder.internal.service.WorkorderServiceImpl;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * What promotion does and refuses to do.
 *
 * <p>#1477 — the three conditions that used to leave promotion as an indistinguishable, bodiless
 * {@code 400} now arrive as three types, and the one that is transient says so.
 *
 * <p>#1479/#1481 — a promotion that succeeds registers its part lines' demand, which is what makes
 * those parts pickable and what raises a shortage when the site cannot cover them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderServiceImpl — what promotion refuses, and what it registers (#1477, #1479, #1481)")
class WorkorderPromotionRefusalTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a02");

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

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

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private PromotedWorkorderDemandPublisher promotedWorkorderDemandPublisher;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    /**
     * The race the SDK integration suites hit: an estimate whose customer was created moments
     * earlier has no replica row yet, so the verdict is unknown rather than negative. Retrying is
     * the correct response, and the exception says so instead of leaving the caller to infer it
     * from an empty body.
     */
    @Test
    @DisplayName("a customer with no replicated verdict yet is refused as retryable")
    void unreplicatedVerdictIsRetryable() {
        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        CustomerRequirementsNotMetException refusal = catchThrowableOfType(
                CustomerRequirementsNotMetException.class, () -> workorderService.createWorkorder(null, CUSTOMER_ID));

        assertThat(refusal).isNotNull();
        assertThat(refusal.isRetryable()).isTrue();
        assertThat(refusal.getErrorCode()).isEqualTo(CustomerRequirementsNotMetException.UNAVAILABLE_CODE);
        assertThat(refusal.getCustomerId()).isEqualTo(CUSTOMER_ID);
    }

    /** A verdict that is known and negative is permanent: no retry resolves it. */
    @Test
    @DisplayName("a replicated negative verdict is refused as permanent")
    void negativeVerdictIsPermanent() {
        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(
                        ExtCustomerPartyReplica.builder().requirementsMet(false).build()));

        CustomerRequirementsNotMetException refusal = catchThrowableOfType(
                CustomerRequirementsNotMetException.class, () -> workorderService.createWorkorder(null, CUSTOMER_ID));

        assertThat(refusal).isNotNull();
        assertThat(refusal.isRetryable()).isFalse();
        assertThat(refusal.getErrorCode()).isEqualTo(CustomerRequirementsNotMetException.NOT_MET_CODE);
    }

    /** A wrong estimate id is a 404-shaped condition, not one of the customer refusals. */
    @Test
    @DisplayName("an unknown estimate id is its own type")
    void unknownEstimateIsItsOwnType() {
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workorderService.createWorkorder(ESTIMATE_ID, null))
                .isInstanceOf(EstimateNotFoundException.class)
                .hasMessageContaining(ESTIMATE_ID.toString());
    }

    /** A met verdict still promotes, so the split above did not close the happy path. */
    @Test
    @DisplayName("a met verdict still creates the workorder")
    void metVerdictStillCreatesTheWorkorder() {
        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(
                        ExtCustomerPartyReplica.builder().requirementsMet(true).build()));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(workorderService.createWorkorder(null, CUSTOMER_ID)).isNotNull();
    }

    /**
     * #1479/#1481: promotion registers the part lines' demand with pos-inventory. Without it the
     * workorder has parts and nothing else — no reservation, so no shortage signal, and no pick
     * list, so {@code getPickTasks} has nothing to return.
     */
    @Test
    @DisplayName("promoting an estimate registers its part lines' demand")
    void promotionRegistersPartsDemand() {
        UUID productId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a03");
        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(
                        ExtCustomerPartyReplica.builder().requirementsMet(true).build()));
        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        estimate.setCustomerId(CUSTOMER_ID);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        EstimateItem partItem = new EstimateItem();
        partItem.setItemType(EstimateItemType.PART);
        partItem.setProductId(productId);
        partItem.setQuantity(new BigDecimal("2"));
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of(partItem));
        when(workorderPartRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkorderPart>> parts = ArgumentCaptor.forClass(List.class);
        verify(promotedWorkorderDemandPublisher).registerPartsDemand(any(Workorder.class), parts.capture());
        assertThat(parts.getValue()).singleElement().satisfies(part -> {
            assertThat(part.getProductEntityId()).isEqualTo(productId);
            assertThat(part.getQuantity()).isEqualByComparingTo("2");
        });
    }

    /** A labour-only promotion has no parts to reserve, and asks for no pick list. */
    @Test
    @DisplayName("a promotion with no part lines registers no demand")
    void labourOnlyPromotionRegistersNoDemand() {
        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(
                        ExtCustomerPartyReplica.builder().requirementsMet(true).build()));
        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        estimate.setCustomerId(CUSTOMER_ID);
        when(estimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(estimate));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        EstimateItem labourItem = new EstimateItem();
        labourItem.setItemType(EstimateItemType.LABOR);
        labourItem.setQuantity(BigDecimal.ONE);
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of(labourItem));

        workorderService.createWorkorder(ESTIMATE_ID, CUSTOMER_ID);

        verify(promotedWorkorderDemandPublisher, never()).registerPartsDemand(any(), anyList());
    }
}
