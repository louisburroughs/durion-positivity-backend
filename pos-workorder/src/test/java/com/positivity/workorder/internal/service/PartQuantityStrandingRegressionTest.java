package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderPartUsageEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The stranding guard behind the gate placement (ADR-0055, #1413).
 *
 * <p>{@code outstandingQuantity = quantity - quantityIssued} is what holds a job in
 * {@code AWAITING_PARTS}. If a fractional quantity could reach {@code workorder_part} while issue
 * is restricted to whole units, that subtraction would never reach zero: the technician issues the
 * whole units, is refused the remainder, and the job waits on parts forever with nothing anyone can
 * do about it. That is why the rule is enforced where the quantity is *authored* — estimate entry
 * and promotion — and not only where it is spent.
 *
 * <p>These two cases are the ends of that argument: nothing fractional gets promoted, and what does
 * get promoted issues down to zero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Fractional part quantities cannot strand a workorder (#1413)")
class PartQuantityStrandingRegressionTest {

    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee01");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee02");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee03");
    private static final UUID SHOP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee04");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee05");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee06");

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

    @Mock
    private ExtInventoryAvailabilityReplicaRepository availabilityRepository;

    @Mock
    private WorkorderPartUsageEventRepository usageEventRepository;

    @Mock
    private InventoryCommandPublisher inventoryCommandPublisher;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<InventoryCommandPublisher> publisherProvider = mock(ObjectProvider.class);

    private PartQuantityDivisibilityService divisibility;
    private WorkorderServiceImpl workorderService;

    @Mock
    private PromotedWorkorderDemandPublisher promotedWorkorderDemandPublisher;

    private WorkorderPartUsageServiceImpl partUsageService;

    @BeforeEach
    void setUp() {
        // No product declares a unit-of-measure scale, which is the platform's actual state:
        // product_uom is unpopulated, so every product is whole-unit.
        when(productUomRepository.findBasePrecisionScales(any())).thenReturn(List.of());
        divisibility = new PartQuantityDivisibilityService(productUomRepository);

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
                divisibility);

        when(publisherProvider.getIfAvailable()).thenReturn(inventoryCommandPublisher);
        partUsageService = new WorkorderPartUsageServiceImpl(
                workorderRepository,
                workorderPartRepository,
                usageEventRepository,
                idempotencyService,
                workorderFactPublisher,
                new PartAvailabilityService(availabilityRepository),
                divisibility,
                workorderStateMachine,
                publisherProvider,
                clock);

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(usageEventRepository.save(any(WorkorderPartUsageEvent.class))).thenAnswer(i -> {
            WorkorderPartUsageEvent event = i.getArgument(0);
            if (event.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
            }
            return event;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("Promotion")
    class Promotion {

        @Test
        @DisplayName("refuses to promote a fractional PART item onto the workorder")
        void refusesFractionalPartItem() {
            givenPromotableEstimate(estimateItem(EstimateItemType.PART, PRODUCT_ID, "4.5"));

            assertThatThrownBy(() -> workorderService.createWorkorder(promotedWorkorder()))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("whole units");

            // Nothing lands on workorder_part: the line that could never be issued to zero does
            // not come into existence.
            verify(workorderPartRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("promotes an integral PART item unchanged")
        void promotesIntegralPartItem() {
            givenPromotableEstimate(estimateItem(EstimateItemType.PART, PRODUCT_ID, "2"));

            workorderService.createWorkorder(promotedWorkorder());

            verify(workorderPartRepository).saveAll(any());
        }

        @Test
        @DisplayName("leaves a fractional LABOR item alone — 1.5 hours is a real thing to sell")
        void leavesFractionalLaborAlone() {
            givenPromotableEstimate(estimateItem(EstimateItemType.LABOR, null, "1.5"));

            workorderService.createWorkorder(promotedWorkorder());

            verify(workorderServiceRepository).saveAll(any());
            verify(workorderPartRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("Issuing to completion")
    class IssuingToCompletion {

        @Test
        @DisplayName("an integral part issues down to zero outstanding without touching AWAITING_PARTS")
        void issuesDownToZeroOutstanding() {
            Workorder workorder = new Workorder();
            workorder.setId(WORKORDER_ID);
            workorder.setShopId(SHOP_ID);
            workorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);

            WorkorderPart part = new WorkorderPart();
            part.setId(PART_ID);
            part.setProductEntityId(PRODUCT_ID);
            part.setDescription("Brake pad");
            part.setQuantity(new BigDecimal("2"));
            part.setQuantityIssued(BigDecimal.ZERO);
            Workorder owner = new Workorder();
            owner.setId(WORKORDER_ID);
            part.setWorkorder(owner);

            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.of(part));
            when(availabilityRepository.findByStockItemIdAndLocationId(PRODUCT_ID.toString(), SHOP_ID))
                    .thenReturn(Optional.of(ExtInventoryAvailabilityReplica.builder()
                            .aggregateId(UUID.randomUUID())
                            .stockItemId(PRODUCT_ID.toString())
                            .locationId(SHOP_ID)
                            .onHandQuantity(BigDecimal.valueOf(10))
                            .allocatedQuantity(BigDecimal.ZERO)
                            .availableToPromiseQuantity(BigDecimal.valueOf(10))
                            .aggregateVersion(1L)
                            .updatedAt(NOW)
                            .build()));

            partUsageService.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null);
            partUsageService.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null);

            // Outstanding reaches exactly zero — the condition that releases the job from
            // AWAITING_PARTS — rather than the 0.5 remainder a fractional line would leave behind.
            assertThat(part.getQuantity().subtract(part.getQuantityIssued())).isEqualByComparingTo("0");
            verify(workorderStateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }
    }

    private EstimateItem estimateItem(EstimateItemType type, UUID productId, String quantity) {
        return EstimateItem.builder()
                .id(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee07"))
                .itemType(type)
                .description("Valvoline MaxLife ATF 1qt")
                .quantity(new BigDecimal(quantity))
                .unitPrice(new BigDecimal("12.00"))
                .lineTotal(new BigDecimal("54.00"))
                .productId(productId)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();
    }

    private void givenPromotableEstimate(EstimateItem approvedItem) {
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
                .thenReturn(List.of(approvedItem));
        when(estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                        ESTIMATE_ID, ApprovalStatus.DECLINED))
                .thenReturn(List.of());
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
}
