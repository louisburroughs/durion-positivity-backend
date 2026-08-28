package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.WorkorderPartUsageEventResponse;
import com.positivity.workorder.internal.dto.WorkorderPartsUsageMapper;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderPartUsageEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Parts usage tracking (CAP:005 story #158): issue / consume / return move quantities on a
 * workorder part line, each operation guarded by its own idempotency namespace and by the
 * issued &gt;= consumed + returned invariant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderPartUsageServiceImpl")
class WorkorderPartUsageServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd11");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd12");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd13");
    private static final UUID EVENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd14");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static final String ISSUE_OPERATION = "part-usage.issue";
    private static final String CONSUME_OPERATION = "part-usage.consume";
    private static final String RETURN_OPERATION = "part-usage.return";

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
    private PartAvailabilityService partAvailabilityService;

    @Mock
    private WorkorderStateMachine workorderStateMachine;

    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<
                    com.positivity.workorder.internal.config.InventoryCommandPublisher>
            inventoryCommandPublisher =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

    private WorkorderPartUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderPartUsageServiceImpl(
                workorderRepository,
                workorderPartRepository,
                usageEventRepository,
                idempotencyService,
                workorderFactPublisher,
                partAvailabilityService,
                org.mockito.Mockito.mock(PartQuantityDivisibilityService.class),
                workorderStateMachine,
                inventoryCommandPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        // These existing cases predate the CAP #1315 gate and are about idempotency, quantity
        // arithmetic and ownership checks, not stock. Default to covered so they keep asserting
        // what they were written to assert; the gate has its own tests.
        org.mockito.Mockito.lenient()
                .when(partAvailabilityService.covers(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder(WORKORDER_ID)));
        when(idempotencyService.getExistingPartUsageEventId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(usageEventRepository.save(any())).thenAnswer(invocation -> {
            WorkorderPartUsageEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                // The entity's id is assigned by the persistence provider and has no setter.
                ReflectionTestUtils.setField(event, "id", EVENT_ID);
            }
            return event;
        });
        when(workorderPartRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Workorder workorder(UUID id) {
        Workorder workorder = new Workorder();
        workorder.setId(id);
        return workorder;
    }

    /** A part attached directly to the workorder, with the supplied issued/consumed/returned totals. */
    private WorkorderPart part(String issued, String consumed, String returned) {
        return WorkorderPart.builder()
                .id(PART_ID)
                .workorder(workorder(WORKORDER_ID))
                .description("Oil filter")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("12.50"))
                .lineTotal(new BigDecimal("25.00"))
                .quantityIssued(new BigDecimal(issued))
                .quantityConsumed(new BigDecimal(consumed))
                .quantityReturned(new BigDecimal(returned))
                .build();
    }

    private void givenPart(WorkorderPart part) {
        when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.of(part));
    }

    @Nested
    @DisplayName("issuePartQuantity")
    class IssuePartQuantity {

        @Test
        @DisplayName("records an ISSUE event and adds to the issued total")
        void issuesQuantity() {
            WorkorderPart part = part("0", "0", "0");
            givenPart(part);

            WorkorderPartUsageEventResponse event =
                    service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null, null);

            assertThat(event.getEventType()).isEqualTo("ISSUE");
            assertThat(event.getQuantity()).isEqualByComparingTo("2");
            assertThat(event.getPerformedBy()).isEqualTo("jane.smith");
            assertThat(event.getPerformedAt()).isEqualTo(NOW);
            assertThat(event.getWorkorderId()).isEqualTo(WORKORDER_ID);
            assertThat(part.getQuantityIssued()).isEqualByComparingTo("2");
            verify(workorderPartRepository).save(part);
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("adds to a non-zero issued total rather than replacing it")
        void accumulatesIssuedQuantity() {
            WorkorderPart part = part("3", "1", "0");
            givenPart(part);

            service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("1.5"), null, null);

            assertThat(part.getQuantityIssued()).isEqualByComparingTo("4.5");
        }

        @Test
        @DisplayName("registers the idempotency key under the issue operation")
        void registersIdempotencyKey() {
            givenPart(part("0", "0", "0"));

            service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "issue-key-1");

            verify(idempotencyService).markKeyProcessedForPartUsage(ISSUE_OPERATION, "issue-key-1", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            WorkorderPartUsageEvent existing = WorkorderPartUsageEvent.builder()
                    .id(EVENT_ID)
                    .workorderPart(part("0", "0", "0"))
                    .workorderId(WORKORDER_ID)
                    .eventType("ISSUE")
                    .quantity(BigDecimal.ONE)
                    .performedBy("jane.smith")
                    .performedAt(NOW)
                    .build();
            when(idempotencyService.getExistingPartUsageEventId(ISSUE_OPERATION, "issue-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            WorkorderPartUsageEventResponse event =
                    service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "issue-key-1");

            assertThat(event).isEqualTo(WorkorderPartsUsageMapper.toResponse(existing));
            verify(usageEventRepository, never()).save(any());
            verify(workorderPartRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartUsageEventId(ISSUE_OPERATION, "issue-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                            () -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "issue-key-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Event not found");
        }

        @Test
        @DisplayName("treats a blank idempotency key as absent")
        void blankKeyIsNotIdempotent() {
            givenPart(part("0", "0", "0"));

            service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "   ");

            verify(idempotencyService, never()).getExistingPartUsageEventId(anyString(), anyString());
            verify(idempotencyService, never()).markKeyProcessedForPartUsage(anyString(), anyString(), any());
            verify(usageEventRepository).save(any());
        }

        @Test
        @DisplayName("rejects a zero or negative quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ZERO, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Quantity must be positive");
            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("-1"), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Quantity must be positive");
        }

        @Test
        @DisplayName("rejects an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(OTHER_WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.issuePartQuantity(OTHER_WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(WorkorderNotFoundException.class);
        }

        @Test
        @DisplayName("rejects an unknown part line")
        void rejectsUnknownPart() {
            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part not found");
        }

        @Test
        @DisplayName("rejects a part that belongs to a different workorder")
        void rejectsPartFromAnotherWorkorder() {
            WorkorderPart foreign = part("0", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("fails when the part is orphaned from any workorder")
        void failsWhenPartHasNoWorkorder() {
            WorkorderPart orphan = part("0", "0", "0");
            orphan.setWorkorder(null);
            orphan.setWorkOrderService(null);
            givenPart(orphan);

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Part has no associated workorder");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("consumePartQuantity")
    class ConsumePartQuantity {

        @Test
        @DisplayName("records a CONSUME event and adds to the consumed total")
        void consumesQuantity() {
            WorkorderPart part = part("4", "1", "0");
            givenPart(part);

            WorkorderPartUsageEventResponse event =
                    service.consumePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null, null);

            assertThat(event.getEventType()).isEqualTo("CONSUME");
            assertThat(part.getQuantityConsumed()).isEqualByComparingTo("3");
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("allows consuming exactly the issued quantity")
        void allowsConsumingAllIssued() {
            WorkorderPart part = part("4", "1", "0");
            givenPart(part);

            service.consumePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("3"), null, null);

            assertThat(part.getQuantityConsumed()).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("rejects consumption beyond the issued quantity")
        void rejectsOverConsumption() {
            givenPart(part("4", "1", "0"));

            assertThatThrownBy(
                            () -> service.consumePartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("3.5"), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Consumption exceeds issued quantity");
            verify(usageEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a non-positive quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ZERO, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Quantity must be positive");
        }

        @Test
        @DisplayName("registers the idempotency key under the consume operation")
        void registersIdempotencyKey() {
            givenPart(part("4", "0", "0"));

            service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "consume-key-1");

            verify(idempotencyService).markKeyProcessedForPartUsage(CONSUME_OPERATION, "consume-key-1", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            WorkorderPartUsageEvent existing = WorkorderPartUsageEvent.builder()
                    .id(EVENT_ID)
                    .workorderPart(part("0", "0", "0"))
                    .workorderId(WORKORDER_ID)
                    .eventType("ISSUE")
                    .quantity(BigDecimal.ONE)
                    .performedBy("jane.smith")
                    .performedAt(NOW)
                    .build();
            when(idempotencyService.getExistingPartUsageEventId(CONSUME_OPERATION, "consume-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            assertThat(service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "consume-key-1"))
                    .isEqualTo(WorkorderPartsUsageMapper.toResponse(existing));
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartUsageEventId(CONSUME_OPERATION, "consume-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "consume-key-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Event not found");
        }

        @Test
        @DisplayName("rejects an unknown workorder, unknown part, and a foreign part")
        void rejectsUnresolvableTargets() {
            when(workorderRepository.findById(OTHER_WORKORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(
                            () -> service.consumePartQuantity(OTHER_WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(WorkorderNotFoundException.class);

            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part not found");

            WorkorderPart foreign = part("4", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);
            assertThatThrownBy(() -> service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("returnPartQuantity")
    class ReturnPartQuantity {

        @Test
        @DisplayName("records a RETURN event and adds to the returned total")
        void returnsQuantity() {
            WorkorderPart part = part("5", "2", "1");
            givenPart(part);

            WorkorderPartUsageEventResponse event =
                    service.returnPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), null, null);

            assertThat(event.getEventType()).isEqualTo("RETURN");
            assertThat(part.getQuantityReturned()).isEqualByComparingTo("3");
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("rejects a return beyond issued minus consumed minus already returned")
        void rejectsOverReturn() {
            givenPart(part("5", "2", "1"));

            assertThatThrownBy(
                            () -> service.returnPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2.5"), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Return quantity exceeds available");
            verify(usageEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a non-positive quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(
                            () -> service.returnPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("-2"), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Quantity must be positive");
        }

        @Test
        @DisplayName("registers the idempotency key under the return operation")
        void registersIdempotencyKey() {
            givenPart(part("5", "0", "0"));

            service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "return-key-1");

            verify(idempotencyService).markKeyProcessedForPartUsage(RETURN_OPERATION, "return-key-1", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            WorkorderPartUsageEvent existing = WorkorderPartUsageEvent.builder()
                    .id(EVENT_ID)
                    .workorderPart(part("0", "0", "0"))
                    .workorderId(WORKORDER_ID)
                    .eventType("ISSUE")
                    .quantity(BigDecimal.ONE)
                    .performedBy("jane.smith")
                    .performedAt(NOW)
                    .build();
            when(idempotencyService.getExistingPartUsageEventId(RETURN_OPERATION, "return-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existing));

            assertThat(service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "return-key-1"))
                    .isEqualTo(WorkorderPartsUsageMapper.toResponse(existing));
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartUsageEventId(RETURN_OPERATION, "return-key-1"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(usageEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "return-key-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Event not found");
        }

        @Test
        @DisplayName("rejects an unknown workorder, unknown part, and a foreign part")
        void rejectsUnresolvableTargets() {
            when(workorderRepository.findById(OTHER_WORKORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(
                            () -> service.returnPartQuantity(OTHER_WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(WorkorderNotFoundException.class);

            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part not found");

            WorkorderPart foreign = part("5", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);
            assertThatThrownBy(() -> service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("usage history")
    class UsageHistory {

        private WorkorderPartUsageEvent event(String type, String quantity) {
            WorkorderPart part = part("5", "1", "0");
            return WorkorderPartUsageEvent.builder()
                    .id(EVENT_ID)
                    .workorderPart(part)
                    .workorderId(WORKORDER_ID)
                    .eventType(type)
                    .quantity(new BigDecimal(quantity))
                    .performedBy("jane.smith")
                    .performedAt(NOW)
                    .notes("shelf stock")
                    .build();
        }

        @Test
        @DisplayName("maps a part's events to responses")
        void mapsPartHistory() {
            givenPart(part("5", "1", "0"));
            when(usageEventRepository.findByWorkorderPartIdOrderByPerformedAtDesc(PART_ID))
                    .thenReturn(List.of(event("ISSUE", "5"), event("CONSUME", "1")));

            List<WorkorderPartUsageEventResponse> history = service.getUsageHistory(WORKORDER_ID, PART_ID);

            assertThat(history).hasSize(2);
            WorkorderPartUsageEventResponse first = history.get(0);
            assertThat(first.getEventType()).isEqualTo("ISSUE");
            assertThat(first.getQuantity()).isEqualByComparingTo("5");
            assertThat(first.getWorkorderPartId()).isEqualTo(PART_ID);
            assertThat(first.getWorkorderId()).isEqualTo(WORKORDER_ID);
            assertThat(first.getPerformedBy()).isEqualTo("jane.smith");
            assertThat(first.getPerformedAt()).isEqualTo(NOW);
            assertThat(first.getNotes()).isEqualTo("shelf stock");
            assertThat(first.getPartDescription()).isEqualTo("Oil filter");
        }

        @Test
        @DisplayName("resolves the owning workorder through the parent service line")
        void resolvesWorkorderThroughServiceLine() {
            WorkorderServiceLine line = new WorkorderServiceLine();
            line.setWorkOrder(workorder(WORKORDER_ID));
            WorkorderPart serviceLinked = part("5", "1", "0");
            serviceLinked.setWorkorder(null);
            serviceLinked.setWorkOrderService(line);
            givenPart(serviceLinked);
            when(usageEventRepository.findByWorkorderPartIdOrderByPerformedAtDesc(PART_ID))
                    .thenReturn(List.of(event("ISSUE", "5")));

            assertThat(service.getUsageHistory(WORKORDER_ID, PART_ID)).hasSize(1);
        }

        @Test
        @DisplayName("rejects history for an unknown workorder, unknown part, or foreign part")
        void rejectsUnresolvableTargets() {
            when(workorderRepository.findById(OTHER_WORKORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getUsageHistory(OTHER_WORKORDER_ID, PART_ID))
                    .isInstanceOf(WorkorderNotFoundException.class);

            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getUsageHistory(WORKORDER_ID, PART_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part not found");

            WorkorderPart foreign = part("5", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);
            assertThatThrownBy(() -> service.getUsageHistory(WORKORDER_ID, PART_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("maps every event on the workorder")
        void mapsWorkorderHistory() {
            when(usageEventRepository.findByWorkorderIdOrderByPerformedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of(event("RETURN", "2")));

            List<WorkorderPartUsageEventResponse> history = service.getAllUsageHistory(WORKORDER_ID);

            assertThat(history).singleElement().satisfies(response -> {
                assertThat(response.getEventType()).isEqualTo("RETURN");
                assertThat(response.getQuantity()).isEqualByComparingTo("2");
            });
        }

        @Test
        @DisplayName("rejects workorder-wide history for an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(OTHER_WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAllUsageHistory(OTHER_WORKORDER_ID))
                    .isInstanceOf(WorkorderNotFoundException.class);
        }

        @Test
        @DisplayName("returns an empty history when nothing has been recorded")
        void emptyHistory() {
            when(usageEventRepository.findByWorkorderIdOrderByPerformedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of());

            assertThat(service.getAllUsageHistory(WORKORDER_ID)).isEmpty();
        }
    }

    @Test
    @DisplayName("issue, consume, and return use separate idempotency namespaces")
    void operationsUseSeparateIdempotencyNamespaces() {
        givenPart(part("10", "0", "0"));

        service.issuePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "shared-key");
        service.consumePartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "shared-key");
        service.returnPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, null, "shared-key");

        verify(idempotencyService).getExistingPartUsageEventId(eq(ISSUE_OPERATION), eq("shared-key"));
        verify(idempotencyService).getExistingPartUsageEventId(eq(CONSUME_OPERATION), eq("shared-key"));
        verify(idempotencyService).getExistingPartUsageEventId(eq(RETURN_OPERATION), eq("shared-key"));
    }
}
