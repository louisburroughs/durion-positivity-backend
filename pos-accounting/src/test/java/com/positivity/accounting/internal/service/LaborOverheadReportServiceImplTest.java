package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.dto.LaborOverheadReportLine;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LaborOverheadReportServiceImpl}: taxonomy completeness, monthly bucketing,
 * subtotal roll-ups, YTD with mid-year asOfMonth, sign preservation, and location-dimension filtering.
 */
@ExtendWith(MockitoExtension.class)
class LaborOverheadReportServiceImplTest {

    private static final String LOCATION = "LOC-1";
    private static final String OTHER_LOCATION = "LOC-2";
    private static final int FISCAL_YEAR = 2026;

    private static final UUID ACCT_WAGES = UUID.fromString("a1000000-0000-7000-8000-000000000001");
    private static final UUID ACCT_WAGES_2 = UUID.fromString("a1000000-0000-7000-8000-00000000000a");
    private static final UUID ACCT_FICA = UUID.fromString("a1000000-0000-7000-8000-000000000002");
    private static final UUID ACCT_UTIL = UUID.fromString("a1000000-0000-7000-8000-000000000003");
    private static final UUID ACCT_INV = UUID.fromString("a1000000-0000-7000-8000-000000000004");
    private static final UUID ACCT_DUST = UUID.fromString("a1000000-0000-7000-8000-000000000005");

    @Mock
    private StatementLineMappingRepository statementLineMappingRepository;

    @Mock
    private JournalEntryLineRepository journalEntryLineRepository;

