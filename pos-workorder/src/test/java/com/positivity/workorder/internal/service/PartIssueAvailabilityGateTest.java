package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderPartUsageEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.IdempotencyService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The per-part issue gate (CAP #1315). Before this, {@code WorkorderPartUsageServiceImpl} issued
 * whatever it was asked for — its own class comment said it checked nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Workorder part issue — owned-ATP gate (CAP #1315)")
class PartIssueAvailabilityGateTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc01");
    private static final UUID SHOP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc02");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc03");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc04");
    private static final UUID OTHER_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc06");

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private WorkorderPartUsageEventRepository usageEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private WorkorderStateMachine workorderStateMachine;

    @Mock
    private ExtInventoryAvailabilityReplicaRepository availabilityRepository;

    @Mock
    private ExtProductUomReplicaRepository productUomRepository;

    @Mock
    private InventoryCommandPublisher inventoryCommandPublisher;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<InventoryCommandPublisher> publisherProvider = mock(ObjectProvider.class);

    private WorkorderPartUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        PartAvailabilityService availability = new PartAvailabilityService(availabilityRepository);
        // No product declares a unit-of-measure scale: product_uom is unpopulated platform-wide,
        // so every product here resolves to whole units, which is the real-world default.
        when(productUomRepository.findBasePrecisionScales(any())).thenReturn(List.of());
        PartQuantityDivisibilityService divisibility = new PartQuantityDivisibilityService(productUomRepository);
        when(publisherProvider.getIfAvailable()).thenReturn(inventoryCommandPublisher);
        service = new WorkorderPartUsageServiceImpl(
                workorderRepository,
                workorderPartRepository,
                usageEventRepository,
                idempotencyService,
                workorderFactPublisher,
                availability,
                divisibility,
                workorderStateMachine,
                publisherProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(usageEventRepository.save(any(WorkorderPartUsageEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Workorder workorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setShopId(SHOP_ID);
        workorder.setStatus(status);
        return workorder;
    }

    private WorkorderPart part(UUID id, UUID productId, String qty, String issued) {
        WorkorderPart part = new WorkorderPart();
        part.setId(id);
        part.setProductEntityId(productId);
        part.setDescription("Brake pad");
        part.setQuantity(new BigDecimal(qty));
        part.setQuantityIssued(new BigDecimal(issued));
        return part;
    }

    private void givenAtp(UUID productId, int atp) {
        when(availabilityRepository.findByStockItemIdAndLocationId(productId.toString(), SHOP_ID))
                .thenReturn(Optional.of(ExtInventoryAvailabilityReplica.builder()
                        .aggregateId(UUID.randomUUID())
                        .stockItemId(productId.toString())
                        .locationId(SHOP_ID)
                        .onHandQuantity(atp)
                        .allocatedQuantity(0)
                        .availableToPromiseQuantity(atp)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    private void givenWorkorderAndPart(Workorder workorder, WorkorderPart part) {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderPartRepository.findById(part.getId())).thenReturn(Optional.of(part));
        Workorder owner = new Workorder();
        owner.setId(WORKORDER_ID);
        part.setWorkorder(owner);
    }

    @Nested
    @DisplayName("Issuing against owned stock")
    class Issuing {

        @Test
        @DisplayName("issues the part and registers demand when the site covers it")
        void issuesWhenCovered() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            givenAtp(PRODUCT_ID, 10);

            service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null);

            assertThat(part.getQuantityIssued()).isEqualByComparingTo("2");
            verify(inventoryCommandPublisher).requestReservation(PART_ID, PRODUCT_ID, 2, SHOP_ID);
        }

        @Test
        @DisplayName("rejects a fractional issue of a product declared in whole units")
        void rejectsFractionalIssueOfWholeUnitProduct() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            givenAtp(PRODUCT_ID, 10);

            // This used to be rounded up into a reservation of 1, holding half a unit against
            // demand nobody asked for (#1363). The quantity is now refused at the door instead.
            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("0.5"), null))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("Brake pad")
                    .hasMessageContaining("whole units")
                    .hasMessageContaining("Enter 1");

            assertThat(part.getQuantityIssued()).isEqualByComparingTo("0");
            verify(usageEventRepository, never()).save(any());
            verify(inventoryCommandPublisher, never())
                    .requestReservation(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("blocks the issue when owned stock at the site is short")
        void blocksWhenShort() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            givenAtp(PRODUCT_ID, 1);

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null))
                    .isInstanceOf(InsufficientPartAvailabilityException.class)
                    .hasMessageContaining("cannot be issued");

            // Nothing is recorded and no demand registered: the part did not leave the shelf.
            assertThat(part.getQuantityIssued()).isEqualByComparingTo("0");
            verify(usageEventRepository, never()).save(any());
            verify(inventoryCommandPublisher, never())
                    .requestReservation(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("blocks the issue when the replica has no row for the item")
        void blocksWhenReplicaSilent() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            when(availabilityRepository.findByStockItemIdAndLocationId(PRODUCT_ID.toString(), SHOP_ID))
                    .thenReturn(Optional.empty());

            // Absence is not evidence of stock; the pessimistic direction is the recoverable one.
            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("1"), null))
                    .isInstanceOf(InsufficientPartAvailabilityException.class);
        }

        @Test
        @DisplayName("does not gate a part that is not inventory at all")
        void doesNotGateNonInventoryPart() {
            WorkorderPart part = part(PART_ID, null, "1", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);

            // Labour and shop supplies carry no productEntityId; there is no owned stock to
            // measure and no catalog declaration to read, so neither gate applies and a fractional
            // quantity stays legitimate. Blocking them would stop jobs on items inventory never
            // tracked.
            service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("0.25"), null);

            assertThat(part.getQuantityIssued()).isEqualByComparingTo("0.25");
            verify(inventoryCommandPublisher, never())
                    .requestReservation(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("passes a divisible product's quantity but cannot reserve it until #1414")
        void divisibleProductPassesTheGateButCannotReserveYet() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            givenAtp(PRODUCT_ID, 10);
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(2));

            // The divisibility gate passes, but the reservation contract is still integer-only
            // until #1414 — so the issue fails loudly here rather than reserving a rounded amount.
            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("1.01"), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot reserve a fractional quantity");
        }
    }

    @Nested
    @DisplayName("Carrying the job to AWAITING_PARTS")
    class AwaitingParts {

        @Test
        @DisplayName("moves the workorder when nothing outstanding can be issued")
        void movesWhenWhollyBlocked() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), part);
            givenAtp(PRODUCT_ID, 0);
            when(workorderPartRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(List.of(part));

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null))
                    .isInstanceOf(InsufficientPartAvailabilityException.class);

            verify(workorderStateMachine)
                    .transitionWorkorder(eq(WORKORDER_ID), eq(WorkorderStatus.AWAITING_PARTS), any(), any());
        }

        @Test
        @DisplayName("leaves the workorder alone when another part can still be issued")
        void leavesStatusWhenWorkRemains() {
            WorkorderPart shortPart = part(PART_ID, PRODUCT_ID, "4", "0");
            WorkorderPart stockedPart = part(OTHER_PART_ID, OTHER_PRODUCT_ID, "3", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.WORK_IN_PROGRESS), shortPart);
            givenAtp(PRODUCT_ID, 0);
            givenAtp(OTHER_PRODUCT_ID, 9);
            when(workorderPartRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(List.of(shortPart, stockedPart));

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null))
                    .isInstanceOf(InsufficientPartAvailabilityException.class);

            // One item is blocked, the job is not. The technician can start another part.
            verify(workorderStateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not attempt the transition from a status the state table forbids")
        void skipsTransitionFromDisallowedStatus() {
            WorkorderPart part = part(PART_ID, PRODUCT_ID, "4", "0");
            givenWorkorderAndPart(workorder(WorkorderStatus.APPROVED), part);
            givenAtp(PRODUCT_ID, 0);

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null))
                    .isInstanceOf(InsufficientPartAvailabilityException.class);

            // WORK_IN_PROGRESS is the only source the transition table allows into AWAITING_PARTS.
            verify(workorderStateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }
    }
}
