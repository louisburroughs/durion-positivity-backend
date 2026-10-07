package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.readmodel.BankStatementCoverage;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.BankOpeningBalanceRequest;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.BankOpeningBalance;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.BankOpeningItemType;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.BankOpeningBalanceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A bank account's opening balance at cutover (#2572; SPEC-accounting-workspace OI-10; Accounting Domain ruling
 * 2026-10-07), following the go-live float pattern (§4.6, AW17).
 *
 * <p>The entry is dated {@code asOfDate} and posted with no override, so its period must be open. The bank side is
 * the account in the path: one line for the statement balance (a debit, a credit when overdrawn), one line per
 * outstanding item carrying its type, reference and own date (a check credits the bank, a deposit in transit
 * debits it) and one line on the account {@code OPENING_BALANCE} / {@code OPENING_BALANCE_EQUITY} resolves to on
 * {@code asOfDate} (3900 in the template) for the net. Each item line is the ledger line bank reconciliation
 * registers as an outstanding item when the account's first statement opens on {@code asOfDate + 1}
 * (SPEC-manual-bank-reconciliation E2, D17, §4.2), so that statement opens with no difference.
 *
 * <p>Once per account: a standing opening (its entry still POSTED) refuses another; the correction is to reverse
 * the entry and run the command again (AW17, ADR-0047). The opening must come first: no standing posted line on
 * the account dated on or before {@code asOfDate} and no committed statement starting on or before it. The account
 * row is locked for the length of the command, so two commands on one account serialize. Idempotent on {@code
 * requestId}: a replay with the same body returns the first result, another body is 409 {@code
 * IDEMPOTENCY_CONFLICT}. The actor comes from the security context (ADR-0018).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankOpeningBalanceServiceImpl implements BankOpeningBalanceService {

    /** The posting category, and the journal entry source type, of every opening. */
    public static final String POSTING_CATEGORY = "OPENING_BALANCE";

    static final String EQUITY_KEY = "OPENING_BALANCE_EQUITY";

    /** {@code AccountingAuditLog.entityType}; the entity id is the opening's row. */
    static final String AUDIT_ENTITY_TYPE = "BANK_OPENING_BALANCE";

    static final String AUDIT_ESTABLISH = "BANK_OPENING_BALANCE_ESTABLISH";

    private final BankOpeningBalanceRepository openings;
    private final GLAccountRepository glAccounts;
    private final JournalEntryLineRepository journalLines;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final AccountingAuditLogRepository auditLogs;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;
    private final BankAccountCurrencies bankAccountCurrencies;
    private final BankStatementCoverage statementCoverage;

    @Override
    public @NonNull Outcome establish(@NonNull UUID glAccountId, @NonNull BankOpeningBalanceRequest request) {
        request.requireValid();
        List<BankOpeningBalanceRequest.OutstandingItem> items = request.items();
        String justification = Objects.requireNonNull(request.justification()).trim();
        UUID requestId = Objects.requireNonNull(request.requestId());
        LocalDate asOfDate = Objects.requireNonNull(request.asOfDate());
        BigDecimal statementBalance = Objects.requireNonNull(request.statementBalance());
        String currencyCode = Objects.requireNonNull(request.currencyCode());
        String hash = hash(glAccountId, asOfDate, statementBalance, currencyCode, items, justification);
        BankOpeningBalance replayed = openings.findByRequestId(requestId).orElse(null);
        if (replayed != null) {
            return replay(replayed, hash);
        }
        requireMinorUnit(statementBalance, "statementBalance");
        for (int i = 0; i < items.size(); i++) {
            requireMinorUnit(items.get(i).amount(), "outstandingItems[" + i + "].amount");
        }
        // Today in the tenant's accounting time zone; an unset zone fails closed (#2558).
        LocalDate today = zoneResolver.today();
        if (asOfDate.isAfter(today)) {
            throw InvalidRequestParameterException.forField(
                    "asOfDate",
                    "asOfDate " + asOfDate + " is after today (" + today + "); the opening is the balance at cutover");
        }

        GLAccount account = glAccounts.lockById(glAccountId).orElse(null);
        // A concurrent duplicate of this request waited on the lock: it answers with the first result.
        BankOpeningBalance committed = openings.findByRequestId(requestId).orElse(null);
        if (committed != null) {
            return replay(committed, hash);
        }
        requireEligible(account, glAccountId, asOfDate);
        String code = Objects.requireNonNull(account).getAccountCode();
        // ADR-0067 PC-9: the amounts are in the bank account's currency, which eligibility pinned to the functional
        // one; another currency is the platform's one refusal, 422 CURRENCY_NOT_SUPPORTED.
        if (!ledgerCurrency.code().equals(currencyCode)) {
            throw new CurrencyNotSupportedException("currencyCode " + currencyCode + " is not bank account " + code
                    + "'s currency " + ledgerCurrency.code());
        }
        if (!openings.findStandingByGlAccountId(glAccountId).isEmpty()) {
            throw new CashSetupException(
                    CashSetupException.Code.BANK_OPENING_BALANCE_ALREADY_ESTABLISHED,
                    "Bank account " + code + " already has an opening balance; correct it by reversing its entry"
                            + " and running the opening again");
        }
        requireFirst(glAccountId, code, asOfDate);
        if (statementBalance.signum() == 0 && items.isEmpty()) {
            throw new CashSetupException(
                    CashSetupException.Code.BANK_OPENING_BALANCE_EMPTY,
                    "A zero statement balance with no outstanding items opens nothing");
        }

        BigDecimal bookBalance = statementBalance;
        for (BankOpeningBalanceRequest.OutstandingItem item : items) {
            bookBalance = item.type() == BankOpeningItemType.DEPOSIT_IN_TRANSIT
                    ? bookBalance.add(item.amount())
                    : bookBalance.subtract(item.amount());
        }
        UUID equity = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, EQUITY_KEY, asOfDate.atStartOfDay());
        JournalEntryResponse posted = post(
                asOfDate,
                "Opening balance of bank account " + code + " at " + asOfDate,
                lines(glAccountId, equity, code, asOfDate, statementBalance, bookBalance, items));

        BankOpeningBalance opening = new BankOpeningBalance();
        opening.setGlAccountId(glAccountId);
        opening.setAsOfDate(asOfDate);
        opening.setStatementBalance(statementBalance);
        opening.setBookBalance(bookBalance);
        opening.setCurrencyCode(currencyCode);
        opening.setJournalEntryId(posted.getJournalEntryId());
        opening.setJustification(justification);
        opening.setActor(RegisterFloatServiceImpl.currentActor());
        opening.setRequestId(requestId);
        opening.setRequestHash(hash);
        BankOpeningBalance saved;
        try {
            // The request unique is the backstop for one requestId racing itself.
            saved = openings.saveAndFlush(opening);
        } catch (DataIntegrityViolationException e) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + requestId + " was concurrently used by another command");
        }
        audit(saved, items.size());
        log.info(
                "Bank account {} opened at {}: statement {}, book {} ({} outstanding items, JE {})",
                code,
                asOfDate,
                statementBalance,
                bookBalance,
                items.size(),
                posted.getJournalEntryId());
        return new Outcome(response(saved, code, posted.getEntryNumber(), false), false);
    }

    // ---- posting --------------------------------------------------------------------------------------------

    private static List<JournalEntryCreateRequest.JournalEntryLineRequest> lines(
            UUID bank,
            UUID equity,
            String code,
            LocalDate asOfDate,
            BigDecimal statementBalance,
            BigDecimal bookBalance,
            List<BankOpeningBalanceRequest.OutstandingItem> items) {
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        // A zero amount posts no line: a zero statement balance with items, or items that net to the statement.
        if (statementBalance.signum() != 0) {
            lines.add(line(bank, statementBalance, "Bank statement balance at " + asOfDate, null));
        }
        for (BankOpeningBalanceRequest.OutstandingItem item : items) {
            boolean deposit = item.type() == BankOpeningItemType.DEPOSIT_IN_TRANSIT;
            String reference = Objects.requireNonNull(item.reference()).trim();
            String description = deposit
                    ? "Deposit in transit " + reference + " made " + item.itemDate()
                    : "Outstanding check " + reference + " written " + item.itemDate();
            lines.add(line(
                    bank,
                    deposit ? item.amount() : item.amount().negate(),
                    description,
                    Map.of(
                            OpeningItemLineDimensions.TYPE,
                            String.valueOf(item.type()),
                            OpeningItemLineDimensions.REFERENCE,
                            reference,
                            OpeningItemLineDimensions.ITEM_DATE,
                            String.valueOf(item.itemDate()))));
        }
        if (bookBalance.signum() != 0) {
            lines.add(line(equity, bookBalance.negate(), "Opening balance equity for bank account " + code, null));
        }
        return lines;
    }

    /** A line of {@code signed} (debit when positive, credit when negative). */
    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal signed, String description, @Nullable Map<String, String> dimensions) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(signed.signum() > 0 ? signed : BigDecimal.ZERO)
                .creditAmount(signed.signum() < 0 ? signed.negate() : BigDecimal.ZERO)
                .description(description)
                .dimensions(dimensions)
                .build();
    }

    private JournalEntryResponse post(
            LocalDate date, String description, List<JournalEntryCreateRequest.JournalEntryLineRequest> lines) {
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(date.atStartOfDay())
                .sourceEventId(sourceEventId(UUIDv7Generator.generate()))
                .sourceEventType(POSTING_CATEGORY)
                .description(description)
                .lines(lines)
                .build());
        // No override: the opening is dated in an open period, whatever the caller holds (as the go-live float).
        return journalEntryService.postJournalEntry(created.getJournalEntryId(), null);
    }

    /** The source event of an opening's journal entry, derived from the command's own posting key. */
    public static @NonNull UUID sourceEventId(@NonNull UUID postingKey) {
        return UUID.nameUUIDFromBytes((POSTING_CATEGORY + ":" + postingKey).getBytes(StandardCharsets.UTF_8));
    }

    // ---- rules ----------------------------------------------------------------------------------------------

    /** An active BANK_CASH account in functional currency (ADR-0067), else 422 ACCOUNT_NOT_ELIGIBLE. */
    private void requireEligible(@Nullable GLAccount account, UUID glAccountId, LocalDate asOfDate) {
        if (account == null) {
            throw notEligible("GL account " + glAccountId + " does not exist");
        }
        if (account.getAccountSubtype() != AccountSubtype.BANK_CASH) {
            throw notEligible("Account " + account.getAccountCode() + " is not a bank account");
        }
        LocalDateTime at = asOfDate.atStartOfDay();
        boolean active = (account.getActivationDate() == null
                        || !account.getActivationDate().isAfter(at))
                && (account.getDeactivationDate() == null
                        || account.getDeactivationDate().isAfter(at));
        if (!active) {
            throw notEligible("Bank account " + account.getAccountCode() + " is not active on " + asOfDate);
        }
        bankAccountCurrencies
                .currencyOf(glAccountId)
                .filter(ledgerCurrency::isForeign)
                .ifPresent(currency -> {
                    throw notEligible("Bank account " + account.getAccountCode() + " is in " + currency
                            + ", not the functional currency " + ledgerCurrency.code());
                });
    }

    /**
     * The opening comes first: no standing posted line on the account dated on or before {@code asOfDate} (a
     * reversal pair does not stand) and no committed statement starting on or before it. Later lines are allowed,
     * so the opening can be entered after trading has started.
     */
    private void requireFirst(UUID glAccountId, String code, LocalDate asOfDate) {
        if (journalLines.countStandingPostedLinesBefore(
                        glAccountId, asOfDate.plusDays(1).atStartOfDay())
                > 0) {
            throw new CashSetupException(
                    CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST,
                    "Bank account " + code + " has posted entries dated on or before " + asOfDate
                            + "; the opening balance must come first");
        }
        if (statementCoverage.startsOnOrBefore(glAccountId, asOfDate)) {
            throw new CashSetupException(
                    CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST,
                    "Bank account " + code + " has a committed bank statement starting on or before " + asOfDate
                            + "; the opening balance must come first");
        }
    }

    private void requireMinorUnit(@Nullable BigDecimal amount, String field) {
        int digits = Math.max(0, Currency.getInstance(ledgerCurrency.code()).getDefaultFractionDigits());
        if (amount != null && amount.stripTrailingZeros().scale() > digits) {
            throw InvalidRequestParameterException.forField(
                    field,
                    field + " has more decimal places than " + ledgerCurrency.code() + " allows (" + digits + ")");
        }
    }

    private static CashSetupException notEligible(String message) {
        return new CashSetupException(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE, message);
    }

    // ---- results --------------------------------------------------------------------------------------------

    private void audit(BankOpeningBalance saved, int itemCount) {
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(AUDIT_ENTITY_TYPE);
        audit.setEntityId(saved.getBankOpeningBalanceId());
        audit.setOperation(AUDIT_ESTABLISH);
        audit.setUserId(saved.getActor());
        audit.setJustification(saved.getJustification());
        audit.setNewValue("glAccountId=" + saved.getGlAccountId() + ";asOfDate=" + saved.getAsOfDate()
                + ";statementBalance=" + saved.getStatementBalance().toPlainString() + ";bookBalance="
                + saved.getBookBalance().toPlainString() + ";currencyCode=" + saved.getCurrencyCode()
                + ";outstandingItems=" + itemCount + ";requestId="
                + saved.getRequestId() + ";journalEntryId=" + saved.getJournalEntryId());
        auditLogs.save(audit);
    }

    private Outcome replay(BankOpeningBalance original, String hash) {
        if (!Objects.equals(original.getRequestHash(), hash)) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + original.getRequestId() + " was already used with a different payload");
        }
        String number = journalEntryService
                .getJournalEntry(original.getJournalEntryId())
                .getEntryNumber();
        String code = glAccounts
                .findById(original.getGlAccountId())
                .map(GLAccount::getAccountCode)
                .orElse(null);
        return new Outcome(response(original, code, number, true), true);
    }

    /** The response, its items read from the entry's own lines so each carries the line id to register. */
    private BankOpeningBalanceResponse response(
            BankOpeningBalance opening, @Nullable String code, @Nullable String entryNumber, boolean replayed) {
        List<BankOpeningBalanceResponse.Item> items =
                journalLines.findByJournalEntry_JournalEntryId(opening.getJournalEntryId()).stream()
                        .filter(l -> opening.getGlAccountId().equals(l.getGlAccountId()))
                        .filter(l -> l.getDimensions() != null
                                && l.getDimensions().get(OpeningItemLineDimensions.TYPE) != null)
                        .sorted(Comparator.comparing(
                                JournalEntryLine::getLineNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(BankOpeningBalanceServiceImpl::item)
                        .toList();
        return new BankOpeningBalanceResponse(
                opening.getGlAccountId(),
                code,
                opening.getAsOfDate(),
                opening.getStatementBalance(),
                opening.getBookBalance(),
                opening.getCurrencyCode(),
                items,
                opening.getJournalEntryId(),
                entryNumber,
                replayed);
    }

    private static BankOpeningBalanceResponse.Item item(JournalEntryLine line) {
        Map<String, String> dimensions = line.getDimensions();
        BigDecimal debit = line.getDebitAmount() == null ? BigDecimal.ZERO : line.getDebitAmount();
        return new BankOpeningBalanceResponse.Item(
                BankOpeningItemType.valueOf(dimensions.get(OpeningItemLineDimensions.TYPE)),
                dimensions.get(OpeningItemLineDimensions.REFERENCE),
                OpeningItemLineDimensions.itemDate(dimensions).orElse(null),
                debit.signum() > 0 ? debit : line.getCreditAmount(),
                line.getLineId());
    }

    /** The fields that steer the command, so a reused requestId with another body is a conflict. */
    private static String hash(
            UUID glAccountId,
            LocalDate asOfDate,
            BigDecimal statementBalance,
            String currencyCode,
            List<BankOpeningBalanceRequest.OutstandingItem> items,
            String justification) {
        RegisterFloatServiceImpl.Hash hash = new RegisterFloatServiceImpl.Hash()
                .field("BANK_OPENING_BALANCE")
                .field(glAccountId)
                .field(asOfDate)
                .field(statementBalance)
                .field(currencyCode)
                .field(items.size());
        for (BankOpeningBalanceRequest.OutstandingItem item : items) {
            hash.field(item.type())
                    .field(Objects.requireNonNull(item.reference()).trim())
                    .field(item.itemDate())
                    .field(item.amount());
        }
        return hash.field(justification).digest();
    }
}
