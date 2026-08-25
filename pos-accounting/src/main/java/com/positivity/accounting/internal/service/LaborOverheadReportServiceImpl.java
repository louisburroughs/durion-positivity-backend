package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.dto.LaborOverheadReportLine;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.LocationFxRate;
import com.positivity.accounting.internal.entity.LocationProfile;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy.LineDef;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.LocationFxRateRepository;
import com.positivity.accounting.internal.repository.LocationProfileRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.service.LaborOverheadReportService;
import com.positivity.security.common.LogSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>Issue #731 enrichment: mappings are per-location-overridable (location rows replace global
 * rows per line code); the report header resolves {@code locationLabel}/{@code currency} from the
 * accounting-side {@link LocationProfile}, defaulting to a US plant (USD, 1.00) when unconfigured.
 * FX ({@link LocationFxRate}) is consulted only for a profiled non-USD plant — an orphaned FX row
 * never skews a USD report — and only then is the {@code usdOnly} line (2.11.4) presented in USD
 * via the year's average rate.
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
    private final LocationProfileRepository locationProfileRepository;
    private final LocationFxRateRepository locationFxRateRepository;

    public LaborOverheadReportServiceImpl(
            StatementLineMappingRepository statementLineMappingRepository,
            JournalEntryLineRepository journalEntryLineRepository,
            LocationProfileRepository locationProfileRepository,
            LocationFxRateRepository locationFxRateRepository) {
        this.statementLineMappingRepository = statementLineMappingRepository;
        this.journalEntryLineRepository = journalEntryLineRepository;
        this.locationProfileRepository = locationProfileRepository;
        this.locationFxRateRepository = locationFxRateRepository;
    }

    @Override
    public @NonNull LaborOverheadCostReport generate(
            @NonNull String locationId, int fiscalYear, @Nullable Integer asOfMonth) {

        int resolvedAsOfMonth = asOfMonth == null ? MONTHS : asOfMonth;
        log.info(
                "Generating Labor & Overhead report for location={}, fiscalYear={}, asOfMonth={}",
                LogSanitizer.forLog(locationId),
                fiscalYear,
                resolvedAsOfMonth);

        Map<String, Set<UUID>> leafToAccounts = resolveLeafToAccounts(locationId);
        Map<String, BigDecimal[]> leafMonthly = aggregateLeafMonthly(leafToAccounts, locationId, fiscalYear);

        LocationProfile profile =
                locationProfileRepository.findByLocationCode(locationId).orElse(null);
        // FX is active only for a profiled local-currency plant; a partial FX config without a
        // profile (or for a USD plant) must not skew the header rates or convert any line.
        boolean localCurrencyPlant = profile != null && !CURRENCY_USD.equals(profile.getCurrencyCode());
        LocationFxRate fxRate = localCurrencyPlant
                ? locationFxRateRepository
                        .findByLocationCodeAndFiscalYear(locationId, fiscalYear)
                        .orElse(null)
                : null;
        BigDecimal averageRate = fxRate != null ? fxRate.getAverageRate() : RATE_US_PLANT;

        Map<String, BigDecimal[]> monthlyByCode = new HashMap<>();
        List<LaborOverheadReportLine> reportLines = new ArrayList<>();
        for (LineDef def : LaborOverheadTaxonomy.lines()) {
            BigDecimal[] monthly = computeMonthly(def, leafMonthly, monthlyByCode);
            if (def.usdOnly() && localCurrencyPlant) {
                monthly = toUsd(monthly, averageRate);
            }
            reportLines.add(toReportLine(def, monthly, resolvedAsOfMonth));
        }

        return LaborOverheadCostReport.builder()
                .locationId(locationId)
                .locationLabel(profile != null ? profile.getLocationLabel() : locationId)
                .fiscalYear(fiscalYear)
                .asOfMonth(resolvedAsOfMonth)
                .currency(profile != null ? profile.getCurrencyCode() : CURRENCY_USD)
                .localCurrencyPerUsd(fxRate != null ? fxRate.getLocalCurrencyPerUsd() : RATE_US_PLANT)
                .averageRate(averageRate)
                .lines(reportLines)
                .build();
    }

    /**
     * Group LABOR_OVERHEAD mappings by line code into the set of contributing GL account ids.
     *
     * <p>Rows with a {@code null} location are the global default; rows carrying the requested
     * location <b>replace</b> the global rows for that line code. Rows scoped to other locations are
     * ignored.
     */
    private Map<String, Set<UUID>> resolveLeafToAccounts(String locationId) {
        Map<String, Set<UUID>> globalAccounts = new HashMap<>();
        Map<String, Set<UUID>> overrideAccounts = new HashMap<>();
        statementLineMappingRepository
                .findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(StatementType.LABOR_OVERHEAD)
                .forEach(mapping -> {
                    UUID accountId = mapping.getGlAccountId();
                    if (accountId == null || mapping.getStatementLineCode() == null) {
                        return;
                    }
                    String mappingLocation = mapping.getLocationId();
                    Map<String, Set<UUID>> target;
                    if (mappingLocation == null) {
                        target = globalAccounts;
                    } else if (mappingLocation.equals(locationId)) {
                        target = overrideAccounts;
                    } else {
                        return;
                    }
                    target.computeIfAbsent(mapping.getStatementLineCode(), key -> new LinkedHashSet<>())
                            .add(accountId);
                });
        Map<String, Set<UUID>> leafToAccounts = new HashMap<>(globalAccounts);
        leafToAccounts.putAll(overrideAccounts);
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

        Map<UUID, BigDecimal[]> accountMonthly = aggregateAccountMonthly(accountIds, locationId, fiscalYear);
        return rollUpLeafMonthly(leafToAccounts, accountMonthly);
    }

    /**
     * Aggregate posted net amounts (debit − credit) per GL account into a 12-element monthly array,
     * filtered to the requested location dimension. A line missing its GL account id or transaction
     * date (malformed data) is silently excluded rather than corrupting the month-index arithmetic.
     */
    private Map<UUID, BigDecimal[]> aggregateAccountMonthly(Set<UUID> accountIds, String locationId, int fiscalYear) {
        Map<UUID, BigDecimal[]> accountMonthly = new HashMap<>();
        if (accountIds.isEmpty()) {
            return accountMonthly;
        }

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
        return accountMonthly;
    }

    /**
     * Sum each leaf line's contributing accounts into its monthly array. A leaf mapped to an
     * account with no posted activity this fiscal year (no entry in {@code accountMonthly}) simply
     * contributes zero, rather than being omitted from the report.
     */
    private Map<String, BigDecimal[]> rollUpLeafMonthly(
            Map<String, Set<UUID>> leafToAccounts, Map<UUID, BigDecimal[]> accountMonthly) {
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
                .usdOnly(def.usdOnly())
                .definition(def.definition())
                .monthly(List.of(monthly))
                .ytd(ytd)
                .build();
    }

    /**
     * Convert a monthly array from local currency to USD using the year's average rate
     * ({@code usd = local / averageRate}). Applied only to {@code usdOnly} lines (2.11.4) on
     * local-currency plants; subtotals are computed from the unconverted local amounts, so a
     * local-currency subtotal is not the arithmetic sum of a converted USD child (source-form
     * behavior). Returns a new array — the memoized subtotal inputs are never mutated.
     */
    private static BigDecimal[] toUsd(BigDecimal[] monthly, BigDecimal averageRate) {
        if (BigDecimal.ONE.compareTo(averageRate) == 0) {
            return monthly;
        }
        BigDecimal[] converted = new BigDecimal[MONTHS];
        for (int m = 0; m < MONTHS; m++) {
            converted[m] = monthly[m].divide(averageRate, 2, RoundingMode.HALF_UP);
        }
        return converted;
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
