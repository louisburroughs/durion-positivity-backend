package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
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
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.VendorBillStatus;
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
import java.util.Comparator;
import java.util.HashMap;
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
 * mappings.
 *
 * @author Louis Burroughs
 * @since 2025-01-01
 */
@Service
@Transactional(readOnly = true)
public class FinancialReportingServiceImpl implements FinancialReportingService {

    private static final Logger log = LoggerFactory.getLogger(FinancialReportingServiceImpl.class);

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01"); // 1 cent tolerance for rounding

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
     * Vendor-bill statuses that represent an open (unsettled) payable obligation
     * for the Aged Payables report: everything except settled ({@code PAID}),
     * voided ({@code VOIDED}), and rejected ({@code REJECTED}) bills.
     */
    private static final Set<VendorBillStatus> OPEN_PAYABLE_STATUSES =
            Set.of(VendorBillStatus.PENDING_RECEIPT_MATCH, VendorBillStatus.MATCH_EXCEPTION, VendorBillStatus.APPROVED);

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
    private final Clock clock;

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
            Clock clock) {
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
        this.clock = clock;
    }

    @Override
    public @NonNull IncomeStatementReport generateIncomeStatement(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        log.info("Generating income statement for period {} to {}", startDate, endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // Load all income statement mappings (ordered by display order)
        List<StatementLineMapping> mappings =
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.INCOME_STATEMENT);

        if (mappings.isEmpty()) {
            log.warn("No statement line mappings configured for INCOME_STATEMENT");
            return IncomeStatementReport.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .lineItems(Map.of())
                    .totalRevenue(BigDecimal.ZERO)
                    .totalExpenses(BigDecimal.ZERO)
                    .netIncome(BigDecimal.ZERO)
                    .generatedAt(Instant.now(clock))
                    .build();
        }

        // Aggregate balances by statement line
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        Map<String, BigDecimal> revenueLines = new HashMap<>();
        Map<String, BigDecimal> expenseLines = new HashMap<>();

        // Precompute balances per distinct account to avoid N+1 queries
        Map<UUID, BigDecimal> accountBalancesById = mappings.stream()
                .map(StatementLineMapping::getGlAccountId)
                .distinct()
                .collect(Collectors.toMap(
                        glAccountId -> glAccountId,
                        glAccountId -> journalEntryRepository.sumPostedBalanceForAccount(
                                glAccountId, startDateTime, endDateTime)));

        for (StatementLineMapping mapping : mappings) {
            BigDecimal accountBalance = accountBalancesById.get(mapping.getGlAccountId());

            // Apply operation type (SUM, SUBTRACT, NEGATE) to accumulate into statement
            // line
            String lineCode = mapping.getStatementLineCode();
            lineItems.compute(lineCode, (key, existingTotal) -> {
                BigDecimal base = existingTotal != null ? existingTotal : BigDecimal.ZERO;
                return applyOperation(base, accountBalance, mapping.getOperation());
            });

            // Track revenue vs expense lines for totals
            BigDecimal currentTotal = lineItems.get(lineCode);
            if (isRevenueLine(lineCode)) {
                revenueLines.put(lineCode, currentTotal);
            } else if (isExpenseLine(lineCode)) {
                expenseLines.put(lineCode, currentTotal);
            }
        }

        // Calculate totals
        BigDecimal totalRevenue = revenueLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = expenseLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netIncome = totalRevenue.subtract(totalExpenses);

        log.info(
                "Income statement generated: revenue={}, expenses={}, netIncome={}",
                totalRevenue,
                totalExpenses,
                netIncome);

        return IncomeStatementReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .lineItems(lineItems)
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

        // Load all balance sheet mappings (ordered by display order)
        List<StatementLineMapping> mappings =
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.BALANCE_SHEET);

        if (mappings.isEmpty()) {
            log.warn("No statement line mappings configured for BALANCE_SHEET");
            return BalanceSheetReport.builder()
                    .asOfDate(asOfDate)
                    .lineItems(Map.of())
                    .totalAssets(BigDecimal.ZERO)
                    .totalLiabilities(BigDecimal.ZERO)
                    .totalEquity(BigDecimal.ZERO)
                    .balanced(true)
                    .generatedAt(Instant.now(clock))
                    .build();
        }

        // Aggregate balances by statement line
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        Map<String, BigDecimal> assetLines = new HashMap<>();
        Map<String, BigDecimal> liabilityLines = new HashMap<>();
        Map<String, BigDecimal> equityLines = new HashMap<>();

        // Precompute balances per distinct account to avoid N+1 queries
        Map<UUID, BigDecimal> accountBalancesById = mappings.stream()
                .map(StatementLineMapping::getGlAccountId)
                .distinct()
                .collect(Collectors.toMap(
                        glAccountId -> glAccountId,
                        glAccountId -> journalEntryRepository.sumPostedBalanceAsOf(glAccountId, asOfDateTime)));

        for (StatementLineMapping mapping : mappings) {
            BigDecimal accountBalance = accountBalancesById.get(mapping.getGlAccountId());

            // Apply operation type (SUM, SUBTRACT, NEGATE) to accumulate into statement
            // line
            String lineCode = mapping.getStatementLineCode();
            lineItems.merge(
                    lineCode, accountBalance, (total, amount) -> applyOperation(total, amount, mapping.getOperation()));

            // Track asset/liability/equity lines for totals
            BigDecimal currentTotal = lineItems.get(lineCode);
            if (isAssetLine(lineCode)) {
                assetLines.put(lineCode, currentTotal);
            } else if (isLiabilityLine(lineCode)) {
                liabilityLines.put(lineCode, currentTotal);
            } else if (isEquityLine(lineCode)) {
                equityLines.put(lineCode, currentTotal);
            }
        }

        // Calculate totals
        BigDecimal totalAssets = assetLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLiabilities = liabilityLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEquity = equityLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

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
                .lineItems(lineItems)
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
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        log.info(
                "Drilling down statement line {} to accounts for period {} to {}",
                statementLineCode,
                startDate,
                endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // Find all accounts mapped to this statement line
        List<StatementLineMapping> mappings = statementLineMappingRepository.findByStatementLineCode(statementLineCode);

        if (mappings.isEmpty()) {
            log.warn("No account mappings found for statement line: {}", statementLineCode);
            return List.of();
        }

        // Calculate balance for each account
        return mappings.stream()
                .map(mapping -> {
                    BigDecimal accountBalance = journalEntryRepository.sumPostedBalanceForAccount(
                            mapping.getGlAccountId(), startDateTime, endDateTime);
                    // Apply operation to transform the balance for display (starting from zero)
                    BigDecimal displayBalance = applyOperation(BigDecimal.ZERO, accountBalance, mapping.getOperation());

                    return AccountDrilldownResponse.builder()
                            .accountId(mapping.getGlAccountId().toString())
                            .accountName(mapping.getAccountName())
                            .balance(displayBalance)
                            .statementLineCode(statementLineCode)
                            .build();
                })
                .toList();
    }

    @Override
    public @NonNull List<JournalLineDrilldownResponse> drilldownToJournalLines(
            @NonNull String accountId, @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        UUID glAccountId;
        try {
            glAccountId = UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            String maskedId = accountId.length() > 8 ? accountId.substring(0, 8) + "..." : accountId;
            log.warn("Invalid UUID format for accountId: {}", maskedId);
            throw new IllegalArgumentException("Invalid UUID format for accountId", e);
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
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        // Collect in-period POSTED lines grouped by account. Ordering within a
        // section is applied later; only POSTED entries are queried, so REVERSED
        // originals drop out while POSTED reversing entries remain (net-zero pairs)
        // with no reversal-linkage special-casing.
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
        // including not-yet-due items (they land in `current`). Aging basis is the due date,
        // falling back to the document date — the same rule generateAgedPayables uses.
        // KNOWN LIMITATION: the
        // open balance is
        // the invoice's CURRENT balance (InvoiceBalanceCalculator derives it from all
        // payment
        // applications/reversals/credit-memos to date), not a balance reconstructed
        // as-of asOfDate, so
        // a back-dated asOfDate reflects today's balances against historical aging
        // dates. A true
        // historical-balance reconstruction is deferred (needs point-in-time
        // application replay).
        // AR-eligible invoices; open balance is derived from accounting-owned
        // facts (payment applications, reversals, credit memos) via the shared
        // InvoiceBalanceCalculator — never fetched from another service.
        List<ExtInvoice> invoices = extInvoiceRepository.findByStatusIn(AR_ELIGIBLE_STATUSES);

        Map<UUID, AgingBuckets> byCustomer = new LinkedHashMap<>();
        for (ExtInvoice invoice : invoices) {
            if (!invoiceBalanceCalculator.isArEligible(invoice)) {
                continue;
            }
            BigDecimal openBalance = invoiceBalanceCalculator.balanceDue(invoice);
            if (openBalance.signum() <= 0) {
                continue; // only positive-open items contribute
            }
            UUID customerId = parsePartyId(invoice.getPartyId(), invoice.getInvoiceId());
            if (customerId == null) {
                continue; // non-UUID party cannot be represented in the contract's UUID field
            }
            if (receivableDocumentDate(invoice).isAfter(asOfDate)) {
                // Raised after asOfDate — the invoice did not exist yet (finding 10). This
                // existence test is against the DOCUMENT date, never the aging date: a
                // not-yet-due invoice already exists and must still be reported.
                continue;
            }
            LocalDate agingDate = receivableAgingDate(invoice);
            long daysPastDue = ChronoUnit.DAYS.between(agingDate, asOfDate);
            // A negative daysPastDue (not yet due) is kept deliberately: it satisfies
            // AgingBuckets' `<= 30` test and so lands in `current`, which is exactly what
            // AgedReceivablesRow.current documents ("includes not-yet-due").
            byCustomer.computeIfAbsent(customerId, key -> new AgingBuckets()).add(daysPastDue, openBalance);
        }

        List<AgedReceivablesRow> rows = byCustomer.entrySet().stream()
                .map(entry -> {
                    AgingBuckets buckets = entry.getValue();
                    return AgedReceivablesRow.builder()
                            .customerId(entry.getKey())
                            .customerName(null) // no directory lookup in this slice
                            .current(buckets.current)
                            .days31To60(buckets.days31To60)
                            .days61To90(buckets.days61To90)
                            .days90Plus(buckets.days90Plus)
                            .totalOutstanding(buckets.total())
                            .build();
                })
                // customerName is null in this slice, so order deterministically by id
                .sorted(Comparator.comparing(AgedReceivablesRow::getCustomerId))
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
        // including not-yet-due items (they land in `current`). Aging basis is the due date,
        // falling back to the document date — the same rule generateAgedReceivables uses.
        // KNOWN LIMITATION: the
        // open balance is
        // the bill's CURRENT balance (total minus all allocations to date), not a
        // balance reconstructed
        // as-of asOfDate. A true historical-balance reconstruction is deferred.
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
            LocalDate agingDate = payableAgingDate(bill);
            long daysPastDue = ChronoUnit.DAYS.between(agingDate, asOfDate);
            // A negative daysPastDue (not yet due) is kept deliberately: it satisfies
            // AgingBuckets' `<= 30` test and so lands in `current`, which is exactly what
            // AgedPayablesRow.current documents ("includes not-yet-due").
            VendorAging aging =
                    byVendor.computeIfAbsent(bill.getVendorId(), key -> new VendorAging(bill.getVendorName()));
            aging.buckets.add(daysPastDue, openBalance);
        }

        List<AgedPayablesRow> rows = byVendor.entrySet().stream()
                .map(entry -> {
                    VendorAging aging = entry.getValue();
                    return AgedPayablesRow.builder()
                            .vendorId(entry.getKey())
                            .vendorName(aging.vendorName)
                            .current(aging.buckets.current)
                            .days31To60(aging.buckets.days31To60)
                            .days61To90(aging.buckets.days61To90)
                            .days90Plus(aging.buckets.days90Plus)
                            .totalOutstanding(aging.buckets.total())
                            .build();
                })
                .sorted(Comparator.comparing(
                                AgedPayablesRow::getVendorName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgedPayablesRow::getVendorId))
                .toList();

        AgingSummary totals = grandTotals(
                byVendor.values().stream().map(aging -> aging.buckets).toList());

        log.info(
                "Aged payables generated as of {}: vendors={}, totalOutstanding={}",
                asOfDate,
                rows.size(),
                totals.getTotalOutstanding());

        return AgedPayablesReport.builder()
                .asOfDate(asOfDate)
                .generatedAt(Instant.now(clock))
                .rows(rows)
                .totals(totals)
                .build();
    }

    // ========================================================================
    // Story T8 (Issue #966) — Sales-Tax Liability report (reconciliation-grade).
    // ========================================================================

    @Override
    public @NonNull TaxLiabilityReport generateTaxLiability(@NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
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
            throw new IllegalArgumentException("Invalid UUID format for accountId", e);
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

        BigDecimal running = openingBalance;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<GeneralLedgerLine> glLines = new ArrayList<>(lines.size());

        for (JournalEntryLine line : lines) {
            JournalEntry entry = line.getJournalEntry();
            BigDecimal debit = nullSafe(line.getDebitAmount());
            BigDecimal credit = nullSafe(line.getCreditAmount());
            running = running.add(debit).subtract(credit);
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
                .openingBalance(openingBalance)
                .lines(glLines)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .closingBalance(running)
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
     * AR aging basis: the invoice's due date, falling back to the invoice document date
     * ({@link #receivableDocumentDate}). This is deliberately the same rule
     * {@link #payableAgingDate} applies on the A/P side (due date, falling back to the bill date),
     * so both halves of the aging report age from one documented basis.
     *
     * <p>{@code due_date} arrived with {@code V22__ext_invoice_due_date.sql} ("collections-aging
     * due date frozen at finalization by pos-invoice") and is also what {@code OLDEST_FIRST}
     * allocation ages by. It is null on drafts and on replica rows built from events predating
     * that enrichment; those rows fall back to the document date.
     */
    private LocalDate receivableAgingDate(ExtInvoice invoice) {
        LocalDate dueDate = invoice.getDueDate();
        return dueDate != null ? dueDate : receivableDocumentDate(invoice);
    }

    /**
     * AR document date (the invoice's own date): {@code invoiceCreatedAt}, falling back to
     * {@code finalizedAt}, then {@code updatedAt}. Instants are read at UTC for
     * timezone-independent, deterministic day math.
     *
     * <p>This answers "did the invoice exist as of the report date?" and is kept distinct from
     * {@link #receivableAgingDate}, which answers "how far past due is it?".
     */
    private LocalDate receivableDocumentDate(ExtInvoice invoice) {
        Instant source = invoice.getInvoiceCreatedAt();
        if (source == null) {
            source = invoice.getFinalizedAt();
        }
        if (source == null) {
            source = invoice.getUpdatedAt();
        }
        return source.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * AP aging basis: the bill's due date, falling back to the bill date — deliberately the same
     * rule {@link #receivableAgingDate} applies on the A/R side (due date, falling back to the
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

    private static AgingSummary grandTotals(java.util.Collection<AgingBuckets> allBuckets) {
        AgingBuckets grand = new AgingBuckets();
        for (AgingBuckets buckets : allBuckets) {
            grand.current = grand.current.add(buckets.current);
            grand.days31To60 = grand.days31To60.add(buckets.days31To60);
            grand.days61To90 = grand.days61To90.add(buckets.days61To90);
            grand.days90Plus = grand.days90Plus.add(buckets.days90Plus);
        }
        return AgingSummary.builder()
                .current(grand.current)
                .days31To60(grand.days31To60)
                .days61To90(grand.days61To90)
                .days90Plus(grand.days90Plus)
                .totalOutstanding(grand.total())
                .build();
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Item-level aging accumulator: each item's full open balance lands in exactly
     * one bucket keyed on whole days past due ({@code asOfDate - agingDate}).
     * Boundaries: {@code d <= 30} current, {@code 31..60}, {@code 61..90},
     * {@code d >= 91} 90+. Not-yet-due items carry a negative {@code d} and land in
     * {@code current} by that same {@code d <= 30} test. Callers exclude items that did
     * not yet exist as of the report date by comparing the item's DOCUMENT date, before
     * adding (finding 10).
     */
    private static final class AgingBuckets {
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal days31To60 = BigDecimal.ZERO;
        private BigDecimal days61To90 = BigDecimal.ZERO;
        private BigDecimal days90Plus = BigDecimal.ZERO;

        void add(long daysPastDue, BigDecimal amount) {
            if (daysPastDue <= 30) {
                current = current.add(amount);
            } else if (daysPastDue <= 60) {
                days31To60 = days31To60.add(amount);
            } else if (daysPastDue <= 90) {
                days61To90 = days61To90.add(amount);
            } else {
                days90Plus = days90Plus.add(amount);
            }
        }

        BigDecimal total() {
            return current.add(days31To60).add(days61To90).add(days90Plus);
        }
    }

    /** Per-vendor aging accumulator carrying the vendor's display name. */
    private static final class VendorAging {
        private final String vendorName;
        private final AgingBuckets buckets = new AgingBuckets();

        VendorAging(String vendorName) {
            this.vendorName = vendorName;
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * Apply operation type to combine an amount with a running total.
     *
     * @param total         the current running total for the statement line
     * @param amount        the account balance to apply
     * @param operationType the operation to perform (SUM, SUBTRACT, or NEGATE)
     * @return the new total after applying the operation
     */
    private BigDecimal applyOperation(BigDecimal total, BigDecimal amount, OperationType operationType) {
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        return switch (operationType) {
            // SUM: add the amount to the total
            case SUM -> total.add(amount);
            // SUBTRACT: subtract the amount from the total
            case SUBTRACT -> total.subtract(amount);
            // NEGATE: flip the sign of the amount before adding (e.g., for credit-normal
            // accounts)
            case NEGATE -> total.add(amount.negate());
        };
    }

    /**
     * Check if statement line code represents revenue (income statement).
     * Supports both legacy codes (e.g. REVENUE_*) and Javadoc-style codes (e.g.
     * PL_REVENUE_*).
     */
    private boolean isRevenueLine(String lineCode) {
        if (lineCode == null) {
            return false;
        }
        return lineCode.startsWith("REVENUE_") || lineCode.startsWith("PL_REVENUE_") || lineCode.contains("INCOME");
    }

    /**
     * Check if statement line code represents expense (income statement).
     * Supports both legacy codes (e.g. EXPENSE_*) and Javadoc-style codes (e.g.
     * PL_EXPENSE_* / PL_EXPENSES_*).
     */
    private boolean isExpenseLine(String lineCode) {
        if (lineCode == null) {
            return false;
        }
        return lineCode.startsWith("EXPENSE_")
                || lineCode.startsWith("PL_EXPENSE_")
                || lineCode.startsWith("PL_EXPENSES_")
                || lineCode.contains("COST");
    }

    /**
     * Check if statement line code represents asset (balance sheet).
     * Supports both legacy codes (e.g. ASSET_*) and Javadoc-style codes (e.g.
     * BS_ASSETS_*).
     */
    private boolean isAssetLine(String lineCode) {
        if (lineCode == null) {
            return false;
        }
        return lineCode.startsWith("ASSET_") || lineCode.startsWith("BS_ASSETS_");
    }

    /**
     * Check if statement line code represents liability (balance sheet).
     * Supports legacy codes (e.g. LIABILITY_*) and likely BS prefixes (e.g.
     * BS_LIAB_*, BS_LIABILITY_*, BS_LIABILITIES_*).
     */
    private boolean isLiabilityLine(String lineCode) {
        if (lineCode == null) {
            return false;
        }
        return lineCode.startsWith("LIABILITY_")
                || lineCode.startsWith("BS_LIAB_")
                || lineCode.startsWith("BS_LIABILITY_")
                || lineCode.startsWith("BS_LIABILITIES_");
    }

    /**
     * Check if statement line code represents equity (balance sheet).
     * Supports both legacy codes (e.g. EQUITY_*) and Javadoc-style codes (e.g.
     * BS_EQUITY_*).
     */
    private boolean isEquityLine(String lineCode) {
        if (lineCode == null) {
            return false;
        }
        return lineCode.startsWith("EQUITY_") || lineCode.startsWith("BS_EQUITY_");
    }
}
