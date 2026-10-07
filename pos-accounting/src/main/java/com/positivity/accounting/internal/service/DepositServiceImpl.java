package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.DepositRecordRequest;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.DepositReversalRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.Deposit;
import com.positivity.accounting.internal.entity.DepositSession;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.DepositStatus;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.DepositRepository;
import com.positivity.accounting.internal.repository.DepositSessionRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bank deposits of drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5, §5.1.1, §7.1 "Undeposited
 * sessions", "Record / reverse deposit", §9.4, §9.5a; AW9, AW10, AW15).
 *
 * <p><b>The balance identity.</b> A selection of sessions balances when its bank drops equal its expected cash plus its
 * clearing net ({@code difference = depositAmount − expectedCash − clearingNet = 0}). Record bank deposit then posts one
 * {@code BANK_DEPOSIT} entry dated {@code depositDate} through the period gate: Dr the bank account (one line, the
 * drops), Cr {@code UNDEPOSITED_FUNDS} (1090) the expected cash, and Dr {@code CASH_CLEARING} (1095) when the clearing
 * net is a credit, Cr when a debit, by its size (a zero line is left out). Accounts resolve through the {@code
 * BANK_DEPOSIT} posting category; the bank side is the chosen account. Any other difference is refused with 422 {@code
 * DEPOSIT_UNBALANCED} naming it: no plug line is ever written.
 *
 * <p><b>Whole sessions, once.</b> A deposit takes each named session whole (all its drops, its whole clearing net); the
 * session rows are locked in session-id order, so two clerks depositing the same session serialize and the second
 * gets 409 {@code DEPOSIT_SESSION_ALREADY_DEPOSITED}.
 *
 * <p><b>Idempotency.</b> Both commands are idempotent on their {@code requestId}: a replay with the same body returns
 * the first result (the deposit as recorded, or as reversed), another body is 409 {@code IDEMPOTENCY_CONFLICT}. The
 * requestId is checked again after the locks, so a concurrent duplicate answers with the first result too.
 *
 * <p><b>Reversal.</b> Reverse deposit reverses the entry through {@link JournalEntryService#reverseJournalEntry} (the
 * default date rules, the period gate and its override, ADR-0047); {@link DepositReversalReaction} marks the deposit
 * {@code REVERSED} and returns its sessions to {@code UNDEPOSITED}, exactly as it does for the same entry reversed
 * through the generic journal-entry reversal, so the deposit and the ledger never disagree.
 *
 * <p><b>Location scope (ADR-0061).</b> The read lists only the sessions whose location the caller's {@code
 * accounting:deposit:create} reaches; a session outside it is unknown to the selection. The commands gate on each
 * session's stored location after the lock (and a replay on the stored deposit's sessions): 403 {@code
 * LOCATION_SCOPE_DENIED}, which never names the location. A session with no location is reachable only by an unscoped
 * caller.
 *
 * <p><b>Currency (ADR-0067).</b> The request states the currency of the sessions' amounts; another than the ledger's is
 * 422 {@code CURRENCY_NOT_SUPPORTED} (PC-9), and a selection amount finer than its minor unit is 422 {@code
 * AMOUNT_PRECISION_EXCEEDS_CURRENCY} (PC-6). A session closed or dropped in another currency never reaches the read
 * model ({@link UndepositedSessionProjection}).
 */
@Slf4j
@Service
@Transactional
public class DepositServiceImpl implements DepositService {

    /** The posting category, and the journal entry source type, of every deposit. */
    static final String POSTING_CATEGORY = JournalEntrySourceTypes.BANK_DEPOSIT;

    static final String UNDEPOSITED_FUNDS_KEY = "UNDEPOSITED_FUNDS";
    static final String CASH_CLEARING_KEY = "CASH_CLEARING";

    /** {@code AccountingAuditLog.entityType}; the entity id is the deposit. */
    static final String AUDIT_ENTITY_TYPE = "BANK_DEPOSIT";

    static final String AUDIT_RECORD = "BANK_DEPOSIT_RECORD";

    /** The {@code (tenant_id, request_id)} unique of {@code deposit} (V16). */
    static final String REQUEST_UNIQUE = "uq_deposit_request";

    /** The {@code (tenant_id, reversal_request_id)} unique of {@code deposit} (V16). */
    static final String REVERSAL_REQUEST_UNIQUE = "uq_deposit_reversal_request";

