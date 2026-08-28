package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
 * Unit tests for {@link WorkorderFactPublisher} (ADR-0044 §6, #897): one snapshot fact per
 * touched workorder per transaction, emitted at beforeCommit from persisted state.
 */
class WorkorderFactPublisherTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final WorkorderRepository workorderRepository = mock(WorkorderRepository.class);
    private final WorkorderPartRepository workorderPartRepository = mock(WorkorderPartRepository.class);
    private final WorkorderServiceRepository workorderServiceRepository = mock(WorkorderServiceRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private WorkorderFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new WorkorderFactPublisher(
                writerProvider,
                workorderRepository,
                workorderPartRepository,
                workorderServiceRepository,
                entityManager);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(WorkorderFactPublisher.class);
    }

    private void fireBeforeCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
        }
    }

    @Test
    @DisplayName("Repeated marks in one transaction emit a single snapshot fact")
    void dedupesWithinTransaction() {
        UUID workorderId = UUID.randomUUID();
        Workorder workorder = Workorder.builder()
                .id(workorderId)
                .workorderNumber("WO-2026-1001")
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .version(3L)
                .build();
        WorkorderPart part = WorkorderPart.builder()
                .id(UUID.randomUUID())
                .productEntityId(UUID.randomUUID())
                .quantity(new BigDecimal("2"))
                .build();
        when(workorderRepository.findById(workorderId)).thenReturn(Optional.of(workorder));
        when(workorderPartRepository.findByWorkorderId(workorderId)).thenReturn(List.of(part));

        publisher.markChanged(workorderId);
        publisher.markChanged(workorderId);
        fireBeforeCommit();

        // Flushed once before assembling any payload, so the pending @Version increment from this
        // transaction's mutations is already reflected in the emitted fact (#1486).
        verify(entityManager, times(1)).flush();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(writer, times(1))
                .publish(
                        eq(WorkorderUpdatedV1.EVENT_TYPE),
                        eq(WorkorderUpdatedV1.SCHEMA_VERSION),
                        eq(workorderId),
                        eq(3L),
                        payloadCaptor.capture());
        WorkorderUpdatedV1 fact = (WorkorderUpdatedV1) payloadCaptor.getValue();
        assertThat(fact.workorderNumber()).isEqualTo("WO-2026-1001");
        assertThat(fact.status()).isEqualTo("WORK_IN_PROGRESS");
        assertThat(fact.parts()).hasSize(1);
        assertThat(fact.parts().get(0).workorderLineId()).isEqualTo(part.getId());
    }

    @Test
    @DisplayName("No-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.markChanged(UUID.randomUUID());
        fireBeforeCommit();

        verify(writer, never()).publish(any(), anyInt(), any(), any());
        verify(writer, never()).publish(any(), anyInt(), any(), anyLong(), any());
    }
}
