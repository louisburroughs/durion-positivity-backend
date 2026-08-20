package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.WorkorderPartAdjustmentEventRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.service.IdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Part adjustments (CAP:005 story #157): substitutions, supplemental returns, and administrative
 * quantity corrections each append an immutable adjustment event and are replayable by
 * idempotency key.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderPartAdjustmentServiceImpl")
class WorkorderPartAdjustmentServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee11");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee12");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee13");
    private static final UUID SUBSTITUTE_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee14");
    private static final UUID NEW_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee15");
    private static final UUID EVENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee16");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static final String SUBSTITUTE_OPERATION = "part-adjustment.substitute";
    private static final String RETURN_OPERATION = "part-adjustment.additional-return";
    private static final String CORRECTION_OPERATION = "part-adjustment.correction";

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private WorkorderPartAdjustmentEventRepository adjustmentEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    private WorkorderPartAdjustmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderPartAdjustmentServiceImpl(
                workorderPartRepository,
                adjustmentEventRepository,
                idempotencyService,
                workorderFactPublisher,
                org.mockito.Mockito.mock(PartQuantityDivisibilityService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(idempotencyService.getExistingPartAdjustmentEventId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(workorderPartRepository.save(any())).thenAnswer(invocation -> {
            WorkorderPart part = invocation.getArgument(0);
            if (part.getId() == null) {
                part.setId(NEW_PART_ID);
            }
            return part;
        });
        when(adjustmentEventRepository.save(any())).thenAnswer(invocation -> {
            WorkorderPartAdjustmentEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(EVENT_ID);
            }
            return event;
        });
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

    private WorkorderPart part(String issued, String consumed, String returned) {
        return WorkorderPart.builder()
                .id(PART_ID)
                .workorder(workorder(WORKORDER_ID))
                .description("Brake pad set")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("49.99"))
                .lineTotal(new BigDecimal("99.98"))
                .taxCode("TX-STD")
                .status(WorkorderItemStatus.OPEN)
                .quantityIssued(new BigDecimal(issued))
                .quantityConsumed(new BigDecimal(consumed))
                .quantityReturned(new BigDecimal(returned))
                .build();
    }

    private void givenPart(WorkorderPart part) {
        when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.of(part));
    }

    private WorkorderPartAdjustmentEvent savedEvent() {
        ArgumentCaptor<WorkorderPartAdjustmentEvent> captor =
                ArgumentCaptor.forClass(WorkorderPartAdjustmentEvent.class);
        verify(adjustmentEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private WorkorderPartAdjustmentEvent existingEvent(String type) {
        return WorkorderPartAdjustmentEvent.builder()
                .id(EVENT_ID)
                .originalPart(part("2", "0", "0"))
                .workorderId(WORKORDER_ID)
                .adjustmentType(type)
                .quantityAdjustment(BigDecimal.ONE)
                .reason("replayed")
                .performedBy("jane.smith")
                .performedAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("substitutePartLine")
    class SubstitutePartLine {

        @Test
        @DisplayName("mirrors the original line onto a new part and records a SUBSTITUTION event")
        void substitutesPart() {
            WorkorderPart original = part("2", "0", "0");
            givenPart(original);

            WorkorderPartAdjustmentEventResponse response = service.substitutePartLine(
                    WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, "supplier backorder");

            ArgumentCaptor<WorkorderPart> partCaptor = ArgumentCaptor.forClass(WorkorderPart.class);
            verify(workorderPartRepository).save(partCaptor.capture());
            WorkorderPart substitute = partCaptor.getValue();
            assertThat(substitute.getProductEntityId()).isEqualTo(SUBSTITUTE_PRODUCT_ID);
            assertThat(substitute.getDescription()).isEqualTo("Brake pad set (Substitute)");
            assertThat(substitute.getQuantity()).isEqualByComparingTo("2");
            assertThat(substitute.getUnitPrice()).isEqualByComparingTo("49.99");
            assertThat(substitute.getLineTotal()).isEqualByComparingTo("99.98");
            assertThat(substitute.getTaxCode()).isEqualTo("TX-STD");
            assertThat(substitute.getStatus()).isEqualTo(WorkorderItemStatus.OPEN);
            assertThat(substitute.getQuantityIssued()).isEqualByComparingTo("0");

            assertThat(response.getAdjustmentType()).isEqualTo("SUBSTITUTION");
            assertThat(response.getSubstitutedWithPartId()).isEqualTo(NEW_PART_ID);
            assertThat(response.getQuantityAdjustment()).isEqualByComparingTo("0");
            assertThat(response.getReason()).isEqualTo("OUT_OF_STOCK");
            assertThat(response.getNotes()).isEqualTo("supplier backorder");
            assertThat(response.getPerformedBy()).isEqualTo("jane.smith");
            assertThat(response.getPerformedAt()).isEqualTo(NOW);
            assertThat(response.getOriginalPartId()).isEqualTo(PART_ID);
            assertThat(response.getOriginalPartDescription()).isEqualTo("Brake pad set");
            assertThat(response.getSubstitutedWithPartDescription()).isNull();

            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("leaves the original part in place for history")
        void keepsOriginalPart() {
            WorkorderPart original = part("2", "0", "0");
            givenPart(original);

            service.substitutePartLine(WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null);

            verify(workorderPartRepository, never()).delete(any());
            assertThat(savedEvent().getOriginalPart()).isSameAs(original);
        }

        @Test
        @DisplayName("registers the idempotency key under the substitute operation")
        void registersIdempotencyKey() {
            givenPart(part("2", "0", "0"));

            service.substitutePartLine(WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", "sub-key", null);

            verify(idempotencyService).markKeyProcessedForPartAdjustment(SUBSTITUTE_OPERATION, "sub-key", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            when(idempotencyService.getExistingPartAdjustmentEventId(SUBSTITUTE_OPERATION, "sub-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existingEvent("SUBSTITUTION")));

            WorkorderPartAdjustmentEventResponse response = service.substitutePartLine(
                    WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", "sub-key", null);

            assertThat(response.getId()).isEqualTo(EVENT_ID);
            assertThat(response.getReason()).isEqualTo("replayed");
            verify(adjustmentEventRepository, never()).save(any());
            verify(workorderPartRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartAdjustmentEventId(SUBSTITUTE_OPERATION, "sub-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", "sub-key", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Adjustment event not found");
        }

        @Test
        @DisplayName("rejects an unknown original part")
        void rejectsUnknownPart() {
            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Original part not found");
        }

        @Test
        @DisplayName("rejects a part that belongs to a different workorder")
        void rejectsForeignPart() {
            WorkorderPart foreign = part("2", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("refuses to substitute a part that has already been consumed")
        void rejectsConsumedPart() {
            givenPart(part("2", "1", "0"));

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot substitute part that has already been consumed");
        }

        @Test
        @DisplayName("refuses to substitute a part with itself")
        void rejectsSelfSubstitution() {
            givenPart(part("2", "0", "0"));

            assertThatThrownBy(() ->
                            service.substitutePartLine(WORKORDER_ID, PART_ID, PART_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Substitute part must be different from original part");
        }

        @Test
        @DisplayName("fails when the part is orphaned from any workorder")
        void failsWhenPartHasNoWorkorder() {
            WorkorderPart orphan = part("2", "0", "0");
            orphan.setWorkorder(null);
            orphan.setWorkOrderService(null);
            givenPart(orphan);

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Part has no workorder reference");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.substitutePartLine(
                            WORKORDER_ID, PART_ID, SUBSTITUTE_PRODUCT_ID, "OUT_OF_STOCK", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("returnUnusedQuantity")
    class ReturnUnusedQuantity {

        @Test
        @DisplayName("records a negative ADDITIONAL_RETURN adjustment and grows the returned total")
        void returnsQuantity() {
            WorkorderPart part = part("5", "2", "1");
            givenPart(part);

            WorkorderPartAdjustmentEventResponse response = service.returnUnusedQuantity(
                    WORKORDER_ID, PART_ID, new BigDecimal("2"), "CUSTOMER_DECLINED", null, "left on shelf");

            assertThat(part.getQuantityReturned()).isEqualByComparingTo("3");
            assertThat(response.getAdjustmentType()).isEqualTo("ADDITIONAL_RETURN");
            assertThat(response.getQuantityAdjustment()).isEqualByComparingTo("-2");
            assertThat(response.getSubstitutedWithPartId()).isNull();
            assertThat(response.getNotes()).isEqualTo("left on shelf");
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("allows returning exactly the available quantity")
        void allowsReturningAllAvailable() {
            WorkorderPart part = part("5", "2", "1");
            givenPart(part);

            service.returnUnusedQuantity(WORKORDER_ID, PART_ID, new BigDecimal("2"), "reason", null, null);

            assertThat(part.getQuantityReturned()).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("rejects a return beyond issued minus consumed minus already returned")
        void rejectsOverReturn() {
            givenPart(part("5", "2", "1"));

            assertThatThrownBy(() -> service.returnUnusedQuantity(
                            WORKORDER_ID, PART_ID, new BigDecimal("2.5"), "reason", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds available quantity");
            verify(adjustmentEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a non-positive quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(
                            () -> service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ZERO, "r", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Return quantity must be positive");
        }

        @Test
        @DisplayName("registers the idempotency key under the additional-return operation")
        void registersIdempotencyKey() {
            givenPart(part("5", "0", "0"));

            service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "reason", "ret-key", null);

            verify(idempotencyService).markKeyProcessedForPartAdjustment(RETURN_OPERATION, "ret-key", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            when(idempotencyService.getExistingPartAdjustmentEventId(RETURN_OPERATION, "ret-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID))
                    .thenReturn(Optional.of(existingEvent("ADDITIONAL_RETURN")));

            assertThat(service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "reason", "ret-key", null)
                            .getAdjustmentType())
                    .isEqualTo("ADDITIONAL_RETURN");
            verify(workorderPartRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartAdjustmentEventId(RETURN_OPERATION, "ret-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.returnUnusedQuantity(
                            WORKORDER_ID, PART_ID, BigDecimal.ONE, "reason", "ret-key", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Adjustment event not found");
        }

        @Test
        @DisplayName("rejects an unknown part and a foreign part")
        void rejectsUnresolvableTargets() {
            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(
                            () -> service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Part not found");

            WorkorderPart foreign = part("5", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);
            assertThatThrownBy(
                            () -> service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(
                            () -> service.returnUnusedQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("correctPartQuantity")
    class CorrectPartQuantity {

        @Test
        @DisplayName("raises the authorized quantity and recomputes the line total")
        void correctsUpwards() {
            WorkorderPart part = part("0", "0", "0");
            givenPart(part);

            WorkorderPartAdjustmentEventResponse response = service.correctPartQuantity(
                    WORKORDER_ID, PART_ID, new BigDecimal("5"), "DATA_ENTRY_ERROR", null, "counted again");

            assertThat(part.getQuantity()).isEqualByComparingTo("5");
            assertThat(part.getLineTotal()).isEqualByComparingTo("249.95");
            assertThat(response.getAdjustmentType()).isEqualTo("CORRECTION");
            assertThat(response.getQuantityAdjustment()).isEqualByComparingTo("3");
            assertThat(response.getSubstitutedWithPartId()).isNull();
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("records a negative adjustment when the quantity is corrected downwards")
        void correctsDownwards() {
            WorkorderPart part = part("0", "0", "0");
            givenPart(part);

            WorkorderPartAdjustmentEventResponse response =
                    service.correctPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "reason", null, null);

            assertThat(response.getQuantityAdjustment()).isEqualByComparingTo("-1");
            assertThat(part.getLineTotal()).isEqualByComparingTo("49.99");
        }

        @Test
        @DisplayName("leaves the line total untouched when the part has no unit price")
        void skipsLineTotalWithoutUnitPrice() {
            WorkorderPart part = part("0", "0", "0");
            part.setUnitPrice(null);
            part.setLineTotal(new BigDecimal("99.98"));
            givenPart(part);

            service.correctPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("4"), "reason", null, null);

            assertThat(part.getQuantity()).isEqualByComparingTo("4");
            assertThat(part.getLineTotal()).isEqualByComparingTo("99.98");
        }

        @Test
        @DisplayName("rejects a non-positive new quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(
                            () -> service.correctPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ZERO, "r", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New quantity must be positive");
            assertThatThrownBy(() ->
                            service.correctPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("-3"), "r", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New quantity must be positive");
        }

        @Test
        @DisplayName("registers the idempotency key under the correction operation")
        void registersIdempotencyKey() {
            givenPart(part("0", "0", "0"));

            service.correctPartQuantity(WORKORDER_ID, PART_ID, new BigDecimal("3"), "reason", "corr-key", null);

            verify(idempotencyService).markKeyProcessedForPartAdjustment(CORRECTION_OPERATION, "corr-key", EVENT_ID);
        }

        @Test
        @DisplayName("replays the recorded event when the idempotency key was already used")
        void replaysExistingEvent() {
            when(idempotencyService.getExistingPartAdjustmentEventId(CORRECTION_OPERATION, "corr-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(existingEvent("CORRECTION")));

            assertThat(service.correctPartQuantity(
                                    WORKORDER_ID, PART_ID, new BigDecimal("3"), "reason", "corr-key", null)
                            .getAdjustmentType())
                    .isEqualTo("CORRECTION");
            verify(workorderPartRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed event has vanished")
        void failsWhenReplayedEventMissing() {
            when(idempotencyService.getExistingPartAdjustmentEventId(CORRECTION_OPERATION, "corr-key"))
                    .thenReturn(Optional.of(EVENT_ID));
            when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.correctPartQuantity(
                            WORKORDER_ID, PART_ID, new BigDecimal("3"), "reason", "corr-key", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Adjustment event not found");
        }

        @Test
        @DisplayName("rejects an unknown part and a foreign part")
        void rejectsUnresolvableTargets() {
            when(workorderPartRepository.findById(PART_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(
                            () -> service.correctPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Part not found");

            WorkorderPart foreign = part("0", "0", "0");
            foreign.setWorkorder(workorder(OTHER_WORKORDER_ID));
            givenPart(foreign);
            assertThatThrownBy(
                            () -> service.correctPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("fails when the security context is empty")
        void failsWithoutAuthenticatedUser() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(
                            () -> service.correctPartQuantity(WORKORDER_ID, PART_ID, BigDecimal.ONE, "r", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Security context has no Authentication");
        }
    }

    @Nested
    @DisplayName("adjustment history")
    class AdjustmentHistory {

        @Test
        @DisplayName("maps the workorder's adjustment events newest first")
        void mapsWorkorderHistory() {
            when(adjustmentEventRepository.findByWorkorderIdOrderByPerformedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of(existingEvent("CORRECTION"), existingEvent("SUBSTITUTION")));

            List<WorkorderPartAdjustmentEventResponse> history = service.getAdjustmentHistory(WORKORDER_ID);

            assertThat(history)
                    .extracting(WorkorderPartAdjustmentEventResponse::getAdjustmentType)
                    .containsExactly("CORRECTION", "SUBSTITUTION");
        }

        @Test
        @DisplayName("maps a single part's adjustment events")
        void mapsPartHistory() {
            when(adjustmentEventRepository.findByOriginalPartIdOrderByPerformedAtDesc(PART_ID))
                    .thenReturn(List.of(existingEvent("ADDITIONAL_RETURN")));

            assertThat(service.getPartAdjustmentHistory(PART_ID))
                    .singleElement()
                    .satisfies(response -> {
                        assertThat(response.getAdjustmentType()).isEqualTo("ADDITIONAL_RETURN");
                        assertThat(response.getOriginalPartId()).isEqualTo(PART_ID);
                        assertThat(response.getOriginalPartDescription()).isEqualTo("Brake pad set");
                    });
        }

        @Test
        @DisplayName("returns an empty history when nothing has been adjusted")
        void emptyHistory() {
            when(adjustmentEventRepository.findByWorkorderIdOrderByPerformedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of());

            assertThat(service.getAdjustmentHistory(WORKORDER_ID)).isEmpty();
        }
    }
}
