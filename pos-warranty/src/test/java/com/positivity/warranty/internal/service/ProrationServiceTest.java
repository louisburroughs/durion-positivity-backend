package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.internal.dto.ProrationOutcome;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.ProrationMethod;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proration math per the PRD §6 table: all four methods, clamping to [0, 1] and
 * [0, originalUnitPrice x quantity], HALF_UP rounding (pct scale 6, money scale 4), and
 * indeterminate (never divide-by-zero) outcomes for missing facts and zero denominators.
 */
class ProrationServiceTest {

    private final ProrationService service = new ProrationService();

    private static WarrantyPolicy policy(ProrationMethod method) {
        return WarrantyPolicy.builder().prorationMethod(method).build();
    }

    private static WarrantyClaimLine line(String unitPrice, String quantity) {
        return WarrantyClaimLine.builder()
                .originalUnitPrice(unitPrice != null ? new BigDecimal(unitPrice) : null)
                .quantity(quantity != null ? new BigDecimal(quantity) : null)
                .build();
    }

    // --- NONE ------------------------------------------------------------------------------

    @Test
    void none_isFullCredit() {
        ProrationOutcome outcome = service.prorate(policy(ProrationMethod.NONE), line("100.00", "2"), null, null);

        assertThat(outcome.indeterminate()).isFalse();
        assertThat(outcome.prorationPct()).isEqualByComparingTo("1");
        assertThat(outcome.prorationPct().scale()).isEqualTo(6);
        assertThat(outcome.amountRequested()).isEqualByComparingTo("200.00");
        assertThat(outcome.amountRequested().scale()).isEqualTo(4);
        assertThat(outcome.inputs()).containsEntry("method", "NONE");
    }

    @Test
    void none_missingPrice_isIndeterminateButKeepsPct() {
        ProrationOutcome outcome = service.prorate(policy(ProrationMethod.NONE), line(null, "1"), null, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isEqualByComparingTo("1");
        assertThat(outcome.amountRequested()).isNull();
        assertThat(outcome.inputs()).containsEntry("missing", List.of("originalUnitPrice"));
    }

    // --- TREAD_DEPTH -----------------------------------------------------------------------

    private static WarrantyClaimLine tireLine(Integer measured, Integer original) {
        return WarrantyClaimLine.builder()
                .measuredTreadDepth(measured)
                .originalTreadDepth(original)
                .originalUnitPrice(new BigDecimal("100.00"))
                .quantity(BigDecimal.ONE)
                .build();
    }

    private static WarrantyPolicy treadPolicy(Integer pullPoint) {
        return WarrantyPolicy.builder()
                .prorationMethod(ProrationMethod.TREAD_DEPTH)
                .treadPullPointThirtySeconds(pullPoint)
                .build();
    }

    @Test
    void treadDepth_industryStandardFraction() {
        // (6 - 2) / (10 - 2) = 0.5
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(6, 10), null, null);

        assertThat(outcome.indeterminate()).isFalse();
        assertThat(outcome.prorationPct()).isEqualByComparingTo("0.5");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("50.00");
    }

    @Test
    void treadDepth_roundsHalfUpAtScale6() {
        // (5 - 2) / (11 - 2) = 1/3 -> 0.333333
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(5, 11), null, null);

