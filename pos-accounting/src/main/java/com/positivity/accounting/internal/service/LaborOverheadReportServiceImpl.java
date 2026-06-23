package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.dto.LaborOverheadReportLine;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy.LineDef;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.service.LaborOverheadReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only implementation of the CAP-316 Labor &amp; Overhead Cost Report.
 *
 * <p>Resolves each leaf line to its mapped GL accounts (persisted {@code StatementLineMapping},
 * {@link StatementType#LABOR_OVERHEAD}), aggregates posted journal-entry-line net amounts
 * (debit − credit) by month for the requested {@code locationId} dimension, computes subtotal rows
 * column-wise from the taxonomy, and derives YTD over the elapsed months. Mirrors the posted-state
 * semantic ({@code je.status = 'POSTED'}) used by the existing financial reporting.
 */
@Service
@Transactional(readOnly = true)
public class LaborOverheadReportServiceImpl implements LaborOverheadReportService {

    private static final Logger log = LoggerFactory.getLogger(LaborOverheadReportServiceImpl.class);

    private static final int MONTHS = 12;
    private static final String LOCATION_DIMENSION = "locationId";
    private static final String CURRENCY_USD = "USD";
    private static final BigDecimal RATE_US_PLANT = new BigDecimal("1.00");

    private final StatementLineMappingRepository statementLineMappingRepository;
    private final JournalEntryLineRepository journalEntryLineRepository;

    public LaborOverheadReportServiceImpl(
            StatementLineMappingRepository statementLineMappingRepository,
            JournalEntryLineRepository journalEntryLineRepository) {
        this.statementLineMappingRepository = statementLineMappingRepository;
        this.journalEntryLineRepository = journalEntryLineRepository;
    }

    @Override
    public @NonNull LaborOverheadCostReport generate(
            @NonNull String locationId, int fiscalYear, @Nullable Integer asOfMonth) {

        int resolvedAsOfMonth = asOfMonth == null ? MONTHS : asOfMonth;
        log.info(
                "Generating Labor & Overhead report for location={}, fiscalYear={}, asOfMonth={}",
                locationId,
                fiscalYear,
                resolvedAsOfMonth);

        Map<String, Set<UUID>> leafToAccounts = resolveLeafToAccounts();
        Map<String, BigDecimal[]> leafMonthly = aggregateLeafMonthly(leafToAccounts, locationId, fiscalYear);

        Map<String, BigDecimal[]> monthlyByCode = new HashMap<>();
        List<LaborOverheadReportLine> reportLines = new ArrayList<>();
        for (LineDef def : LaborOverheadTaxonomy.lines()) {
            BigDecimal[] monthly = computeMonthly(def, leafMonthly, monthlyByCode);
            reportLines.add(toReportLine(def, monthly, resolvedAsOfMonth));
        }

        return LaborOverheadCostReport.builder()
                .locationId(locationId)
                .locationLabel(locationId)
                .fiscalYear(fiscalYear)
                .asOfMonth(resolvedAsOfMonth)
                .currency(CURRENCY_USD)
                .localCurrencyPerUsd(RATE_US_PLANT)
                .averageRate(RATE_US_PLANT)
                .lines(reportLines)
                .build();
    }

    /** Group LABOR_OVERHEAD mappings by line code into the set of contributing GL account ids. */
    private Map<String, Set<UUID>> resolveLeafToAccounts() {
        Map<String, Set<UUID>> leafToAccounts = new HashMap<>();
        statementLineMappingRepository
                .findByStatementTypeOrderByDisplayOrder(StatementType.LABOR_OVERHEAD)
                .forEach(mapping -> {
                    UUID accountId = mapping.getGlAccountId();
                    if (accountId != null && mapping.getStatementLineCode() != null) {
                        leafToAccounts
                                .computeIfAbsent(mapping.getStatementLineCode(), key -> new LinkedHashSet<>())
                                .add(accountId);
                    }
                });
        return leafToAccounts;
    }

    /**
     * Aggregate posted net amounts (debit − credit) per leaf line into a 12-element monthly array,
     * filtered to the requested location dimension.
     */
    private Map<String, BigDecimal[]> aggregateLeafMonthly(
            Map<String, Set<UUID>> leafToAccounts, String locationId, int fiscalYear) {

        Set<UUID> accountIds = new HashSet<>();
        leafToAccounts.values().forEach(accountIds::addAll);

        Map<UUID, BigDecimal[]> accountMonthly = new HashMap<>();
        if (!accountIds.isEmpty()) {
            LocalDateTime start = LocalDate.of(fiscalYear, 1, 1).atStartOfDay();
            LocalDateTime end = LocalDate.of(fiscalYear, 12, 31).atTime(LocalTime.MAX);
            List<JournalEntryLine> lines =
                    journalEntryLineRepository.findPostedLinesByAccountsAndDateRange(accountIds, start, end);
            for (JournalEntryLine line : lines) {
                if (!matchesLocation(line, locationId)) {
                    continue;
                }
                UUID accountId = line.getGlAccountId();
                LocalDateTime transactionDate = line.getJournalEntry().getTransactionDate();
                if (accountId == null || transactionDate == null) {
                    continue;
                }
                int monthIndex = transactionDate.getMonthValue() - 1;
                BigDecimal net = nullToZero(line.getDebitAmount()).subtract(nullToZero(line.getCreditAmount()));
                BigDecimal[] perAccount = accountMonthly.computeIfAbsent(accountId, key -> zeroMonths());
                perAccount[monthIndex] = perAccount[monthIndex].add(net);
            }
        }

        Map<String, BigDecimal[]> leafMonthly = new HashMap<>();
        leafToAccounts.forEach((code, accounts) -> {
            BigDecimal[] monthly = zeroMonths();
            for (UUID accountId : accounts) {
                BigDecimal[] perAccount = accountMonthly.get(accountId);
                if (perAccount != null) {
                    for (int m = 0; m < MONTHS; m++) {
                        monthly[m] = monthly[m].add(perAccount[m]);
                    }
                }
            }
            leafMonthly.put(code, monthly);
        });
        return leafMonthly;
    }

    /** Recursively compute a line's monthly array: leaves from aggregation, subtotals from children. */
    private BigDecimal[] computeMonthly(
            LineDef def, Map<String, BigDecimal[]> leafMonthly, Map<String, BigDecimal[]> memo) {
        BigDecimal[] cached = memo.get(def.code());
        if (cached != null) {
            return cached;
        }

        BigDecimal[] monthly;
        if (def.isSubtotal()) {
            monthly = zeroMonths();
            for (String childCode : def.childCodes()) {
                LineDef child = LaborOverheadTaxonomy.byCode(childCode);
                BigDecimal[] childMonthly = computeMonthly(child, leafMonthly, memo);
                for (int m = 0; m < MONTHS; m++) {
                    monthly[m] = monthly[m].add(childMonthly[m]);
                }
            }
        } else {
            monthly = leafMonthly.getOrDefault(def.code(), zeroMonths());
        }

        memo.put(def.code(), monthly);
        return monthly;
    }

    private LaborOverheadReportLine toReportLine(LineDef def, BigDecimal[] monthly, int asOfMonth) {
        BigDecimal ytd = BigDecimal.ZERO;
        for (int m = 0; m < asOfMonth && m < MONTHS; m++) {
            ytd = ytd.add(monthly[m]);
        }
        return LaborOverheadReportLine.builder()
                .code(def.code())
                .label(def.label())
                .parentCode(def.parentCode())
                .level(def.level())
                .costType(def.costType())
                .isSubtotal(def.isSubtotal())
                .definition(def.definition())
                .monthly(List.of(monthly))
                .ytd(ytd)
                .build();
    }

    private boolean matchesLocation(JournalEntryLine line, String locationId) {
        Map<String, String> dimensions = line.getDimensions();
        return dimensions != null && locationId.equals(dimensions.get(LOCATION_DIMENSION));
    }

    private static BigDecimal[] zeroMonths() {
        BigDecimal[] months = new BigDecimal[MONTHS];
        for (int m = 0; m < MONTHS; m++) {
            months[m] = BigDecimal.ZERO;
        }
        return months;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
