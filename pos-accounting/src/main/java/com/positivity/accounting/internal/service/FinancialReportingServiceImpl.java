package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.JournalLineDrilldownResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.service.FinancialReportingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
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

    private final JournalEntryRepository journalEntryRepository;
    private final StatementLineMappingRepository statementLineMappingRepository;
    private final Clock clock;

    public FinancialReportingServiceImpl(
            JournalEntryRepository journalEntryRepository,
            StatementLineMappingRepository statementLineMappingRepository,
            Clock clock) {
        this.journalEntryRepository = journalEntryRepository;
        this.statementLineMappingRepository = statementLineMappingRepository;
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
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(StatementType.INCOME_STATEMENT);

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
                statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(StatementType.BALANCE_SHEET);

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