    /** Deposits recorded (#2514 "Audit and observability"). */
    static final String RECORDED_METRIC = "accounting.deposit.recorded";

    /** Record bank deposit commands refused as unbalanced. */
    static final String UNBALANCED_METRIC = "accounting.deposit.unbalanced";

    private static final int DESCRIPTION_MAX = 500;

    private final UndepositedSessionRepository sessions;
    private final UndepositedSessionDropRepository drops;
    private final DepositRepository deposits;
    private final DepositSessionRepository depositSessions;
    private final GLAccountRepository glAccounts;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final AccountingAuditLogRepository auditLogs;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;
    private final FunctionalCurrency functionalCurrency;
    private final BankAccountCurrencies bankAccountCurrencies;
    private final DepositFacts facts;
    private final @Nullable Counter recorded;
    private final @Nullable Counter unbalanced;

    @SuppressWarnings("java:S107") // one collaborator per concern of the command, as the other cash commands
    public DepositServiceImpl(
            UndepositedSessionRepository sessions,
            UndepositedSessionDropRepository drops,
            DepositRepository deposits,
            DepositSessionRepository depositSessions,
            GLAccountRepository glAccounts,
            GLMappingResolver glMappingResolver,
            JournalEntryService journalEntryService,
            AccountingAuditLogRepository auditLogs,
            AccountingCalendarZoneResolver zoneResolver,
            LedgerCurrency ledgerCurrency,
            FunctionalCurrency functionalCurrency,
            BankAccountCurrencies bankAccountCurrencies,
            DepositFacts facts,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.sessions = sessions;
        this.drops = drops;
        this.deposits = deposits;
        this.depositSessions = depositSessions;
        this.glAccounts = glAccounts;
        this.glMappingResolver = glMappingResolver;
        this.journalEntryService = journalEntryService;
        this.auditLogs = auditLogs;
        this.zoneResolver = zoneResolver;
        this.ledgerCurrency = ledgerCurrency;
        this.functionalCurrency = functionalCurrency;
        this.bankAccountCurrencies = bankAccountCurrencies;
        this.facts = facts;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.recorded = registry == null
                ? null
                : Counter.builder(RECORDED_METRIC)
                        .description("Bank deposits of drawer cash recorded")
                        .register(registry);
        this.unbalanced = registry == null
                ? null
                : Counter.builder(UNBALANCED_METRIC)
                        .description("Record bank deposit commands refused as unbalanced")
                        .register(registry);
    }