        assertThat(outcome.prorationPct()).isEqualTo(new BigDecimal("0.333333"));
    }

    @Test
    void treadDepth_belowPullPoint_clampsToZero() {
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(1, 10), null, null);

        assertThat(outcome.indeterminate()).isFalse();
        assertThat(outcome.prorationPct()).isEqualByComparingTo("0");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("0");
    }

    @Test
    void treadDepth_aboveOriginal_clampsToOne() {
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(12, 10), null, null);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("1");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("100.00");
    }

    @Test
    void treadDepth_zeroDenominator_isIndeterminateNotDivideByZero() {
        // original == pull point -> zero usable tread
        ProrationOutcome outcome = service.prorate(treadPolicy(10), tireLine(6, 10), null, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
        assertThat(outcome.amountRequested()).isNull();
    }

    @Test
    void treadDepth_missingMeasurement_isIndeterminate() {
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(null, 10), null, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
        assertThat(outcome.inputs()).containsEntry("missing", List.of("measuredTreadDepth"));
    }

    @Test
    void treadDepth_nullPullPoint_defaultsToZero() {
        // (5 - 0) / (10 - 0) = 0.5
        ProrationOutcome outcome = service.prorate(treadPolicy(null), tireLine(5, 10), null, null);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("0.5");
        assertThat(outcome.inputs()).containsEntry("treadPullPointThirtySeconds", 0);
    }

    // --- MILEAGE ---------------------------------------------------------------------------

    private static WarrantyPolicy mileagePolicy(Integer limit) {
        return WarrantyPolicy.builder()
                .prorationMethod(ProrationMethod.MILEAGE)
                .mileageLimit(limit)
                .build();
    }

    @Test
    void mileage_fraction() {
        // (40000 - 10000) / 40000 = 0.75
        ProrationOutcome outcome = service.prorate(mileagePolicy(40000), line("100.00", "4"), 10000L, null);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("0.75");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("300.00");
    }

    @Test
    void mileage_overLimit_clampsToZero() {
        ProrationOutcome outcome = service.prorate(mileagePolicy(40000), line("100.00", "1"), 50000L, null);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("0");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("0");
    }

    @Test
    void mileage_unknownMilesDriven_isIndeterminate() {
        ProrationOutcome outcome = service.prorate(mileagePolicy(40000), line("100.00", "1"), null, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
        assertThat(outcome.inputs()).containsEntry("missing", List.of("milesDriven"));
    }

    @Test
    void mileage_zeroLimit_isIndeterminateNotDivideByZero() {
        ProrationOutcome outcome = service.prorate(mileagePolicy(0), line("100.00", "1"), 100L, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
    }

    // --- TIME ------------------------------------------------------------------------------

    private static WarrantyPolicy timePolicy(Integer durationMonths) {
        return WarrantyPolicy.builder()
                .prorationMethod(ProrationMethod.TIME)
                .durationMonths(durationMonths)
                .build();
    }

    @Test
    void time_fraction() {
        // (12 - 3) / 12 = 0.75
        ProrationOutcome outcome = service.prorate(timePolicy(12), line("100.00", "1"), null, 3L);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("0.75");
        assertThat(outcome.amountRequested()).isEqualByComparingTo("75.00");
    }

    @Test
    void time_pastDuration_clampsToZero() {
        ProrationOutcome outcome = service.prorate(timePolicy(12), line("100.00", "1"), null, 24L);

        assertThat(outcome.prorationPct()).isEqualByComparingTo("0");
    }

    @Test
    void time_unknownElapsed_isIndeterminate() {
        ProrationOutcome outcome = service.prorate(timePolicy(12), line("100.00", "1"), null, null);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
        assertThat(outcome.inputs()).containsEntry("missing", List.of("monthsElapsed"));
    }

    @Test
    void time_zeroDuration_isIndeterminateNotDivideByZero() {
        ProrationOutcome outcome = service.prorate(timePolicy(0), line("100.00", "1"), null, 1L);

        assertThat(outcome.indeterminate()).isTrue();
        assertThat(outcome.prorationPct()).isNull();
    }

    // --- Audit inputs ------------------------------------------------------------------------

    @Test
    void inputs_freezeEveryNumberThatFedTheComputation() {
        ProrationOutcome outcome = service.prorate(treadPolicy(2), tireLine(6, 10), null, null);

        assertThat(outcome.inputs())
                .containsEntry("method", "TREAD_DEPTH")
                .containsEntry("measuredTreadDepth", 6)
                .containsEntry("originalTreadDepth", 10)
                .containsEntry("treadPullPointThirtySeconds", 2)
                .containsEntry("originalUnitPrice", new BigDecimal("100.00"))
                .containsEntry("quantity", BigDecimal.ONE)
                .containsKey("prorationPct")
                .containsKey("amountRequested");
    }

    @Test
    void missingQuantity_defaultsToOne() {
        ProrationOutcome outcome = service.prorate(policy(ProrationMethod.NONE), line("59.99", null), null, null);

        assertThat(outcome.amountRequested()).isEqualByComparingTo("59.99");
        assertThat(outcome.inputs()).containsEntry("quantity", BigDecimal.ONE);
    }
}
