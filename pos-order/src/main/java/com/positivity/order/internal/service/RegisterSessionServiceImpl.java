package com.positivity.order.internal.service;

import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.client.TaxPlausibilityPort.StatedAmount;
import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.dto.CashMovementStatedTax;
import com.positivity.order.internal.dto.CashMovementSummary;
import com.positivity.order.internal.dto.RegisterSessionSummary;
import com.positivity.order.internal.dto.SessionReport;
import com.positivity.order.internal.entity.CashMovement;
import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.entity.CashMovementType;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SessionPolicyType;
import com.positivity.order.internal.exception.CashMovementIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.CashMovementRefusedException.Refusal;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.RegisterFloatLocationMismatchException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.repository.CashMovementRepository;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.order.internal.service.model.CashMovementResult;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register (drawer) sessions and cash management (parity stories G1/G2, spec R6.1–R6.6). The Odoo
 * {@code pos.session} + cash-control analog: a session is a drawer shift on one terminal, orders
 * tendered while it is OPEN bind to it, and close reconciles the counted drawer against the
 * theoretical cash (opening float + Σ session CASH settlements + Σ cash movements).
 *
 * <p>CAP:550 S16 (#2512; SPEC-accounting-workspace §4.6, AW15, AW16, AW19, AW31):
 *
 * <ul>
 *   <li>A session opens with the register's configured float from pos-order's copy of accounting's
 *       floats (zero when the register has none; it can be negative after an accounting reversal),
 *       and the opener is the caller. A difference at open or close shows as over/short.
 *   <li>A movement carries one of the fixed {@link CashMovementReason}s with that reason's fields; the
 *       cashier is the caller. A type switched off in the tenant's drawer policy is refused at once;
 *       a limited type is checked against the session's running total of that reason including the
 *       new amount, and above the cashier limit — and for every float change — the request must carry
 *       a manager's single-use approval token ({@link CashMovementApprovalService}). A float movement
 *       must close the gap between the configured float and the float now in the drawer, exactly.
 *   <li>Movements of one session are serialised on the session's row lock, and a movement is
 *       idempotent on the register's {@code requestId}: a replay returns the first result, even after
 *       its token was used; the same id with another payload is a conflict.
 *   <li>Confirm-close compares the over/short with the policy's tolerance and publishes the close
 *       fact at schema version 2 with every movement.
 * </ul>
 *
 * <p>CAP:550 S40 (#2578): a successful open queues {@code order.session.opened} in its transaction; a refused
 * open queues nothing. {@link RegisterSessionFactsBootstrap} re-emits it for every active session at start.
 */
@Slf4j
@Service
public class RegisterSessionServiceImpl implements RegisterSessionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final String CASH = "CASH";
    private static final List<RegisterSessionStatus> ACTIVE_STATUSES =
            List.of(RegisterSessionStatus.OPEN, RegisterSessionStatus.CLOSING);

    private final RegisterSessionRepository registerSessionRepository;
    private final CashMovementRepository cashMovementRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final OrderDomainEventPublisher domainEventPublisher;
    private final HouseAccountReplica houseAccounts;
    private final SessionPolicyService sessionPolicyService;
    private final CashMovementApprovalService approvalService;
    private final ExtAccountingRegisterFloatRepository registerFloatRepository;
    private final ExtAccountingPettyExpenseCategoryRepository categoryRepository;
    private final FunctionalCurrency functionalCurrency;
    private final DrawerStatedTax drawerStatedTax;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;

    static final String REFUSED_COUNTER = "order.cash_movement.refused";
    static final String OPENING_FLOAT_ADJUSTED = "order.session.opening_float.adjusted";

    /** The unique key that makes the register's requestId idempotent (V4). */
    private static final String REQUEST_ID_CONSTRAINT = "uq_cash_movement_request";

    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_RECEIPT_REFERENCE_LENGTH = 128;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_SUPPLIER_NAME_LENGTH = 200;
    private static final java.util.regex.Pattern REGIME_CODE = java.util.regex.Pattern.compile("^[A-Z0-9_]{1,32}$");
    private static final String STATED_TAXES = "statedTaxes";
    private static final String SUPPLIER_NAME = "supplierName";
    private static final String SUPPLIER_REGISTRATION_NUMBER = "supplierRegistrationNumber";

    @SuppressWarnings("java:S107") // one collaborator per concern of the drawer; grouping them would hide them
    public RegisterSessionServiceImpl(
            RegisterSessionRepository registerSessionRepository,
            CashMovementRepository cashMovementRepository,
            SalesOrderRepository salesOrderRepository,
            OrderPaymentRecordRepository paymentRecordRepository,
            OrderDomainEventPublisher domainEventPublisher,
            HouseAccountReplica houseAccounts,
            SessionPolicyService sessionPolicyService,
            CashMovementApprovalService approvalService,
            ExtAccountingRegisterFloatRepository registerFloatRepository,
            ExtAccountingPettyExpenseCategoryRepository categoryRepository,
            FunctionalCurrency functionalCurrency,
            DrawerStatedTax drawerStatedTax,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.registerSessionRepository = registerSessionRepository;
        this.cashMovementRepository = cashMovementRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.houseAccounts = houseAccounts;
        this.sessionPolicyService = sessionPolicyService;
        this.approvalService = approvalService;
        this.registerFloatRepository = registerFloatRepository;
        this.categoryRepository = categoryRepository;
        this.functionalCurrency = functionalCurrency;
        this.drawerStatedTax = drawerStatedTax;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary openSession(@NonNull OpenSessionCommand command) {
        // Block while an OPEN *or* CLOSING session owns the terminal: a session being counted at
        // close still holds the drawer (the DB partial unique index covers status <> 'CLOSED').
        if (registerSessionRepository.existsByTerminalIdAndStatusIn(command.terminalId(), ACTIVE_STATUSES)) {
            throw new RegisterSessionConflictException(
                    "Terminal " + command.terminalId() + " already has an active register session (OPEN or CLOSING)");
        }

        // #2573 (Order ruling): the location is the request's, else the register's float location (the
        // register's home per accounting), else the terminal's previous session's.
        Optional<ExtAccountingRegisterFloat> floatCopy = registerFloatRepository.findByRegisterId(command.terminalId());
        UUID locationId = command.locationId() != null
                ? command.locationId()
                : floatCopy
                        .map(ExtAccountingRegisterFloat::getLocationId)
                        .orElseGet(() -> previousLocation(command.terminalId()));
        // ADR-0061 §3 (#1872): the session is opened *at* the resolved location, so the scope check
        // runs here — after the defaults are applied — rather than in the controller, or a scoped
        // caller could open a drawer at another shop by simply omitting locationId. A session with no
        // location at all answers "" which a scoped caller cannot cover (fail closed); an unscoped or
        // pre-rollout caller is unchanged.
        SecurityContextHelper.locationScope()
                .require(OrderPermissions.ORDER_SESSION_OPEN, locationId == null ? "" : locationId.toString());
        if (floatCopy.isPresent() && !floatCopy.get().getLocationId().equals(locationId)) {
            // #2573: no register moves during an open session, and a drawer never opens away from the
            // location its float is held at. The float's location is named only when the caller may see it.
            UUID floatLocation = floatCopy.get().getLocationId();
            boolean floatLocationVisible = SecurityContextHelper.locationScope()
                    .covers(OrderPermissions.ORDER_SESSION_OPEN, floatLocation.toString());
            throw new RegisterFloatLocationMismatchException(
                    command.terminalId(), locationId, floatLocationVisible ? floatLocation : null);
        }
        // ADR-0067: the drawer's currency for its whole life, whatever the configuration later says.
        String drawerCurrency = functionalCurrency.code();
        if (floatCopy.isPresent() && !drawerCurrency.equals(floatCopy.get().getCurrencyCode())) {
            // #2577 (PC-9): a float is never compared or counted across currencies; like a float held at
            // another location, it does not open this drawer.
            throw new CurrencyNotSupportedException("Register " + command.terminalId() + " has its configured float"
                    + " in " + floatCopy.get().getCurrencyCode() + "; this drawer counts " + drawerCurrency
                    + ", so it does not open until accounting states the float in " + drawerCurrency);
        }

        // AW16: the register's configured float, never a request value or the previous count; zero when
        // there is none, and never negative cash in a drawer.
        BigDecimal openingFloat = openingFloat(command.terminalId(), floatCopy);
        Instant now = Instant.now(clock);
        RegisterSession session = RegisterSession.builder()
                .terminalId(command.terminalId())
                .locationId(locationId)
                .openedByClerkId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .status(RegisterSessionStatus.OPEN)
                .openingFloat(openingFloat)
                .currencyCode(drawerCurrency)
                .openedAt(now)
                .build();
        RegisterSession saved = registerSessionRepository.save(session);
        // CAP:550 S40 (#2578): the opened fact rides this transaction's outbox, so it exists exactly when the
        // session does; every refusal above throws before it is queued.
        domainEventPublisher.publishRegisterSessionOpened(saved);
        return toSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<RegisterSessionSummary> currentSessionForTerminal(@NonNull String terminalId) {
        Optional<RegisterSession> open =
                registerSessionRepository.findFirstByTerminalIdAndStatus(terminalId, RegisterSessionStatus.OPEN);
        if (open.isPresent()) {
            return open.map(this::toSummary);
        }
        return registerSessionRepository
                .findFirstByTerminalIdAndStatus(terminalId, RegisterSessionStatus.CLOSING)
                .map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull RegisterSessionSummary getSession(@NonNull UUID sessionId) {
        return toSummary(require(sessionId));
    }

    @Override
    @Transactional
    public @NonNull CashMovementResult recordCashMovement(@NonNull CashMovementCommand command) {
        // CAP:550 S32d item 6, the binding call order: validate, replay, local checks, the pos-tax call (outside the
        // row lock, before the token is used), then lock and record. A refusal before the record leaves the token
        // unspent, so the register may resend the same requestId with a corrected payload.
        ValidMovement shaped = validate(command);
        // ADR-0061: 404 first, then the caller's reach at the drawer's location — a scoped cashier
        // cannot record (or replay) a movement on another shop's drawer.
        RegisterSession drawer = require(command.sessionId());
        requireInScope(drawer, OrderPermissions.ORDER_SESSION_CASH_MOVEMENT);
        // ADR-0067: the drawer's own currency, stamped when it opened (never the live configuration).
        if (!drawer.getCurrencyCode().equals(shaped.currencyCode())) {
            throw new CurrencyNotSupportedException("This drawer's cash is counted in " + drawer.getCurrencyCode()
                    + "; " + shaped.currencyCode() + " is not supported");
        }
        // ADR-0067 PC-6: every amount fits the drawer currency's minor unit, refused in one 422, never rounded.
        ValidMovement movement = atCurrencyExponent(shaped, drawer.getCurrencyCode());

        // Idempotent replay first (§8.2): a retry returns the first result, even after its approval
        // token was used, and is never re-checked against today's policy or pos-tax.
        Optional<CashMovementResult> replay = replay(movement);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Local checks, before pos-tax is asked and before any lock.
        requireOpen(drawer);
        CashMovementReason reason = movement.reason();
        SessionPolicyType type = reason.policyType();
        SessionPolicyView policy = sessionPolicyService.current();
        if (!policy.allowed(type)) {
            // Never retroactive: recorded movements of the type stand and are carried on the close fact.
            throw refused(Refusal.TYPE_NOT_ALLOWED, reason + " movements are switched off in the drawer policy");
        }
        Instant occurredAt = Instant.now(clock);
        LocalDate movementDate = DrawerStatedTax.movementDate(occurredAt);
        if (reason == CashMovementReason.PETTY_EXPENSE) {
            ExtAccountingPettyExpenseCategory category =
                    categoryRepository.findByCode(movement.categoryCode()).orElse(null);
            if (category == null || !category.isActive()) {
                throw refused(
                        Refusal.CATEGORY_UNKNOWN,
                        "Petty-expense category " + movement.categoryCode() + " is not an active category");
            }
            requireOffered(movement, drawerStatedTax.offeredRegimes(drawer, category, movementDate));
            requireWithinTotal(movement);
        }

        // The plausibility call: only with stated tax, outside the session's row lock, before approvalService.use.
        DrawerStatedTax.TaxCheck taxCheck = movement.statedTaxes().isEmpty()
                ? null
                : drawerStatedTax.check(
                        drawer,
                        movementDate,
                        movement.amount(),
                        movement.statedTaxes(),
                        movement.supplierRegistrationNumber());

        RegisterSession session = registerSessionRepository
                .findByIdForUpdate(command.sessionId())
                .orElseThrow(() -> new RegisterSessionNotFoundException(command.sessionId()));
        // Re-check under the session's lock: a concurrent request with the same id recorded it while
        // this one waited.
        replay = replay(movement);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireOpen(session);

        List<CashMovement> recorded =
                cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(session.getSessionId());
        if (reason.isFloatChange()) {
            requireRecordedFloatChange(session, recorded, reason, movement.amount());
        }

        CashMovementApproval approval = null;
        boolean limitsApply = limitsApply(policy, session);
        if (needsManager(policy, type, runningTotal(recorded, reason).add(movement.amount()), limitsApply)
                || movement.approvalToken() != null) {
            if (movement.approvalToken() == null) {
                throw refused(
                        Refusal.APPROVAL_REQUIRED,
                        type == SessionPolicyType.FLOAT_CHANGE
                                ? "A float change needs a manager's approval"
                                : "The session's " + reason + " total would exceed the cashier limit; a manager's"
                                        + " approval is required");
            }
            try {
                approval = approvalService.use(
                        movement.approvalToken(),
                        session.getSessionId(),
                        reason,
                        movement.amount(),
                        session.getCurrencyCode(),
                        movement.categoryCode(),
                        movement.vendorId());
            } catch (CashMovementRefusedException e) {
                count(e.refusal());
                throw e;
            }
        }

        CashMovement saved;
        try {
            saved = cashMovementRepository.saveAndFlush(CashMovement.builder()
                    .sessionId(session.getSessionId())
                    .requestId(movement.requestId())
                    .reasonCode(reason)
                    .movementType(reason.direction())
                    .amount(movement.amount())
                    .currencyCode(session.getCurrencyCode())
                    .categoryCode(movement.categoryCode())
                    .vendorId(movement.vendorId())
                    .bagNumber(movement.bagNumber())
                    .receiptReference(movement.receiptReference())
                    .note(movement.note())
                    .clerkId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .clerkUserId(currentUserId())
                    .approvedBy(approval == null ? null : approval.getApproverUserId())
                    .approvalId(approval == null ? null : approval.getApprovalId())
                    .occurredAt(occurredAt)
                    .supplierName(movement.supplierName())
                    .supplierRegistrationNumber(movement.supplierRegistrationNumber())
                    .taxPlausibility(taxCheck == null ? null : taxCheck.taxPlausibility())
                    .supplierRegistrationRequired(taxCheck == null ? null : taxCheck.supplierRegistrationRequired())
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, REQUEST_ID_CONSTRAINT)) {
                throw e;
            }
            // The same requestId was recorded on another session meanwhile: not this request's movement.
            throw new CashMovementIdempotencyConflictException(
                    "requestId " + movement.requestId() + " was already used for another cash movement");
        }
        drawerStatedTax.record(saved.getMovementId(), movement.statedTaxes());
        if (approval != null) {
            approval.setUsedByMovementId(saved.getMovementId());
        }
        return new CashMovementResult(toMovementSummary(saved, movement.statedTaxesView()), false);
    }

    private static void requireOpen(RegisterSession session) {
        if (session.getStatus() != RegisterSessionStatus.OPEN) {
            throw new RegisterSessionConflictException("Cash movements require an OPEN session; session "
                    + session.getSessionId() + " is " + session.getStatus());
        }
    }

    /** Amendment A1: every stated regime is one the session offers for the category today, else one 422. */
    private static void requireOffered(ValidMovement movement, List<String> offered) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        List<StatedAmount> taxes = movement.statedTaxes();
        for (int i = 0; i < taxes.size(); i++) {
            if (!offered.contains(taxes.get(i).regime())) {
                errors.add(new ApiError.FieldError(
                        STATED_TAXES + "[" + i + "].regime", "is not offered for this category here and today"));
            }
        }
        if (!errors.isEmpty()) {
            throw new CashMovementTaxRefusedException(
                    CashMovementTaxRefusedException.Code.TAX_REGIME_NOT_OFFERED,
                    "A stated tax regime is not offered for this category, location, currency and date",
                    errors);
        }
    }

    /**
     * The local arithmetic bound (S32b's check (a), the same code and keys): each stated amount is below the receipt
     * total, and so is their sum, checked only when more than one amount is stated.
     */
    private static void requireWithinTotal(ValidMovement movement) {
        List<StatedAmount> taxes = movement.statedTaxes();
        List<ApiError.FieldError> errors = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < taxes.size(); i++) {
            BigDecimal amount = taxes.get(i).amount();
            sum = sum.add(amount);
            if (amount.compareTo(movement.amount()) >= 0) {
                errors.add(new ApiError.FieldError(
                        STATED_TAXES + "[" + i + "].amount", "must be below the movement's amount"));
            }
        }
        if (taxes.size() > 1 && sum.compareTo(movement.amount()) >= 0) {
            errors.add(new ApiError.FieldError(
                    STATED_TAXES, "the stated amounts must sum to below the movement's amount"));
        }
        if (!errors.isEmpty()) {
            throw new CashMovementTaxRefusedException(
                    CashMovementTaxRefusedException.Code.TAX_AMOUNT_IMPLAUSIBLE,
                    "The stated tax is not plausible for the movement's amount",
                    errors);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull CashMovementOptions cashMovementOptions(@NonNull UUID sessionId) {
        RegisterSession session = require(sessionId);
        requireInScope(session, OrderPermissions.ORDER_SESSION_CASH_MOVEMENT);
        SessionPolicyView policy = sessionPolicyService.current();
        List<CashMovement> recorded = cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId);
        boolean open = session.getStatus() == RegisterSessionStatus.OPEN;
        boolean limitsApply = limitsApply(policy, session);
        List<CashMovementOptions.ReasonOption> reasons = new ArrayList<>();
        for (CashMovementReason reason : CashMovementReason.values()) {
            SessionPolicyType type = reason.policyType();
            BigDecimal limit = policy.cashierLimit(type);
            reasons.add(new CashMovementOptions.ReasonOption(
                    reason.name(),
                    reason.direction().name(),
                    open && policy.allowed(type),
                    limitsApply ? limit : null,
                    scale(runningTotal(recorded, reason)),
                    policy.alwaysNeedsManager(type) || (!limitsApply && limit != null),
                    requiredFields(reason)));
        }
        // CAP:550 S32d: offered regimes come from local replicas only; the evidence rule is asked of pos-tax only
        // when some regime is registered, and a pos-tax that does not answer gives null, never a failed read.
        LocalDate today = DrawerStatedTax.movementDate(Instant.now(clock));
        List<String> registered = drawerStatedTax.registeredRegimes(session, today);
        List<CashMovementOptions.CategoryOption> categories =
                categoryRepository.findByStatusOrderByCodeAsc(ExtAccountingPettyExpenseCategory.ACTIVE).stream()
                        .map(category -> new CashMovementOptions.CategoryOption(
                                category.getCode(),
                                category.getLabel(),
                                category.getExamples(),
                                category.isTaxRecoverable() ? registered : List.of()))
                        .toList();
        CashMovementOptions.EvidenceRule evidenceRule =
                registered.isEmpty() ? null : drawerStatedTax.evidenceRule(session, today);
        return new CashMovementOptions(sessionId, session.getCurrencyCode(), reasons, categories, evidenceRule);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CashMovementSummary> listCashMovements(@NonNull UUID sessionId) {
        RegisterSession session = require(sessionId);
        return summaries(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId), session);
    }

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary beginClose(@NonNull UUID sessionId, @NonNull BigDecimal countedCash) {
        RegisterSession session = require(sessionId);
        if (session.getStatus() == RegisterSessionStatus.CLOSED) {
            throw new RegisterSessionConflictException("Session " + sessionId + " is already closed");
        }
        if (salesOrderRepository.existsBySessionIdAndStatus(sessionId, SalesOrderStatus.PENDING_PAYMENT)) {
            throw new SessionCloseBlockedException(sessionId);
        }
        session.setCountedCash(scale(countedCash));
        session.setStatus(RegisterSessionStatus.CLOSING);
        session.setClosingStartedAt(Instant.now(clock));
        return toSummary(registerSessionRepository.save(session));
    }

    @Override
    @Transactional
    public @NonNull RegisterSessionSummary confirmClose(@NonNull UUID sessionId) {
        RegisterSession session = require(sessionId);
        if (session.getStatus() != RegisterSessionStatus.CLOSING) {
            throw new RegisterSessionConflictException(
                    "Confirm-close requires a session in CLOSING (call begin-close first); session " + sessionId
                            + " is " + session.getStatus());
        }
        // Re-check R6.2: an order could have re-entered PENDING_PAYMENT since begin-close.
        if (salesOrderRepository.existsBySessionIdAndStatus(sessionId, SalesOrderStatus.PENDING_PAYMENT)) {
            throw new SessionCloseBlockedException(sessionId);
        }
        BigDecimal counted = session.getCountedCash() != null ? session.getCountedCash() : ZERO;
        BigDecimal cashMovementTotal = cashMovementTotal(sessionId);
        BigDecimal cashSettlements = cashSettlements(sessionId);
        BigDecimal theoretical =
                scale(session.getOpeningFloat().add(cashSettlements).add(cashMovementTotal));
        BigDecimal overShort = scale(counted.subtract(theoretical));

        SessionPolicyView policy = sessionPolicyService.current();
        // A tolerance stated in another currency than the drawer's cannot be compared: any difference
        // then needs the variance approval (fail closed, ADR-0067 PC-9).
        BigDecimal tolerance = limitsApply(policy, session) ? policy.overShortTolerance() : ZERO;
        if (overShort.abs().compareTo(tolerance) > 0) {
            if (!SecurityContextHelper.hasAuthority(OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE)) {
                throw new AccessDeniedException("Register session over/short of " + overShort
                        + " exceeds the drawer policy's over/short tolerance of " + tolerance
                        + "; permission '" + OrderPermissions.ORDER_SESSION_APPROVE_VARIANCE + "' is required");
            }
            session.setVarianceApproved(true);
            session.setVarianceApprovedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        }

        Instant now = Instant.now(clock);
        session.setTheoreticalCash(theoretical);
        session.setOverShort(overShort);
        session.setStatus(RegisterSessionStatus.CLOSED);
        session.setClosedByClerkId(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        session.setClosedAt(now);
        RegisterSession saved = registerSessionRepository.save(session);

        List<RegisterSessionClosedV1.TenderTotal> tenderTotals = tenderTotals(sessionId).entrySet().stream()
                .map(e -> new RegisterSessionClosedV1.TenderTotal(e.getKey(), e.getValue()))
                .toList();
        domainEventPublisher.publishRegisterSessionClosed(
                saved,
                new RegisterSessionClosedV1(
                        saved.getSessionId(),
                        saved.getTerminalId(),
                        saved.getLocationId(),
                        saved.getOpenedByClerkId(),
                        saved.getClosedByClerkId(),
                        saved.getOpeningFloat(),
                        counted,
                        theoretical,
                        overShort,
                        saved.isVarianceApproved(),
                        saved.getCurrencyCode(),
                        tenderTotals,
                        cashMovementTotal,
                        saved.getOpenedAt(),
                        now,
                        factMovements(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId), saved)));
        return toSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SessionReport xReport(@NonNull UUID sessionId) {
        return buildReport(require(sessionId), "X");
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SessionReport zReport(@NonNull UUID sessionId) {
        return buildReport(require(sessionId), "Z");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SessionReport buildReport(RegisterSession session, String reportType) {
        UUID sessionId = session.getSessionId();
        BigDecimal cashMovementTotal = cashMovementTotal(sessionId);
        BigDecimal cashSettlements = cashSettlements(sessionId);
        BigDecimal theoretical =
                scale(session.getOpeningFloat().add(cashSettlements).add(cashMovementTotal));
        BigDecimal counted = session.getCountedCash();
        BigDecimal overShort = counted != null ? scale(counted.subtract(theoretical)) : null;
        List<SessionReport.TenderTotal> tenderTotals = tenderTotals(sessionId).entrySet().stream()
                .map(e -> new SessionReport.TenderTotal(e.getKey(), e.getValue()))
                .toList();
        List<CashMovementSummary> movements =
                summaries(cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId), session);
        List<SalesOrder> sessionOrders = salesOrderRepository.findBySessionId(sessionId);
        long orderCount = sessionOrders.size();
        return new SessionReport(
                sessionId,
                session.getTerminalId(),
                session.getLocationId(),
                session.getStatus().name(),
                reportType,
                session.getOpeningFloat(),
                tenderTotals,
                cashSettlements,
                cashMovementTotal,
                theoretical,
                counted,
                overShort,
                orderCount,
                walkInByClerk(sessionOrders),
                movements,
                session.getOpenedAt(),
                Instant.now(clock));
    }

    /**
     * Walk-in share per cashier (CAP:550 S8, spec §4.4 item 2): over the session's orders that left
     * DRAFT, how many each clerk sold to the tenant's CASH house account and for how much. A clerk
     * with no walk-in orders is still listed, at zero, so the share reads against their whole
     * count. Keyed on the order's {@code clerkId}, which cart creation takes from the request.
     */
    private List<SessionReport.ClerkWalkInShare> walkInByClerk(List<SalesOrder> sessionOrders) {
        List<SalesOrder> leftDraft = sessionOrders.stream()
                .filter(order -> order.getStatus() != SalesOrderStatus.DRAFT)
                .toList();
        Set<UUID> walkInCustomers = houseAccounts.cashSaleIdsAmong(
                leftDraft.stream().map(SalesOrder::getCustomerId).toList());
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (SalesOrder order : leftDraft) {
            long[] count = counts.computeIfAbsent(order.getClerkId(), _ -> new long[2]);
            count[0]++;
            totals.putIfAbsent(order.getClerkId(), ZERO);
            if (order.getCustomerId() != null && walkInCustomers.contains(order.getCustomerId())) {
                count[1]++;
                totals.merge(order.getClerkId(), order.getGrandTotal(), BigDecimal::add);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SessionReport.ClerkWalkInShare(
                        entry.getKey(), entry.getValue()[0], entry.getValue()[1], scale(totals.get(entry.getKey()))))
                .toList();
    }

    private RegisterSession require(UUID sessionId) {
        return registerSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new RegisterSessionNotFoundException(sessionId));
    }

    /**
     * The opening float (orchestrator decision l7): the register's configured float, zero when it has
     * none, and floored at zero — a negative accounting float (possible after a reversal) opens the drawer
     * at zero with a warning and {@value #OPENING_FLOAT_ADJUSTED}{@code {reason=negative}}; a drawer never
     * holds negative cash. The caller has already refused a float held at another location (#2573) or in
     * another currency than the drawer's (#2577).
     */
    private BigDecimal openingFloat(String terminalId, Optional<ExtAccountingRegisterFloat> floatCopy) {
        BigDecimal configured = floatCopy.map(copy -> scale(copy.getAmount())).orElse(ZERO);
        if (configured.signum() < 0) {
            log.warn("Register {} has a negative configured float; the drawer opens at zero", terminalId);
            countFloatAdjusted("negative");
            return ZERO;
        }
        return configured;
    }

    private void countFloatAdjusted(String reason) {
        if (meterRegistry != null) {
            Counter.builder(OPENING_FLOAT_ADJUSTED)
                    .description("Configured floats not used as they stand for a drawer")
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** The caller's reach at the session's location for {@code permission} (ADR-0061; 403 otherwise). */
    private static void requireInScope(RegisterSession session, String permission) {
        UUID location = session.getLocationId();
        SecurityContextHelper.locationScope().require(permission, location == null ? "" : location.toString());
    }

    private static @Nullable UUID currentUserId() {
        try {
            return SecurityContextHelper.getCurrentUserIdAsUuid().orElse(null);
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** Whether {@code e} is the violation of the named constraint (cause chain, Postgres message). */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A float movement must close the gap between the configured float and the float now in the
     * drawer (opening float ± earlier float movements) exactly, in its direction (§4.6 "Float"). A float
     * held at another location, or in another currency than the drawer's (#2577: 422 {@code
     * CURRENCY_NOT_SUPPORTED}), is never compared.
     */
    private void requireRecordedFloatChange(
            RegisterSession session, List<CashMovement> recorded, CashMovementReason reason, BigDecimal amount) {
        BigDecimal drawerFloat = scale(session.getOpeningFloat())
                .add(runningTotal(recorded, CashMovementReason.FLOAT_INCREASE))
                .subtract(runningTotal(recorded, CashMovementReason.FLOAT_DECREASE));
        Optional<ExtAccountingRegisterFloat> floatCopy =
                registerFloatRepository.findByRegisterId(session.getTerminalId());
        if (floatCopy.isPresent() && !floatCopy.get().getLocationId().equals(session.getLocationId())) {
            // #2573: the target is never taken as zero for a float held elsewhere — that would let a cashier
            // take the whole float out of the drawer.
            throw refused(
                    Refusal.FLOAT_CHANGE_NOT_RECORDED,
                    "The register's configured float is held at another location than this drawer's; no float"
                            + " change can be recorded on it here");
        }
        if (floatCopy.isPresent()
                && !session.getCurrencyCode().equals(floatCopy.get().getCurrencyCode())) {
            // #2577 (ADR-0067 PC-9): a drawer opened before the copy existed keeps its own stamp; a float in
            // another currency is never compared with the drawer's cash.
            throw new CurrencyNotSupportedException("The register's configured float is in "
                    + floatCopy.get().getCurrencyCode() + "; this drawer counts " + session.getCurrencyCode()
                    + ", so no float change can be recorded on it");
        }
        BigDecimal target = floatCopy.map(copy -> scale(copy.getAmount())).orElse(ZERO);
        BigDecimal gap = target.subtract(drawerFloat);
        BigDecimal expected = reason == CashMovementReason.FLOAT_INCREASE ? gap : gap.negate();
        // l7: never move a drawer toward a negative float.
        if (target.signum() < 0 || expected.signum() <= 0 || expected.compareTo(amount) != 0) {
            throw refused(
                    Refusal.FLOAT_CHANGE_NOT_RECORDED,
                    "No recorded float change matches a " + reason + " of "
                            + amount.stripTrailingZeros().toPlainString());
        }
    }

    /** Σ of the session's movements of {@code reason} (pre-S16 movements carry none). */
    private static BigDecimal runningTotal(List<CashMovement> recorded, CashMovementReason reason) {
        return recorded.stream()
                .filter(m -> m.getReasonCode() == reason)
                .map(CashMovement::getAmount)
                .reduce(ZERO, BigDecimal::add);
    }

    /**
     * Above the cashier limit on the running total, or a type that always needs a manager. A limit stated
     * in another currency than the drawer's cannot be compared, so it then always needs a manager (fail
     * closed, ADR-0067 PC-9).
     */
    private static boolean needsManager(
            SessionPolicyView policy, SessionPolicyType type, BigDecimal runningTotal, boolean limitsApply) {
        if (policy.alwaysNeedsManager(type)) {
            return true;
        }
        BigDecimal limit = policy.cashierLimit(type);
        return limit != null && (!limitsApply || runningTotal.compareTo(limit) > 0);
    }

    /**
     * Whether the drawer policy's amounts are in the drawer's currency. They differ only when the
     * functional currency was reconfigured between the policy's write (or, with no policy, now) and the
     * drawer's open.
     */
    private static boolean limitsApply(SessionPolicyView policy, RegisterSession session) {
        return policy.currencyCode().equals(session.getCurrencyCode());
    }

    private static List<String> requiredFields(CashMovementReason reason) {
        return switch (reason) {
            case PETTY_EXPENSE -> List.of("categoryCode", "receiptReference", "note");
            case VENDOR_COD -> List.of("vendorId");
            case BANK_DROP -> List.of("bagNumber");
            case FLOAT_INCREASE, FLOAT_DECREASE -> List.of();
        };
    }

    /**
     * A validated movement request. {@link #toString()} never prints the approval token, the supplier's name or the
     * supplier's number.
     */
    private record ValidMovement(
            UUID sessionId,
            UUID requestId,
            CashMovementReason reason,
            BigDecimal amount,
            String currencyCode,
            @Nullable String categoryCode,
            @Nullable UUID vendorId,
            @Nullable String bagNumber,
            @Nullable String receiptReference,
            @Nullable String note,
            @Nullable String approvalToken,
            @Nullable String supplierName,
            List<StatedAmount> statedTaxes,
            @Nullable String supplierRegistrationNumber) {

        /**
         * Whether {@code m}, with its recorded stated taxes, records this same request (the approval token is not part
         * of the payload). Stated taxes compare as a set by regime, amounts by {@code compareTo}; the number in its
         * normalised form.
         */
        boolean samePayloadAs(CashMovement m, List<CashMovementStatedTax> recordedTaxes) {
            return sessionId.equals(m.getSessionId())
                    && reason == m.getReasonCode()
                    && amount.compareTo(m.getAmount()) == 0
                    && currencyCode.equals(m.getCurrencyCode())
                    && Objects.equals(categoryCode, m.getCategoryCode())
                    && Objects.equals(vendorId, m.getVendorId())
                    && Objects.equals(bagNumber, m.getBagNumber())
                    && Objects.equals(receiptReference, m.getReceiptReference())
                    && Objects.equals(note, m.getNote())
                    && Objects.equals(supplierName, m.getSupplierName())
                    && Objects.equals(supplierRegistrationNumber, m.getSupplierRegistrationNumber())
                    && sameStatedTaxes(recordedTaxes);
        }

        private boolean sameStatedTaxes(List<CashMovementStatedTax> recordedTaxes) {
            if (statedTaxes.size() != recordedTaxes.size()) {
                return false;
            }
            Map<String, BigDecimal> recordedByRegime = new LinkedHashMap<>();
            recordedTaxes.forEach(tax -> recordedByRegime.put(tax.regime(), tax.amount()));
            return statedTaxes.stream()
                    .allMatch(tax -> recordedByRegime.containsKey(tax.regime())
                            && recordedByRegime.get(tax.regime()).compareTo(tax.amount()) == 0);
        }

        /** The stated taxes as a read shows them. */
        List<CashMovementStatedTax> statedTaxesView() {
            return statedTaxes.stream()
                    .sorted(java.util.Comparator.comparing(StatedAmount::regime))
                    .map(tax -> new CashMovementStatedTax(tax.regime(), tax.amount()))
                    .toList();
        }

        @Override
        public String toString() {
            return "ValidMovement[sessionId=" + sessionId + ", requestId=" + requestId + ", reason=" + reason
                    + ", amount=" + amount + ", currencyCode=" + currencyCode + ", categoryCode=" + categoryCode
                    + ", vendorId=" + vendorId + ", bagNumber=" + bagNumber + ", receiptReference=" + receiptReference
                    + ", approvalToken=" + (approvalToken == null ? "absent" : "present")
                    + ", supplierNameProvided=" + (supplierName != null) + ", statedTaxes=" + statedTaxes
                    + ", supplierRegistrationNumberProvided=" + (supplierRegistrationNumber != null) + "]";
        }
    }

    private static ValidMovement validate(CashMovementCommand command) {
        if (command.requestId() == null) {
            throw new RegisterSessionRequestValidationException("requestId is required");
        }
        if (command.requestId().version() != 7) {
            throw new RegisterSessionRequestValidationException("requestId must be a UUIDv7");
        }
        CashMovementReason reason = CashMovementReason.parse(command.reason())
                .orElseThrow(() -> new RegisterSessionRequestValidationException(
                        "reason must be one of PETTY_EXPENSE, VENDOR_COD, BANK_DROP, FLOAT_INCREASE, FLOAT_DECREASE"));
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new RegisterSessionRequestValidationException("Cash movement amount must be positive");
        }
        if (!FunctionalCurrency.isIsoCode(command.currencyCode())) {
            throw new RegisterSessionRequestValidationException(
                    "currencyCode is required and must be an ISO 4217 code");
        }
        String categoryCode = trimmed(command.categoryCode(), "categoryCode", MAX_CODE_LENGTH);
        String bagNumber = trimmed(command.bagNumber(), "bagNumber", MAX_CODE_LENGTH);
        String receiptReference = trimmed(command.receiptReference(), "receiptReference", MAX_RECEIPT_REFERENCE_LENGTH);
        String note = trimmed(command.note(), "note", MAX_NOTE_LENGTH);
        switch (reason) {
            case PETTY_EXPENSE -> {
                require(categoryCode, "categoryCode", reason);
                require(receiptReference, "receiptReference", reason);
                require(note, "note", reason);
            }
            case VENDOR_COD -> {
                if (command.vendorId() == null) {
                    throw new RegisterSessionRequestValidationException("vendorId is required for VENDOR_COD");
                }
            }
            case BANK_DROP -> require(bagNumber, "bagNumber", reason);
            case FLOAT_INCREASE, FLOAT_DECREASE -> {
                // No reason field: the amount must match a recorded float change.
            }
        }
        StatedTaxShape statedTax = statedTaxShape(command, reason);
        String token =
                command.approvalToken() == null || command.approvalToken().isBlank()
                        ? null
                        : command.approvalToken().trim();
        return new ValidMovement(
                command.sessionId(),
                command.requestId(),
                reason,
                command.amount(),
                command.currencyCode().trim(),
                reason == CashMovementReason.PETTY_EXPENSE ? categoryCode : null,
                reason == CashMovementReason.VENDOR_COD ? command.vendorId() : null,
                reason == CashMovementReason.BANK_DROP ? bagNumber : null,
                reason == CashMovementReason.PETTY_EXPENSE ? receiptReference : null,
                note,
                token,
                statedTax.supplierName(),
                statedTax.amounts(),
                statedTax.registrationNumber());
    }

    /** The stated-tax fields after the shape rules (S32d item 5, amendment A2). */
    private record StatedTaxShape(
            @Nullable String supplierName,
            List<StatedAmount> amounts,
            @Nullable String registrationNumber) {}

    /**
     * The shape rules of the three stated-tax fields, every failure in one 400 {@code
     * REGISTER_SESSION_INVALID_ARGUMENT} with {@code fieldErrors[]} (ADR-0017 §3). The messages are value-free: the
     * handler logs the exception at WARN.
     */
    private static StatedTaxShape statedTaxShape(CashMovementCommand command, CashMovementReason reason) {
        List<CashMovementCommand.StatedTax> sent = command.statedTaxes() == null ? List.of() : command.statedTaxes();
        boolean nameSent =
                command.supplierName() != null && !command.supplierName().isBlank();
        boolean numberSent = command.supplierRegistrationNumber() != null;
        if (reason != CashMovementReason.PETTY_EXPENSE) {
            List<ApiError.FieldError> errors = new ArrayList<>();
            if (!sent.isEmpty()) {
                errors.add(new ApiError.FieldError(STATED_TAXES, "is accepted on PETTY_EXPENSE only"));
            }
            if (nameSent) {
                errors.add(new ApiError.FieldError(SUPPLIER_NAME, "is accepted on PETTY_EXPENSE only"));
            }
            if (numberSent) {
                errors.add(new ApiError.FieldError(SUPPLIER_REGISTRATION_NUMBER, "is accepted on PETTY_EXPENSE only"));
            }
            invalidIfAny(errors);
            return new StatedTaxShape(null, List.of(), null);
        }
        List<ApiError.FieldError> errors = new ArrayList<>();
        List<StatedAmount> amounts = new ArrayList<>();
        java.util.Set<String> regimes = new java.util.HashSet<>();
        for (int i = 0; i < sent.size(); i++) {
            CashMovementCommand.StatedTax tax = sent.get(i);
            String regime = tax == null ? null : tax.regime();
            BigDecimal amount = tax == null ? null : tax.amount();
            String key = STATED_TAXES + "[" + i + "]";
            boolean regimeValid = regime != null && REGIME_CODE.matcher(regime).matches();
            if (!regimeValid) {
                errors.add(new ApiError.FieldError(
                        key + ".regime", "is required: 1 to 32 upper-case letters, digits or underscores"));
            } else if (!regimes.add(regime)) {
                errors.add(new ApiError.FieldError(key + ".regime", "is stated more than once"));
            }
            if (amount == null || amount.signum() <= 0) {
                errors.add(new ApiError.FieldError(key + ".amount", "is required and must be positive"));
            }
            if (regimeValid && amount != null) {
                amounts.add(new StatedAmount(regime, amount));
            }
        }
        String supplierName = nameSent ? command.supplierName().trim() : null;
        if (supplierName != null && supplierName.length() > MAX_SUPPLIER_NAME_LENGTH) {
            errors.add(new ApiError.FieldError(
                    SUPPLIER_NAME, "must be at most " + MAX_SUPPLIER_NAME_LENGTH + " characters"));
        }
        if (!sent.isEmpty() && supplierName == null) {
            errors.add(new ApiError.FieldError(SUPPLIER_NAME, "is required when a tax amount is stated"));
        }
        String registrationNumber = null;
        if (numberSent) {
            if (sent.isEmpty()) {
                errors.add(new ApiError.FieldError(
                        SUPPLIER_REGISTRATION_NUMBER, "is accepted only with at least one stated tax amount"));
            } else {
                registrationNumber = SupplierRegistrationNumbers.locallyAccepted(command.supplierRegistrationNumber())
                        .orElse(null);
                if (registrationNumber == null) {
                    errors.add(new ApiError.FieldError(
                            SUPPLIER_REGISTRATION_NUMBER,
                            "must be 1 to " + SupplierRegistrationNumbers.MAX_LENGTH
                                    + " characters without whitespace or control characters"));
                }
            }
        }
        invalidIfAny(errors);
        return new StatedTaxShape(supplierName, List.copyOf(amounts), registrationNumber);
    }

    private static void invalidIfAny(List<ApiError.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new RegisterSessionRequestValidationException(
                    "The cash movement's stated-tax fields are malformed", errors);
        }
    }

    /**
     * Step 1's precision check (ADR-0067 PC-6; S32d revisions (2) and (4)): the movement's amount and each stated
     * amount fit the drawer currency's minor unit, every offending one named in one 422 {@code
     * AMOUNT_PRECISION_EXCEEDS_CURRENCY}. Nothing is rounded: a representable amount is only stated at the exponent
     * ({@code 40.000} CAD is {@code 40.00}).
     */
    private static ValidMovement atCurrencyExponent(ValidMovement movement, String currencyCode) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (!DrawerAmounts.representable(movement.amount(), currencyCode)) {
            errors.add(new ApiError.FieldError("amount", "has more decimals than " + currencyCode + " allows"));
        }
        List<StatedAmount> taxes = movement.statedTaxes();
        for (int i = 0; i < taxes.size(); i++) {
            if (!DrawerAmounts.representable(taxes.get(i).amount(), currencyCode)) {
                errors.add(new ApiError.FieldError(
                        STATED_TAXES + "[" + i + "].amount", "has more decimals than " + currencyCode + " allows"));
            }
        }
        if (!errors.isEmpty()) {
            throw new CashMovementTaxRefusedException(
                    CashMovementTaxRefusedException.Code.AMOUNT_PRECISION_EXCEEDS_CURRENCY,
                    "An amount has more decimals than the drawer's currency allows; nothing is rounded",
                    errors);
        }
        return new ValidMovement(
                movement.sessionId(),
                movement.requestId(),
                movement.reason(),
                DrawerAmounts.atExponent(movement.amount(), currencyCode),
                movement.currencyCode(),
                movement.categoryCode(),
                movement.vendorId(),
                movement.bagNumber(),
                movement.receiptReference(),
                movement.note(),
                movement.approvalToken(),
                movement.supplierName(),
                taxes.stream()
                        .map(tax ->
                                new StatedAmount(tax.regime(), DrawerAmounts.atExponent(tax.amount(), currencyCode)))
                        .toList(),
                movement.supplierRegistrationNumber());
    }

    private static void require(@Nullable String value, String field, CashMovementReason reason) {
        if (value == null) {
            throw new RegisterSessionRequestValidationException(field + " is required for " + reason);
        }
    }

    private static @Nullable String trimmed(@Nullable String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new RegisterSessionRequestValidationException(
                    field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    /** The first result of the same request, or a conflict when the id was used for another payload. */
    private Optional<CashMovementResult> replay(ValidMovement movement) {
        return cashMovementRepository.findByRequestId(movement.requestId()).map(existing -> {
            List<CashMovementStatedTax> recordedTaxes = drawerStatedTax
                    .statedTaxes(List.of(existing.getMovementId()), movement.currencyCode())
                    .getOrDefault(existing.getMovementId(), List.of());
            if (!movement.samePayloadAs(existing, recordedTaxes)) {
                // Value-free (S32d item 7): the handler may log this message, so it names no field's value.
                throw new CashMovementIdempotencyConflictException(
                        "requestId " + movement.requestId() + " was already used for a different cash movement");
            }
            return new CashMovementResult(toMovementSummary(existing, recordedTaxes), true);
        });
    }

    private CashMovementRefusedException refused(Refusal refusal, String message) {
        count(refusal);
        return new CashMovementRefusedException(refusal, message);
    }

    private void count(Refusal refusal) {
        if (meterRegistry != null) {
            Counter.builder(REFUSED_COUNTER)
                    .description("Drawer cash movements refused by a drawer rule")
                    .tag("code", refusal.code())
                    .register(meterRegistry)
                    .increment();
        }
    }

    private UUID previousLocation(String terminalId) {
        return registerSessionRepository
                .findFirstByTerminalIdOrderByOpenedAtDesc(terminalId)
                .map(RegisterSession::getLocationId)
                .orElse(null);
    }

    /** Net CASH settled over the session: Σ SETTLED − Σ REVERSED. */
    private BigDecimal cashSettlements(UUID sessionId) {
        return paymentRecordRepository.findBySessionId(sessionId).stream()
                .filter(r -> CASH.equalsIgnoreCase(r.getMethodType()))
                .map(RegisterSessionServiceImpl::signedAmount)
                .reduce(ZERO, BigDecimal::add);
    }

    /** Net settled per tender method over the session (Σ SETTLED − Σ REVERSED), ordered by method. */
    private Map<String, BigDecimal> tenderTotals(UUID sessionId) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (OrderPaymentRecord record : paymentRecordRepository.findBySessionId(sessionId)) {
            String method = record.getMethodType() == null ? "OTHER" : record.getMethodType();
            totals.merge(method, signedAmount(record), BigDecimal::add);
        }
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        totals.forEach((k, v) -> scaled.put(k, scale(v)));
        return scaled;
    }

    /** Signed Σ of cash movements: PAID_IN positive, PAID_OUT negative. */
    private BigDecimal cashMovementTotal(UUID sessionId) {
        return cashMovementRepository.findBySessionIdOrderByOccurredAtAsc(sessionId).stream()
                .map(m -> m.getMovementType() == CashMovementType.PAID_IN
                        ? m.getAmount()
                        : m.getAmount().negate())
                .reduce(ZERO, BigDecimal::add);
    }

    private static BigDecimal signedAmount(OrderPaymentRecord record) {
        return record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                ? record.getAmount()
                : record.getAmount().negate();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private RegisterSessionSummary toSummary(RegisterSession s) {
        return new RegisterSessionSummary(
                s.getSessionId(),
                s.getTerminalId(),
                s.getLocationId(),
                s.getOpenedByClerkId(),
                s.getStatus().name(),
                s.getOpeningFloat(),
                s.getCountedCash(),
                s.getTheoreticalCash(),
                s.getOverShort(),
                s.isVarianceApproved(),
                s.getCurrencyCode(),
                s.getClosedByClerkId(),
                s.getOpenedAt(),
                s.getClosingStartedAt(),
                s.getClosedAt());
    }

    /** The session's movements as reads show them, each with its stated taxes. */
    private List<CashMovementSummary> summaries(List<CashMovement> movements, RegisterSession session) {
        Map<UUID, List<CashMovementStatedTax>> taxes = drawerStatedTax.statedTaxes(
                movements.stream().map(CashMovement::getMovementId).toList(), session.getCurrencyCode());
        return movements.stream()
                .map(m -> toMovementSummary(m, taxes.getOrDefault(m.getMovementId(), List.of())))
                .toList();
    }

    private static CashMovementSummary toMovementSummary(CashMovement m, List<CashMovementStatedTax> statedTaxes) {
        return new CashMovementSummary(
                m.getMovementId(),
                m.getSessionId(),
                m.getRequestId(),
                m.getReasonCode() == null ? null : m.getReasonCode().name(),
                m.getMovementType().name(),
                m.getAmount(),
                m.getCurrencyCode(),
                m.getCategoryCode(),
                m.getVendorId(),
                m.getBagNumber(),
                m.getReceiptReference(),
                m.getNote(),
                m.getClerkId(),
                m.getClerkUserId(),
                m.getApprovedBy(),
                m.getOccurredAt(),
                m.getSupplierName(),
                statedTaxes,
                m.getSupplierRegistrationNumber() != null,
                m.getTaxPlausibility(),
                m.getSupplierRegistrationRequired());
    }

    /** The session's movements on the close fact, each with its stated taxes, never null. */
    private List<RegisterSessionClosedV1.Movement> factMovements(
            List<CashMovement> movements, RegisterSession session) {
        Map<UUID, List<CashMovementStatedTax>> taxes = drawerStatedTax.statedTaxes(
                movements.stream().map(CashMovement::getMovementId).toList(), session.getCurrencyCode());
        return movements.stream()
                .map(m ->
                        toFactMovement(m, session.getCurrencyCode(), taxes.getOrDefault(m.getMovementId(), List.of())))
                .toList();
    }

    /** One movement on the close fact (schema version 2; the five S32d fields appended, {@code statedTaxes} never null). */
    private static RegisterSessionClosedV1.Movement toFactMovement(
            CashMovement m, String sessionCurrency, List<CashMovementStatedTax> statedTaxes) {
        return new RegisterSessionClosedV1.Movement(
                m.getMovementId(),
                m.getReasonCode() == null ? null : m.getReasonCode().name(),
                m.getMovementType() == CashMovementType.PAID_IN
                        ? RegisterSessionClosedV1.Movement.IN
                        : RegisterSessionClosedV1.Movement.OUT,
                m.getAmount(),
                m.getCurrencyCode() == null ? sessionCurrency : m.getCurrencyCode(),
                m.getCategoryCode(),
                m.getVendorId(),
                m.getBagNumber(),
                m.getReceiptReference(),
                m.getClerkId(),
                m.getClerkUserId(),
                m.getApprovedBy(),
                m.getOccurredAt(),
                m.getSupplierName(),
                statedTaxes.stream()
                        .map(tax -> new RegisterSessionClosedV1.StatedTax(tax.regime(), tax.amount()))
                        .toList(),
                m.getSupplierRegistrationNumber(),
                m.getTaxPlausibility(),
                m.getSupplierRegistrationRequired());
    }
}