    // ---- read ------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public @NonNull UndepositedSessionsResponse undeposited(
            @NonNull List<UUID> sessionIds, @Nullable UUID bankGlAccountId) {
        LocalDate asOf = zoneResolver.today();
        List<UndepositedSession> visible =
                sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED).stream()
                        .filter(session -> reaches(AccountingPermissions.DEPOSIT_CREATE, session.getLocationId()))
                        .toList();
        Map<UUID, List<UndepositedSessionDrop>> dropsBySession = dropsOf(visible);
        List<UndepositedSessionsResponse.Session> views = visible.stream()
                .map(session -> view(session, dropsBySession, asOf))
                .toList();

        UndepositedSessionsResponse.Selection selection = null;
        if (!sessionIds.isEmpty()) {
            Map<UUID, UndepositedSession> byId =
                    visible.stream().collect(Collectors.toMap(UndepositedSession::getSessionId, Function.identity()));
            List<UndepositedSession> selected = new ArrayList<>();
            for (UUID sessionId : new LinkedHashSet<>(sessionIds)) {
                UndepositedSession session = byId.get(sessionId);
                if (session == null) {
                    // Unknown, already deposited, or outside the caller's reach: never told apart (ADR-0061).
                    throw InvalidRequestParameterException.forField(
                            "sessionId", "No undeposited register session " + sessionId + " to select");
                }
                selected.add(session);
            }
            selection = selection(selected, bankGlAccountId, asOf);
        }
        return new UndepositedSessionsResponse(asOf, ledgerCurrency.code(), views, selection);
    }

    private UndepositedSessionsResponse.Selection selection(
            List<UndepositedSession> selected, @Nullable UUID bankGlAccountId, LocalDate asOf) {
        Totals totals = Totals.of(selected);
        LocalDateTime at = asOf.atStartOfDay();
        GLAccount bank = null;
        if (bankGlAccountId != null) {
            bank = glAccounts
                    .findById(bankGlAccountId)
                    .orElseThrow(() -> InvalidRequestParameterException.forField(
                            "bankGlAccountId", "No GL account " + bankGlAccountId));
        }
        Map<UUID, GLAccount> accounts = new LinkedHashMap<>();
        UUID undepositedFunds = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, UNDEPOSITED_FUNDS_KEY, at);
        UUID cashClearing = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, CASH_CLEARING_KEY, at);
        glAccounts
                .findAllById(List.of(undepositedFunds, cashClearing))
                .forEach(a -> accounts.put(a.getGlAccountId(), a));

        List<UndepositedSessionsResponse.PreviewLine> lines = new ArrayList<>();
        addPreview(lines, bank, bankGlAccountId, totals.depositAmount());
        addPreview(
                lines,
                accounts.get(undepositedFunds),
                undepositedFunds,
                totals.expectedCash().negate());
        addPreview(
                lines,
                accounts.get(cashClearing),
                cashClearing,
                totals.clearingNet().negate());
        return new UndepositedSessionsResponse.Selection(
                selected.stream().map(UndepositedSession::getSessionId).toList(),
                totals.depositAmount(),
                totals.expectedCash(),
                totals.clearingNet(),
                totals.difference(),
                totals.difference().signum() == 0,
                lines);
    }

    private static void addPreview(
            List<UndepositedSessionsResponse.PreviewLine> lines,
            @Nullable GLAccount account,
            @Nullable UUID accountId,
            BigDecimal signed) {
        if (signed.signum() == 0) {
            return;
        }
        lines.add(new UndepositedSessionsResponse.PreviewLine(
                accountId,
                account == null ? null : account.getAccountCode(),
                account == null ? null : account.getAccountName(),
                signed.signum() > 0 ? UndepositedSessionsResponse.Side.DEBIT : UndepositedSessionsResponse.Side.CREDIT,
                signed.abs()));
    }

    // ---- record ----------------------------------------------------------------------------------------------

    @Override
    public @NonNull Outcome record(@NonNull DepositRecordRequest request) {
        request.requireValid();
        UUID requestId = Objects.requireNonNull(request.requestId());
        UUID bankGlAccountId = Objects.requireNonNull(request.bankGlAccountId());
        LocalDate depositDate = Objects.requireNonNull(request.depositDate());
        String currencyCode = Objects.requireNonNull(request.currencyCode());
        List<UUID> sessionIds = Objects.requireNonNull(request.sessionIds());
        String slip = blankToNull(request.depositSlipReference());
        String override = blankToNull(request.overrideJustification());
        String hash = recordHash(bankGlAccountId, depositDate, currencyCode, sessionIds, slip, override);

        Deposit replayed = deposits.findByRequestId(requestId).orElse(null);
        if (replayed != null) {
            return replayRecord(replayed, hash);
        }
        // ADR-0067 PC-9: the sessions' amounts are in the functional currency; another is the platform's one refusal.
        if (ledgerCurrency.isForeign(currencyCode)) {
            throw new CurrencyNotSupportedException("currencyCode " + currencyCode
                    + " is not the functional currency " + ledgerCurrency.code()
                    + "; drawer cash is deposited in the functional currency only");
        }
        GLAccount bank = requireEligibleBankAccount(bankGlAccountId, depositDate);

        List<UndepositedSession> locked = sessions.lockBySessionIdIn(sessionIds);
        // A concurrent duplicate of this request waited on the locks: it answers with the first result.
        Deposit committed = deposits.findByRequestId(requestId).orElse(null);
        if (committed != null) {
            return replayRecord(committed, hash);
        }
        if (locked.size() != sessionIds.size()) {
            Set<UUID> found =
                    locked.stream().map(UndepositedSession::getSessionId).collect(Collectors.toSet());
            List<UUID> unknown =
                    sessionIds.stream().filter(id -> !found.contains(id)).toList();
            throw InvalidRequestParameterException.forField(
                    "sessionIds", "No register session " + unknown + " is waiting to be deposited");
        }
        // ADR-0061: the caller's reach at each stored session's location, after the lock and before its status.
        locked.forEach(session -> requireInScope(AccountingPermissions.DEPOSIT_CREATE, session.getLocationId()));
        for (UndepositedSession session : locked) {
            if (session.getStatus() != UndepositedSessionStatus.UNDEPOSITED) {
                throw alreadyDeposited(session);
            }
        }

        Totals totals = Totals.of(locked);
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        amounts.put("selection.depositAmount", totals.depositAmount());
        amounts.put("selection.expectedCash", totals.expectedCash());
        amounts.put("selection.clearingNet", totals.clearingNet());
        functionalCurrency.requireMinorUnits(amounts);
        if (totals.depositAmount().signum() == 0) {
            throw InvalidRequestParameterException.forField(
                    "sessionIds",
                    "The selected sessions hold no bank drops: there is no cash to take to the bank;"
                            + " deposit them together with a session that does");
        }
        if (totals.difference().signum() != 0) {
            if (unbalanced != null) {
                unbalanced.increment();
            }
            throw unbalancedRefusal(totals);
        }

        // Business-date posting through the period gate: closed → accounting:period:override + justification.
        JournalEntryResponse posted = post(bank, depositDate, totals, locked.size(), slip, override);

        Deposit deposit = new Deposit();
        deposit.setBankGlAccountId(bank.getGlAccountId());
        deposit.setBankAccountCode(bank.getAccountCode());
        deposit.setDepositDate(depositDate);
        deposit.setAmount(totals.depositAmount());
        deposit.setExpectedCash(totals.expectedCash());
        deposit.setClearingNet(totals.clearingNet());
        deposit.setCurrencyCode(currencyCode);
        deposit.setDepositSlipReference(slip);
        deposit.setJournalEntryId(posted.getJournalEntryId());
        deposit.setJournalEntryNumber(posted.getEntryNumber());
        deposit.setStatus(DepositStatus.RECORDED);
        deposit.setOverrideJustification(override);
        deposit.setRecordedBy(RegisterFloatServiceImpl.currentActor());
        deposit.setRequestId(requestId);
        deposit.setRequestHash(hash);
        Deposit saved;
        try {
            saved = deposits.saveAndFlush(deposit);
        } catch (DataIntegrityViolationException e) {
            // The request unique is the backstop for one requestId racing itself; any other violation is not ours.
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains(REQUEST_UNIQUE)) {
                throw e;
            }
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + requestId + " was concurrently used by another command");
        }

        List<DepositSession> taken = new ArrayList<>();
        for (UndepositedSession session : locked) {
            DepositSession row = new DepositSession();
            row.setDepositId(saved.getDepositId());
            row.setSessionId(session.getSessionId());
            row.setTerminalId(session.getTerminalId());
            row.setLocationId(session.getLocationId());
            row.setClosedAt(session.getClosedAt());
            row.setDepositAmount(session.getDepositAmount());
            row.setExpectedCash(session.getExpectedCash());
            row.setClearingNet(session.getClearingNet());
            taken.add(row);
            session.setStatus(UndepositedSessionStatus.DEPOSITED);
            session.setDepositId(saved.getDepositId());
        }
        taken.sort(Comparator.comparing(DepositSession::getClosedAt).thenComparing(DepositSession::getSessionId));
        List<DepositSession> savedSessions = depositSessions.saveAllAndFlush(taken);
        // A merge, so a persistence context the posting cleared still writes them; the row locks stay held.
        sessions.saveAllAndFlush(locked);

        Map<UUID, List<String>> bags = bagNumbers(sessionIds);
        audit(saved, savedSessions, bags);
        facts.changed(saved, savedSessions, saved.getRecordedBy());
        countAfterCommit(recorded);
        log.info(
                "Bank deposit {} recorded into {}: {} session(s), amount {}, expected cash {}, clearing net {} {} (JE {})",
                saved.getDepositId(),
                saved.getBankAccountCode(),
                savedSessions.size(),
                saved.getAmount(),
                saved.getExpectedCash(),
                saved.getClearingNet(),
                saved.getCurrencyCode(),
                saved.getJournalEntryNumber());
        return new Outcome(response(saved, savedSessions, bags, true, false), false);
    }

    private JournalEntryResponse post(
            GLAccount bank,
            LocalDate depositDate,
            Totals totals,
            int sessionCount,
            @Nullable String slip,
            @Nullable String override) {
        LocalDateTime at = depositDate.atStartOfDay();
        UUID undepositedFunds = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, UNDEPOSITED_FUNDS_KEY, at);
        UUID cashClearing = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, CASH_CLEARING_KEY, at);
        String description = abbreviate("Bank deposit of " + sessionCount + " register session(s) into "
                + bank.getAccountCode() + (slip == null ? "" : ", slip " + slip));
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        // Signed amounts, debit positive; they sum to the difference, which is zero here, and a zero line is left out.
        addLine(lines, bank.getGlAccountId(), totals.depositAmount(), "Drawer cash deposited");
        addLine(lines, undepositedFunds, totals.expectedCash().negate(), "Expected cash taken to the bank");
        addLine(lines, cashClearing, totals.clearingNet().negate(), "Register cash clearing taken to the bank");
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(at)
                .sourceEventId(sourceEventId(UUIDv7Generator.generate()))
                .sourceEventType(POSTING_CATEGORY)
                .description(description)
                .lines(lines)
                .build());
        return journalEntryService.postJournalEntry(created.getJournalEntryId(), override);
    }

    private static void addLine(
            List<JournalEntryCreateRequest.JournalEntryLineRequest> lines,
            UUID account,
            BigDecimal signed,
            String description) {
        if (signed.signum() == 0) {
            return;
        }
        lines.add(JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(signed.signum() > 0 ? signed : BigDecimal.ZERO)
                .creditAmount(signed.signum() < 0 ? signed.negate() : BigDecimal.ZERO)
                .description(description)
                .build());
    }

    /** The source event of a deposit's entry, derived from the command's own posting key. */
    static @NonNull UUID sourceEventId(@NonNull UUID postingKey) {
        return UUID.nameUUIDFromBytes((POSTING_CATEGORY + ":" + postingKey).getBytes(StandardCharsets.UTF_8));
    }

    private Outcome replayRecord(Deposit original, String hash) {
        List<DepositSession> taken = sessionsOf(original);
        requireInScope(AccountingPermissions.DEPOSIT_CREATE, taken);
        if (!Objects.equals(original.getRequestHash(), hash)) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + original.getRequestId() + " was already used with a different payload");
        }
        return new Outcome(response(original, taken, bagNumbers(sessionIdsOf(taken)), true, true), true);
    }

    // ---- reverse ---------------------------------------------------------------------------------------------

    @Override
    public @NonNull Outcome reverse(@NonNull UUID depositId, @NonNull DepositReversalRequest request) {
        request.requireValid();
        UUID requestId = Objects.requireNonNull(request.requestId());
        String reason = Objects.requireNonNull(request.reason()).trim();
        String override = blankToNull(request.overrideJustification());
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("REVERSE")
                .field(depositId)
                .field(reason)
                .field(request.reversalDate())
                .field(override)
                .digest();

        Deposit replayed = deposits.findByReversalRequestId(requestId).orElse(null);
        if (replayed != null) {
            return replayReversal(replayed, depositId, hash);
        }
        Deposit deposit = deposits.lockById(depositId).orElseThrow(() -> notFound(depositId));
        // A concurrent duplicate of this request waited on the lock: it answers with the first result.
        Deposit committed = deposits.findByReversalRequestId(requestId).orElse(null);
        if (committed != null) {
            return replayReversal(committed, depositId, hash);
        }
        List<DepositSession> taken = sessionsOf(deposit);
        // ADR-0061: the caller's reach at every location the deposit took cash from, after the lock.
        requireInScope(AccountingPermissions.DEPOSIT_REVERSE, taken);
        if (deposit.getStatus() == DepositStatus.REVERSED) {
            throw new CashSetupException(
                    CashSetupException.Code.DEPOSIT_ALREADY_REVERSED,
                    "Bank deposit " + deposit.getJournalEntryNumber() + " was already reversed by "
                            + deposit.getReversalJournalEntryNumber() + "; record a new deposit instead",
                    deposit.getDepositId().toString(),
                    null);
        }
        deposit.setReversalRequestId(requestId);
        deposit.setReversalRequestHash(hash);
        try {
            deposits.saveAndFlush(deposit);
        } catch (DataIntegrityViolationException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains(REVERSAL_REQUEST_UNIQUE)) {
                throw e;
            }
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + requestId + " was concurrently used by another command");
        }

        // The EXISTING reversal (ADR-0047): default date rules, period gate and override. Its in-process event reaches
        // DepositReversalReaction in this transaction, which marks the deposit REVERSED and returns the sessions.
        journalEntryService.reverseJournalEntry(deposit.getJournalEntryId(), reason, request.reversalDate(), override);

        // The reversal clears the persistence context (JournalEntryRepository.markReversed): read the deposit again.
        Deposit reversed = deposits.findById(depositId).orElseThrow(() -> notFound(depositId));
        return new Outcome(response(reversed, taken, bagNumbers(sessionIdsOf(taken)), false, false), false);
    }

    private Outcome replayReversal(Deposit original, UUID depositId, String hash) {
        List<DepositSession> taken = sessionsOf(original);
        requireInScope(AccountingPermissions.DEPOSIT_REVERSE, taken);
        if (!original.getDepositId().equals(depositId) || !Objects.equals(original.getReversalRequestHash(), hash)) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + original.getReversalRequestId() + " was already used with a different payload");
        }
        return new Outcome(response(original, taken, bagNumbers(sessionIdsOf(taken)), false, true), true);
    }

    // ---- get -------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public @NonNull DepositResponse get(@NonNull UUID depositId) {
        Deposit deposit = deposits.findById(depositId).orElseThrow(() -> notFound(depositId));
        List<DepositSession> taken = sessionsOf(deposit);
        requireInScope(AccountingPermissions.DEPOSIT_CREATE, taken);
        return response(deposit, taken, bagNumbers(sessionIdsOf(taken)), false, false);
    }

    // ---- rules -----------------------------------------------------------------------------------------------

    /**
     * An active, reconcilable BANK_CASH account in functional currency (ADR-0067), else 422 {@code
     * DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE}.
     */
    private GLAccount requireEligibleBankAccount(UUID glAccountId, LocalDate date) {
        GLAccount account = glAccounts
                .findById(glAccountId)
                .orElseThrow(() -> notEligible("GL account " + glAccountId + " does not exist"));
        if (account.getAccountSubtype() != AccountSubtype.BANK_CASH || !account.isReconcilable()) {
            throw notEligible("Account " + account.getAccountCode() + " is not a reconcilable bank account");
        }
        LocalDateTime at = date.atStartOfDay();
        boolean active = (account.getActivationDate() == null
                        || !account.getActivationDate().isAfter(at))
                && (account.getDeactivationDate() == null
                        || account.getDeactivationDate().isAfter(at));
        if (!active) {
            throw notEligible("Bank account " + account.getAccountCode() + " is not active on " + date);
        }
        bankAccountCurrencies
                .currencyOf(glAccountId)
                .filter(ledgerCurrency::isForeign)
                .ifPresent(currency -> {
                    throw notEligible("Bank account " + account.getAccountCode() + " is in " + currency
                            + ", not the functional currency " + ledgerCurrency.code());
                });
        return account;
    }

    private CashSetupException alreadyDeposited(UndepositedSession session) {
        String deposit = session.getDepositId() == null
                ? "another deposit"
                : deposits.findById(session.getDepositId())
                        .map(Deposit::getJournalEntryNumber)
                        .map(number -> "deposit " + number)
                        .orElse("another deposit");
        return new CashSetupException(
                CashSetupException.Code.DEPOSIT_SESSION_ALREADY_DEPOSITED,
                "Register " + session.getTerminalId() + "'s session closed "
                        + zoneResolver.postingDate(session.getClosedAt()) + " is already in " + deposit
                        + "; a session is deposited whole, once",
                session.getSessionId().toString(),
                "Refresh the undeposited sessions and select again");
    }

    private CashSetupException unbalancedRefusal(Totals totals) {
        BigDecimal callsFor = totals.expectedCash().add(totals.clearingNet());
        String direction = totals.difference().signum() < 0 ? "short" : "over";
        return new CashSetupException(
                CashSetupException.Code.DEPOSIT_UNBALANCED,
                "The selected sessions do not balance: their bank drops total "
                        + functionalCurrency.display(totals.depositAmount())
                        + " but their expected cash less the drawer payouts and over/short is "
                        + functionalCurrency.display(callsFor) + ", " + direction + " by "
                        + functionalCurrency.display(totals.difference().abs()) + " "
                        + ledgerCurrency.code() + ". Nothing was posted; deposit the other sessions and correct"
                        + " this session's postings by reversal",
                null,
                "Deposit the other sessions; correct the unbalanced session's postings by reversal, never by a plug");
    }

    /** {@code covers} for a stored location; a session with none is reachable only by an unscoped caller. */
    private static boolean reaches(String permission, @Nullable UUID locationId) {
        return SecurityContextHelper.locationScope()
                .covers(permission, locationId == null ? "" : locationId.toString());
    }

    private static void requireInScope(String permission, @Nullable UUID locationId) {
        SecurityContextHelper.locationScope().require(permission, locationId == null ? "" : locationId.toString());
    }

    private static void requireInScope(String permission, Collection<DepositSession> taken) {
        taken.forEach(session -> requireInScope(permission, session.getLocationId()));
    }

    private static CashSetupException notEligible(String message) {
        return new CashSetupException(CashSetupException.Code.DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE, message);
    }

    private static CashSetupException notFound(UUID depositId) {
        return new CashSetupException(
                CashSetupException.Code.DEPOSIT_NOT_FOUND, "Bank deposit " + depositId + " not found");
    }

    // ---- results ---------------------------------------------------------------------------------------------

    private List<DepositSession> sessionsOf(Deposit deposit) {
        return depositSessions.findByDepositIdOrderByClosedAtAscSessionIdAsc(deposit.getDepositId());
    }

    private static List<UUID> sessionIdsOf(List<DepositSession> taken) {
        return taken.stream().map(DepositSession::getSessionId).toList();
    }

    private Map<UUID, List<UndepositedSessionDrop>> dropsOf(Collection<UndepositedSession> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> sessionByRow = rows.stream()
                .collect(Collectors.toMap(
                        UndepositedSession::getUndepositedSessionId, UndepositedSession::getSessionId));
        Map<UUID, List<UndepositedSessionDrop>> bySession = new LinkedHashMap<>();
        drops.findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(sessionByRow.keySet())
                .forEach(drop -> bySession
                        .computeIfAbsent(sessionByRow.get(drop.getUndepositedSessionId()), k -> new ArrayList<>())
                        .add(drop));
        return bySession;
    }

    /** The bag numbers of each session's drops, in the order recorded; a drop without a bag is left out. */
    private Map<UUID, List<String>> bagNumbers(Collection<UUID> sessionIds) {
        Map<UUID, List<String>> bags = new LinkedHashMap<>();
        dropsOf(sessions.findBySessionIdIn(sessionIds))
                .forEach((sessionId, sessionDrops) -> bags.put(
                        sessionId,
                        sessionDrops.stream()
                                .map(UndepositedSessionDrop::getBagNumber)
                                .filter(Objects::nonNull)
                                .toList()));
        return bags;
    }

    private UndepositedSessionsResponse.Session view(
            UndepositedSession session, Map<UUID, List<UndepositedSessionDrop>> dropsBySession, LocalDate asOf) {
        LocalDate closeDate = zoneResolver.postingDate(session.getClosedAt());
        return new UndepositedSessionsResponse.Session(
                session.getSessionId(),
                session.getTerminalId(),
                session.getLocationId(),
                session.getClosedAt(),
                closeDate,
                Math.max(0, ChronoUnit.DAYS.between(closeDate, asOf)),
                session.getOpeningFloat(),
                session.getCountedCash(),
                session.getTheoreticalCash(),
                session.getOverShort(),
                session.getExpectedCash(),
                session.getClearingNet(),
                session.getDepositAmount(),
                dropsBySession.getOrDefault(session.getSessionId(), List.of()).stream()
                        .map(drop -> new UndepositedSessionsResponse.Drop(
                                drop.getMovementId(), drop.getBagNumber(), drop.getAmount()))
                        .toList());
    }

    /**
     * The response. {@code asRecorded} answers Record bank deposit and its replays with the deposit as it was recorded
     * (the first result, whatever happened since); otherwise the deposit as it stands.
     */
    private static DepositResponse response(
            Deposit deposit,
            List<DepositSession> taken,
            Map<UUID, List<String>> bags,
            boolean asRecorded,
            boolean replayed) {
        boolean reversed = !asRecorded && deposit.getStatus() == DepositStatus.REVERSED;
        return new DepositResponse(
                deposit.getDepositId(),
                reversed ? DepositStatus.REVERSED : DepositStatus.RECORDED,
                deposit.getBankGlAccountId(),
                deposit.getBankAccountCode(),
                deposit.getDepositDate(),
                deposit.getAmount(),
                deposit.getExpectedCash(),
                deposit.getClearingNet(),
                deposit.getCurrencyCode(),
                deposit.getDepositSlipReference(),
                deposit.getJournalEntryId(),
                deposit.getJournalEntryNumber(),
                taken.stream()
                        .map(session -> new DepositResponse.Session(
                                session.getSessionId(),
                                session.getTerminalId(),
                                session.getLocationId(),
                                session.getClosedAt(),
                                session.getDepositAmount(),
                                session.getExpectedCash(),
                                session.getClearingNet(),
                                bags.getOrDefault(session.getSessionId(), List.of())))
                        .toList(),
                reversed ? deposit.getReversalJournalEntryId() : null,
                reversed ? deposit.getReversalJournalEntryNumber() : null,
                reversed ? deposit.getReversalDate() : null,
                reversed ? deposit.getReversalReason() : null,
                deposit.getRecordedBy(),
                reversed ? deposit.getReversedBy() : null,
                reversed ? deposit.getReversedAt() : null,
                replayed);
    }

    private void audit(Deposit deposit, List<DepositSession> taken, Map<UUID, List<String>> bags) {
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(AUDIT_ENTITY_TYPE);
        audit.setEntityId(deposit.getDepositId());
        audit.setOperation(AUDIT_RECORD);
        audit.setUserId(deposit.getRecordedBy());
        audit.setJustification(deposit.getOverrideJustification());
        audit.setNewValue("bankGlAccountId=" + deposit.getBankGlAccountId() + ";bankAccountCode="
                + deposit.getBankAccountCode() + ";depositDate=" + deposit.getDepositDate() + ";amount="
                + deposit.getAmount().toPlainString() + ";expectedCash="
                + deposit.getExpectedCash().toPlainString()
                + ";clearingNet=" + deposit.getClearingNet().toPlainString() + ";currencyCode="
                + deposit.getCurrencyCode() + ";sessions=" + sessionIdsOf(taken) + ";bagNumbers="
                + taken.stream()
                        .flatMap(session -> bags.getOrDefault(session.getSessionId(), List.of()).stream())
                        .toList()
                + ";depositSlipReference=" + deposit.getDepositSlipReference() + ";requestId="
                + deposit.getRequestId() + ";journalEntryId=" + deposit.getJournalEntryId()
                + ";overrideJustification=" + deposit.getOverrideJustification());
        auditLogs.save(audit);
    }

    /** A counter, incremented once the command commits, so a rolled-back deposit is never counted. */
    private static void countAfterCommit(@Nullable Counter counter) {
        if (counter == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }

    /** The fields that steer Record bank deposit, so a reused requestId with another body is a conflict. */
    private static String recordHash(
            UUID bankGlAccountId,
            LocalDate depositDate,
            String currencyCode,
            List<UUID> sessionIds,
            @Nullable String slip,
            @Nullable String override) {
        RegisterFloatServiceImpl.Hash hash = new RegisterFloatServiceImpl.Hash()
                .field("RECORD")
                .field(bankGlAccountId)
                .field(depositDate)
                .field(currencyCode)
                .field(sessionIds.size());
        sessionIds.stream().sorted().forEach(hash::field);
        return hash.field(slip).field(override).digest();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String abbreviate(String text) {
        return text.length() <= DESCRIPTION_MAX ? text : text.substring(0, DESCRIPTION_MAX - 1) + "…";
    }

    /** A selection's amounts and the balance identity's difference. */
    record Totals(BigDecimal depositAmount, BigDecimal expectedCash, BigDecimal clearingNet) {

        static Totals of(Collection<UndepositedSession> selected) {
            BigDecimal deposit = BigDecimal.ZERO;
            BigDecimal expected = BigDecimal.ZERO;
            BigDecimal clearing = BigDecimal.ZERO;
            for (UndepositedSession session : selected) {
                deposit = deposit.add(session.getDepositAmount());
                expected = expected.add(session.getExpectedCash());
                clearing = clearing.add(session.getClearingNet());
            }
            return new Totals(deposit, expected, clearing);
        }

        /** {@code depositAmount − expectedCash − clearingNet}: zero when the deposit balances. */
        BigDecimal difference() {
            return depositAmount.subtract(expectedCash).subtract(clearingNet);
        }
    }
}
