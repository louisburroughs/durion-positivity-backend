package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.positivity.order.internal.entity.OrderStatusHistory;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderNotEditableException;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Transition-matrix tests for {@link OrderStateMachine} (parity story A1, #1059): every enum pair
 * is exercised against the allowed map, and history rows are appended on each transition.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderStateMachine — parity story A1 (#1059)")
class OrderStateMachineTest {

    /** Expected allowed-transition map from spec §2.1 — the test's independent source of truth. */
    private static final Map<SalesOrderStatus, Set<SalesOrderStatus>> EXPECTED = Map.ofEntries(
            Map.entry(
                    SalesOrderStatus.DRAFT,
                    Set.of(
                            SalesOrderStatus.QUOTED,
                            SalesOrderStatus.PENDING_PAYMENT,
                            SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.QUOTED,
                    Set.of(
                            SalesOrderStatus.DRAFT,
                            SalesOrderStatus.PENDING_PAYMENT,
                            SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.PENDING_PAYMENT,
                    Set.of(SalesOrderStatus.COMPLETED, SalesOrderStatus.VOIDED, SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(
                    SalesOrderStatus.CANCEL_REQUESTED,
                    Set.of(
                            SalesOrderStatus.WORKORDER_CANCELLED,
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCELLED,
                            SalesOrderStatus.CANCEL_FAILED_WORKEXEC,
                            SalesOrderStatus.CANCEL_FAILED_BILLING)),
            Map.entry(
                    SalesOrderStatus.WORKORDER_CANCELLED,
                    Set.of(
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCELLED,
                            SalesOrderStatus.CANCEL_FAILED_BILLING)),
            Map.entry(SalesOrderStatus.PAYMENT_REVERSED, Set.of(SalesOrderStatus.CANCELLED)),
            Map.entry(
                    SalesOrderStatus.CANCEL_FAILED_WORKEXEC,
                    Set.of(SalesOrderStatus.CANCEL_REQUESTED, SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW)),
            Map.entry(
                    SalesOrderStatus.CANCEL_FAILED_BILLING,
                    Set.of(
                            SalesOrderStatus.CANCEL_REQUESTED,
                            SalesOrderStatus.PAYMENT_REVERSED,
                            SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW)),
            Map.entry(SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW, Set.of(SalesOrderStatus.CANCEL_REQUESTED)),
            Map.entry(SalesOrderStatus.COMPLETED, Set.of()),
            Map.entry(SalesOrderStatus.VOIDED, Set.of()),
            Map.entry(SalesOrderStatus.CANCELLED, Set.of()));

    @Mock
    private OrderStatusHistoryRepository historyRepository;

    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStateMachine(historyRepository, Clock.systemUTC());
    }

    private SalesOrder orderIn(SalesOrderStatus status) {
        return SalesOrder.builder()
                .orderId(UUID.randomUUID())
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(status)
                .subtotal(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Every enum pair matches the spec §2.1 matrix — allowed transitions apply, others 409")
    void transitionMatrix_everyPair() {
        for (SalesOrderStatus from : SalesOrderStatus.values()) {
            for (SalesOrderStatus to : SalesOrderStatus.values()) {
                boolean allowed = EXPECTED.getOrDefault(from, Set.of()).contains(to);
                assertThat(stateMachine.isAllowed(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed);

                SalesOrder order = orderIn(from);
                if (allowed) {
                    stateMachine.transition(order, to, "matrix");
                    assertThat(order.getStatus()).isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> stateMachine.transition(order, to, "matrix"))
                            .isInstanceOf(InvalidOrderStateTransitionException.class);
                    assertThat(order.getStatus()).isEqualTo(from);
                }
            }
        }
    }

    @Test
    @DisplayName("transition appends a history row carrying from/to and reason")
    void transition_writesHistory() {
        SalesOrder order = orderIn(SalesOrderStatus.DRAFT);
        stateMachine.transition(order, SalesOrderStatus.QUOTED, "customer asked for a quote");

        ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        OrderStatusHistory row = captor.getValue();
        assertThat(row.getFromStatus()).isEqualTo(SalesOrderStatus.DRAFT);
        assertThat(row.getToStatus()).isEqualTo(SalesOrderStatus.QUOTED);
        assertThat(row.getReason()).isEqualTo("customer asked for a quote");
        assertThat(row.getOrderId()).isEqualTo(order.getOrderId());
    }

    @Test
    @DisplayName("recordCreation writes the initial DRAFT row with null fromStatus")
    void recordCreation_writesInitialRow() {
        SalesOrder order = orderIn(SalesOrderStatus.DRAFT);
        stateMachine.recordCreation(order);

        ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isNull();
        assertThat(captor.getValue().getToStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    @DisplayName("requireEditable passes for DRAFT and rejects every other status")
    void requireEditable_draftOnly() {
        stateMachine.requireEditable(orderIn(SalesOrderStatus.DRAFT));
        for (SalesOrderStatus status : SalesOrderStatus.values()) {
            if (status == SalesOrderStatus.DRAFT) {
                continue;
            }
            assertThatThrownBy(() -> stateMachine.requireEditable(orderIn(status)))
                    .as("requireEditable(%s)", status)
                    .isInstanceOf(OrderNotEditableException.class);
        }
    }
}
