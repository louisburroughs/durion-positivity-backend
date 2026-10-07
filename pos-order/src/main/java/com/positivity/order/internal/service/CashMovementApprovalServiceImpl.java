package com.positivity.order.internal.service;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.order.internal.client.StepUpPort;
import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementApprovalStatus;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.repository.CashMovementApprovalRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import com.positivity.security.common.LocationAncestorResolver;
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
import java.util.Collection;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manager approval of a drawer cash movement at a shared register (CAP:550 S16, #2512;
 * SPEC-accounting-workspace §4.6, AW31).
 *
 * <p><b>Step-up.</b> Under the cashier's own sign-in, the register sends the manager's credentials.
 * pos-security-service checks them once in the caller's tenant under the sign-in lockout policy and
 * answers who the person is, whether they hold {@code order:session:approve_cash_movement} and with what
 * location scope; it issues no token and opens no session, and the cashier's session is untouched. The
 * approval needs the person to hold the permission <em>at the drawer's location</em>: their scope is
 * evaluated here against pos-order's location replica, exactly as a token of theirs would be (ADR-0061),
 * so a manager scoped to another shop cannot approve this drawer. A failed check, for any reason, is
 * {@code CASH_MOVEMENT_APPROVAL_DENIED} (never 401); the cashier's own credentials are {@code
 * CASH_MOVEMENT_SELF_APPROVAL}. The remote call runs outside any database transaction, and after {@code
 * pos.order.session.max-denied-approvals} failures on one drawer the step-up stops asking
 * pos-security-service, so a drawer cannot be used to lock managers out. The password is never stored
 * or logged.
 *
 * <p><b>Token.</b> 32 random bytes, returned once; only its SHA-256 hash is stored with the approver's
 * user id. It is bound to the session, the reason, the amount and the category or vendor, expires after
 * {@code pos.order.session.approval-token-ttl} (five minutes by default, as pos-invoice's elevation) and
 * is used by at most one movement, whose recorder must not be the approver. An expired token is marked
 * {@code EXPIRED} in a transaction of its own when a movement presents it.
 */
@Slf4j
@Service
public class CashMovementApprovalServiceImpl implements CashMovementApprovalService {

    static final String ELEVATED_COUNTER = "order.cash_movement.elevated";

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final CashMovementApprovalRepository approvalRepository;
    private final RegisterSessionRepository registerSessionRepository;
    private final StepUpPort stepUpPort;
    private final LocationAncestorResolver locationAncestors;
    private final FunctionalCurrency functionalCurrency;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final TransactionTemplate ownTransaction;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${pos.order.session.approval-token-ttl:PT5M}")
    private Duration tokenTtl = Duration.ofMinutes(5);

    @Value("${pos.order.session.max-denied-approvals:5}")
    private int maxDeniedApprovals = 5;

    @SuppressWarnings("java:S107") // one collaborator per concern of the approval
    public CashMovementApprovalServiceImpl(
            CashMovementApprovalRepository approvalRepository,
            RegisterSessionRepository registerSessionRepository,
            StepUpPort stepUpPort,
            LocationAncestorResolver locationAncestors,
            FunctionalCurrency functionalCurrency,
            Clock clock,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.approvalRepository = approvalRepository;
        this.registerSessionRepository = registerSessionRepository;
        this.stepUpPort = stepUpPort;
        this.locationAncestors = locationAncestors;
        this.functionalCurrency = functionalCurrency;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Not {@code @Transactional}: each repository call is its own short transaction, so no database
     * connection is held across the remote step-up call (review m4).
     */
    @Override
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
        if (!FunctionalCurrency.isIsoCode(command.currencyCode())) {
            throw new RegisterSessionRequestValidationException(
                    "currencyCode is required and must be an ISO 4217 code");
        }
        if (!functionalCurrency.isFunctional(command.currencyCode())) {
            throw new CurrencyNotSupportedException("Drawer cash is counted in " + functionalCurrency.code()
                    + ", the functional currency; " + command.currencyCode().trim() + " is not supported");
        }
        RegisterSession session = registerSessionRepository
                .findById(command.sessionId())
                .orElseThrow(() -> new RegisterSessionNotFoundException(command.sessionId()));
        // ADR-0061: after the 404, the cashier's own reach at the drawer (the endpoint's permission).
        UUID location = session.getLocationId();
        SecurityContextHelper.locationScope()
                .require(OrderPermissions.ORDER_SESSION_CASH_MOVEMENT, location == null ? "" : location.toString());
        if (session.getStatus() != RegisterSessionStatus.OPEN) {
            throw new RegisterSessionConflictException("Cash movement approvals require an OPEN session; session "
                    + session.getSessionId() + " is " + session.getStatus());
        }
        UUID callerUserId = currentUserId()
                .orElseThrow(() -> new CashMovementRefusedException(
                        Refusal.CALLER_UNIDENTIFIED,
                        "Your sign-in carries no user id, so a manager's approval cannot be checked against it;"
                                + " sign in again"));
        String cashier = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        if (session.getStepUpDenials() >= maxDeniedApprovals) {
            log.warn(
                    "Cash movement approval refused without a check: {} failed approvals on sessionId={} cashier={}",
                    session.getStepUpDenials(),
                    session.getSessionId(),
                    cashier);
            throw denied("Too many failed manager approvals on this drawer; close it and open a new session");
        }

        StepUpPort.StepUpResult verified;
        try {
            verified = stepUpPort.verify(
                    username, password, OrderPermissions.ORDER_SESSION_APPROVE_CASH_MOVEMENT, location);
        } catch (CashMovementRefusedException e) {
            countDenial(session);
            throw e;
        }
        if (verified.userId().equals(callerUserId) || isCallersName(username)) {
            log.warn(
                    "Cash movement approval refused: self-approval sessionId={} cashier={}",
                    session.getSessionId(),
                    cashier);
            throw new CashMovementRefusedException(
                    Refusal.SELF_APPROVAL, "A cash movement cannot be approved by the cashier who records it");
        }
        if (!verified.holdsPermission() || !reaches(verified, location)) {
            countDenial(session);
            log.warn(
                    "Cash movement approval denied sessionId={} cashier={} approver={} holdsPermission={}",
                    session.getSessionId(),
                    cashier,
                    verified.userId(),
                    verified.holdsPermission());
            throw denied("The manager's credentials could not be verified for this approval");
        }

        String token = newToken();
        Instant expiresAt = Instant.now(clock).plus(tokenTtl);
        CashMovementApproval approval = approvalRepository.save(CashMovementApproval.builder()
                .sessionId(session.getSessionId())
                .reasonCode(reason)
                .amount(scale(amount))
                .currencyCode(functionalCurrency.code())
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
        return new CashMovementApprovalResult(token, expiresAt, scale(amount), functionalCurrency.code());
    }

    @Override
    @Transactional
    public @NonNull CashMovementApproval use(
            @NonNull String token,
            @NonNull UUID sessionId,
            @NonNull CashMovementReason reason,
            @NonNull BigDecimal amount,
            @NonNull String currencyCode,
            @Nullable String categoryCode,
            @Nullable UUID vendorId) {
        CashMovementApproval approval = approvalRepository
                .findByTokenHash(hash(token))
                .orElseThrow(() -> invalid("The approval token is not known"));
        Instant now = Instant.now(clock);
        if (approval.getStatus() != CashMovementApprovalStatus.ISSUED) {
            throw invalid("The approval token was already used or has expired");
        }
        if (!now.isBefore(approval.getExpiresAt())) {
            // The refusal rolls the movement back; the expiry is recorded on its own (review l5).
            ownTransaction.executeWithoutResult(_ -> approvalRepository.markExpired(approval.getApprovalId()));
            throw invalid("The approval token has expired");
        }
        boolean bound = approval.getSessionId().equals(sessionId)
                && approval.getReasonCode() == reason
                && approval.getAmount().compareTo(amount) == 0
                && currencyCode.equals(approval.getCurrencyCode())
                && Objects.equals(approval.getCategoryCode(), blankToNull(categoryCode))
                && Objects.equals(approval.getVendorId(), vendorId);
        if (!bound) {
            throw invalid("The approval token was issued for another movement");
        }
        UUID callerUserId = currentUserId()
                .orElseThrow(() -> new CashMovementRefusedException(
                        Refusal.CALLER_UNIDENTIFIED,
                        "Your sign-in carries no user id, so the approval cannot be checked against it; sign in"
                                + " again"));
        if (approval.getApproverUserId().equals(callerUserId)) {
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
     * Whether the approver's grant reaches the drawer's location, by the same decision table as {@code
     * LocationScope#covers}: a global grant reaches everywhere; a scoped one reaches a location whose
     * ancestor set on a scoped dimension holds one of the approver's assigned nodes. A drawer with no
     * location, or an approver with no assignment, is not reached by a scoped grant (fail closed). This is
     * the approver's reach, not the caller's, so it is computed here rather than through the caller's
     * {@code LocationScope}.
     */
    private boolean reaches(StepUpPort.StepUpResult approver, @Nullable UUID location) {
        if (!approver.financialScoped() && !approver.otherScoped()) {
            return true;
        }
        if (location == null || approver.assignedLocationIds().isEmpty()) {
            return false;
        }
        AncestorSets ancestors = locationAncestors.ancestorsOf(location);
        return (approver.financialScoped() && intersects(ancestors.financial(), approver.assignedLocationIds()))
                || (approver.otherScoped() && intersects(ancestors.other(), approver.assignedLocationIds()));
    }

    private static boolean intersects(Collection<UUID> ancestors, Collection<UUID> nodes) {
        for (UUID node : nodes) {
            if (ancestors.contains(node)) {
                return true;
            }
        }
        return false;
    }

    private void countDenial(RegisterSession session) {
        registerSessionRepository.countStepUpDenial(session.getSessionId());
    }

    private static CashMovementRefusedException denied(String message) {
        return new CashMovementRefusedException(Refusal.APPROVAL_DENIED, message);
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
