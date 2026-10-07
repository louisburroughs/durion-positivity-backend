package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.dto.RegisterSessionSummary;
import com.positivity.order.internal.dto.SessionReport;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementType;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.RegisterFloatLocationMismatchException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

    private final com.positivity.order.internal.repository.ExtCustomerRepository extCustomerRepository =
            org.mockito.Mockito.mock(com.positivity.order.internal.repository.ExtCustomerRepository.class);

    @Mock
    private SessionPolicyService sessionPolicyService;

    @Mock
    private CashMovementApprovalService approvalService;

    @Mock
    private ExtAccountingRegisterFloatRepository registerFloatRepository;

    @Mock
    private ExtAccountingPettyExpenseCategoryRepository categoryRepository;

    private RegisterSessionServiceImpl service;

    /** The drawer policy's defaults (CAP:550 S16): tolerance 5.00, petty on at 50.00, COD off. */
    static final SessionPolicyView DEFAULT_POLICY =
            new SessionPolicyView(null, true, new BigDecimal("50.0000"), false, null, new BigDecimal("5.0000"), "USD");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = org.mockito.Mockito.mock(ObjectProvider.class);
        service = new RegisterSessionServiceImpl(
                registerSessionRepository,
                cashMovementRepository,
                salesOrderRepository,
                paymentRecordRepository,
                domainEventPublisher,
                new com.positivity.order.internal.service.HouseAccountReplica(extCustomerRepository),
                sessionPolicyService,
                approvalService,
                registerFloatRepository,
                categoryRepository,
                new com.positivity.order.internal.config.FunctionalCurrency("USD"),
                clock,
                meters);
        org.mockito.Mockito.lenient().when(sessionPolicyService.current()).thenReturn(DEFAULT_POLICY);
        // Default caller: a pre-rollout token (no loc_* claims), which ADR-0061 treats as unscoped
        // so the existing expectations are unchanged (#1872). Tests that need authorities or a
        // scope replace it. Set here rather than in a second @BeforeEach because JUnit does not
        // define the order between two of them in one class (S8745).
        authenticate(LocationScope.unscoped());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** A second shop, outside the scoped caller's reach. */
    private static final UUID OTHER_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** The node a scoped caller is assigned: a region above {@link #LOCATION}. */
    private static final UUID REGION_NODE = UUID.fromString("00000000-0000-0000-0000-000000000a00");

    /** Replica stand-in: LOCATION sits under REGION_NODE on the OTHER dimension; OTHER_LOCATION does not. */
    private static final LocationAncestorResolver RESOLVER = id -> {
        if (LOCATION.equals(id)) {
            return new AncestorSets(Set.of(LOCATION), Set.of(LOCATION, REGION_NODE));
        }
        if (OTHER_LOCATION.equals(id)) {
            return new AncestorSets(Set.of(OTHER_LOCATION), Set.of(OTHER_LOCATION));
        }
        return AncestorSets.EMPTY;
    };

    private static void authenticate(LocationScope scope, String... authorities) {
        var grants = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        var token = new UsernamePasswordAuthenticationToken("opener", "n/a", grants);
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "opener",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** A caller whose order:session:open is scoped (OTHER dimension) to the given assigned nodes. */
    private static LocationScope openScopedTo(UUID... nodes) {
        return LocationScope.of(
                Set.of(), Set.of(OrderPermissions.ORDER_SESSION_OPEN), Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    private static RegisterSession openSession(UUID id) {
        return RegisterSession.builder()
                .sessionId(id)
                .version(0L)
                .terminalId(TERMINAL)
                .locationId(LOCATION)
                .openedByClerkId("clerk-1")
                .status(RegisterSessionStatus.OPEN)
                .currencyCode("USD")
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
    @DisplayName("RSS-001 (CAP:550 S16): a register with no configured float opens at zero, opened by the caller")
    void open_defaultsFloatToZero() {
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        when(registerFloatRepository.findByRegisterId(TERMINAL)).thenReturn(Optional.empty());
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, LOCATION));

        assertThat(summary.status()).isEqualTo("OPEN");
        assertThat(summary.openingFloat()).isEqualByComparingTo("0.00");
        // ADR-0018: the opener is the security context's caller, never a request field.
        assertThat(summary.openedByClerkId()).isEqualTo("opener");
        // MAJOR-1 (ADR-0067 R-2): the drawer is stamped with the functional currency when it opens.
        assertThat(summary.currencyCode()).isEqualTo("USD");
        verify(registerSessionRepository).save(argThat(saved -> "USD".equals(saved.getCurrencyCode())));
    }

    @Test
    @DisplayName("RSS-002 (CAP:550 S16 AC7, AW16): the opening float is the configured float, not the previous count")
    void open_usesConfiguredFloat() {
        RegisterSession prior = openSession(UUID.randomUUID());
        prior.setStatus(RegisterSessionStatus.CLOSED);
        prior.setCountedCash(new BigDecimal("275.5000"));
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        // The previous count is never the opening float; with a float copy its location wins (#2573).
        org.mockito.Mockito.lenient()
                .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                .thenReturn(Optional.of(prior));
        when(registerFloatRepository.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(LOCATION)
                        .amount(new BigDecimal("200.0000"))
                        .build()));
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, null));

        assertThat(summary.openingFloat()).isEqualByComparingTo("200.00");
        assertThat(summary.locationId()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("RSS-002b (CAP:550 S16, decision l7): a negative configured float (after an accounting reversal) opens"
            + " the drawer at zero")
    void open_negativeConfiguredFloat() {
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        when(registerFloatRepository.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(LOCATION)
                        .amount(new BigDecimal("-25.0000"))
                        .build()));
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, LOCATION));

        assertThat(summary.openingFloat()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("RSS-002c (#2573): opening at A while the register's float is held at B is 422, no session row")
    void open_atAnotherLocationThanTheFloatIsRefused() {
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        when(registerFloatRepository.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(OTHER_LOCATION)
                        .amount(new BigDecimal("200.0000"))
                        .build()));

        assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, LOCATION)))
                .isInstanceOf(RegisterFloatLocationMismatchException.class)
                .satisfies(e -> {
                    RegisterFloatLocationMismatchException mismatch = (RegisterFloatLocationMismatchException) e;
                    assertThat(mismatch.terminalId()).isEqualTo(TERMINAL);
                    assertThat(mismatch.requestedLocationId()).isEqualTo(LOCATION);
                    // An unscoped caller may see the float's location.
                    assertThat(mismatch.floatLocationId()).isEqualTo(OTHER_LOCATION);
                });
        verify(registerSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("RSS-002d (#2573): a caller whose scope does not cover the float's location is not told it")
    void open_mismatchHidesAnOutOfScopeFloatLocation() {
        authenticate(openScopedTo(REGION_NODE));
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        when(registerFloatRepository.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(OTHER_LOCATION)
                        .amount(new BigDecimal("200.0000"))
                        .build()));

        assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, LOCATION)))
                .isInstanceOf(RegisterFloatLocationMismatchException.class)
                .satisfies(e -> assertThat(((RegisterFloatLocationMismatchException) e).floatLocationId())
                        .isNull())
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(OTHER_LOCATION.toString()));
        verify(registerSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("RSS-002e (#2573): with no locationId the drawer opens where its float is held, not where it last was")
    void open_withoutLocationFollowsTheFloat() {
        RegisterSession prior = openSession(UUID.randomUUID());
        prior.setStatus(RegisterSessionStatus.CLOSED);
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(false);
        org.mockito.Mockito.lenient()
                .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                .thenReturn(Optional.of(prior)); // previous session at LOCATION
        when(registerFloatRepository.findByRegisterId(TERMINAL))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerId(TERMINAL)
                        .locationId(OTHER_LOCATION)
                        .amount(new BigDecimal("180.0000"))
                        .build()));
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, null));

        assertThat(summary.locationId()).isEqualTo(OTHER_LOCATION);
        assertThat(summary.openingFloat()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("RSS-006b (CAP:550 S16 AC6): with the tolerance lowered to 3.00, an over/short of 4.00 needs"
            + " approve_variance")
    void confirmClose_policyToleranceApplies() {
        org.mockito.Mockito.when(sessionPolicyService.current())
                .thenReturn(new SessionPolicyView(
                        1L, true, new BigDecimal("50.0000"), false, null, new BigDecimal("3.0000"), "USD"));
        authorize(OrderPermissions.ORDER_SESSION_CLOSE);
        UUID id = UUID.randomUUID();
        RegisterSession closing = openSession(id);
        closing.setStatus(RegisterSessionStatus.CLOSING);
        closing.setCountedCash(new BigDecimal("154.0000")); // theoretical 150 -> +4 over (beyond 3, within 5)
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(closing));
        when(salesOrderRepository.existsBySessionIdAndStatus(id, SalesOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of(cashSettled("50.00")));
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirmClose(id)).isInstanceOf(AccessDeniedException.class);
        verify(registerSessionRepository, never()).save(any());

        authorize(OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE);
        when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RegisterSessionSummary closed = service.confirmClose(id);
        assertThat(closed.varianceApproved()).isTrue();
        assertThat(closed.overShort()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("RSS-003: a second open on the same terminal is a 409 conflict")
    void open_secondOpenConflicts() {
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, LOCATION)))
                .isInstanceOf(RegisterSessionConflictException.class);
        verify(registerSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("RSS-003b: a CLOSING session on the terminal also blocks a new open")
    void open_blockedByClosingSession() {
        // existsByTerminalIdAndStatusIn covers OPEN and CLOSING; a session mid-close still holds it.
        when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, LOCATION)))
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
        when(registerSessionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(closing));

        assertThatThrownBy(() -> service.recordCashMovement(new CashMovementCommand(
                        id,
                        com.positivity.shared.id.UUIDv7Generator.generate(),
                        "BANK_DROP",
                        new BigDecimal("10.00"),
                        "USD",
                        null,
                        null,
                        "BAG-1",
                        null,
                        null,
                        null)))
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
                .note("bank run")
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

    private static final UUID HOUSE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static com.positivity.order.internal.entity.SalesOrder sessionOrder(
            UUID sessionId, String clerkId, SalesOrderStatus status, UUID customerId, String grandTotal) {
        return com.positivity.order.internal.entity.SalesOrder.builder()
                .orderId(UUID.randomUUID())
                .sessionId(sessionId)
                .clerkId(clerkId)
                .terminalId(TERMINAL)
                .status(status)
                .customerId(customerId)
                .subtotal(new BigDecimal(grandTotal))
                .grandTotal(new BigDecimal(grandTotal))
                .build();
    }

    @Test
    @DisplayName("RSS-W1 (CAP:550 S8 AC7): X and Z reports carry the walk-in share per cashier")
    void report_walkInShareByClerk() {
        UUID id = UUID.randomUUID();
        UUID registered = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(openSession(id)));
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of());
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());
        when(salesOrderRepository.findBySessionId(id))
                .thenReturn(List.of(
                        // Clerk A: three orders that left DRAFT, one of them a walk-in sale.
                        sessionOrder(id, "clerk-a", SalesOrderStatus.COMPLETED, HOUSE_ACCOUNT_ID, "84.3700"),
                        sessionOrder(id, "clerk-a", SalesOrderStatus.COMPLETED, registered, "40.0000"),
                        sessionOrder(id, "clerk-a", SalesOrderStatus.PENDING_PAYMENT, registered, "12.0000"),
                        // Clerk B: two orders, no walk-in.
                        sessionOrder(id, "clerk-b", SalesOrderStatus.COMPLETED, registered, "15.0000"),
                        sessionOrder(id, "clerk-b", SalesOrderStatus.VOIDED, null, "9.0000"),
                        // Still DRAFT: counted for nobody, walk-in or not.
                        sessionOrder(id, "clerk-a", SalesOrderStatus.DRAFT, HOUSE_ACCOUNT_ID, "500.0000"),
                        sessionOrder(id, "clerk-c", SalesOrderStatus.DRAFT, null, "1.0000")));
        when(extCustomerRepository.findAllById(any()))
                .thenReturn(List.of(
                        com.positivity.order.internal.entity.ExtCustomer.builder()
                                .partyId(HOUSE_ACCOUNT_ID)
                                .status("ACTIVE")
                                .houseAccount(
                                        com.positivity.domainevents.customer.CustomerPartyUpdatedV1
                                                .HOUSE_ACCOUNT_CASH_SALE)
                                .build(),
                        com.positivity.order.internal.entity.ExtCustomer.builder()
                                .partyId(registered)
                                .status("ACTIVE")
                                .displayName("Walk-in customer")
                                .build()));

        for (SessionReport report : List.of(service.xReport(id), service.zReport(id))) {
            assertThat(report.orderCount())
                    .as("orderCount is unchanged: every session order")
                    .isEqualTo(7);
            assertThat(report.walkInByClerk()).hasSize(2);
            SessionReport.ClerkWalkInShare a = report.walkInByClerk().get(0);
            assertThat(a.clerkId()).isEqualTo("clerk-a");
            assertThat(a.orderCount()).isEqualTo(3);
            assertThat(a.walkInOrderCount()).isEqualTo(1);
            assertThat(a.walkInTotal()).isEqualByComparingTo("84.37");
            SessionReport.ClerkWalkInShare b = report.walkInByClerk().get(1);
            assertThat(b.clerkId()).isEqualTo("clerk-b");
            assertThat(b.orderCount()).isEqualTo(2);
            assertThat(b.walkInOrderCount()).isZero();
            assertThat(b.walkInTotal()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    @DisplayName("RSS-W2: a session with no orders past DRAFT reports an empty walk-in share")
    void report_walkInShareEmpty() {
        UUID id = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.of(openSession(id)));
        when(paymentRecordRepository.findBySessionId(id)).thenReturn(List.of());
        when(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(id)).thenReturn(List.of());
        when(salesOrderRepository.findBySessionId(id)).thenReturn(List.of());

        assertThat(service.xReport(id).walkInByClerk()).isEmpty();
    }

    @Test
    @DisplayName("RSS-010: an unknown session id is a 404")
    void getSession_notFound() {
        UUID id = UUID.randomUUID();
        when(registerSessionRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSession(id)).isInstanceOf(RegisterSessionNotFoundException.class);
    }

    @Nested
    @DisplayName("openSession location scope (ADR-0061 §3, #1872)")
    class OpenSessionLocationScope {

        private void stubNoActiveSessionAndSave() {
            when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                    .thenReturn(false);
            when(registerSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("requested location in reach: the session opens there")
        void inReach_opens() {
            authenticate(openScopedTo(REGION_NODE), OrderPermissions.ORDER_SESSION_OPEN);
            stubNoActiveSessionAndSave();
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.empty());

            RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, LOCATION));

            assertThat(summary.locationId()).isEqualTo(LOCATION);
            assertThat(summary.status()).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("requested location out of reach: LocationScopeDeniedException, nothing saved")
        void outOfReach_denies() {
            authenticate(openScopedTo(REGION_NODE), OrderPermissions.ORDER_SESSION_OPEN);
            when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                    .thenReturn(false);
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, OTHER_LOCATION)))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.type(LocationScopeDeniedException.class))
                    .satisfies(denied -> {
                        assertThat(denied.permission()).isEqualTo(OrderPermissions.ORDER_SESSION_OPEN);
                        assertThat(denied.locationId()).isEqualTo(OTHER_LOCATION.toString());
                    });
            verify(registerSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName(
                "omitted locationId defaulting to a previous session at a shop out of reach is denied — no bypass by omission")
        void defaultedLocationOutOfReach_denies() {
            authenticate(openScopedTo(REGION_NODE), OrderPermissions.ORDER_SESSION_OPEN);
            RegisterSession prior = openSession(UUID.randomUUID());
            prior.setStatus(RegisterSessionStatus.CLOSED);
            prior.setLocationId(OTHER_LOCATION);
            when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                    .thenReturn(false);
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.of(prior));

            assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, null)))
                    .isInstanceOf(LocationScopeDeniedException.class);
            verify(registerSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("omitted locationId defaulting to a previous session in reach opens there")
        void defaultedLocationInReach_opens() {
            authenticate(openScopedTo(REGION_NODE), OrderPermissions.ORDER_SESSION_OPEN);
            RegisterSession prior = openSession(UUID.randomUUID());
            prior.setStatus(RegisterSessionStatus.CLOSED);
            stubNoActiveSessionAndSave();
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.of(prior));

            RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, null));

            assertThat(summary.locationId()).isEqualTo(LOCATION);
        }

        @Test
        @DisplayName("a session that resolves to no location at all fails closed for a scoped caller")
        void noLocation_deniesScopedCaller() {
            authenticate(openScopedTo(REGION_NODE), OrderPermissions.ORDER_SESSION_OPEN);
            when(registerSessionRepository.existsByTerminalIdAndStatusIn(eq(TERMINAL), any()))
                    .thenReturn(false);
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.openSession(new OpenSessionCommand(TERMINAL, null)))
                    .isInstanceOf(LocationScopeDeniedException.class);
            verify(registerSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("pre-rollout token (no loc_* claims) opens at any location as before")
        void unscopedCaller_isUnchanged() {
            authenticate(LocationScope.unscoped(), OrderPermissions.ORDER_SESSION_OPEN);
            stubNoActiveSessionAndSave();
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.empty());

            RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, OTHER_LOCATION));

            assertThat(summary.locationId()).isEqualTo(OTHER_LOCATION);
        }

        @Test
        @DisplayName("caller whose open grant is global (claims present, permission unscoped) is unchanged")
        void globalGrant_isNotLocationChecked() {
            authenticate(
                    LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER),
                    OrderPermissions.ORDER_SESSION_OPEN);
            stubNoActiveSessionAndSave();
            org.mockito.Mockito.lenient()
                    .when(registerSessionRepository.findFirstByTerminalIdOrderByOpenedAtDesc(TERMINAL))
                    .thenReturn(Optional.empty());

            RegisterSessionSummary summary = service.openSession(new OpenSessionCommand(TERMINAL, OTHER_LOCATION));

            assertThat(summary.locationId()).isEqualTo(OTHER_LOCATION);
        }
    }
}
