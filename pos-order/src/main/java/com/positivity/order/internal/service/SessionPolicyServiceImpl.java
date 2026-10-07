package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.exception.SessionPolicyConflictException;
import com.positivity.order.internal.exception.SessionPolicyValidationException;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
import com.positivity.order.internal.service.model.SessionPolicyChangeView;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's drawer policy (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6 "Drawer limits",
 * AW19). One row per tenant, written on the first change; until then the defaults apply: petty
 * expenses allowed with a cashier limit of 50.00, vendor cash on delivery off, over/short tolerance
 * 5.00. A bank drop is always allowed with no limit, and a float change is always allowed and always
 * needs a manager; neither is configurable.
 *
 * <p>Vendor cash on delivery cannot be switched on yet: the vendor a payout names must be checked
 * against pos-order's vendor copy, which arrives with S24 (#2517; the story's "Spec discrepancy 1",
 * G15). Until then a PUT switching it on is refused, so it stays at its default Off.
 *
 * <p>Racing PUTs: the row's version (or, for the first change, the one-row-per-tenant key) makes the
 * loser fail at flush; it is answered 409 and retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionPolicyServiceImpl implements SessionPolicyService {

    static final BigDecimal DEFAULT_PETTY_EXPENSE_LIMIT = scale(new BigDecimal("50.00"));
    static final BigDecimal DEFAULT_OVER_SHORT_TOLERANCE = scale(new BigDecimal("5.00"));

    /** Whether vendor cash on delivery may be switched on: not before pos-order's vendor copy (S24). */
    static final boolean VENDOR_COD_AVAILABLE = false;

    static final int MIN_JUSTIFICATION_LENGTH = 10;
    static final int MAX_JUSTIFICATION_LENGTH = 1000;

    static final String PETTY_EXPENSE_ALLOWED = "PETTY_EXPENSE_ALLOWED";
    static final String PETTY_EXPENSE_LIMIT = "PETTY_EXPENSE_LIMIT";
    static final String VENDOR_COD_ALLOWED = "VENDOR_COD_ALLOWED";
    static final String VENDOR_COD_LIMIT = "VENDOR_COD_LIMIT";
    static final String OVER_SHORT_TOLERANCE = "OVER_SHORT_TOLERANCE";

    private static final SessionPolicyView DEFAULTS =
            new SessionPolicyView(null, true, DEFAULT_PETTY_EXPENSE_LIMIT, false, null, DEFAULT_OVER_SHORT_TOLERANCE);

    private final SessionPolicyRepository sessionPolicyRepository;
    private final SessionPolicyChangeRepository sessionPolicyChangeRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull SessionPolicyView current() {
        return sessionPolicyRepository
                .findFirstByOrderByCreatedAtAsc()
                .map(SessionPolicyServiceImpl::toView)
                .orElse(DEFAULTS);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SessionPolicyChangeView> history() {
        return sessionPolicyChangeRepository.findAllByOrderByChangedAtDescChangeIdDesc().stream()
                .map(change -> new SessionPolicyChangeView(
                        change.getSetting(),
                        change.getOldValue(),
                        change.getNewValue(),
                        change.getActor(),
                        change.getJustification(),
                        change.getChangedAt()))
                .toList();
    }

    @Override
    @Transactional
    public @NonNull SessionPolicyView update(@NonNull UpdateSessionPolicyCommand command) {
        String justification = validate(command);
        SessionPolicy stored =
                sessionPolicyRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
        SessionPolicyView before = stored == null ? DEFAULTS : toView(stored);

        boolean pettyAllowed = command.pettyExpenseAllowed();
        BigDecimal pettyLimit = scaleOrNull(command.pettyExpenseLimit());
        boolean codAllowed = command.vendorCodAllowed();
        BigDecimal codLimit = scaleOrNull(command.vendorCodLimit());
        BigDecimal tolerance = scale(command.overShortTolerance());

        List<String[]> changes = new ArrayList<>();
        diff(changes, PETTY_EXPENSE_ALLOWED, before.pettyExpenseAllowed(), pettyAllowed);
        diff(changes, PETTY_EXPENSE_LIMIT, before.pettyExpenseLimit(), pettyLimit);
        diff(changes, VENDOR_COD_ALLOWED, before.vendorCodAllowed(), codAllowed);
        diff(changes, VENDOR_COD_LIMIT, before.vendorCodLimit(), codLimit);
        diff(changes, OVER_SHORT_TOLERANCE, before.overShortTolerance(), tolerance);
        if (changes.isEmpty()) {
            // A PUT that changes nothing writes nothing (§4.6): no row, no history.
            return before;
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        SessionPolicy policy = stored != null ? stored : new SessionPolicy();
        policy.setPettyExpenseAllowed(pettyAllowed);
        policy.setPettyExpenseLimit(pettyLimit);
        policy.setVendorCodAllowed(codAllowed);
        policy.setVendorCodLimit(codLimit);
        policy.setOverShortTolerance(tolerance);
        policy.setUpdatedBy(actor);
        SessionPolicy saved;
        try {
            saved = sessionPolicyRepository.saveAndFlush(policy);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new SessionPolicyConflictException(
                    "The drawer policy was changed by someone else; read it again and retry", e);
        }

        Instant now = Instant.now(clock);
        for (String[] change : changes) {
            sessionPolicyChangeRepository.save(SessionPolicyChange.builder()
                    .setting(change[0])
                    .oldValue(change[1])
                    .newValue(change[2])
                    .actor(actor)
                    .justification(justification)
                    .policyVersion(saved.getVersion())
                    .changedAt(now)
                    .build());
        }
        log.info("Drawer policy changed by {}: {} setting(s), version {}", actor, changes.size(), saved.getVersion());
        return toView(saved);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String validate(UpdateSessionPolicyCommand command) {
        String justification =
                command.justification() == null ? "" : command.justification().trim();
        if (justification.length() < MIN_JUSTIFICATION_LENGTH) {
            throw new SessionPolicyValidationException(
                    "justification must be at least " + MIN_JUSTIFICATION_LENGTH + " characters");
        }
        if (justification.length() > MAX_JUSTIFICATION_LENGTH) {
            throw new SessionPolicyValidationException(
                    "justification must be at most " + MAX_JUSTIFICATION_LENGTH + " characters");
        }
        if (command.pettyExpenseAllowed() == null || command.vendorCodAllowed() == null) {
            throw new SessionPolicyValidationException("pettyExpense.allowed and vendorCod.allowed are required");
        }
        if (command.overShortTolerance() == null) {
            throw new SessionPolicyValidationException("overShortTolerance is required");
        }
        requireNotNegative("pettyExpense.cashierLimit", command.pettyExpenseLimit());
        requireNotNegative("vendorCod.cashierLimit", command.vendorCodLimit());
        requireNotNegative("overShortTolerance", command.overShortTolerance());
        if (command.pettyExpenseAllowed() && command.pettyExpenseLimit() == null) {
            throw new SessionPolicyValidationException(
                    "pettyExpense.cashierLimit is required while petty expenses are allowed");
        }
        if (command.vendorCodAllowed()) {
            if (command.vendorCodLimit() == null) {
                throw new SessionPolicyValidationException(
                        "vendorCod.cashierLimit is required to switch vendor cash on delivery on");
            }
            if (!VENDOR_COD_AVAILABLE) {
                throw new SessionPolicyValidationException(
                        "vendor cash on delivery cannot be switched on until pos-order holds the vendor list");
            }
        }
        return justification;
    }

    private static void requireNotNegative(String field, @Nullable BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new SessionPolicyValidationException(field + " must not be negative");
        }
    }

    private static void diff(List<String[]> changes, String setting, boolean before, boolean after) {
        if (before != after) {
            changes.add(new String[] {setting, Boolean.toString(before), Boolean.toString(after)});
        }
    }

    private static void diff(
            List<String[]> changes, String setting, @Nullable BigDecimal before, @Nullable BigDecimal after) {
        boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
        if (!same) {
            changes.add(new String[] {setting, display(before), display(after)});
        }
    }

    private static @Nullable String display(@Nullable BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static SessionPolicyView toView(SessionPolicy policy) {
        return new SessionPolicyView(
                policy.getVersion(),
                policy.isPettyExpenseAllowed(),
                policy.getPettyExpenseLimit(),
                policy.isVendorCodAllowed(),
                policy.getVendorCodLimit(),
                Objects.requireNonNull(policy.getOverShortTolerance()));
    }

    private static @Nullable BigDecimal scaleOrNull(@Nullable BigDecimal value) {
        return value == null ? null : scale(value);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
