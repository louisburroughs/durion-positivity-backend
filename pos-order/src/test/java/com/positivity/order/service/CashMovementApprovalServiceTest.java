package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.order.internal.client.StepUpPort;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementApprovalStatus;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.repository.CashMovementApprovalRepository;
import com.positivity.order.internal.repository.CashMovementStepUpDenialRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * CAP:550 S16 (#2512; AW31): the step-up mints a single-use token bound to one movement; the token is
 * stored only as its hash, expires, is used once, and its approver is never the cashier and must reach
 * the drawer's location. The pos-security-service check is stubbed: verified, denied, lacking the
 * permission, and scoped.
 */
@DisplayName("CashMovementApprovalServiceImpl — step-up and single-use token (AW31)")
class CashMovementApprovalServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a001");
    private static final UUID CASHIER_ID = UUID.fromString("01900000-0000-7000-8000-00000000a0c1");
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-00000000b001");
    private static final UUID LOCATION = UUID.fromString("01900000-0000-7000-8000-00000000d001");
    private static final UUID REGION = UUID.fromString("01900000-0000-7000-8000-00000000d0aa");
    private static final UUID OTHER_SHOP = UUID.fromString("01900000-0000-7000-8000-00000000d002");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String PERMISSION = OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT;

    /** LOCATION sits under REGION on the OTHER dimension. */
    private static final LocationAncestorResolver RESOLVER = id -> LOCATION.equals(id)
            ? new AncestorSets(Set.of(LOCATION), Set.of(LOCATION, REGION))
            : new AncestorSets(Set.of(id), Set.of(id));

    private final CashMovementApprovalRepository approvals = mock(CashMovementApprovalRepository.class);
    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final CashMovementStepUpDenialRepository denials = mock(CashMovementStepUpDenialRepository.class);
    private final StepUpPort stepUp = mock(StepUpPort.class);
    private final AtomicReference<CashMovementApproval> stored = new AtomicReference<>();
    private RegisterSession session;
    private CashMovementApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService(Clock.fixed(NOW, ZoneOffset.UTC));
        session = RegisterSession.builder()
                .sessionId(SESSION_ID)
                .terminalId("T-1")
                .locationId(LOCATION)
                .currencyCode("USD")
                .status(RegisterSessionStatus.OPEN)
                .build();
        when(sessions.findById(SESSION_ID)).thenAnswer(_ -> Optional.of(session));
        when(approvals.save(any())).thenAnswer(inv -> {
            CashMovementApproval approval = inv.getArgument(0);
            if (approval.getApprovalId() == null) {
                approval.setApprovalId(UUID.randomUUID());
            }
            stored.set(approval);
            return approval;
        });
        when(approvals.findByTokenHash(any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get())
                        .filter(a -> a.getTokenHash().equals(inv.getArgument(0))));
        signIn("cashier", CASHIER_ID, LocationScope.unscoped());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private CashMovementApprovalServiceImpl newService(Clock at) {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        return new CashMovementApprovalServiceImpl(
                approvals, sessions, denials, stepUp, RESOLVER, at, mock(PlatformTransactionManager.class), meters);
    }

    private static void signIn(String username, UUID userId, LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        Map<String, Object> details = new HashMap<>();
        details.put(GatewaySecurityConstants.DETAIL_USERNAME, username);
        if (userId != null) {
            details.put(GatewaySecurityConstants.DETAIL_USER_ID, userId);
        }
        details.put(GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, scope);
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static CashMovementApprovalCommand command(String username, String amount) {
        return new CashMovementApprovalCommand(
                SESSION_ID, username, "s3cret", "PETTY_EXPENSE", new BigDecimal(amount), "USD", "SHOP_SUPPLIES", null);
    }

    private static StepUpPort.StepUpResult globalHolder(UUID userId) {
        return new StepUpPort.StepUpResult(userId, true, false, false, List.of());
    }

    private void managerVerifies(StepUpPort.StepUpResult result) {
        when(stepUp.verify("manager", "s3cret", PERMISSION, LOCATION)).thenReturn(result);
    }

    private String mintFor(String amount) {
        managerVerifies(globalHolder(MANAGER_ID));
        return service.approve(command("manager", amount)).approvalToken();
    }

    private static Refusal refusalOf(Throwable e) {
        return ((CashMovementRefusedException) e).refusal();
    }

    @Test
    @DisplayName("a verified holder gets a token; only its hash is stored, bound to amount and currency, 5 min expiry")
    void mintsBoundToken() {
        managerVerifies(globalHolder(MANAGER_ID));

        CashMovementApprovalResult result = service.approve(command("manager", "25.00"));

        CashMovementApproval approval = stored.get();
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.currencyCode()).isEqualTo("USD");
        assertThat(result.amount()).isEqualByComparingTo("25.00");
        assertThat(approval.getTokenHash())
                .isEqualTo(CashMovementApprovalServiceImpl.hash(result.approvalToken()))
                .isNotEqualTo(result.approvalToken());
        assertThat(approval.getApproverUserId()).isEqualTo(MANAGER_ID);
        assertThat(approval.getRequestedBy()).isEqualTo("cashier");
        assertThat(approval.getStatus()).isEqualTo(CashMovementApprovalStatus.ISSUED);
        assertThat(approval.getCurrencyCode()).isEqualTo("USD");
        assertThat(approval.getCategoryCode()).isEqualTo("SHOP_SUPPLIES");
        assertThat(approval.toString()).doesNotContain("s3cret");
        assertThat(command("manager", "1").toString()).doesNotContain("s3cret");
        assertThat(result.toString()).doesNotContain(result.approvalToken());
    }

    @Test
    @DisplayName("the step-up is asked about order:session:approve_cash_movement at the drawer's location")
    void asksForThePermissionAtTheLocation() {
        mintFor("25.00");

        ArgumentCaptor<UUID> location = ArgumentCaptor.forClass(UUID.class);
        verify(stepUp).verify(any(), any(), any(), location.capture());
        assertThat(location.getValue()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("AC2: the caller's own credentials — CASH_MOVEMENT_SELF_APPROVAL and no token")
    void selfApprovalAtStepUp() {
        when(stepUp.verify("cashier", "s3cret", PERMISSION, LOCATION)).thenReturn(globalHolder(CASHIER_ID));

        assertThatThrownBy(() -> service.approve(command("cashier", "25.00")))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.SELF_APPROVAL));
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("l5: a caller whose sign-in has no user id is refused with its own cause before any check")
    void callerWithoutUserIdIsUnidentified() {
        signIn("cashier", null, LocationScope.unscoped());

        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.CALLER_UNIDENTIFIED));
        verify(stepUp, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC14: a person without order:session:approve_cash_movement — APPROVAL_DENIED, denial counted")
    void lacksPermissionDenied() {
        when(stepUp.verify("clerk2", "s3cret", PERMISSION, LOCATION))
                .thenReturn(new StepUpPort.StepUpResult(UUID.randomUUID(), false, false, false, List.of()));

        assertThatThrownBy(() -> service.approve(command("clerk2", "25.00")))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_DENIED));
        verify(approvals, never()).save(any());
        verify(denials)
                .save(argThat(d -> SESSION_ID.equals(d.getSessionId()) && "clerk2".equals(d.getApproverUsername())));
    }

    @Test
    @DisplayName("AC14: a refused check passes through as APPROVAL_DENIED, no token, denial counted")
    void checkRefusalPassesThrough() {
        when(stepUp.verify("manager", "wrong", PERMISSION, LOCATION))
                .thenThrow(new CashMovementRefusedException(Refusal.APPROVAL_DENIED, "denied"));
        when(stepUp.verify(" Manager ", "wrong", PERMISSION, LOCATION))
                .thenThrow(new CashMovementRefusedException(Refusal.APPROVAL_DENIED, "denied"));

        assertThatThrownBy(() -> service.approve(new CashMovementApprovalCommand(
                        SESSION_ID, " Manager ", "wrong", "PETTY_EXPENSE", BigDecimal.TEN, "USD", null, null)))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_DENIED));
        verify(approvals, never()).save(any());
        // Counted per drawer and sign-in name, trimmed and lower case.
        verify(denials)
                .save(argThat(d -> SESSION_ID.equals(d.getSessionId()) && "manager".equals(d.getApproverUsername())));
    }

    @Test
    @DisplayName("M3: a manager scoped to another shop cannot approve this drawer — DENIED, no location in the message")
    void approverOutOfReachIsDenied() {
        managerVerifies(new StepUpPort.StepUpResult(MANAGER_ID, true, false, true, List.of(OTHER_SHOP)));

        assertThatThrownBy(() -> service.approve(command("manager", "25.00"))).satisfies(e -> {
            assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_DENIED);
            assertThat(e.getMessage()).doesNotContain(OTHER_SHOP.toString()).doesNotContain(LOCATION.toString());
        });
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("M3: a manager scoped to the drawer's region approves it; a scoped grant with no nodes does not")
    void approverInReachApproves() {
        managerVerifies(new StepUpPort.StepUpResult(MANAGER_ID, true, false, true, List.of(REGION)));
        assertThat(service.approve(command("manager", "25.00")).approvalToken()).isNotBlank();

        managerVerifies(new StepUpPort.StepUpResult(MANAGER_ID, true, true, false, List.of()));
        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_DENIED));
    }

    @Test
    @DisplayName("M3: a cashier scoped to another shop is refused before any credential is checked")
    void cashierOutOfReachIsRefused() {
        signIn(
                "cashier",
                CASHIER_ID,
                LocationScope.of(
                        Set.of(),
                        Set.of(OrderPermissions.ORDER_SESSION_CASH_MOVEMENT),
                        Optional.of(Set.of(OTHER_SHOP)),
                        true,
                        RESOLVER));

        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .isInstanceOf(LocationScopeDeniedException.class);
        verify(stepUp, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MINOR-1: three refusals of one manager name on the drawer stop the step-up for that name only")
    void deniedApprovalsAreCappedPerManagerName() {
        when(denials.countBySessionIdAndApproverUsername(SESSION_ID, "manager")).thenReturn(3L);
        when(denials.countBySessionIdAndApproverUsername(SESSION_ID, "manager2"))
                .thenReturn(2L);

        // The default cap (3) sits below pos-security-service's sign-in lockout (5 attempts).
        assertThatThrownBy(() -> service.approve(command("MANAGER", "25.00")))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_DENIED));
        verify(stepUp, never()).verify(any(), any(), any(), any());

        // Another manager can still approve the same drawer.
        when(stepUp.verify("manager2", "s3cret", PERMISSION, LOCATION)).thenReturn(globalHolder(MANAGER_ID));
        assertThat(service.approve(command("manager2", "25.00")).approvalToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("MINOR-1: a sign-in name longer than the stored column is a 400 before any check")
    void overlongManagerNameIsInvalid() {
        assertThatThrownBy(() -> service.approve(command("m".repeat(256), "25.00")))
                .isInstanceOf(RegisterSessionRequestValidationException.class);
        verify(stepUp, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ADR-0067: a missing currency is 400; one other than the drawer's stamp is 422; both before any check")
    void currencyRules() {
        assertThatThrownBy(() -> service.approve(new CashMovementApprovalCommand(
                        SESSION_ID, "manager", "s3cret", "PETTY_EXPENSE", BigDecimal.TEN, null, null, null)))
                .isInstanceOf(RegisterSessionRequestValidationException.class);
        assertThatThrownBy(() -> service.approve(new CashMovementApprovalCommand(
                        SESSION_ID, "manager", "s3cret", "PETTY_EXPENSE", BigDecimal.TEN, "EUR", null, null)))
                .isInstanceOf(CurrencyNotSupportedException.class);
        verify(stepUp, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("MAJOR-1: the token is minted in the drawer's stamped currency, not today's configuration")
    void tokenUsesTheDrawerStamp() {
        session.setCurrencyCode("CAD");
        managerVerifies(globalHolder(MANAGER_ID));

        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .isInstanceOf(CurrencyNotSupportedException.class)
                .hasMessageContaining("CAD");
        CashMovementApprovalResult minted = service.approve(new CashMovementApprovalCommand(
                SESSION_ID,
                "manager",
                "s3cret",
                "PETTY_EXPENSE",
                new BigDecimal("25.00"),
                "CAD",
                "SHOP_SUPPLIES",
                null));

        assertThat(minted.currencyCode()).isEqualTo("CAD");
        assertThat(stored.get().getCurrencyCode()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("a session that is not OPEN is a conflict and checks no credentials")
    void sessionNotOpen() {
        session.setStatus(RegisterSessionStatus.CLOSING);

        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .isInstanceOf(RegisterSessionConflictException.class);
        verify(stepUp, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("single use: the first use marks it USED, a second use is CASH_MOVEMENT_APPROVAL_INVALID")
    void singleUse() {
        String token = mintFor("25.00");

        CashMovementApproval used = service.use(
                token,
                SESSION_ID,
                CashMovementReason.PETTY_EXPENSE,
                new BigDecimal("25.0000"),
                "USD",
                "SHOP_SUPPLIES",
                null);

        assertThat(used.getStatus()).isEqualTo(CashMovementApprovalStatus.USED);
        assertThat(used.getApproverUserId()).isEqualTo(MANAGER_ID);
        assertThatThrownBy(() -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_INVALID));
    }

    @Test
    @DisplayName("binding: a token for 80.00 used for 85.00, another session, reason, category or currency is INVALID")
    void binding() {
        String token = mintFor("80.00");

        for (Runnable mismatch : List.<Runnable>of(
                () -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("85.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null),
                () -> service.use(
                        token,
                        UUID.randomUUID(),
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("80.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null),
                () -> service.use(
                        token, SESSION_ID, CashMovementReason.BANK_DROP, new BigDecimal("80.00"), "USD", null, null),
                () -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("80.00"),
                        "USD",
                        "OFFICE_SUPPLIES",
                        null),
                () -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("80.00"),
                        "CAD",
                        "SHOP_SUPPLIES",
                        null))) {
            assertThatThrownBy(mismatch::run)
                    .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_INVALID));
        }
        assertThat(stored.get().getStatus()).isEqualTo(CashMovementApprovalStatus.ISSUED);
    }

    @Test
    @DisplayName(
            "l5: after five minutes the token is INVALID and is marked EXPIRED on its own; an unknown token is INVALID")
    void expiryAndUnknown() {
        String token = mintFor("25.00");
        CashMovementApprovalServiceImpl later = newService(Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_INVALID));
        verify(approvals).markExpired(stored.get().getApprovalId());
        assertThatThrownBy(() -> service.use(
                        "not-a-token", SESSION_ID, CashMovementReason.PETTY_EXPENSE, BigDecimal.ONE, "USD", null, null))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.APPROVAL_INVALID));
    }

    @Test
    @DisplayName("AC2: the token's approver recording the movement under their own sign-in — SELF_APPROVAL")
    void approverCannotUseOwnToken() {
        String token = mintFor("25.00");
        signIn("manager", MANAGER_ID, LocationScope.unscoped());

        assertThatThrownBy(() -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e -> assertThat(refusalOf(e)).isEqualTo(Refusal.SELF_APPROVAL));
    }
}
