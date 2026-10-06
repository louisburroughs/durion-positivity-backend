package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedPayablesRow;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesRow;
import com.positivity.accounting.internal.dto.AgingSummary;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.EntryNumberGapCheck;
import com.positivity.accounting.internal.dto.GeneralLedgerAccountSection;
import com.positivity.accounting.internal.dto.GeneralLedgerLine;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.JournalLineDrilldownResponse;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import com.positivity.accounting.internal.dto.TrialBalanceAccountTotal;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.CreditMemoTax;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.enums.NormalSide;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.InvalidDateRangeException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for financial reporting (Income Statement, Balance
 * Sheet).
 * Aggregates posted journal entries using configurable Chart of Accounts
 * mappings; an account with a posted balance and no mapping for a statement lands
 * on one of the computed lines below, so no balance is left off a statement
 * (CAP:550 S35, #2524; SPEC-accounting-workspace §5.4, §9.5).
 *
 * @author Louis Burroughs
 * @since 2025-01-01
 */
@Service
@Transactional(readOnly = true)
public class FinancialReportingServiceImpl implements FinancialReportingService {

    private static final Logger log = LoggerFactory.getLogger(FinancialReportingServiceImpl.class);

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01"); // 1 cent tolerance for rounding

    // Statement line codes the service computes (CAP:550 S35, #2524). They are never seeded: each
    // collects the accounts that have a posted balance and no mapping for the statement, by account
    // type. BS_IN_THE_BANK is also a named line (1000): any further BANK_CASH account joins it (AW9).
    static final String BS_IN_THE_BANK = "BS_IN_THE_BANK";
    static final String BS_OTHER_ASSETS = "BS_OTHER_ASSETS";
    static final String BS_OTHER_LIABILITIES = "BS_OTHER_LIABILITIES";
    static final String BS_OTHER_EQUITY = "BS_OTHER_EQUITY";
    static final String BS_PROFIT_NOT_YET_CLOSED = "BS_PROFIT_NOT_YET_CLOSED";
    static final String IS_OTHER_INCOME = "IS_OTHER_INCOME";
    static final String IS_OTHER_EXPENSES = "IS_OTHER_EXPENSES";

    /** The statement each computed line belongs to, in the order the lines are appended. */
    private static final Map<String, StatementType> COMPUTED_LINES;

    static {
        Map<String, StatementType> computed = new LinkedHashMap<>();
        computed.put(BS_IN_THE_BANK, StatementType.BALANCE_SHEET);
        computed.put(BS_OTHER_ASSETS, StatementType.BALANCE_SHEET);
        computed.put(BS_OTHER_LIABILITIES, StatementType.BALANCE_SHEET);
        computed.put(BS_OTHER_EQUITY, StatementType.BALANCE_SHEET);
        computed.put(BS_PROFIT_NOT_YET_CLOSED, StatementType.BALANCE_SHEET);
        computed.put(IS_OTHER_INCOME, StatementType.INCOME_STATEMENT);
        computed.put(IS_OTHER_EXPENSES, StatementType.INCOME_STATEMENT);
        COMPUTED_LINES = Map.copyOf(computed);
    }

    /** Append order of the computed lines after the named ones. */
    private static final List<String> COMPUTED_LINE_ORDER = List.of(
            BS_IN_THE_BANK,
            BS_OTHER_ASSETS,
            BS_OTHER_LIABILITIES,
            BS_OTHER_EQUITY,
            BS_PROFIT_NOT_YET_CLOSED,
            IS_OTHER_INCOME,
            IS_OTHER_EXPENSES);

    /**
     * Prefix of journal-entry monthly sequence scopes ({@code JE-{YYYYMM}}),
     * matching {@code JournalEntryServiceImpl#entryNumberScopeKey}.
     */
    private static final String ENTRY_NUMBER_SCOPE_PREFIX = "JE-";

    /**
     * pos-invoice lifecycle statuses in which an invoice participates in AR
     * (mirrors {@code InvoiceBalanceCalculator}'s eligibility set). Used to load
     * candidate invoices for the Aged Receivables report.
     */
    private static final Set<String> AR_ELIGIBLE_STATUSES = Set.of("FINALIZED", "POSTED");

    /**
     * Vendor-bill statuses the Aged Payables report loads: approved bills, which are aged, and the
     * bills not yet approved, which are reported beside the buckets and never aged (AW11; CAP:550
     * S35, #2524). Settled ({@code PAID}), voided, rejected and currency-held ({@code CURRENCY_HOLD},
     * not a ledger-currency payable, #2309) bills stay out.
     */
    private static final Set<VendorBillStatus> OPEN_PAYABLE_STATUSES =
            Set.of(VendorBillStatus.PENDING_RECEIPT_MATCH, VendorBillStatus.MATCH_EXCEPTION, VendorBillStatus.APPROVED);

    /** The one status whose open bills are aged. */
    private static final Set<VendorBillStatus> APPROVED_PAYABLE_STATUSES = Set.of(VendorBillStatus.APPROVED);

    /**
     * Statuses of open bills not yet approved, reported beside the buckets and never aged (AW11). S12
     * (#2509) adds {@code AWAITING_APPROVAL} here. A bill in any other status is neither aged nor
     * counted as unapproved.
     */
    private static final Set<VendorBillStatus> UNAPPROVED_PAYABLE_STATUSES =
            Set.of(VendorBillStatus.PENDING_RECEIPT_MATCH, VendorBillStatus.MATCH_EXCEPTION);

    /**
     * Chart-of-accounts code of the single Sales-Tax Payable account (D-4: one GL
     * account, report-time jurisdiction aggregation). The T8 report reconciles its
     * total net tax against this account's credit-normal period activity.
     */
    private static final String SALES_TAX_PAYABLE_ACCOUNT_CODE = "2200";

    /** Reconciliation tolerance for the GL-drift flag (1 cent). */
    private static final BigDecimal RECON_TOLERANCE = new BigDecimal("0.01");

    private final JournalEntryRepository journalEntryRepository;
    private final StatementLineMappingRepository statementLineMappingRepository;
    private final AccountingSequenceRepository accountingSequenceRepository;
    private final GLAccountRepository glAccountRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtInvoiceTaxRepository extInvoiceTaxRepository;
    private final CreditMemoRepository creditMemoRepository;
    private final CreditMemoTaxRepository creditMemoTaxRepository;
    private final VendorBillRepository vendorBillRepository;
    private final APPaymentAllocationRepository apPaymentAllocationRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final DatabaseDialectSupport databaseDialectSupport;
    private final DisplayReferenceResolver displayReferenceResolver;
    private final Clock clock;
    private final LedgerCurrency ledgerCurrency;

    public FinancialReportingServiceImpl(
            JournalEntryRepository journalEntryRepository,
            StatementLineMappingRepository statementLineMappingRepository,
            AccountingSequenceRepository accountingSequenceRepository,
            GLAccountRepository glAccountRepository,
            ExtInvoiceRepository extInvoiceRepository,
            ExtInvoiceTaxRepository extInvoiceTaxRepository,
            CreditMemoRepository creditMemoRepository,
            CreditMemoTaxRepository creditMemoTaxRepository,
            VendorBillRepository vendorBillRepository,
            APPaymentAllocationRepository apPaymentAllocationRepository,
            InvoiceBalanceCalculator invoiceBalanceCalculator,
            DatabaseDialectSupport databaseDialectSupport,
            DisplayReferenceResolver displayReferenceResolver,
            Clock clock,
            LedgerCurrency ledgerCurrency) {
        this.journalEntryRepository = journalEntryRepository;
        this.statementLineMappingRepository = statementLineMappingRepository;
        this.accountingSequenceRepository = accountingSequenceRepository;
        this.glAccountRepository = glAccountRepository;
        this.extInvoiceRepository = extInvoiceRepository;
        this.extInvoiceTaxRepository = extInvoiceTaxRepository;
        this.creditMemoRepository = creditMemoRepository;
        this.creditMemoTaxRepository = creditMemoTaxRepository;
        this.vendorBillRepository = vendorBillRepository;
        this.apPaymentAllocationRepository = apPaymentAllocationRepository;
        this.invoiceBalanceCalculator = invoiceBalanceCalculator;
        this.databaseDialectSupport = databaseDialectSupport;
        this.displayReferenceResolver = displayReferenceResolver;
        this.clock = clock;
        this.ledgerCurrency = ledgerCurrency;
    }

    @Override
    public @NonNull IncomeStatementReport generateIncomeStatement(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new InvalidDateRangeException("End date cannot be before start date");
        }

        log.info("Generating income statement for period {} to {}", startDate, endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        List<StatementLineMapping> mappings =
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.INCOME_STATEMENT);
        StatementFigures figures = computeStatement(
                StatementType.INCOME_STATEMENT,
                mappings,
                statementAccounts(StatementType.INCOME_STATEMENT, startDateTime, endDateTime));

        // Totals follow the account's type, never the line code (issue #2394): each account on the
        // statement counts once, on its own normal side, whatever line it sits on and whatever
        // operation presents it there. A line that mixes revenue and expense accounts therefore
        // still splits correctly between the two totals.
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        for (StatementAccount account : figures.accountsOnStatement()) {
            if (account.type() == AccountType.REVENUE) {
                totalRevenue = totalRevenue.add(account.debitsMinusCredits().negate());
            } else if (account.type() == AccountType.EXPENSE) {
                totalExpenses = totalExpenses.add(account.debitsMinusCredits());
            } else {
                log.warn(
                        "Income statement maps account {} of type {}; it is shown on its line but counted"
                                + " in neither total",
                        account.id(),
                        account.type());
            }
        }

        BigDecimal netIncome = totalRevenue.subtract(totalExpenses);

        log.info(
                "Income statement generated: revenue={}, expenses={}, netIncome={}",
                totalRevenue,
                totalExpenses,
                netIncome);

        return IncomeStatementReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .lineItems(figures.lineItems())
                .totalRevenue(totalRevenue)
                .totalExpenses(totalExpenses)
                .netIncome(netIncome)
                .generatedAt(Instant.now(clock))
                .build();
    }

    @Override
    public @NonNull BalanceSheetReport generateBalanceSheet(@NonNull LocalDate asOfDate) {

        log.info("Generating balance sheet as of {}", asOfDate);

        LocalDateTime asOfDateTime = asOfDate.atTime(LocalTime.MAX);

        List<StatementLineMapping> mappings =
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.BALANCE_SHEET);
        StatementFigures figures = computeStatement(
                StatementType.BALANCE_SHEET,
                mappings,
                statementAccounts(StatementType.BALANCE_SHEET, null, asOfDateTime));

        // Totals follow the account's type, never the line code (issue #2394): each account on the
        // statement counts once, on the side of the equation it belongs to, whatever operation
        // presents it on its line. Revenue and expense accounts are earnings not yet closed to equity
        // (mapped, or collected on BS_PROFIT_NOT_YET_CLOSED), so they count toward equity as credits
        // minus debits. Every account with a balance is on some line, so the equation covers them all.
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;
        for (StatementAccount account : figures.accountsOnStatement()) {
            if (account.type() == null) {
                log.warn(
                        "Balance sheet maps account {} whose type is unknown; it is shown on its line but"
                                + " counted in no total",
                        account.id());
                continue;
            }
            switch (account.type()) {
                case ASSET -> totalAssets = totalAssets.add(account.debitsMinusCredits());
                case LIABILITY ->
                    totalLiabilities =
                            totalLiabilities.add(account.debitsMinusCredits().negate());
                case EQUITY, REVENUE, EXPENSE ->
                    totalEquity = totalEquity.add(account.debitsMinusCredits().negate());
            }
        }

        // Validate balance sheet equation: Assets = Liabilities + Equity (within
        // tolerance)
        BigDecimal difference =
                totalAssets.subtract(totalLiabilities.add(totalEquity)).abs();
        boolean balanced = difference.compareTo(BALANCE_TOLERANCE) <= 0;

        if (!balanced) {
            log.warn(
                    "Balance sheet equation not balanced: assets={}, liabilities+equity={}, diff={}",
                    totalAssets,
                    totalLiabilities.add(totalEquity),
                    difference);
        } else {
            log.info(
                    "Balance sheet generated: assets={}, liabilities={}, equity={}, balanced={}",
                    totalAssets,
                    totalLiabilities,
                    totalEquity,
                    balanced);
        }

        return BalanceSheetReport.builder()
                .asOfDate(asOfDate)
                .lineItems(figures.lineItems())
                .totalAssets(totalAssets)
                .totalLiabilities(totalLiabilities)
                .totalEquity(totalEquity)
                .balanced(balanced)
                .generatedAt(Instant.now(clock))
                .build();
    }

    @Override
    public @NonNull TrialBalanceReport generateTrialBalance(@NonNull LocalDate asOf) {

        log.info("Generating trial balance as of {}", asOf);

        LocalDateTime asOfDateTime = asOf.atTime(LocalTime.MAX);

        // Per-account aggregation happens in the database (grouped JPQL over
        // POSTED lines, ordered by account code) — the line set is never
        // materialized in memory.
        List<TrialBalanceAccountTotal> accountTotals =
                journalEntryRepository.sumPostedDebitsCreditsByAccountAsOf(asOfDateTime);

        List<TrialBalanceRow> rows = accountTotals.stream()
                .map(total -> TrialBalanceRow.builder()
                        .accountId(total.glAccountId().toString())
                        .accountNumber(total.accountCode())
                        .accountName(total.accountName())
                        .totalDebit(total.totalDebit())
                        .totalCredit(total.totalCredit())
                        .balance(total.totalDebit().subtract(total.totalCredit()))
                        .build())
                .toList();

        BigDecimal totalDebit =
                rows.stream().map(TrialBalanceRow::getTotalDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit =
                rows.stream().map(TrialBalanceRow::getTotalCredit).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Computed, never assumed: an unbalanced ledger (A1 constraint
        // violation) must surface operationally as balanced = false.
        boolean balanced = totalDebit.compareTo(totalCredit) == 0;
        if (!balanced) {
            log.warn(
                    "Trial balance NOT balanced as of {}: totalDebit={}, totalCredit={}, diff={}",
                    asOf,
                    totalDebit,
                    totalCredit,
                    totalDebit.subtract(totalCredit));
        }

        // Per contract: an empty ledger reports an empty gap footnote (and the
        // gap query is PostgreSQL-only, so it is not touched needlessly).
        List<EntryNumberGapCheck> entryNumberGaps = rows.isEmpty() ? List.of() : checkEntryNumberGaps(asOf);

        log.info(
                "Trial balance generated as of {}: accounts={}, totalDebit={}, totalCredit={}, balanced={}, gapScopes={}",
                asOf,
                rows.size(),
                totalDebit,
                totalCredit,
                balanced,
                entryNumberGaps.size());

        return TrialBalanceReport.builder()
                .asOfDate(asOf)
                .generatedAt(Instant.now(clock))
                .rows(rows)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .balanced(balanced)
                .entryNumberGaps(entryNumberGaps)
                .build();
    }

    /**
     * Run the entry-number gap-check (story A2's
     * {@code AccountingSequenceRepository#findMissingEntryNumbers}) for every
     * monthly journal-entry sequence scope up to and including the as-of
     * month, keeping only scopes that actually have missing numbers. Scope
     * keys are {@code JE-{YYYYMM}} on the entry's transaction month, so the
     * lexicographic comparison against the as-of month boundary is a correct
     * chronological cut. A clean ledger yields an empty footnote.
     *
     * <p>The gap query is PostgreSQL-only and fails to parse on H2, so on any
     * other dialect (the dev profile and the H2-backed Spring Boot tests) the
     * footnote is reported empty rather than executing the query. This is the
     * enforcement of the constraint that
     * {@code AccountingSequenceRepository#findMissingEntryNumbers} documents:
     * before it existed, whether the report blew up on H2 depended on whether
     * any {@code accounting_sequence} row had handed out a number yet.
     */
    private List<EntryNumberGapCheck> checkEntryNumberGaps(LocalDate asOf) {
        if (!databaseDialectSupport.isPostgreSql()) {
            log.debug("Skipping entry-number gap footnote as of {}: the gap query requires PostgreSQL", asOf);
            return List.of();
        }

        String asOfScopeBoundary =
                String.format("%s%04d%02d", ENTRY_NUMBER_SCOPE_PREFIX, asOf.getYear(), asOf.getMonthValue());

        return accountingSequenceRepository.findAllByOrderByScopeKeyAsc().stream()
                .map(AccountingSequence::getScopeKey)
                .filter(scopeKey ->
                        scopeKey.startsWith(ENTRY_NUMBER_SCOPE_PREFIX) && scopeKey.compareTo(asOfScopeBoundary) <= 0)
                .map(scopeKey -> EntryNumberGapCheck.builder()
                        .scopeKey(scopeKey)
                        .missingNumbers(accountingSequenceRepository.findMissingEntryNumbers(scopeKey))
                        .build())
                .filter(gapCheck -> !gapCheck.getMissingNumbers().isEmpty())
                .toList();
    }

    @Override
    public @NonNull List<AccountDrilldownResponse> drilldownToAccounts(
            @NonNull String statementLineCode, @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new InvalidDateRangeException("End date cannot be before start date");
        }

        log.info(
                "Drilling down statement line {} to accounts for period {} to {}",
                statementLineCode,
                startDate,
                endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // The accounts mapped to this line, and the statement the line belongs to: a named line's
        // statement comes from its mappings, a computed line's from the table above.
        List<StatementLineMapping> lineMappings =
                statementLineMappingRepository.findByStatementLineCode(statementLineCode);
        StatementType statementType = lineMappings.isEmpty()
                ? COMPUTED_LINES.get(statementLineCode)
                : lineMappings.get(0).getStatementType();
        if (statementType == null) {
            log.warn("No account mappings found for statement line: {}", statementLineCode);
            return List.of();
        }

        // A balance-sheet line reports as-of balances at endDate; the others period movement, so
        // in either case the rows add up to the line they expand.
        List<StatementAccount> withActivity = statementAccounts(
                statementType, statementType == StatementType.BALANCE_SHEET ? null : startDateTime, endDateTime);
        Map<UUID, StatementAccount> activityById = new HashMap<>();
        for (StatementAccount account : withActivity) {
            activityById.put(account.id(), account);
        }

        List<AccountDrilldownResponse> rows = new ArrayList<>();

        // Named: every mapped account, with the GL account's own name, code and type; an account
        // without activity shows a zero row.
        Set<UUID> mappedIds =
                lineMappings.stream().map(StatementLineMapping::getGlAccountId).collect(Collectors.toSet());
        Map<UUID, GLAccount> mappedAccounts = mappedIds.isEmpty()
                ? Map.of()
                : glAccountRepository.findAllById(mappedIds).stream()
                        .collect(Collectors.toMap(GLAccount::getGlAccountId, account -> account));
        for (StatementLineMapping mapping : lineMappings) {
            GLAccount account = mappedAccounts.get(mapping.getGlAccountId());
            StatementAccount activity = activityById.get(mapping.getGlAccountId());
            BigDecimal balance = activity != null ? activity.debitsMinusCredits() : BigDecimal.ZERO;
            AccountType accountType = account != null ? account.getAccountType() : null;
            rows.add(AccountDrilldownResponse.builder()
                    .accountId(mapping.getGlAccountId().toString())
                    .accountCode(account != null ? account.getAccountCode() : mapping.getAccountName())
                    .accountName(account != null ? account.getAccountName() : mapping.getAccountName())
                    .accountType(accountType)
                    .balance(statementContribution(balance, accountType, mapping.getOperation()))
                    .statementLineCode(statementLineCode)
                    .build());
        }

        // Computed: the accounts with a balance, no mapping for the statement, that this line
        // collects (BS_IN_THE_BANK collects further BANK_CASH accounts beside its named one).
        if (COMPUTED_LINES.containsKey(statementLineCode)) {
            Set<UUID> mappedForStatement =
                    statementLineMappingRepository
                            .findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(statementType)
                            .stream()
                            .map(StatementLineMapping::getGlAccountId)
                            .collect(Collectors.toSet());
            for (StatementAccount account : withActivity) {
                if (mappedForStatement.contains(account.id())
                        || account.debitsMinusCredits().signum() == 0) {
                    continue;
                }
                if (statementLineCode.equals(computedLineFor(statementType, account.type(), account.subtype()))) {
                    rows.add(AccountDrilldownResponse.builder()
                            .accountId(account.id().toString())
                            .accountCode(account.code())
                            .accountName(account.name())
                            .accountType(account.type())
                            .balance(computedContribution(statementLineCode, account))
                            .statementLineCode(statementLineCode)
                            .build());
                }
            }
        }

        if (rows.isEmpty()) {
            log.warn("No account mappings found for statement line: {}", statementLineCode);
        }
        return rows;
    }

    @Override
    public @NonNull List<JournalLineDrilldownResponse> drilldownToJournalLines(
            @NonNull String accountId, @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new InvalidDateRangeException("End date cannot be before start date");
        }

        UUID glAccountId;
        try {
            glAccountId = UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            String maskedId = accountId.length() > 8 ? accountId.substring(0, 8) + "..." : accountId;
            log.warn("Invalid UUID format for accountId: {}", maskedId);
            throw new InvalidRequestParameterException("Invalid UUID format for accountId", e);
        }

        String maskedAccountId = accountId.substring(0, 8) + "...";
        log.info("Drilling down account {} to journal lines for period {} to {}", maskedAccountId, startDate, endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // Find all posted journal entries affecting this account
        List<JournalEntry> entries =
                journalEntryRepository.findPostedEntriesForAccount(glAccountId, startDateTime, endDateTime);

        // Extract journal lines for this account
        return entries.stream()
                .flatMap(entry -> entry.getLines().stream()
                        .filter(line -> glAccountId.equals(line.getGlAccountId()))
                        .map(line -> JournalLineDrilldownResponse.builder()
                                .journalEntryId(entry.getJournalEntryId())
                                .transactionDate(entry.getTransactionDate().toLocalDate())
                                .description(line.getDescription())
                                .debitAmount(line.getDebitAmount())
                                .creditAmount(line.getCreditAmount())
                                .sourceEventId(entry.getSourceEventId())
                                .sourceEventType(entry.getSourceEventType())
                                .build()))
                .toList();
    }

    // ========================================================================
    // Story G2 (Issue #960) — General Ledger + Aged AR/AP.
    // ========================================================================

    @Override
    public @NonNull GeneralLedgerReport generateGeneralLedger(
            @Nullable String accountId, @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new InvalidDateRangeException("End date cannot be before start date");
        }

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // Collect in-period ledger lines (POSTED and REVERSED entries, never DRAFT; #2308) grouped
        // by account. Ordering within a section is applied later; a reversed original stays beside
        // its POSTED reversal and the pair nets to zero with no reversal-linkage special-casing.
        Map<UUID, List<JournalEntryLine>> linesByAccount = new LinkedHashMap<>();
        if (accountId != null) {
            UUID glAccountId = parseAccountId(accountId);
            log.info("Generating general ledger for account {} for period {} to {}", glAccountId, startDate, endDate);
            List<JournalEntry> entries =
                    journalEntryRepository.findPostedEntriesForAccount(glAccountId, startDateTime, endDateTime);
            collectAccountLines(entries, glAccountId, linesByAccount);
        } else {
            log.info("Generating general ledger for all accounts for period {} to {}", startDate, endDate);
            List<JournalEntry> entries = journalEntryRepository.findPostedEntriesInRange(startDateTime, endDateTime);
            collectAccountLines(entries, null, linesByAccount);
        }

        if (linesByAccount.isEmpty()) {
            return GeneralLedgerReport.builder()
                    .accountId(accountId)
                    .startDate(startDate)
                    .endDate(endDate)
                    .generatedAt(Instant.now(clock))
                    .accounts(List.of())
                    .totalDebit(BigDecimal.ZERO)
                    .totalCredit(BigDecimal.ZERO)
                    .build();
        }

        // Account metadata (number/name) resolved in one batch to avoid N+1 lazy
        // loads and to key section ordering off the chart-of-accounts code.
        Map<UUID, GLAccount> accountsById = glAccountRepository.findAllById(linesByAccount.keySet()).stream()
                .collect(Collectors.toMap(GLAccount::getGlAccountId, account -> account));

        List<GeneralLedgerAccountSection> sections = new ArrayList<>();
        for (Map.Entry<UUID, List<JournalEntryLine>> accountEntry : linesByAccount.entrySet()) {
            UUID glAccountId = accountEntry.getKey();
            GLAccount account = accountsById.get(glAccountId);
            sections.add(buildAccountSection(glAccountId, account, accountEntry.getValue(), startDateTime));
        }

        // Sections ordered by account number (chart-of-accounts code).
        sections.sort(Comparator.comparing(
                GeneralLedgerAccountSection::getAccountNumber, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal grandDebit = sections.stream()
                .map(GeneralLedgerAccountSection::getTotalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grandCredit = sections.stream()
                .map(GeneralLedgerAccountSection::getTotalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info(
                "General ledger generated: sections={}, totalDebit={}, totalCredit={}",
                sections.size(),
                grandDebit,
                grandCredit);

        return GeneralLedgerReport.builder()
                .accountId(accountId)
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(Instant.now(clock))
                .accounts(sections)
                .totalDebit(grandDebit)
                .totalCredit(grandCredit)
                .build();
    }

    @Override
    public @NonNull AgedReceivablesReport generateAgedReceivables(@NonNull LocalDate asOfDate) {

        log.info("Generating aged receivables as of {}", asOfDate);

        // As-of semantics (finding 10): an item whose DOCUMENT (invoice) date is after asOfDate
        // did not exist yet and is excluded; everything that did exist is bucketed on asOfDate,
        // not-yet-due items in `notYetDue`. Aging basis is the due date,
        // falling back to the document date — the same rule generateAgedPayables uses.
        // KNOWN LIMITATION: the open balance is the invoice's CURRENT balance
        // (InvoiceBalanceCalculator derives it from all payment applications/reversals/credit-memos
        // to date), not a balance reconstructed as-of asOfDate, so a back-dated asOfDate reflects
        // today's balances against historical aging dates. A true historical-balance
        // reconstruction is deferred (needs point-in-time application replay).
        // AR-eligible invoices; open balance is derived from accounting-owned
        // facts (payment applications, reversals, credit memos) via the shared
        // InvoiceBalanceCalculator — never fetched from another service.
        List<ExtInvoice> invoices = extInvoiceRepository.findByStatusIn(AR_ELIGIBLE_STATUSES);
        // The CASH walk-in account is not a customer to age (#2508, §4.4 item 2): its open sales are the unpaid
        // walk-in sales read's, so aged receivables plus that read's balance is the AR subledger. The ledger
        // itself is unchanged (ADR-0047).
        Set<UUID> walkInPartyIds = invoiceBalanceCalculator.walkInPartyIds();

        Map<UUID, AgingBuckets> byCustomer = new LinkedHashMap<>();
        for (ExtInvoice invoice : invoices) {
            if (InvoiceBalanceCalculator.isWalkIn(invoice, walkInPartyIds)) {
                continue;
            }
            BigDecimal balanceDue = invoiceBalanceCalculator.balanceDue(invoice);
            // The open-invoice rule the receivables worklist uses too (#2502, BR-2), and the same
            // currency-scale rounding, so a customer's open invoices add up to this report's total.
            if (!InvoiceBalanceCalculator.isOpenReceivable(invoice, balanceDue, ledgerCurrency.code())) {
                continue; // only positive-open items contribute
            }
            BigDecimal openBalance = InvoiceBalanceCalculator.atCurrencyScale(balanceDue, ledgerCurrency.code());
            UUID customerId = parsePartyId(invoice.getPartyId(), invoice.getInvoiceId());
            if (customerId == null) {
                continue; // non-UUID party cannot be represented in the contract's UUID field
            }
            if (InvoiceBalanceCalculator.receivableDocumentDate(invoice).isAfter(asOfDate)) {
                // Raised after asOfDate — the invoice did not exist yet (finding 10). This
                // existence test is against the DOCUMENT date, never the aging date: a
                // not-yet-due invoice already exists and must still be reported.
                continue;
            }
            LocalDate agingDate = InvoiceBalanceCalculator.receivableAgingDate(invoice);
            long daysPastDue = ChronoUnit.DAYS.between(agingDate, asOfDate);
            byCustomer.computeIfAbsent(customerId, key -> new AgingBuckets()).add(daysPastDue, openBalance);
        }

        // Names and customer numbers from accounting's own replica (ADR-0044 R3), one query for the
        // whole report; a party the replica has not seen gets null, never its id (P8).
        Map<UUID, ResolvedDisplayReference> customers =
                displayReferenceResolver.resolve(DisplayReferenceType.CUSTOMER, byCustomer.keySet());

        List<AgedReceivablesRow> rows = byCustomer.entrySet().stream()
                .map(entry -> {
                    AgingBuckets buckets = entry.getValue();
                    ResolvedDisplayReference customer =
                            customers.getOrDefault(entry.getKey(), ResolvedDisplayReference.EMPTY);
                    return AgedReceivablesRow.builder()
                            .customerId(entry.getKey())
                            .customerName(customer.displayName())
                            .customerReference(customer.displayReference())
                            .notYetDue(buckets.notYetDue)
                            .days1To30(buckets.days1To30)
                            .days31To60(buckets.days31To60)
                            .days61To90(buckets.days61To90)
                            .days90Plus(buckets.days90Plus)
                            .overdue(buckets.overdue())
                            .totalOutstanding(buckets.total())
                            .build();
                })
                .sorted(Comparator.comparing(
                                AgedReceivablesRow::getCustomerName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(AgedReceivablesRow::getCustomerId))
                .toList();

        AgingSummary totals = grandTotals(byCustomer.values());

        log.info(
                "Aged receivables generated as of {}: customers={}, totalOutstanding={}",
                asOfDate,
                rows.size(),
                totals.getTotalOutstanding());

        return AgedReceivablesReport.builder()
                .asOfDate(asOfDate)
                .generatedAt(Instant.now(clock))
                .rows(rows)
                .totals(totals)
                .build();
    }

    @Override
    public @NonNull AgedPayablesReport generateAgedPayables(@NonNull LocalDate asOfDate) {

        log.info("Generating aged payables as of {}", asOfDate);

        // As-of semantics (finding 10): an item whose DOCUMENT (bill) date is after asOfDate
        // did not exist yet and is excluded; everything that did exist is bucketed on asOfDate,
        // not-yet-due items in `notYetDue`. Aging basis is the due date, falling back to the
        // document date — the same rule generateAgedReceivables uses. Only APPROVED bills are aged
        // (AW11): a bill not yet approved is reported beside the buckets, unaged.
        // KNOWN LIMITATION: the open balance is the bill's CURRENT balance (total minus all
        // allocations to date), not a balance reconstructed as-of asOfDate. A true
        // historical-balance reconstruction is deferred.
        List<VendorBill> bills = vendorBillRepository.findByStatusIn(OPEN_PAYABLE_STATUSES);

        // Batch every bill's allocated total in one query to avoid a per-bill N+1
        // (finding 9).
        List<UUID> billIds = bills.stream().map(VendorBill::getVendorBillId).toList();
        Map<UUID, BigDecimal> allocatedByBill = billIds.isEmpty()
                ? Map.of()
                : apPaymentAllocationRepository.sumAllocatedAmountByVendorBillIdIn(billIds).stream()
                        .collect(Collectors.toMap(
                                APPaymentAllocationRepository.VendorBillAllocationSum::getVendorBillId,
                                APPaymentAllocationRepository.VendorBillAllocationSum::getAllocated));

        Map<UUID, VendorAging> byVendor = new LinkedHashMap<>();
        for (VendorBill bill : bills) {
            if (ledgerCurrency.isForeign(bill.getCurrency())) {
                // A bill in another currency is never summed into ledger-currency totals (ADR-0067
                // PC-9, #2309). Held bills are not loaded at all; this also keeps out any that escaped.
                continue;
            }
            BigDecimal allocated = nullSafe(allocatedByBill.get(bill.getVendorBillId()));
            BigDecimal openBalance = nullSafe(bill.getTotalAmount()).subtract(allocated);
            if (openBalance.signum() <= 0) {
                continue; // only positive-open items contribute
            }
            if (payableDocumentDate(bill).isAfter(asOfDate)) {
                // Billed after asOfDate — the bill did not exist yet (finding 10). This
                // existence test is against the DOCUMENT date, never the aging date: a
                // not-yet-due bill already exists and must still be reported.
                continue;
            }
            boolean approved = APPROVED_PAYABLE_STATUSES.contains(bill.getStatus());
            boolean unapproved = UNAPPROVED_PAYABLE_STATUSES.contains(bill.getStatus());
            if (!approved && !unapproved) {
                continue; // held, voided, rejected or paid: neither aged nor unapproved (§4.2)
            }
            VendorAging aging =
                    byVendor.computeIfAbsent(bill.getVendorId(), key -> new VendorAging(bill.getVendorName()));
            if (unapproved) {
                // Not yet approved: counted beside the buckets, never in one (AW11, §4.2).
                aging.unapproved = aging.unapproved.add(openBalance);
                aging.unapprovedBillCount++;
                continue;
            }
            LocalDate agingDate = payableAgingDate(bill);
            long daysPastDue = ChronoUnit.DAYS.between(agingDate, asOfDate);
            aging.buckets.add(daysPastDue, openBalance);
        }

        List<AgedPayablesRow> rows = byVendor.entrySet().stream()
                .map(entry -> {
                    VendorAging aging = entry.getValue();
                    BigDecimal totalOutstanding = aging.buckets.total();
                    return AgedPayablesRow.builder()
                            .vendorId(entry.getKey())
                            .vendorName(aging.vendorName)
                            .notYetDue(aging.buckets.notYetDue)
                            .days1To30(aging.buckets.days1To30)
                            .days31To60(aging.buckets.days31To60)
                            .days61To90(aging.buckets.days61To90)
                            .days90Plus(aging.buckets.days90Plus)
                            .overdue(aging.buckets.overdue())
                            .totalOutstanding(totalOutstanding)
                            .unapproved(aging.unapproved)
                            .unapprovedBillCount(aging.unapprovedBillCount)
                            .totalIncludingUnapproved(totalOutstanding.add(aging.unapproved))
                            .build();
                })
                .sorted(Comparator.comparing(
                                AgedPayablesRow::getVendorName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgedPayablesRow::getVendorId))
                .toList();

        AgingSummary totals = grandTotals(
                byVendor.values().stream().map(aging -> aging.buckets).toList());
        BigDecimal unapproved =
                byVendor.values().stream().map(aging -> aging.unapproved).reduce(BigDecimal.ZERO, BigDecimal::add);
        int unapprovedBillCount = byVendor.values().stream()
                .mapToInt(aging -> aging.unapprovedBillCount)
                .sum();

        log.info(
                "Aged payables generated as of {}: vendors={}, totalOutstanding={}, unapprovedBills={}",
                asOfDate,
                rows.size(),
                totals.getTotalOutstanding(),
                unapprovedBillCount);

        return AgedPayablesReport.builder()
                .asOfDate(asOfDate)
                .generatedAt(Instant.now(clock))
                .rows(rows)
                .totals(totals)
                .unapproved(unapproved)
                .unapprovedBillCount(unapprovedBillCount)
                .totalIncludingUnapproved(totals.getTotalOutstanding().add(unapproved))
                .build();
    }

    // ========================================================================
    // Story T8 (Issue #966) — Sales-Tax Liability report (reconciliation-grade).
    // ========================================================================

    /**
     * The #1629 deposit-take exclusion applies to every window, including periods whose tax was
     * already computed and filed before the fix deployed: a marked deposit-take invoice's tax rows
     * leave the report retroactively, per the accounting ruling on #1629 (its tax was never a real
     * liability). A re-run of a previously filed window can therefore return a smaller figure than
     * was filed — that delta is the correction, not a defect, and it is bounded to invoices both
     * marked (post-V29 enrichment) and taxed (pre-#1629 source fix).
     */
    @Override
    public @NonNull TaxLiabilityReport generateTaxLiability(@NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new InvalidDateRangeException("End date cannot be before start date");
        }

        log.info("Generating sales-tax liability report for period {} to {}", startDate, endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
        Instant startInstant = startDateTime.toInstant(ZoneOffset.UTC);
        Instant endInstant = endDateTime.toInstant(ZoneOffset.UTC);

        Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction = new HashMap<>();

        accumulateInvoiceTax(byJurisdiction, startInstant, endInstant);
        BigDecimal unattributedCredits = accumulatePostedCredits(byJurisdiction, startInstant, endInstant)
                .subtract(accumulateVoidedCredits(byJurisdiction, startInstant, endInstant));

        // --- Rows ordered state -> county -> city -> special, then by code ---
        List<TaxLiabilityRow> rows = byJurisdiction.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().toRow(entry.getKey()))
                .toList();

        BigDecimal totalTaxableBase = sum(rows, TaxLiabilityRow::getTaxableBase);
        BigDecimal totalExemptBase = sum(rows, TaxLiabilityRow::getExemptBase);
        BigDecimal totalGross = sum(rows, TaxLiabilityRow::getTaxCollectedGross);
        BigDecimal totalCreditsNetted = sum(rows, TaxLiabilityRow::getCreditsNetted);
        BigDecimal totalNetTax = sum(rows, TaxLiabilityRow::getNetTax);

        TaxLiabilityReconciliation reconciliation =
                reconcileAgainstTaxPayable(totalNetTax, unattributedCredits, startDateTime, endDateTime);

        log.info(
                "Sales-tax liability generated for {}..{}: jurisdictions={}, gross={}, credits={}, netTax={}, glDrift={}",
                startDate,
                endDate,
                rows.size(),
                totalGross,
                totalCreditsNetted,
                totalNetTax,
                reconciliation.getDrift());

        return TaxLiabilityReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(Instant.now(clock))
                .rows(rows)
                .totalTaxableBase(totalTaxableBase)
                .totalExemptBase(totalExemptBase)
                .totalTaxCollectedGross(totalGross)
                .totalCreditsNetted(totalCreditsNetted)
                .totalNetTax(totalNetTax)
                .reconciliation(reconciliation)
                .build();
    }

    /**
     * Buckets invoice-side (accrual) tax collected in the period into {@code byJurisdiction},
     * finalization-period tax bucketed by whether each row is exempt or taxable.
     */
    private void accumulateInvoiceTax(
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction, Instant startInstant, Instant endInstant) {
        List<ExtInvoice> invoices = extInvoiceRepository.findByFinalizedAtBetween(startInstant, endInstant);
        // Deposit-take invoices excluded (#1629, Accounting ruling — mirrors the #1623 filter in
        // AccountingAnalyticsServiceImpl.getCollectionsAnalytics): a deposit-take document is a
        // contract liability, not a taxable sale. Marked rows can carry tax minted before the
        // #1629 source fix and must not reach the report; rows replicated before the V29
        // enrichment are UNMARKED and still contribute (forward-only until a replay lands, per
        // ADR-0057's consequences). The settlement invoice alone establishes taxable base and tax
        // liability.
        List<UUID> invoiceIds = invoices.stream()
                .filter(invoice -> invoice.getDepositSourceType() == null)
                .map(ExtInvoice::getInvoiceId)
                .distinct()
                .toList();
        List<ExtInvoiceTax> invoiceTaxRows =
                invoiceIds.isEmpty() ? List.of() : extInvoiceTaxRepository.findByInvoiceIdIn(invoiceIds);

        for (ExtInvoiceTax row : invoiceTaxRows) {
            applyInvoiceTaxRow(byJurisdiction, row);
        }
    }

    /**
     * Filters out credit memos whose {@code originalInvoiceId} is a deposit-take invoice (#1629).
     *
     * <p>The credit legs of the tax-liability report can reference an original invoice finalized
     * outside this report's own window — {@link #accumulateInvoiceTax} only loads invoices
     * finalized within {@code [startInstant, endInstant]}, but a credit memo posted or voided in
     * that window may sit against an invoice finalized long before it. So the deposit flag is
     * loaded fresh here, scoped to just the credits' own {@code originalInvoiceId}s, rather than
     * reused from that window-scoped load.
     */
    private List<CreditMemo> excludeDepositTakeOriginals(List<CreditMemo> credits) {
        List<UUID> originalInvoiceIds = credits.stream()
                .map(CreditMemo::getOriginalInvoiceId)
                .distinct()
                .toList();
        Set<UUID> depositTakeOriginalIds = originalInvoiceIds.isEmpty()
                ? Set.of()
                : extInvoiceRepository.findAllById(originalInvoiceIds).stream()
                        .filter(invoice -> invoice.getDepositSourceType() != null)
                        .map(ExtInvoice::getInvoiceId)
                        .collect(Collectors.toSet());
        if (depositTakeOriginalIds.isEmpty()) {
            return credits;
        }
        return credits.stream()
                .filter(credit -> !depositTakeOriginalIds.contains(credit.getOriginalInvoiceId()))
                .toList();
    }

    /** Adds one invoice tax row to its jurisdiction's exempt or taxable running totals. */
    private static void applyInvoiceTaxRow(
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction, ExtInvoiceTax row) {
        JurisdictionAccumulator acc = accumulatorFor(byJurisdiction, row);
        if (row.isExempt()) {
            acc.exemptBase = acc.exemptBase.add(nullSafe(row.getTaxableBase()));
            String reason = row.getExemptionReasonCode();
            if (reason != null && !reason.isBlank()) {
                acc.exemptionReasons.add(reason);
            }
        } else {
            acc.taxableBase = acc.taxableBase.add(nullSafe(row.getTaxableBase()));
            acc.taxCollectedGross = acc.taxCollectedGross.add(nullSafe(row.getTaxAmount()));
        }
    }

    /**
     * Nets credit memos posted (any status but DRAFT) in the period into {@code byJurisdiction}
     * (issue #997 — a later APPLIED/VOIDED transition does not remove the Dr 2200 entry, so
     * dropping it here would show up as GL drift). Attribution comes from the credit's own frozen
     * per-jurisdiction breakdown (issue #996), falling back to the pro-rata allocator for credits
     * issued before that breakdown existed.
     *
     * @return the portion of posted-credit tax that could not be attributed to a jurisdiction
     */
    private BigDecimal accumulatePostedCredits(
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction, Instant startInstant, Instant endInstant) {
        List<CreditMemo> credits = creditMemoRepository.findByStatusNotAndPostedTimestampBetween(
                CreditMemoStatus.DRAFT, startInstant, endInstant);
        if (credits.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // #1629: a credit memo against a deposit-take original must contribute nothing here —
        // its tax was never counted on the gross side (accumulateInvoiceTax excludes deposit-take
        // invoices), so netting the reversal would drive a jurisdiction's netTax negative against
        // tax never counted. The original can be finalized outside this report's window, so the
        // deposit flag is loaded fresh by these credits' originalInvoiceIds rather than reused
        // from accumulateInvoiceTax's window-scoped invoice load.
        List<CreditMemo> eligibleCredits = excludeDepositTakeOriginals(credits);
        if (eligibleCredits.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<UUID> originalInvoiceIds = eligibleCredits.stream()
                .map(CreditMemo::getOriginalInvoiceId)
                .distinct()
                .toList();
        Map<UUID, List<ExtInvoiceTax>> taxByOriginalInvoice =
                extInvoiceTaxRepository.findByInvoiceIdIn(originalInvoiceIds).stream()
                        .collect(Collectors.groupingBy(ExtInvoiceTax::getInvoiceId));

        List<UUID> creditMemoIds =
                eligibleCredits.stream().map(CreditMemo::getCreditMemoId).toList();
        Map<UUID, List<CreditMemoTax>> attributionByCredit =
                creditMemoTaxRepository.findByCreditMemoIdIn(creditMemoIds).stream()
                        .collect(Collectors.groupingBy(CreditMemoTax::getCreditMemoId));

        BigDecimal unattributed = BigDecimal.ZERO;
        for (CreditMemo credit : eligibleCredits) {
            unattributed = unattributed.add(netCreditAcrossJurisdictions(
                    credit, attributionByCredit, taxByOriginalInvoice, byJurisdiction, false));
        }
        return unattributed;
    }

    /**
     * Restores tax reversed by credit memos voided in the period into {@code byJurisdiction}
     * (issue #997 symmetry): a void posts a reversing Cr 2200 entry in the period it happens, so
     * that period's report must restore the reversed tax (negative creditsNetted) rather than
     * leave it as unexplained GL drift. The memo's original posting-period contribution is
     * untouched — no retroactive restatement; a memo posted and voided in the same period
     * contributes net zero.
     *
     * @return the portion of voided-credit tax that could not be attributed to a jurisdiction
     */
    private BigDecimal accumulateVoidedCredits(
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction, Instant startInstant, Instant endInstant) {
        List<CreditMemo> voids = creditMemoRepository.findByStatusAndVoidedTimestampBetween(
                CreditMemoStatus.VOIDED, startInstant, endInstant);
        if (voids.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // #1629: symmetry with accumulatePostedCredits — a void against a deposit-take original
        // never entered netTax on the posted leg, so it must not restore anything here either.
        List<CreditMemo> eligibleVoids = excludeDepositTakeOriginals(voids);
        if (eligibleVoids.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<UUID> voidInvoiceIds = eligibleVoids.stream()
                .map(CreditMemo::getOriginalInvoiceId)
                .distinct()
                .toList();
        Map<UUID, List<ExtInvoiceTax>> taxByVoidInvoice =
                extInvoiceTaxRepository.findByInvoiceIdIn(voidInvoiceIds).stream()
                        .collect(Collectors.groupingBy(ExtInvoiceTax::getInvoiceId));
        Map<UUID, List<CreditMemoTax>> attributionByVoid =
                creditMemoTaxRepository
                        .findByCreditMemoIdIn(eligibleVoids.stream()
                                .map(CreditMemo::getCreditMemoId)
                                .toList())
                        .stream()
                        .collect(Collectors.groupingBy(CreditMemoTax::getCreditMemoId));

        BigDecimal unattributed = BigDecimal.ZERO;
        for (CreditMemo voided : eligibleVoids) {
            unattributed = unattributed.add(
                    netCreditAcrossJurisdictions(voided, attributionByVoid, taxByVoidInvoice, byJurisdiction, true));
        }
        return unattributed;
    }

    /**
     * Net one credit memo's {@code taxAmountReversed} into the per-jurisdiction
     * accumulators.
     *
     * <p>
     * Attribution source, in order of preference:
     *
     * <ol>
     * <li><b>The credit's own frozen breakdown</b> ({@code credit_memo_tax}, issue
     * #996) —
     * written at credit-memo creation and summing exactly to the scalar. This is
     * the
     * actual jurisdictional tax reversed, not an estimate of it.</li>
     * <li><b>Pro-rata fallback</b> ({@link TaxCreditAllocator}) for credits issued
     * before
     * that table existed: allocate the scalar across the original invoice's
     * per-jurisdiction collected tax.</li>
     * </ol>
     *
     * @param restore when true (issue #997 void symmetry) every attributed amount
     *                is applied with
     *                the opposite sign — the void-period restoration of a
     *                previously netted
     *                reversal — using the identical attribution source, so restore
     *                amounts mirror
     *                the netted ones jurisdiction-for-jurisdiction
     * @return the portion of this credit's reversed tax that could <em>not</em> be
     *         attributed
     *         to any jurisdiction (zero in the normal case). Surfacing it in the
     *         reconciliation
     *         block explains the resulting GL drift instead of leaving it phantom.
     *         Callers on the
     *         restore path subtract this value so an unattributed void cancels its
     *         unattributed
     *         posting symmetrically.
     */
    private BigDecimal netCreditAcrossJurisdictions(
            CreditMemo credit,
            Map<UUID, List<CreditMemoTax>> attributionByCredit,
            Map<UUID, List<ExtInvoiceTax>> taxByOriginalInvoice,
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction,
            boolean restore) {

        BigDecimal reversed = nullSafe(credit.getTaxAmountReversed());
        if (reversed.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sign = restore ? BigDecimal.ONE.negate() : BigDecimal.ONE;

        // (1) Preferred: the credit's own frozen per-jurisdiction attribution.
        List<CreditMemoTax> attribution = attributionByCredit.get(credit.getCreditMemoId());
        if (attribution != null && !attribution.isEmpty()) {
            for (CreditMemoTax row : attribution) {
                JurisdictionKey key = new JurisdictionKey(row.getJurisdictionType(), row.getJurisdictionCode());
                JurisdictionAccumulator acc = byJurisdiction.computeIfAbsent(key, JurisdictionAccumulator::new);
                acc.creditsNetted = acc.creditsNetted.add(
                        nullSafe(row.getTaxAmountReversed()).multiply(sign));
            }
            return BigDecimal.ZERO;
        }

        // (2) Fallback for pre-#996 credits: pro-rata across the original invoice's
        // collected tax.
        List<ExtInvoiceTax> originalRows = taxByOriginalInvoice.getOrDefault(credit.getOriginalInvoiceId(), List.of());

        // Weights are the original invoice's per-jurisdiction collected tax. Ensure
        // every
        // jurisdiction the credit touches has a row so its type/code is recoverable.
        // Build the weights first and only materialize accumulator rows once the
        // allocation has
        // actually produced a share. Creating them up front left all-zero phantom
        // jurisdiction rows
        // in the report whenever attribution turned out to be impossible (a
        // fully-exempt original
        // invoice, say) — jurisdictions the period never collected or credited a cent
        // in.
        Map<JurisdictionKey, BigDecimal> weights = new LinkedHashMap<>();
        for (ExtInvoiceTax row : originalRows) {
            weights.merge(keyFor(row), nullSafe(row.getTaxAmount()), BigDecimal::add);
        }

        Map<JurisdictionKey, BigDecimal> allocated =
                weights.isEmpty() ? Map.of() : TaxCreditAllocator.allocate(reversed, weights);
        if (allocated.isEmpty()) {
            // Either the original invoice has no replicated tax rows at all, or every row
            // carries
            // zero tax_amount (e.g. a fully-exempt invoice). Netting against a jurisdiction
            // that
            // collected no tax would hide the mismatch, so the reversal stays unattributed
            // — and
            // is reported as such, which is what makes the resulting drift explainable.
            log.warn(
                    "Credit memo {} reverses tax {} but carries no frozen jurisdiction breakdown and its original "
                            + "invoice {} has no attributable ext_invoice_tax rows; reversal left unattributed",
                    credit.getCreditMemoId(),
                    reversed,
                    credit.getOriginalInvoiceId());
            return reversed;
        }
        for (Map.Entry<JurisdictionKey, BigDecimal> entry : allocated.entrySet()) {
            JurisdictionAccumulator acc = byJurisdiction.computeIfAbsent(entry.getKey(), JurisdictionAccumulator::new);
            acc.creditsNetted = acc.creditsNetted.add(entry.getValue().multiply(sign));
        }
        return BigDecimal.ZERO;
    }

    /**
     * Reconcile the report's total net tax against the Sales-Tax Payable (2200)
     * account's
     * credit-normal period activity. {@code sumPostedBalanceForAccount} returns
     * {@code Σdebit - Σcredit}; the credit-normal (liability) net owed is its
     * negation.
     * Invoice finalization posts {@code Cr 2200}, credit memos post
     * {@code Dr 2200}, so on
     * a clean ledger the 2200 net activity equals the report net tax and drift is
     * zero.
     *
     * <p>
     * {@code unattributedCredits} is the reversed tax that could not be tied to any
     * jurisdiction (issue #996). It is excluded from the jurisdiction rows by
     * design, so it
     * necessarily inflates the drift by its own value — reporting it makes that
     * component of
     * the drift explainable rather than phantom.
     */
    private TaxLiabilityReconciliation reconcileAgainstTaxPayable(
            BigDecimal reportNetTax,
            BigDecimal unattributedCredits,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime) {

        BigDecimal glNetActivity = glAccountRepository
                .findByAccountCode(SALES_TAX_PAYABLE_ACCOUNT_CODE)
                .map(account -> nullSafe(journalEntryRepository.sumPostedBalanceForAccount(
                                account.getGlAccountId(), startDateTime, endDateTime))
                        .negate())
                .orElse(BigDecimal.ZERO);

        BigDecimal drift = reportNetTax.subtract(glNetActivity);
        // Unattributed credits are excluded from the jurisdiction rows by construction,
        // so they
        // inflate reportNetTax by exactly their own value while the GL still carries
        // the matching
        // Dr 2200. That component of the drift is therefore fully explained.
        // `reconciled` flags
        // the *unexplained* remainder, so a ledger whose only discrepancy is a credit
        // we could not
        // attribute still reads reconciled (issue #996 AC-3) — the amount stays visible
        // in
        // `unattributedCredits` rather than being silently folded away.
        BigDecimal unexplainedDrift = drift.subtract(unattributedCredits);
        boolean reconciled = unexplainedDrift.abs().compareTo(RECON_TOLERANCE) <= 0;

        return TaxLiabilityReconciliation.builder()
                .taxPayableAccountCode(SALES_TAX_PAYABLE_ACCOUNT_CODE)
                .glNetActivity(glNetActivity)
                .reportNetTax(reportNetTax)
                .unattributedCredits(unattributedCredits)
                .drift(drift)
                .reconciled(reconciled)
                .build();
    }

    private static JurisdictionKey keyFor(ExtInvoiceTax row) {
        return new JurisdictionKey(row.getJurisdictionType(), row.getJurisdictionCode());
    }

    private static JurisdictionAccumulator accumulatorFor(
            Map<JurisdictionKey, JurisdictionAccumulator> byJurisdiction, ExtInvoiceTax row) {
        return byJurisdiction.computeIfAbsent(keyFor(row), JurisdictionAccumulator::new);
    }

    private static BigDecimal sum(
            List<TaxLiabilityRow> rows, java.util.function.Function<TaxLiabilityRow, BigDecimal> extractor) {
        return rows.stream().map(extractor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Mutable per-jurisdiction accumulator, materialized into a
     * {@link TaxLiabilityRow}.
     */
    private static final class JurisdictionAccumulator {
        private BigDecimal taxableBase = BigDecimal.ZERO;
        private BigDecimal exemptBase = BigDecimal.ZERO;
        private BigDecimal taxCollectedGross = BigDecimal.ZERO;
        private BigDecimal creditsNetted = BigDecimal.ZERO;
        private final TreeSet<String> exemptionReasons = new TreeSet<>();

        JurisdictionAccumulator(JurisdictionKey key) {
            // key retained implicitly by the map; fields default to zero
        }

        TaxLiabilityRow toRow(JurisdictionKey key) {
            return TaxLiabilityRow.builder()
                    .jurisdictionType(key.type())
                    .jurisdictionCode(key.code())
                    .jurisdictionName(null) // replica carries only the code
                    .taxableBase(taxableBase)
                    .exemptBase(exemptBase)
                    .exemptionReasons(new ArrayList<>(exemptionReasons))
                    .taxCollectedGross(taxCollectedGross)
                    .creditsNetted(creditsNetted)
                    .netTax(taxCollectedGross.subtract(creditsNetted))
                    .build();
        }
    }

    // ========== Story G2 Private Helpers ==========

    private UUID parseAccountId(String accountId) {
        try {
            return UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            String maskedId = accountId.length() > 8 ? accountId.substring(0, 8) + "..." : accountId;
            log.warn("Invalid UUID format for accountId: {}", maskedId);
            throw new InvalidRequestParameterException("Invalid UUID format for accountId", e);
        }
    }

    /**
     * Group the account-relevant lines of the given POSTED entries by GL account.
     * When {@code filterAccountId} is non-null, only that account's lines are
     * collected; otherwise every line is grouped by its own account.
     */
    private void collectAccountLines(
            List<JournalEntry> entries, @Nullable UUID filterAccountId, Map<UUID, List<JournalEntryLine>> target) {
        for (JournalEntry entry : entries) {
            for (JournalEntryLine line : entry.getLines()) {
                UUID lineAccountId = line.getGlAccountId();
                if (filterAccountId != null && !filterAccountId.equals(lineAccountId)) {
                    continue;
                }
                target.computeIfAbsent(lineAccountId, key -> new ArrayList<>()).add(line);
            }
        }
    }

    /**
     * Build a single General Ledger account section: opening balance (POSTED net
     * strictly before the period), chronological in-period lines with running
     * balance, period debit/credit totals, and the closing balance.
     */
    private GeneralLedgerAccountSection buildAccountSection(
            UUID glAccountId, @Nullable GLAccount account, List<JournalEntryLine> lines, LocalDateTime startDateTime) {

        BigDecimal openingBalance =
                nullSafe(journalEntryRepository.sumPostedBalanceForAccountBefore(glAccountId, startDateTime));

        // Chronological order: transaction date, then entry number, with stable
        // tie-breakers so equal-keyed lines are deterministic.
        lines.sort(Comparator.comparing(
                        (JournalEntryLine line) -> line.getJournalEntry().getTransactionDate())
                .thenComparing(
                        line -> line.getJournalEntry().getEntryNumber(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(line -> line.getJournalEntry().getJournalEntryId())
                .thenComparing(JournalEntryLine::getLineNumber, Comparator.nullsLast(Comparator.naturalOrder())));

        // Signed figures are debit positive; the normal-side figures put them on the account's
        // usual side so a reader can say "went up" or "went down" (#2524, AW3).
        AccountType accountType = account != null ? account.getAccountType() : null;
        NormalSide normalSide = NormalSide.of(accountType);

        BigDecimal running = openingBalance;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<GeneralLedgerLine> glLines = new ArrayList<>(lines.size());

        for (JournalEntryLine line : lines) {
            JournalEntry entry = line.getJournalEntry();
            BigDecimal debit = nullSafe(line.getDebitAmount());
            BigDecimal credit = nullSafe(line.getCreditAmount());
            BigDecimal movement = debit.subtract(credit);
            running = running.add(movement);
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);

            glLines.add(GeneralLedgerLine.builder()
                    .journalEntryId(entry.getJournalEntryId())
                    .entryNumber(entry.getEntryNumber())
                    .transactionDate(entry.getTransactionDate().toLocalDate())
                    .description(line.getDescription())
                    .debitAmount(debit.signum() != 0 ? debit : null)
                    .creditAmount(credit.signum() != 0 ? credit : null)
                    .runningBalance(running)
                    .direction(
                            normalSide.normalBalance(movement).signum() >= 0
                                    ? GeneralLedgerLine.Direction.INCREASE
                                    : GeneralLedgerLine.Direction.DECREASE)
                    .normalRunningBalance(normalSide.normalBalance(running))
                    .sourceEventType(entry.getSourceEventType())
                    .build());
        }

        String accountNumber =
                account != null ? account.getAccountCode() : firstNonNull(lines, JournalEntryLine::getAccountCode);
        String accountName =
                account != null ? account.getAccountName() : firstNonNull(lines, JournalEntryLine::getAccountName);

        return GeneralLedgerAccountSection.builder()
                .accountId(glAccountId.toString())
                .accountNumber(accountNumber != null ? accountNumber : "")
                .accountName(accountName != null ? accountName : "")
                .accountType(accountType)
                .normalSide(normalSide)
                .openingBalance(openingBalance)
                .normalOpeningBalance(normalSide.normalBalance(openingBalance))
                .lines(glLines)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .closingBalance(running)
                .normalClosingBalance(normalSide.normalBalance(running))
                .build();
    }

    private static String firstNonNull(
            List<JournalEntryLine> lines, java.util.function.Function<JournalEntryLine, String> extractor) {
        return lines.stream()
                .map(extractor)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * AP aging basis: the bill's due date, falling back to the bill date — deliberately the same
     * rule {@link InvoiceBalanceCalculator#receivableAgingDate} applies on the A/R side (due date, falling back to the
     * invoice date). {@code due_date} is nullable on {@code vendor_bill} (terms not yet known);
     * those bills fall back to the bill date.
     */
    private LocalDate payableAgingDate(VendorBill bill) {
        LocalDateTime source = bill.getDueDate() != null ? bill.getDueDate() : bill.getBillDate();
        return source.toLocalDate();
    }

    /**
     * AP document date (the bill's own date): {@code billDate}, which the schema declares
     * non-null, falling back to the due date for the same defensive reason
     * {@link #payableAgingDate} falls back the other way.
     *
     * <p>This answers "did the bill exist as of the report date?" and is kept distinct from
     * {@link #payableAgingDate}, which answers "how far past due is it?".
     */
    private LocalDate payableDocumentDate(VendorBill bill) {
        LocalDateTime source = bill.getBillDate() != null ? bill.getBillDate() : bill.getDueDate();
        return source.toLocalDate();
    }

    @Nullable
    private UUID parsePartyId(@Nullable String partyId, UUID invoiceId) {
        if (partyId == null) {
            log.warn("Invoice {} has no partyId; excluded from aged receivables", invoiceId);
            return null;
        }
        try {
            return UUID.fromString(partyId);
        } catch (IllegalArgumentException e) {
            log.warn("Invoice {} has non-UUID partyId; excluded from aged receivables", invoiceId);
            return null;
        }
    }

    private static AgingSummary grandTotals(Collection<AgingBuckets> allBuckets) {
        AgingBuckets grand = new AgingBuckets();
        for (AgingBuckets buckets : allBuckets) {
            grand.notYetDue = grand.notYetDue.add(buckets.notYetDue);
            grand.days1To30 = grand.days1To30.add(buckets.days1To30);
            grand.days31To60 = grand.days31To60.add(buckets.days31To60);
            grand.days61To90 = grand.days61To90.add(buckets.days61To90);
            grand.days90Plus = grand.days90Plus.add(buckets.days90Plus);
        }
        return AgingSummary.builder()
                .notYetDue(grand.notYetDue)
                .days1To30(grand.days1To30)
                .days31To60(grand.days31To60)
                .days61To90(grand.days61To90)
                .days90Plus(grand.days90Plus)
                .overdue(grand.overdue())
                .totalOutstanding(grand.total())
                .build();
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Item-level aging accumulator: each item's full open balance lands in exactly
     * one bucket keyed on whole days past due ({@code asOfDate - agingDate}).
     * Boundaries (CAP:550 S35, #2524): {@code d <= 0} not yet due (due today is not
     * overdue), {@code 1..30}, {@code 31..60}, {@code 61..90}, {@code d >= 91} 90+.
     * {@code overdue} is the sum of the four late buckets and {@code total} is
     * {@code notYetDue + overdue}. Callers exclude items that did not yet exist as of
     * the report date by comparing the item's DOCUMENT date, before adding (finding 10).
     */
    static final class AgingBuckets {
        private BigDecimal notYetDue = BigDecimal.ZERO;
        private BigDecimal days1To30 = BigDecimal.ZERO;
        private BigDecimal days31To60 = BigDecimal.ZERO;
        private BigDecimal days61To90 = BigDecimal.ZERO;
        private BigDecimal days90Plus = BigDecimal.ZERO;

        void add(long daysPastDue, BigDecimal amount) {
            if (daysPastDue <= 0) {
                notYetDue = notYetDue.add(amount);
            } else if (daysPastDue <= 30) {
                days1To30 = days1To30.add(amount);
            } else if (daysPastDue <= 60) {
                days31To60 = days31To60.add(amount);
            } else if (daysPastDue <= 90) {
                days61To90 = days61To90.add(amount);
            } else {
                days90Plus = days90Plus.add(amount);
            }
        }

        BigDecimal overdue() {
            return days1To30.add(days31To60).add(days61To90).add(days90Plus);
        }

        BigDecimal total() {
            return notYetDue.add(overdue());
        }
    }

    /**
     * Per-vendor aging accumulator carrying the vendor's display name, the aged buckets of its
     * approved bills and the unaged open amount of its bills not yet approved.
     */
    private static final class VendorAging {
        private final String vendorName;
        private final AgingBuckets buckets = new AgingBuckets();
        private BigDecimal unapproved = BigDecimal.ZERO;
        private int unapprovedBillCount;

        VendorAging(String vendorName) {
            this.vendorName = vendorName;
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * One GL account with a posted balance on a statement window: its chart data and its ledger
     * balance as debits minus credits (CAP:550 S35, #2524).
     */
    record StatementAccount(
            @NonNull UUID id,
            @Nullable String code,
            @Nullable String name,
            @Nullable AccountType type,
            @Nullable AccountSubtype subtype,
            @NonNull BigDecimal debitsMinusCredits) {}

    /**
     * The lines of one statement and the accounts that landed on them.
     *
     * @param lineItems           line code to amount, named lines first in display order, then the
     *                            computed lines that collected something
     * @param accountsOnStatement every account on some line: mapped ones (zero when without
     *                            activity) and the ones a computed line collected
     */
    record StatementFigures(
            @NonNull Map<String, BigDecimal> lineItems,
            @NonNull List<StatementAccount> accountsOnStatement) {}

    /**
     * Every account with ledger activity in the window, with its balance, in one grouped query:
     * as of {@code endDateTime} when {@code startDateTime} is null (the balance sheet), else the
     * period movement. Chart data comes from one further lookup; an account the lookup does not
     * return keeps a null type and lands on no computed line.
     */
    private @NonNull List<StatementAccount> statementAccounts(
            @NonNull StatementType statementType,
            @Nullable LocalDateTime startDateTime,
            @NonNull LocalDateTime endDateTime) {
        List<TrialBalanceAccountTotal> totals = startDateTime == null
                ? journalEntryRepository.sumPostedDebitsCreditsByAccountAsOf(endDateTime)
                : journalEntryRepository.sumPostedDebitsCreditsByAccountInRange(startDateTime, endDateTime);
        if (totals.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids =
                totals.stream().map(TrialBalanceAccountTotal::glAccountId).collect(Collectors.toSet());
        Map<UUID, GLAccount> accountsById = glAccountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(GLAccount::getGlAccountId, account -> account));
        List<StatementAccount> accounts = new ArrayList<>(totals.size());
        for (TrialBalanceAccountTotal total : totals) {
            GLAccount account = accountsById.get(total.glAccountId());
            accounts.add(new StatementAccount(
                    total.glAccountId(),
                    account != null ? account.getAccountCode() : total.accountCode(),
                    account != null ? account.getAccountName() : total.accountName(),
                    account != null ? account.getAccountType() : null,
                    account != null ? account.getAccountSubtype() : null,
                    nullSafe(total.totalDebit()).subtract(nullSafe(total.totalCredit()))));
        }
        log.debug("Statement {} window has {} accounts with activity", statementType, accounts.size());
        return accounts;
    }

    /**
     * Lay the accounts out on the statement's lines: each mapping contributes its account's balance
     * (on the account's normal side, after the mapping's operation) to its named line; an account
     * with a non-zero balance and no mapping for the statement lands on the computed line for its
     * type. Named lines appear whether or not they have activity; a computed line appears only when
     * it collected something.
     */
    private @NonNull StatementFigures computeStatement(
            @NonNull StatementType statementType,
            @NonNull List<StatementLineMapping> mappings,
            @NonNull List<StatementAccount> accountsWithActivity) {
        Map<UUID, StatementAccount> activityById = new HashMap<>();
        for (StatementAccount account : accountsWithActivity) {
            activityById.put(account.id(), account);
        }

        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        Map<UUID, StatementAccount> onStatement = new LinkedHashMap<>();

        // Named lines, in display order. A mapped account without activity is on the statement at
        // zero; its type comes from the chart so the totals still know it.
        Set<UUID> mappedIds =
                mappings.stream().map(StatementLineMapping::getGlAccountId).collect(Collectors.toSet());
        Map<UUID, GLAccount> mappedWithoutActivity = new HashMap<>();
        Set<UUID> missing = new HashSet<>(mappedIds);
        missing.removeAll(activityById.keySet());
        if (!missing.isEmpty()) {
            for (GLAccount account : glAccountRepository.findAllById(missing)) {
                mappedWithoutActivity.put(account.getGlAccountId(), account);
            }
        }
        for (StatementLineMapping mapping : mappings) {
            UUID accountId = mapping.getGlAccountId();
            StatementAccount account = activityById.get(accountId);
            if (account == null) {
                GLAccount chart = mappedWithoutActivity.get(accountId);
                account = new StatementAccount(
                        accountId,
                        chart != null ? chart.getAccountCode() : mapping.getAccountName(),
                        chart != null ? chart.getAccountName() : mapping.getAccountName(),
                        chart != null ? chart.getAccountType() : null,
                        chart != null ? chart.getAccountSubtype() : null,
                        BigDecimal.ZERO);
            }
            onStatement.putIfAbsent(accountId, account);
            lineItems.merge(
                    mapping.getStatementLineCode(),
                    statementContribution(account.debitsMinusCredits(), account.type(), mapping.getOperation()),
                    BigDecimal::add);
        }

        // Computed lines: whatever has a balance and no line yet, by type, appended in a fixed order.
        Map<String, BigDecimal> computed = new HashMap<>();
        List<String> computedCodes = new ArrayList<>();
        for (StatementAccount account : accountsWithActivity) {
            if (mappedIds.contains(account.id()) || account.debitsMinusCredits().signum() == 0) {
                continue;
            }
            String lineCode = computedLineFor(statementType, account.type(), account.subtype());
            if (lineCode == null) {
                continue; // not this statement's kind of account (or an unknown type, logged by the totals)
            }
            onStatement.putIfAbsent(account.id(), account);
            computed.merge(lineCode, computedContribution(lineCode, account), BigDecimal::add);
            computedCodes.add(account.code());
        }
        for (String lineCode : COMPUTED_LINE_ORDER) {
            BigDecimal amount = computed.get(lineCode);
            if (amount != null) {
                lineItems.merge(lineCode, amount, BigDecimal::add);
            }
        }
        if (!computedCodes.isEmpty()) {
            log.info(
                    "{}: {} account(s) without a named line fell on computed lines: {}",
                    statementType,
                    computedCodes.size(),
                    computedCodes);
        }

        return new StatementFigures(lineItems, new ArrayList<>(onStatement.values()));
    }

    /**
     * The computed line an unmapped account lands on, by statement and account type (CAP:550 S35
     * PROPOSED 2): on the balance sheet a {@code BANK_CASH} asset joins {@code BS_IN_THE_BANK}
     * (AW9), other assets, liabilities and equity their "other" line, and revenue and expenses
     * {@code BS_PROFIT_NOT_YET_CLOSED}; on the income statement revenue goes to
     * {@code IS_OTHER_INCOME} and expenses to {@code IS_OTHER_EXPENSES}. Null when the account does
     * not belong on the statement or its type is unknown.
     */
    static @Nullable String computedLineFor(
            @NonNull StatementType statementType, @Nullable AccountType type, @Nullable AccountSubtype subtype) {
        if (type == null) {
            return null;
        }
        return switch (statementType) {
            case BALANCE_SHEET ->
                switch (type) {
                    case ASSET -> subtype == AccountSubtype.BANK_CASH ? BS_IN_THE_BANK : BS_OTHER_ASSETS;
                    case LIABILITY -> BS_OTHER_LIABILITIES;
                    case EQUITY -> BS_OTHER_EQUITY;
                    case REVENUE, EXPENSE -> BS_PROFIT_NOT_YET_CLOSED;
                };
            case INCOME_STATEMENT ->
                switch (type) {
                    case REVENUE -> IS_OTHER_INCOME;
                    case EXPENSE -> IS_OTHER_EXPENSES;
                    case ASSET, LIABILITY, EQUITY -> null;
                };
            case LABOR_OVERHEAD -> null;
        };
    }

    /**
     * What an account contributes to the computed line it fell on: revenue less expenses on
     * {@code BS_PROFIT_NOT_YET_CLOSED} (credits minus debits for both), otherwise the balance on the
     * account's normal side, as a {@code SUM} mapping would present it.
     */
    private static @NonNull BigDecimal computedContribution(
            @NonNull String lineCode, @NonNull StatementAccount account) {
        if (BS_PROFIT_NOT_YET_CLOSED.equals(lineCode)) {
            return account.debitsMinusCredits().negate();
        }
        return statementContribution(account.debitsMinusCredits(), account.type(), OperationType.SUM);
    }

    /**
     * The signed amount one mapped account contributes to its statement line (issue #2394).
     *
     * <p>The ledger balance arrives as debits minus credits. It is first put on the account's
     * normal side, so a reader sees the sign they expect: assets and expenses as debits minus
     * credits, liabilities, equity and revenue as credits minus debits. The mapping's operation
     * then applies to that amount:
     *
     * <ul>
     *   <li>{@code SUM} adds it to the line;
     *   <li>{@code SUBTRACT} takes it off the line;
     *   <li>{@code NEGATE} keeps the meaning it had before signs followed the account type:
     *       credits minus debits whatever the type. It was how a mapping presented a
     *       credit-normal account as a positive amount, so on such an account it now equals
     *       {@code SUM} rather than flipping the sign a second time; on a debit-normal account
     *       it still reverses the balance.
     * </ul>
     *
     * <p>An account of unknown type is treated as debit-normal, which is the balance as stored.
     *
     * @param debitsMinusCredits the account's ledger balance for the window, debits minus credits
     * @param accountType        the account's type, or null when it could not be loaded
     * @param operation          the mapping's operation
     * @return the amount to add to the statement line
     */
    static @NonNull BigDecimal statementContribution(
            @Nullable BigDecimal debitsMinusCredits,
            @Nullable AccountType accountType,
            @NonNull OperationType operation) {
        BigDecimal balance = debitsMinusCredits != null ? debitsMinusCredits : BigDecimal.ZERO;
        BigDecimal normalSide = NormalSide.of(accountType).normalBalance(balance);

        return switch (operation) {
            case SUM -> normalSide;
            case SUBTRACT -> normalSide.negate();
            case NEGATE -> balance.negate();
        };
    }
}
