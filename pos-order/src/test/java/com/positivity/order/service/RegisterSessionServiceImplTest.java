package com.positivity.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementType;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.RegisterSessionServiceImpl;
import com.positivity.order.service.model.CashMovementCommand;
import com.positivity.order.service.model.OpenSessionCommand;
import com.positivity.order.service.model.RegisterSessionSummary;
import com.positivity.order.service.model.SessionReport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for register sessions and cash management (parity stories G1/G2, spec R6.1–R6.6).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterSessionServiceImpl — parity stories G1/G2")
class RegisterSessionServiceImplTest {

    private static final String TERMINAL = "terminal-1";
    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RegisterSessionRepository registerSessionRepository;

    @Mock
    private CashMovementRepository cashMovementRepository;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private OrderPaymentRecordRepository paymentRecordRepository;

    @Mock
    private OrderDomainEventPublisher domainEventPublisher;

    private RegisterSessionServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new RegisterSessionServiceImpl(
                registerSessionRepository,
                cashMovementRepository,
                salesOrderRepository,
                paymentRecordRepository,
                domainEventPublisher,
                clock);
        ReflectionTestUtils.setField(service, "authorizedDifferenceLimit", new BigDecimal("5.00"));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static RegisterSession openSession(UUID id) {
        return RegisterSession.builder()
                .sessionId(id)
                .version(0L)
                .terminalId(TERMINAL)
                .locationId(LOCATION)
                .openedByClerkId("clerk-1")
                .status(RegisterSessionStatus.OPEN)
                .openingFloat(new BigDecimal("100.0000"))
                .openedAt(Instant.parse("2026-07-23T08:00:00Z"))
                .build();
    }

    private static OrderPaymentRecord cashSettled(String amount) {
        return OrderPaymentRecord.builder()
                .orderId(UUID.randomUUID())
                .recordType(OrderPaymentRecord.RecordType.SETTLED)
                .methodType("CASH")
                .amount(new BigDecimal(amount))
                .occurredAt(Instant.now())
                .build();
    }

