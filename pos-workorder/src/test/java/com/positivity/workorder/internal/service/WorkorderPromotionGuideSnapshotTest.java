package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Promotion must carry the guide-time snapshot forward (#1569, sourcing plan §6.3 item 2): both
 * promotion builder sites — approved LABOR items and declined recommendations — copy the six
 * guide columns from the estimate item onto the workorder service line, because the
 * overlap-aware estimated-hours sum reads them from the workorder side after promotion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Promotion copies the guide-time snapshot onto workorder service lines (#1569)")
class WorkorderPromotionGuideSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-09-02T09:00:00Z");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa01");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa02");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa03");
    private static final UUID SHOP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa04");
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa05");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

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
    private WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private PromotedWorkorderDemandPublisher promotedWorkorderDemandPublisher;

    @Mock
    private WorkorderStateMachine workorderStateMachine;

    @Mock
    private WorkorderLaborEntryRepository workorderLaborEntryRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private FleetAuthorizationService fleetAuthorizationService;

    @Mock
    private ExtProductUomReplicaRepository productUomRepository;

    private WorkorderServiceImpl workorderService;

    @BeforeEach
    void setUp() {
        when(productUomRepository.findBasePrecisionScales(any())).thenReturn(List.of());

        workorderService = new WorkorderServiceImpl(
                clock,
                workorderRepository,
                estimateRepository,
                estimateItemRepository,
                workorderServiceRepository,
                workorderPartRepository,
                extCustomerPartyReplicaRepository,
                workorderFactPublisher,
                promotedWorkorderDemandPublisher,
                workorderStateMachine,
                workorderLaborEntryRepository,
                applicationEventPublisher,
                auditEventRepository,
                idempotencyService,
                promotionValidationService,
                peopleAvailabilityLocalService,
                fleetAuthorizationService,
                new PartQuantityDivisibilityService(productUomRepository));

        when(extCustomerPartyReplicaRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtCustomerPartyReplica.builder()
                        .partyId(CUSTOMER_ID)
                        .requirementsMet(true)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(i -> {
            Workorder saved = i.getArgument(0);
            saved.setId(WORKORDER_ID);
            return saved;
        });
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of());
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.DECLINED))
                .thenReturn(List.of());
    }

    private static EstimateItem laborItemWithGuideSnapshot(ApprovalStatus approvalStatus) {
        return EstimateItem.builder()
                .id(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa06"))
                .itemType(EstimateItemType.LABOR)
                .description("Front brake pads")
                .quantity(new BigDecimal("1.5"))
                .unitPrice(new BigDecimal("120.00"))
                .lineTotal(new BigDecimal("180.00"))
                .serviceId(SERVICE_ID)
                .guideHours(new BigDecimal("1.5"))
                .guideSourceCode("MOCKGUIDE")
                .guideSourceRevision("2026-09-01")
                .guideMatchGrade("EXACT")
                .guideOverlapGroup("WHEEL-OFF")
                .guideIncludedOpCodes("BRAKE-PAD-FRONT,BRAKE-HW-KIT")
                .approvalStatus(approvalStatus)
                .rejectionReason(approvalStatus == ApprovalStatus.DECLINED ? "too expensive" : null)
                .build();
    }

    private static void assertGuideSnapshotCopied(WorkorderServiceLine line) {
        assertThat(line.getGuideHours()).isEqualByComparingTo("1.5");
        assertThat(line.getGuideSourceCode()).isEqualTo("MOCKGUIDE");
        assertThat(line.getGuideSourceRevision()).isEqualTo("2026-09-01");
        assertThat(line.getGuideMatchGrade()).isEqualTo("EXACT");
        assertThat(line.getGuideOverlapGroup()).isEqualTo("WHEEL-OFF");
        assertThat(line.getGuideIncludedOpCodes()).isEqualTo("BRAKE-PAD-FRONT,BRAKE-HW-KIT");
    }

    private Workorder promotedWorkorder() {
        Estimate estimate = new Estimate();
        estimate.setId(ESTIMATE_ID);
        return Workorder.builder()
                .estimate(estimate)
                .customerId(CUSTOMER_ID)
                .shopId(SHOP_ID)
                .status(WorkorderStatus.DRAFT)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<WorkorderServiceLine> capturedSavedLines() {
        ArgumentCaptor<List<WorkorderServiceLine>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(workorderServiceRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an approved LABOR item promotes with all six guide columns copied")
    void approvedLaborItemCarriesGuideSnapshot() {
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.APPROVED))
                .thenReturn(List.of(laborItemWithGuideSnapshot(ApprovalStatus.APPROVED)));

        workorderService.createWorkorder(promotedWorkorder());

        List<WorkorderServiceLine> lines = capturedSavedLines();
        assertThat(lines).hasSize(1);
        WorkorderServiceLine line = lines.get(0);
        assertGuideSnapshotCopied(line);
        assertThat(line.getServiceEntityId()).isEqualTo(SERVICE_ID);
        assertThat(line.getDeclined()).isFalse();
    }

    @Test
    @DisplayName("a declined recommendation promotes with the same six guide columns copied")
    void declinedLaborItemCarriesGuideSnapshot() {
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.DECLINED))
                .thenReturn(List.of(laborItemWithGuideSnapshot(ApprovalStatus.DECLINED)));

        workorderService.createWorkorder(promotedWorkorder());

        List<WorkorderServiceLine> lines = capturedSavedLines();
        assertThat(lines).hasSize(1);
        WorkorderServiceLine line = lines.get(0);
        assertGuideSnapshotCopied(line);
        assertThat(line.getDeclined()).isTrue();
        assertThat(line.getDeclineReason()).isEqualTo("too expensive");
    }
}
