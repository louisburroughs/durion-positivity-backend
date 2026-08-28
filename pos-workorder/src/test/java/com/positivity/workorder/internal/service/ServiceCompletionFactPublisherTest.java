package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.domainevents.workorder.WorkorderServiceLineDeclinedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link ServiceCompletionFactPublisher} (FI-3, #1133): one service-completion fact
 * plus one declined fact per declined service line, emitted at beforeCommit from persisted state.
 */
class ServiceCompletionFactPublisherTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final WorkorderRepository workorderRepository = mock(WorkorderRepository.class);
    private final WorkorderPartRepository workorderPartRepository = mock(WorkorderPartRepository.class);
    private final WorkorderServiceRepository workorderServiceRepository = mock(WorkorderServiceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);

    private ServiceCompletionFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new ServiceCompletionFactPublisher(
                clock, writerProvider, workorderRepository, workorderPartRepository, workorderServiceRepository);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(ServiceCompletionFactPublisher.class);
    }

    private void fireBeforeCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
        }
    }

    private WorkorderServiceLine serviceLine(String description, BigDecimal total, boolean declined, String reason) {
        return serviceLine(description, total, declined, reason, WorkorderItemStatus.COMPLETED);
    }

    private WorkorderServiceLine serviceLine(
            String description, BigDecimal total, boolean declined, String reason, WorkorderItemStatus status) {
        return WorkorderServiceLine.builder()
                .id(UUID.randomUUID())
                .serviceEntityId(UUID.randomUUID())
                .description(description)
                .quantity(BigDecimal.ONE)
                .unitPrice(total)
                .lineTotal(total)
                .declined(declined)
                .declineReason(reason)
                .status(status)
                .build();
    }

    private WorkorderPart part(BigDecimal total, WorkorderItemStatus status) {
        return WorkorderPart.builder()
                .id(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(total)
                .lineTotal(total)
                .declined(false)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("Completion emits one service-completion fact and one declined fact per declined line")
    void emitsCompletionAndDeclineFacts() {
        UUID workorderId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        Workorder workorder = Workorder.builder()
                .id(workorderId)
                .workorderNumber("WO-2026-1001")
                .customerId(partyId)
                .vehicleId(vehicleId)
                .completedAt(Instant.parse("2026-07-20T10:00:00Z"))
                .build();
        WorkorderServiceLine performed = serviceLine("Oil change", new BigDecimal("100.00"), false, null);
        WorkorderServiceLine declined =
                serviceLine("Brake pads", new BigDecimal("300.00"), true, "Defer to next visit");
        // A non-declined CANCELLED line must not count as performed nor into the amount.
        WorkorderServiceLine cancelled =
                serviceLine("Tire rotation", new BigDecimal("40.00"), false, null, WorkorderItemStatus.CANCELLED);

        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId))
                .thenReturn(List.of(performed, declined, cancelled));
        // A part attached to a service line (not the workorder directly) must still be counted.
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                .thenReturn(List.of(part(new BigDecimal("50.00"), WorkorderItemStatus.COMPLETED)));
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                .thenReturn(List.of());

        publisher.markCompleted(workorderId);
        publisher.markCompleted(workorderId);
        fireBeforeCommit();

        ArgumentCaptor<Object> completed = ArgumentCaptor.forClass(Object.class);
        verify(writer, times(1))
                .publish(
                        eq(WorkorderServiceCompletedV1.EVENT_TYPE),
                        eq(WorkorderServiceCompletedV1.SCHEMA_VERSION),
                        eq(workorderId),
                        completed.capture());
        WorkorderServiceCompletedV1 completionFact = (WorkorderServiceCompletedV1) completed.getValue();
        assertThat(completionFact.partyId()).isEqualTo(partyId);
        assertThat(completionFact.vehicleId()).isEqualTo(vehicleId);
        assertThat(completionFact.services()).hasSize(1);
        assertThat(completionFact.services().get(0).description()).isEqualTo("Oil change");
        // Performed service (100) + service-line part (50); declined (300) and CANCELLED (40) excluded.
        assertThat(completionFact.totalAmount()).isEqualByComparingTo("150.00");

        ArgumentCaptor<Object> declinedFact = ArgumentCaptor.forClass(Object.class);
        verify(writer, times(1))
                .publish(
                        eq(WorkorderServiceLineDeclinedV1.EVENT_TYPE),
                        eq(WorkorderServiceLineDeclinedV1.SCHEMA_VERSION),
                        eq(workorderId),
                        declinedFact.capture());
        WorkorderServiceLineDeclinedV1 fact = (WorkorderServiceLineDeclinedV1) declinedFact.getValue();
        assertThat(fact.description()).isEqualTo("Brake pads");
        assertThat(fact.declineReason()).isEqualTo("Defer to next visit");
        assertThat(fact.workorderLineId()).isEqualTo(declined.getId());
    }

    @Test
    @DisplayName("No declined lines means no declined facts")
    void noDeclinedLinesNoDeclineFacts() {
        UUID workorderId = UUID.randomUUID();
        Workorder workorder = Workorder.builder()
                .id(workorderId)
                .completedAt(Instant.parse("2026-07-20T10:00:00Z"))
                .build();
        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderServiceRepository.findByWorkOrder_Id(workorderId))
                .thenReturn(List.of(serviceLine("Oil change", new BigDecimal("100.00"), false, null)));
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId))
                .thenReturn(List.of());
        when(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId))
                .thenReturn(List.of());

        publisher.markCompleted(workorderId);
        fireBeforeCommit();

        verify(writer, times(1)).publish(eq(WorkorderServiceCompletedV1.EVENT_TYPE), anyInt(), any(), any());
        verify(writer, never()).publish(eq(WorkorderServiceLineDeclinedV1.EVENT_TYPE), anyInt(), any(), any());
    }

    @Test
    @DisplayName("No-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.markCompleted(UUID.randomUUID());
        fireBeforeCommit();

        verify(writer, never()).publish(any(), anyInt(), any(), any());
    }
}