    private LaborOverheadReportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LaborOverheadReportServiceImpl(statementLineMappingRepository, journalEntryLineRepository);
    }

    private void mappings(StatementLineMapping... mappings) {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(eq(StatementType.LABOR_OVERHEAD)))
                .thenReturn(List.of(mappings));
    }

    private void postedLines(JournalEntryLine... lines) {
        when(journalEntryLineRepository.findPostedLinesByAccountsAndDateRange(any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(lines)));
    }

    private static StatementLineMapping mapping(String lineCode, UUID accountId) {
        StatementLineMapping mapping = new StatementLineMapping();
        mapping.setStatementType(StatementType.LABOR_OVERHEAD);
        mapping.setStatementLineCode(lineCode);
        mapping.setGlAccountId(accountId);
        return mapping;
    }

    private static JournalEntryLine line(
            UUID accountId, int month, String location, BigDecimal debit, BigDecimal credit) {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(LocalDateTime.of(FISCAL_YEAR, month, 15, 0, 0));

        JournalEntryLine line = new JournalEntryLine();
        line.setJournalEntry(entry);
        line.setGlAccountId(accountId);
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        line.setDimensions(Map.of("locationId", location));
        return line;
    }

    private static LaborOverheadReportLine find(LaborOverheadCostReport report, String code) {
        return report.getLines().stream()
                .filter(line -> line.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing line " + code));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void generate_returnsFullCanonicalTaxonomyInOrder() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO));

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(report.getLines()).hasSameSizeAs(LaborOverheadTaxonomy.lines());
        assertThat(report.getLines().get(0).getCode()).isEqualTo("1.1");
        assertThat(report.getLines().get(report.getLines().size() - 1).getCode())
                .isEqualTo(LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD);
        assertThat(find(report, "1.1.1").getMonthly()).hasSize(12);
    }

    @Test
    void generate_bucketsLeafAmountsByMonth() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO),
                line(ACCT_WAGES, 1, LOCATION, money("500"), BigDecimal.ZERO),
                line(ACCT_WAGES, 10, LOCATION, money("700"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 12), "1.1.1");

        assertThat(wages.getMonthly().get(0)).isEqualByComparingTo("1500"); // January
        assertThat(wages.getMonthly().get(9)).isEqualByComparingTo("700"); // October
        assertThat(wages.getYtd()).isEqualByComparingTo("2200");
    }

    @Test
    void generate_filtersByLocationDimension() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO),
                line(ACCT_WAGES, 1, OTHER_LOCATION, money("9999"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 12), "1.1.1");

        assertThat(wages.getMonthly().get(0)).isEqualByComparingTo("1000");
    }

    @Test
    void generate_rollsUpSubtotalsAndTotals() {
        mappings(mapping("1.1.1", ACCT_WAGES), mapping("1.3.1", ACCT_FICA));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO),
                line(ACCT_FICA, 1, LOCATION, money("76"), BigDecimal.ZERO));

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(find(report, "1.1").getMonthly().get(0)).isEqualByComparingTo("1000"); // 1.1.1 + 1.1.2
        assertThat(find(report, "1.3").getMonthly().get(0)).isEqualByComparingTo("76"); // 1.3.1
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_LABOR).getMonthly().get(0))
                .isEqualByComparingTo("1076"); // 1.1 + 1.2 + 1.3 + 1.5
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD)
                        .getMonthly()
                        .get(0))
                .isEqualByComparingTo("1076"); // labor only (no overhead postings)
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_LABOR).getYtd()).isEqualByComparingTo("1076");
    }

    @Test
    void generate_ytdBoundedByAsOfMonth() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(
                line(ACCT_WAGES, 3, LOCATION, money("300"), BigDecimal.ZERO),
                line(ACCT_WAGES, 9, LOCATION, money("900"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 6), "1.1.1");

        assertThat(wages.getMonthly().get(8)).isEqualByComparingTo("900"); // September still populated
        assertThat(wages.getYtd()).isEqualByComparingTo("300"); // only through June
    }

    @Test
    void generate_preservesNegativeSignForIncomeLines() {
        mappings(mapping("2.14", ACCT_DUST));
        postedLines(line(ACCT_DUST, 4, LOCATION, BigDecimal.ZERO, money("250"))); // credit -> income

        LaborOverheadReportLine dust = find(service.generate(LOCATION, FISCAL_YEAR, 12), "2.14");

        assertThat(dust.getMonthly().get(3)).isEqualByComparingTo("-250"); // April
        assertThat(dust.getYtd()).isEqualByComparingTo("-250");
    }

    @Test
    void generate_withNoMappings_returnsFullLayoutAllZero() {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(any()))
                .thenReturn(List.of());

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(report.getLines()).hasSameSizeAs(LaborOverheadTaxonomy.lines());
        assertThat(report.getLines())
                .allSatisfy(line -> assertThat(line.getYtd()).isEqualByComparingTo("0"));
        assertThat(find(report, "2.11.4")).isNotNull(); // USD line still present
    }

    @Test
    void generate_populatesUsPlantCurrencyContext() {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(any()))
                .thenReturn(List.of());

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, null);

        assertThat(report.getCurrency()).isEqualTo("USD");
        assertThat(report.getLocalCurrencyPerUsd()).isEqualByComparingTo("1.00");
        assertThat(report.getAverageRate()).isEqualByComparingTo("1.00");
        assertThat(report.getAsOfMonth()).isEqualTo(12); // null defaults to December
        assertThat(report.getLocationId()).isEqualTo(LOCATION);
    }

    @Test
    void generate_rollsUpOverheadAndGrandTotalFromDistinctSections() {
        mappings(mapping("1.1.1", ACCT_WAGES), mapping("2.10", ACCT_UTIL));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO),
                line(ACCT_UTIL, 1, LOCATION, money("250"), BigDecimal.ZERO));

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_LABOR).getMonthly().get(0))
                .isEqualByComparingTo("1000");
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_OVERHEAD)
                        .getMonthly()
                        .get(0))
                .isEqualByComparingTo("250");
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD)
                        .getMonthly()
                        .get(0))
                .isEqualByComparingTo("1250"); // labor + overhead, distinct sections
    }

    @Test
    void generate_negativeIncomeLineReducesOverheadTotal() {
        mappings(mapping("2.10", ACCT_UTIL), mapping("2.13", ACCT_INV));
        postedLines(
                line(ACCT_UTIL, 2, LOCATION, money("300"), BigDecimal.ZERO),
                line(ACCT_INV, 2, LOCATION, BigDecimal.ZERO, money("50"))); // credit -> interest income

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(find(report, "2.13").getMonthly().get(1)).isEqualByComparingTo("-50");
        assertThat(find(report, LaborOverheadTaxonomy.TOTAL_OVERHEAD)
                        .getMonthly()
                        .get(1))
                .isEqualByComparingTo("250"); // 300 - 50, sign survives roll-up
    }

    @Test
    void generate_sumsMultipleAccountsMappedToOneLine() {
        mappings(mapping("1.1.1", ACCT_WAGES), mapping("1.1.1", ACCT_WAGES_2));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("1000"), BigDecimal.ZERO),
                line(ACCT_WAGES_2, 1, LOCATION, money("400"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 12), "1.1.1");

        assertThat(wages.getMonthly().get(0)).isEqualByComparingTo("1400"); // both accounts summed
    }

    @Test
    void generate_ytdLowerBoundAsOfMonthOne() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("100"), BigDecimal.ZERO),
                line(ACCT_WAGES, 2, LOCATION, money("200"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 1), "1.1.1");

        assertThat(wages.getMonthly().get(1)).isEqualByComparingTo("200"); // February still populated
        assertThat(wages.getYtd()).isEqualByComparingTo("100"); // YTD only January
    }

    @Test
    void generate_ytdDefaultSumsFullYear() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        postedLines(
                line(ACCT_WAGES, 1, LOCATION, money("100"), BigDecimal.ZERO),
                line(ACCT_WAGES, 6, LOCATION, money("60"), BigDecimal.ZERO),
                line(ACCT_WAGES, 12, LOCATION, money("12"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, null), "1.1.1");

        assertThat(wages.getYtd()).isEqualByComparingTo("172"); // null asOfMonth -> all 12 months
    }

    @Test
    void generate_excludesLinesWithoutMatchingLocationDimension() {
        mappings(mapping("1.1.1", ACCT_WAGES));
        JournalEntryLine nullDimensions = line(ACCT_WAGES, 1, LOCATION, money("100"), BigDecimal.ZERO);
        nullDimensions.setDimensions(null);
        JournalEntryLine missingLocationKey = line(ACCT_WAGES, 1, LOCATION, money("200"), BigDecimal.ZERO);
        missingLocationKey.setDimensions(Map.of("costCenterId", "CC-1"));
        postedLines(nullDimensions, missingLocationKey, line(ACCT_WAGES, 1, LOCATION, money("5"), BigDecimal.ZERO));

        LaborOverheadReportLine wages = find(service.generate(LOCATION, FISCAL_YEAR, 12), "1.1.1");

        assertThat(wages.getMonthly().get(0)).isEqualByComparingTo("5"); // only the matching-location line counts
    }

    @Test
    void generate_flagsUsdOnlyOnLine2_11_4() {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrder(any()))
                .thenReturn(List.of());

        LaborOverheadCostReport report = service.generate(LOCATION, FISCAL_YEAR, 12);

        assertThat(find(report, "2.11.4").isUsdOnly()).isTrue();
        assertThat(find(report, "2.11.5").isUsdOnly()).isFalse();
        assertThat(find(report, "1.1.1").isUsdOnly()).isFalse();
    }
}
