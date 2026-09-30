package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.web.common.ReplicationPendingException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1994: an absent workorder or part row is "not yet" (503 {@code WORKORDER_REPLICATION_PENDING});
 * a row that is present but wrong keeps the status that describes it.
 */
class WorkorderValidationServiceTest {

    private static final UUID WORKORDER_ID = UUID.fromString("01960003-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("01960003-0000-7000-8000-0000000000a2");
    private static final UUID LINE_ID = UUID.fromString("01960003-0000-7000-8000-0000000000b1");
    private static final UUID PRODUCT_ID = UUID.fromString("01960003-0000-7000-8000-0000000000c1");

    private final ExtWorkorderReplicaRepository workorders = mock(ExtWorkorderReplicaRepository.class);
    private final ExtWorkorderPartReplicaRepository parts = mock(ExtWorkorderPartReplicaRepository.class);
    private final WorkorderValidationService service = new WorkorderValidationService(workorders, parts);

    private ExtWorkorderReplica workorder(String status) {
        return ExtWorkorderReplica.builder()
                .workorderId(WORKORDER_ID)
                .status(status)
                .build();
    }

    private ExtWorkorderPartReplica part(UUID workorderId, UUID productId) {
        return ExtWorkorderPartReplica.builder()
                .workorderLineId(LINE_ID)
                .workorderId(workorderId)
                .productEntityId(productId)
                .build();
    }

    @Test
    @DisplayName("a workorder with no replica row is 503 WORKORDER_REPLICATION_PENDING, not a 409")
    void missingWorkorderIsReplicationPending() {
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWorkorderLineValidation(WORKORDER_ID.toString(), LINE_ID.toString()))
                .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKORDER_REPLICATION_PENDING");
                    assertThat(e.getReferenceId()).isEqualTo(WORKORDER_ID);
                });
    }

    @Test
    @DisplayName("a part line with no replica row is 503 WORKORDER_REPLICATION_PENDING, not a 400")
    void missingPartLineIsReplicationPending() {
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder("IN_PROGRESS")));
        when(parts.findById(LINE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWorkorderLineValidation(WORKORDER_ID.toString(), LINE_ID.toString()))
                .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKORDER_REPLICATION_PENDING");
                    assertThat(e.getReferenceId()).isEqualTo(LINE_ID);
                });
    }

    @Test
    @DisplayName("a present part line that belongs to another workorder keeps its 400 IllegalArgumentException")
    void lineOnAnotherWorkorderKeepsItsStatus() {
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder("IN_PROGRESS")));
        when(parts.findById(LINE_ID)).thenReturn(Optional.of(part(OTHER_WORKORDER_ID, PRODUCT_ID)));

        assertThatThrownBy(() -> service.getWorkorderLineValidation(WORKORDER_ID.toString(), LINE_ID.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found on workorder");
    }

    @Test
    @DisplayName("a present line without a product keeps its 409 IllegalStateException")
    void lineWithoutProductKeepsItsStatus() {
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder("IN_PROGRESS")));
        when(parts.findById(LINE_ID)).thenReturn(Optional.of(part(WORKORDER_ID, null)));

        assertThatThrownBy(() -> service.getWorkorderLineValidation(WORKORDER_ID.toString(), LINE_ID.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no productEntityId");
    }

    @Test
    @DisplayName("a replicated workorder and line still validate")
    void presentRowsValidate() {
        when(workorders.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder("IN_PROGRESS")));
        when(parts.findById(LINE_ID)).thenReturn(Optional.of(part(WORKORDER_ID, PRODUCT_ID)));

        WorkorderValidationService.WorkorderLineValidation verdict =
                service.getWorkorderLineValidation(WORKORDER_ID.toString(), LINE_ID.toString());

        assertThat(verdict.status()).isEqualTo("IN_PROGRESS");
        assertThat(verdict.demandedProductId()).isEqualTo(PRODUCT_ID.toString());
    }
}
