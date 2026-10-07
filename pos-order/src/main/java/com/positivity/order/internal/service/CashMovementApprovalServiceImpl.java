package com.positivity.order.internal.service;

import com.positivity.order.internal.client.StepUpPort;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementApprovalStatus;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.repository.CashMovementApprovalRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.security.common.SecurityContextHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager approval of a drawer cash movement at a shared register (CAP:550 S16, #2512;
 * SPEC-accounting-workspace §4.6, AW31): the step-up and the single-use token it returns.
 *
 * <p><b>Step-up.</b> Under the cashier's own sign-in, the register sends the manager's credentials.
 * pos-security-service checks them once in the caller's tenant under the sign-in lockout policy and
 * answers who the person is and whether they hold {@code order:session:approve_cash_movement}; it
 * issues no token and opens no session, and the cashier's session is untouched. A failed check, for
 * any reason, is {@code CASH_MOVEMENT_APPROVAL_DENIED} (never 401); the cashier's own credentials are
 * {@code CASH_MOVEMENT_SELF_APPROVAL}. The password is never stored or logged.
 *
 * <p><b>Token.</b> 32 random bytes, returned once; only its SHA-256 hash is stored with the approver's
 * user id. It is bound to the session, the reason, the amount and the category or vendor, expires after
 * {@code pos.order.session.approval-token-ttl} (five minutes by default, as pos-invoice's elevation) and
 * is used by at most one movement, whose recorder must not be the approver.
 */
@Slf4j
@Service
public class CashMovementApprovalService {

    static final String ELEVATED_COUNTER = "order.cash_movement.elevated";

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final CashMovementApprovalRepository approvalRepository;
    private final RegisterSessionRepository registerSessionRepository;
    private final StepUpPort stepUpPort;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${pos.order.session.approval-token-ttl:PT5M}")
    private Duration tokenTtl = Duration.ofMinutes(5);

    public CashMovementApprovalService(
            CashMovementApprovalRepository approvalRepository,
            RegisterSessionRepository registerSessionRepository,
            StepUpPort stepUpPort,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.approvalRepository = approvalRepository;
        this.registerSessionRepository = registerSessionRepository;
        this.stepUpPort = stepUpPort;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /**
     * The step-up: verify the manager's credentials and mint a single-use approval token bound to the
     * movement it approves.
     */
    @Transactional
    public @NonNull CashMovementApprovalResult approve(@NonNull CashMovementApprovalCommand command) {
        String username = requireText(command.managerUsername(), "managerUsername");
        String password = requireText(command.managerPassword(), "managerPassword");
        CashMovementReason reason = CashMovementReason.parse(command.reason())
                .orElseThrow(() -> new RegisterSessionRequestValidationException(
                        "reason must be one of PETTY_EXPENSE, VENDOR_COD, BANK_DROP, FLOAT_INCREASE, FLOAT_DECREASE"));
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new RegisterSessionRequestValidationException("amount must be positive");
        }
        RegisterSession session = registerSessionRepository
                .findById(command.sessionId())
                .orElseThrow(() -> new RegisterSessionNotFoundException(command.sessionId()));
        if (session.getStatus() != RegisterSessionStatus.OPEN) {
            throw new RegisterSessionConflictException("Cash movement approvals require an OPEN session; session "
                    + session.getSessionId() + " is " + session.getStatus());
        }

        String cashier = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        StepUpPort.StepUpResult verified =
                stepUpPort.verify(username, password, OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT);
        if (isCaller(verified.userId()) || isCallersName(username)) {
            log.warn(
                    "Cash movement approval refused: self-approval sessionId={} cashier={}",
                    session.getSessionId(),
                    cashier);
            throw new CashMovementRefusedException(
                    Refusal.SELF_APPROVAL, "A cash movement cannot be approved by the cashier who records it");
        }
        if (!verified.holdsPermission()) {
            log.warn(
                    "Cash movement approval denied sessionId={} cashier={} approver={}",
                    session.getSessionId(),
                    cashier,
                    verified.userId());
            throw new CashMovementRefusedException(
                    Refusal.APPROVAL_DENIED, "The manager's credentials could not be verified for this approval");
        }

        String token = newToken();
        Instant expiresAt = Instant.now(clock).plus(tokenTtl);
        CashMovementApproval approval = approvalRepository.save(CashMovementApproval.builder()
                .sessionId(session.getSessionId())
                .reasonCode(reason)
                .amount(scale(amount))
                .categoryCode(blankToNull(command.categoryCode()))
                .vendorId(command.vendorId())
                .tokenHash(hash(token))
                .approverUserId(verified.userId())
                .requestedBy(cashier)
                .status(CashMovementApprovalStatus.ISSUED)
                .expiresAt(expiresAt)
                .build());
        log.info(
                "Cash movement approval issued approvalId={} sessionId={} reason={} cashier={} approver={}",
                approval.getApprovalId(),
                session.getSessionId(),
                reason,
                cashier,
                verified.userId());
        return new CashMovementApprovalResult(token, expiresAt);
    }

    /**
     * Use {@code token} for the movement described, inside the caller's transaction (which holds the
     * session's row lock). Returns the approval, now USED; the caller records the movement id on it.
     *
     * @throws CashMovementRefusedException {@code APPROVAL_INVALID} for an unknown, used, expired or
     *     mismatched token; {@code SELF_APPROVAL} when the approver is the caller
     */
    @Transactional
    public @NonNull CashMovementApproval use(
            @NonNull String token,
            @NonNull UUID sessionId,
            @NonNull CashMovementReason reason,
            @NonNull BigDecimal amount,
            @Nullable String categoryCode,
            @Nullable UUID vendorId) {
        CashMovementApproval approval = approvalRepository
                .findByTokenHash(hash(token))
                .orElseThrow(() -> invalid("The approval token is not known"));
        Instant now = Instant.now(clock);
        if (approval.getStatus() != CashMovementApprovalStatus.ISSUED) {
            throw invalid("The approval token was already used");
        }
        if (!now.isBefore(approval.getExpiresAt())) {
            throw invalid("The approval token has expired");
        }
        boolean bound = approval.getSessionId().equals(sessionId)
                && approval.getReasonCode() == reason
                && approval.getAmount().compareTo(amount) == 0
                && Objects.equals(approval.getCategoryCode(), blankToNull(categoryCode))
                && Objects.equals(approval.getVendorId(), vendorId);
        if (!bound) {
            throw invalid("The approval token was issued for another movement");
        }
        if (isCaller(approval.getApproverUserId())) {
            throw new CashMovementRefusedException(
                    Refusal.SELF_APPROVAL, "A cash movement cannot be approved by the cashier who records it");
        }
        approval.setStatus(CashMovementApprovalStatus.USED);
        approval.setUsedAt(now);
        if (meterRegistry != null) {
            Counter.builder(ELEVATED_COUNTER)
                    .description("Drawer cash movements recorded with a manager's approval")
                    .tag("reason", reason.name())
                    .register(meterRegistry)
                    .increment();
        }
        return approvalRepository.save(approval);
    }

    /**
     * Whether the verified person is the caller. Compared by user id; when the caller's context carries
     * none, fail closed rather than let a cashier approve their own movement.
     */
    private static boolean isCaller(@NonNull UUID approverUserId) {
        return currentUserId().map(approverUserId::equals).orElse(true);
    }

    /** The manager's sign-in name is the caller's own (a second guard beside the user id). */
    private static boolean isCallersName(@NonNull String managerUsername) {
        String caller = SecurityContextHelper.getCurrentUsernameOrDefault("");
        return caller.trim()
                .toLowerCase(Locale.ROOT)
                .equals(managerUsername.trim().toLowerCase(Locale.ROOT));
    }

    private static Optional<UUID> currentUserId() {
        try {
            return SecurityContextHelper.getCurrentUserIdAsUuid();
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private static CashMovementRefusedException invalid(String message) {
        return new CashMovementRefusedException(Refusal.APPROVAL_INVALID, message);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    /** Lowercase hex SHA-256 of the token; the only form stored. */
    static @NonNull String hash(@NonNull String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RegisterSessionRequestValidationException(field + " is required");
        }
        return value;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