    private void authorize(String... authorities) {
        var grants = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("closer", "n/a", grants));
    }

    @Test
    @DisplayName("RSS-001: opening a session with no prior close defaults float to zero")
    void open_defaultsFloatToZero() {
        when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.OPEN))
                .thenReturn(Optional.empty());
        when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                .thenReturn(Optional.empty());
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary =
                service.openSession(new OpenSessionCommand(TERMINAL, LOCATION, null, "clerk-1"));

        assertThat(summary.status()).isEqualTo("OPEN");
        assertThat(summary.openingFloat()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("RSS-002: opening float carries forward from the previous counted close")
    void open_carriesForwardPreviousClose() {
        RegisterSession prior = openSession(UUID.randomUUID());
        prior.setStatus(RegisterSessionStatus.CLOSED);
        prior.setCountedCash(new BigDecimal("275.5000"));
        when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.OPEN))
                .thenReturn(Optional.empty());
        when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                .thenReturn(Optional.of(prior));
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, null, null, "clerk-1"));

        assertThat(summary.openingFloat()).isEqualByComparingTo("275.50");
        assertThat(summary.locationId()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("RSS-003: a second open on the same terminal is a 409 conflict")
    void open_secondOpenConflicts() {
        when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.OPEN))
                .thenReturn(Optional.of(openSession(UUID.randomUUID())));

        assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, LOCATION, null, "clerk-1")))
                .isInstanceOf(RegisterSessionConflictException.class);
        verify(registerSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("RSS-004: a cash movement against a non-open session is rejected")
    void cashMovement_requiresOpenSession() {
        UUID id = UUID.randomUUID();
        RegisterSession closing = openSession(id);
        closing.setStatus(RegisterSessionStatus.CLOSING);
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(closing));

        assertThatThrownBy(() -> service.recordCashMovement(
                        new CashMovementCommand(id, "PAID_IN", new BigDecimal("10.00"), "float top-up", "clerk-1")))
                .isInstanceOf(RegisterSessionConflictException.class);
    }

    @Test
    @DisplayName("RSS-005: begin-close is blocked while a session order is PENDING_PAYMENT")
    void beginClose_blockedByPendingPayment() {
        UUID id = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(openSession(id)));
        when(salesOrderRepository.existsBySessionIdAndStatus(id, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(true);

        assertThatThrownBy(() -> service.beginClose(id, new BigDecimal("100.00")))
                .isInstanceOf(SessionCloseBlockedException.class);
    }

    @Test
    @DisplayName("RSS-006: confirm-close computes theoretical cash and a within-limit over/short needs no approval")
    void confirmClose_withinLimit() {
        UUID id = UUID.randomUUID();
        RegisterSession closing = openSession(id);
        closing.setStatus(RegisterSessionStatus.CLOSING);
        closing.setCountedCash(new BigDecimal("152.0000")); // theoretical 150 -> +2 over (within 5)
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(closing));
        when(salesOrderRepository.existsBySessionIdAndStatus(id, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);
        // opening 100 + cash settlements 50 + movements 0 = 150 theoretical
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of(cashSettled("50.00")));
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.confirmClose(id);

        assertThat(summary.status()).isEqualTo("CLOSED");
        assertThat(summary.theoreticalCash()).isEqualByComparingTo("150.00");
        assertThat(summary.overShort()).isEqualByComparingTo("2.00");
        assertThat(summary.varianceApproved()).isFalse();

        ArgumentCaptor<RegisterSessionClosedV1> captor = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(domainEventPublisher).publishRegisterSessionClosed(any(), captor.capture());
        assertThat(captor.getValue().overShort()).isEqualByComparingTo("2.00");
        assertThat(captor.getValue().tenderTotals()).anySatisfy(t -> {
            assertThat(t.methodType()).isEqualTo("CASH");
            assertThat(t.amount()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    @DisplayName("RSS-007: an over/short beyond the limit is denied without approve_variance")
    void confirmClose_overLimitDeniedWithoutPermission() {
        // Authenticated closer, but without the approve_variance authority.
        authorize(OrderPermissions.ORDER_SESSION_CLOSE);
        UUID id = UUID.randomUUID();
        RegisterSession closing = openSession(id);
        closing.setStatus(RegisterSessionStatus.CLOSING);
        closing.setCountedCash(new BigDecimal("140.0000")); // theoretical 150 -> -10 short (beyond 5)
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(closing));
        when(salesOrderRepository.existsBySessionIdAndStatus(id, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of(cashSettled("50.00")));
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirmClose(id)).isInstanceOf(AccessDeniedException.class);
        verify(registerSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("RSS-008: an over/short beyond the limit closes when approve_variance is held")
    void confirmClose_overLimitApproved() {
        authorize(OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE);
        UUID id = UUID.randomUUID();
        RegisterSession closing = openSession(id);
        closing.setStatus(RegisterSessionStatus.CLOSING);
        closing.setCountedCash(new BigDecimal("140.0000"));
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(closing));
        when(salesOrderRepository.existsBySessionIdAndStatus(id, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of(cashSettled("50.00")));
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.confirmClose(id);

        assertThat(summary.status()).isEqualTo("CLOSED");
        assertThat(summary.overShort()).isEqualByComparingTo("-10.00");
        assertThat(summary.varianceApproved()).isTrue();
    }

    @Test
    @DisplayName("RSS-009: cash movements are signed into theoretical cash (PAID_OUT reduces it)")
    void xReport_theoreticalIncludesMovements() {
        UUID id = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(openSession(id)));
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of(cashSettled("50.00")));
        CashMovement paidOut = CashMovement.builder()
                .movementId(UUID.randomUUID())
                .sessionId(id)
                .movementType(CashMovementType.PAID_OUT)
                .amount(new BigDecimal("20.0000"))
                .reason("bank run")
                .clerkId("clerk-1")
                .occurredAt(Instant.now())
                .build();
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of(paidOut));
        when(salesOrderRepository.findBySessionId(id)).thenReturn(List.of());

        SessionReport report = service.xReport(id);

        // opening 100 + cash 50 - movement 20 = 130
        assertThat(report.theoreticalCash()).isEqualByComparingTo("130.00");
        assertThat(report.cashMovements()).isEqualByComparingTo("-20.00");
        assertThat(report.reportType()).isEqualTo("X");
    }

    @Test
    @DisplayName("RSS-010: an unknown session id is a 404")
    void getSession_notFound() {
        UUID id = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSession(id)).isInstanceOf(RegisterSessionNotFoundException.class);
    }
}
