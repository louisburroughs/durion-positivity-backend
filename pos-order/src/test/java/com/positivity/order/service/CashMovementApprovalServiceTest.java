package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.StepUpPort;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementApprovalStatus;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.repository.CashMovementApprovalRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.security.common.GatewaySecurityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * CAP:550 S16 (#2512; AW31): the step-up mints a single-use token bound to one movement; the token is
 * stored only as its hash, expires, is used once, and its approver is never the cashier. The
 * pos-security-service check is stubbed: verified, denied, and lacking the permission.
 */
@DisplayName("CashMovementApprovalService — step-up and single-use token (AW31)")
class CashMovementApprovalServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a001");
    private static final UUID CASHIER_ID = UUID.fromString("01900000-0000-7000-8000-00000000a0c1");
    private static final UUID MANAGER_ID = UUID.fromString("01900000-0000-7000-8000-00000000b001");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final CashMovementApprovalRepository approvals = mock(CashMovementApprovalRepository.class);
    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final StepUpPort stepUp = mock(StepUpPort.class);
    private final AtomicReference<CashMovementApproval> stored = new AtomicReference<>();
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private CashMovementApprovalService service;

    @BeforeEach
    void setUp() {
        service = newService(clock);
        when(sessions.findById(SESSION_ID))
                .thenReturn(Optional.of(RegisterSession.builder()
                        .sessionId(SESSION_ID)
                        .terminalId("T-1")
                        .status(RegisterSessionStatus.OPEN)
                        .build()));
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
        signIn("cashier", CASHIER_ID);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private CashMovementApprovalService newService(Clock at) {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        return new CashMovementApprovalService(approvals, sessions, stepUp, at, meters);
    }

    private static void signIn(String username, UUID userId) {
        var token = new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username, GatewaySecurityConstants.DETAIL_USER_ID, userId));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static CashMovementApprovalCommand command(String username, String amount) {
        return new CashMovementApprovalCommand(
                SESSION_ID, username, "s3cret", "PETTY_EXPENSE", new BigDecimal(amount), "SHOP_SUPPLIES", null);
    }

    private String mintFor(String amount) {
        when(stepUp.verify("manager", "s3cret", OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT))
                .thenReturn(new StepUpPort.StepUpResult(MANAGER_ID, true));
        return service.approve(command("manager", amount)).approvalToken();
    }

    @Test
    @DisplayName("a verified holder gets a token; only its hash is stored, with the approver and a five-minute expiry")
    void mintsBoundToken() {
        CashMovementApprovalResult result = service.approve(commandVerified());

        CashMovementApproval approval = stored.get();
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(approval.getTokenHash())
                .isEqualTo(CashMovementApprovalService.hash(result.approvalToken()))
                .isNotEqualTo(result.approvalToken());
        assertThat(approval.getApproverUserId()).isEqualTo(MANAGER_ID);
        assertThat(approval.getRequestedBy()).isEqualTo("cashier");
        assertThat(approval.getStatus()).isEqualTo(CashMovementApprovalStatus.ISSUED);
        assertThat(approval.getAmount()).isEqualByComparingTo("25.00");
        assertThat(approval.getCategoryCode()).isEqualTo("SHOP_SUPPLIES");
        assertThat(approval.toString()).doesNotContain("s3cret");
        assertThat(command("manager", "1").toString()).doesNotContain("s3cret");
    }

    private CashMovementApprovalCommand commandVerified() {
        when(stepUp.verify("manager", "s3cret", OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT))
                .thenReturn(new StepUpPort.StepUpResult(MANAGER_ID, true));
        return command("manager", "25.00");
    }

    @Test
    @DisplayName("AC2: the caller's own credentials — CASH_MOVEMENT_SELF_APPROVAL and no token")
    void selfApprovalAtStepUp() {
        when(stepUp.verify("cashier", "s3cret", OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT))
                .thenReturn(new StepUpPort.StepUpResult(CASHIER_ID, true));

        assertThatThrownBy(() -> service.approve(command("cashier", "25.00")))
                .isInstanceOf(CashMovementRefusedException.class)
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.SELF_APPROVAL));
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("AC14: a person without order:session:approve_cash_movement — CASH_MOVEMENT_APPROVAL_DENIED, no token")
    void lacksPermissionDenied() {
        when(stepUp.verify("clerk2", "s3cret", OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT))
                .thenReturn(new StepUpPort.StepUpResult(UUID.randomUUID(), false));

        assertThatThrownBy(() -> service.approve(command("clerk2", "25.00")))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.APPROVAL_DENIED));
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName(
            "AC14: wrong credentials (the check refuses) — the refusal passes through as APPROVAL_DENIED, no token")
    void checkRefusalPassesThrough() {
        when(stepUp.verify("manager", "wrong", OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT))
                .thenThrow(new CashMovementRefusedException(Refusal.APPROVAL_DENIED, "denied"));

        assertThatThrownBy(() -> service.approve(new CashMovementApprovalCommand(
                        SESSION_ID, "manager", "wrong", "PETTY_EXPENSE", BigDecimal.TEN, null, null)))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.APPROVAL_DENIED));
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("a session that is not OPEN is a conflict and checks no credentials")
    void sessionNotOpen() {
        when(sessions.findById(SESSION_ID))
                .thenReturn(Optional.of(RegisterSession.builder()
                        .sessionId(SESSION_ID)
                        .status(RegisterSessionStatus.CLOSING)
                        .build()));

        assertThatThrownBy(() -> service.approve(command("manager", "25.00")))
                .isInstanceOf(RegisterSessionConflictException.class);
        verify(stepUp, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("single use: the first use marks it USED, a second use is CASH_MOVEMENT_APPROVAL_INVALID")
    void singleUse() {
        String token = mintFor("25.00");

        CashMovementApproval used = service.use(
                token, SESSION_ID, CashMovementReason.PETTY_EXPENSE, new BigDecimal("25.0000"), "SHOP_SUPPLIES", null);

        assertThat(used.getStatus()).isEqualTo(CashMovementApprovalStatus.USED);
        assertThat(used.getApproverUserId()).isEqualTo(MANAGER_ID);
        assertThatThrownBy(() -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.APPROVAL_INVALID));
    }

    @Test
    @DisplayName("binding: a token for 80.00 used for 85.00, another session, reason or category is INVALID")
    void binding() {
        String token = mintFor("80.00");

        for (Runnable mismatch : List.<Runnable>of(
                () -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("85.00"),
                        "SHOP_SUPPLIES",
                        null),
                () -> service.use(
                        token,
                        UUID.randomUUID(),
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("80.00"),
                        "SHOP_SUPPLIES",
                        null),
                () -> service.use(token, SESSION_ID, CashMovementReason.BANK_DROP, new BigDecimal("80.00"), null, null),
                () -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("80.00"),
                        "OFFICE_SUPPLIES",
                        null))) {
            assertThatThrownBy(mismatch::run)
                    .satisfies(e -> assertThat(((CashMovementRefusedException) e).refusal())
                            .isEqualTo(Refusal.APPROVAL_INVALID));
        }
        assertThat(stored.get().getStatus()).isEqualTo(CashMovementApprovalStatus.ISSUED);
    }

    @Test
    @DisplayName("expiry: after five minutes the token is INVALID; an unknown token is INVALID")
    void expiryAndUnknown() {
        String token = mintFor("25.00");
        CashMovementApprovalService later = newService(Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.APPROVAL_INVALID));
        assertThatThrownBy(() -> service.use(
                        "not-a-token", SESSION_ID, CashMovementReason.PETTY_EXPENSE, BigDecimal.ONE, null, null))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.APPROVAL_INVALID));
    }

    @Test
    @DisplayName("AC2: the token's approver recording the movement under their own sign-in — SELF_APPROVAL")
    void approverCannotUseOwnToken() {
        String token = mintFor("25.00");
        signIn("manager", MANAGER_ID);

        assertThatThrownBy(() -> service.use(
                        token,
                        SESSION_ID,
                        CashMovementReason.PETTY_EXPENSE,
                        new BigDecimal("25.00"),
                        "SHOP_SUPPLIES",
                        null))
                .satisfies(e ->
                        assertThat(((CashMovementRefusedException) e).refusal()).isEqualTo(Refusal.SELF_APPROVAL));
    }

    @Test
    @DisplayName("the step-up is asked about order:session:approve_cash_movement, never a token or a session")
    void asksForTheApprovalPermission() {
        mintFor("25.00");

        ArgumentCaptor<String> permission = ArgumentCaptor.forClass(String.class);
        verify(stepUp).verify(any(), any(), permission.capture());
        assertThat(permission.getValue()).isEqualTo("order:session:approve_cash_movement");
    }
}
