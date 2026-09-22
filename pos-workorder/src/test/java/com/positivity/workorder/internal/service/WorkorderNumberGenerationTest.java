package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.exception.DocumentNumberConflictException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for workorder human-number generation at creation: prefix-swap from
 * the linked estimate when free, sequence fallback on collision, and own sequence
 * for estimate-less workorders.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderNumberGenerationTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-06-24T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID CUSTOMER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

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

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private DocumentNumberAllocator documentNumberAllocator;

    @InjectMocks
    private com.positivity.workorder.internal.service.WorkorderServiceImpl service;

    /** Stands in for the counter: the first number from {@code firstValue} the predicate calls free. */
    @BeforeEach
    void allocateFirstFreeNumber() {
        when(documentNumberAllocator.allocate(anyString(), anyString(), anyLong(), any()))
                .thenAnswer(inv -> {
                    String prefix = inv.getArgument(1);
                    long value = inv.getArgument(2);
                    Predicate<String> taken = inv.getArgument(3);
                    while (taken.test(prefix + value)) {
                        value++;
                    }
                    return prefix + value;
                });
    }

    @Test
    void fromEstimate_swapsPrefixWhenFree() {
        Estimate estimate = Estimate.builder()
                .id(UUID.randomUUID())
                .estimateNumber("EST-2026-1001")
                .customerId(CUSTOMER)
                .build();
        when(estimateRepository.findById(any())).thenReturn(Optional.of(estimate));
        when(extCustomerPartyReplicaRepository.findById(any()))
                .thenReturn(
                        java.util.Optional.of(com.positivity.workorder.internal.entity.ExtCustomerPartyReplica.builder()
                                .requirementsMet(true)
                                .build()));
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(any(), any()))
                .thenReturn(List.of());
        when(workorderRepository.existsByWorkorderNumber(any())).thenReturn(false);
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createWorkorder(estimate.getId(), CUSTOMER);

        assertThat(captureSaved().getWorkorderNumber()).isEqualTo("WO-2026-1001");
    }

    @Test
    void fromEstimate_fallsBackToSequenceWhenSwapTaken() {
        Estimate estimate = Estimate.builder()
                .id(UUID.randomUUID())
                .estimateNumber("EST-2026-1001")
                .customerId(CUSTOMER)
                .build();
        when(estimateRepository.findById(any())).thenReturn(Optional.of(estimate));
        when(extCustomerPartyReplicaRepository.findById(any()))
                .thenReturn(
                        java.util.Optional.of(com.positivity.workorder.internal.entity.ExtCustomerPartyReplica.builder()
                                .requirementsMet(true)
                                .build()));
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(any(), any()))
                .thenReturn(List.of());
        when(workorderRepository.existsByWorkorderNumber("WO-2026-1001")).thenReturn(true);
        when(workorderRepository.existsByWorkorderNumber("WO-2026-1000")).thenReturn(false);
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createWorkorder(estimate.getId(), CUSTOMER);

        assertThat(captureSaved().getWorkorderNumber()).isEqualTo("WO-2026-1000");
    }

    @Test
    void withoutEstimate_usesSequence() {
        when(extCustomerPartyReplicaRepository.findById(any()))
                .thenReturn(
                        java.util.Optional.of(com.positivity.workorder.internal.entity.ExtCustomerPartyReplica.builder()
                                .requirementsMet(true)
                                .build()));
        when(workorderRepository.existsByWorkorderNumber(any())).thenReturn(false);
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createWorkorder(null, CUSTOMER);

        assertThat(captureSaved().getWorkorderNumber()).isEqualTo("WO-2026-1000");
    }

    @Test
    void fromEstimate_holdsTheYearScopeLockWhileCheckingTheSwappedNumber() {
        Estimate estimate = Estimate.builder()
                .id(UUID.randomUUID())
                .estimateNumber("EST-2026-1001")
                .customerId(CUSTOMER)
                .build();
        stubPromotableEstimate(estimate);
        when(workorderRepository.existsByWorkorderNumber(any())).thenReturn(false);
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createWorkorder(estimate.getId(), CUSTOMER);

        InOrder order = inOrder(documentNumberAllocator, workorderRepository);
        order.verify(documentNumberAllocator).lockScope("WO-2026", 1000L);
        order.verify(workorderRepository).existsByWorkorderNumber("WO-2026-1001");
        order.verify(workorderRepository).save(any());
    }

    @Test
    void withoutEstimate_drawsFromTheTenantYearScope() {
        stubRequirementsMet();
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createWorkorder(null, CUSTOMER);

        verify(documentNumberAllocator).allocate(eq("WO-2026"), eq("WO-2026-"), eq(1000L), any());
    }

    @Test
    void numberCollisionOnInsert_isATypedConflictNotAServerError() {
        stubRequirementsMet();
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException(
                        "could not execute statement [ERROR: duplicate key value violates unique constraint"
                                + " \"ux_workorder_workorder_number\"]"))
                .when(workorderRepository)
                .flush();

        assertThatThrownBy(() -> service.createWorkorder(null, CUSTOMER))
                .isInstanceOf(DocumentNumberConflictException.class)
                .hasMessageContaining("WO-2026-1000");
    }

    @Test
    void otherIntegrityViolationOnInsert_isNotReportedAsANumberCollision() {
        stubRequirementsMet();
        when(workorderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("violates foreign key constraint \"workorder_estimate_fkey\""))
                .when(workorderRepository)
                .flush();

        assertThatThrownBy(() -> service.createWorkorder(null, CUSTOMER))
                .isExactlyInstanceOf(DataIntegrityViolationException.class);
    }

    private void stubRequirementsMet() {
        when(extCustomerPartyReplicaRepository.findById(any()))
                .thenReturn(Optional.of(com.positivity.workorder.internal.entity.ExtCustomerPartyReplica.builder()
                        .requirementsMet(true)
                        .build()));
    }

    private void stubPromotableEstimate(Estimate estimate) {
        when(estimateRepository.findById(any())).thenReturn(Optional.of(estimate));
        stubRequirementsMet();
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(any(), any()))
                .thenReturn(List.of());
    }

    private Workorder captureSaved() {
        ArgumentCaptor<Workorder> captor = ArgumentCaptor.forClass(Workorder.class);
        verify(workorderRepository).save(captor.capture());
        return captor.getValue();
    }
}
