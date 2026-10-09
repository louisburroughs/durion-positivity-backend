package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import com.positivity.domainevents.order.RegisterSessionClosedV1.StatedTax;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * CAP:550 S32d item 9 (AW52): the per-regime recovery matrix of a petty expense at close. Fixture data only (CAD,
 * GST_HST).
 */
@DisplayName("S32d petty-expense recovery decisions")
class PettyExpenseRecoveryDeciderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T20:00:00Z"), ZoneOffset.UTC);
    private static final Instant RECORDED = Instant.parse("2026-10-20T15:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-10-20");

    private final InputTaxRecoveryFlags flags = mock(InputTaxRecoveryFlags.class);
    private final InputTaxRecoveryService settings = mock(InputTaxRecoveryService.class);
    private final PettyExpenseRecoveryDecider decider =
            new PettyExpenseRecoveryDecider(flags, settings, TestZoneResolvers.utc(CLOCK));

    private static Movement movement(
            String amount,
            String stated,
            String supplierName,
            String receipt,
            String number,
            String plausibility,
            Boolean required,
            Instant occurredAt) {
        return new Movement(
                UUID.randomUUID(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal(amount),
                "CAD",
                "STAFF_MEALS",
                null,
                null,
                receipt,
                "clerk-1",
                null,
                null,
                occurredAt,
                supplierName,
                stated == null ? List.of() : List.of(new StatedTax("GST_HST", new BigDecimal(stated))),
                number,
                plausibility,
                required);
    }

    private static Movement eligible(String stated) {
        return movement("40.00", stated, "Diner", "R-1", null, Movement.PLAUSIBLE, Boolean.FALSE, RECORDED);
    }

    private void onAt(String percent) {
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(true);
        when(settings.shareInForce("STAFF_MEALS", RECORDED)).thenReturn(Optional.of(new BigDecimal(percent)));
    }

    @Test
    @DisplayName("AC 5: an eligible regime at 100 % recovers the stated amount")
    void eligibleRecoversAll() {
        onAt("100.00");

        assertThat(decider.decide(eligible("4.60"))).singleElement().satisfies(d -> {
            assertThat(d.recovered()).isEqualByComparingTo("4.60");
            assertThat(d.withheldReason()).isNull();
        });
    }

    @Test
    @DisplayName("AC 7: 3.15 at 50 % recovers 1.58 (HALF_UP at the currency exponent)")
    void halfUpAtFifty() {
        onAt("50.00");

        assertThat(decider.decide(eligible("3.15")).getFirst().recovered()).isEqualTo(new BigDecimal("1.58"));
    }

    @Test
    @DisplayName("AC 7 / AW52: the share in force when the movement was recorded, not at close")
    void shareAtRecording() {
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(true);
        Instant at1000 = Instant.parse("2026-10-20T10:00:00Z");
        Instant at1500 = Instant.parse("2026-10-20T15:00:00Z");
        when(settings.shareInForce("STAFF_MEALS", at1000)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(settings.shareInForce("STAFF_MEALS", at1500)).thenReturn(Optional.of(new BigDecimal("50.00")));

        BigDecimal morning = decider.decide(
                        movement("40.00", "3.15", "Diner", "R-1", null, Movement.PLAUSIBLE, null, at1000))
                .getFirst()
                .recovered();
        BigDecimal afternoon = decider.decide(
                        movement("40.00", "3.15", "Diner", "R-2", null, Movement.PLAUSIBLE, null, at1500))
                .getFirst()
                .recovered();

        assertThat(morning).isEqualByComparingTo("3.15");
        assertThat(afternoon).isEqualByComparingTo("1.58");
    }

    @Test
    @DisplayName("AC 3: no registration on the movement's date withholds NOT_REGISTERED")
    void notRegistered() {
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(false);
        when(settings.shareInForce(anyString(), any())).thenReturn(Optional.of(new BigDecimal("100.00")));

        assertThat(decider.decide(eligible("4.60")).getFirst()).satisfies(d -> {
            assertThat(d.withheldReason()).isEqualTo("NOT_REGISTERED");
            assertThat(d.recovered()).isZero();
        });
    }

    @Test
    @DisplayName("item 9: a category not recoverable when recorded withholds CATEGORY_NOT_RECOVERABLE")
    void categoryNotRecoverable() {
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(true);
        when(settings.shareInForce("STAFF_MEALS", RECORDED)).thenReturn(Optional.empty());

        assertThat(decider.decide(eligible("4.60")).getFirst().withheldReason()).isEqualTo("CATEGORY_NOT_RECOVERABLE");
    }

    @Test
    @DisplayName("ruling 4: pos-order's copy offered the regime (recoverable) but accounting's history says not at"
            + " recording: CATEGORY_NOT_RECOVERABLE, and the stated amount still gets its row")
    void accountingHistoryDecidesNotTheOrderCopy() {
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(true);
        // The fact carries no recoverability: pos-order offered GST_HST from its copy, but accounting's own history
        // has the category off when the movement was recorded (AW52).
        when(settings.shareInForce("STAFF_MEALS", RECORDED)).thenReturn(Optional.empty());

        assertThat(decider.decide(eligible("4.60"))).singleElement().satisfies(d -> {
            assertThat(d.withheldReason()).isEqualTo("CATEGORY_NOT_RECOVERABLE");
            assertThat(d.recovered()).isZero();
            assertThat(d.stated()).isEqualByComparingTo("4.60");
        });
    }

    @Test
    @DisplayName("ruling 4: the reasons are decided in their fixed order; the first that applies is recorded")
    void reasonsInFixedOrder() {
        // Every condition fails at once: no registration, category off, not checked, no evidence, number missing.
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(false);
        when(settings.shareInForce(anyString(), any())).thenReturn(Optional.empty());
        Movement worst = movement("150.00", "7.14", null, null, null, null, true, RECORDED);
        assertThat(decider.decide(worst).getFirst().withheldReason()).isEqualTo("NOT_REGISTERED");

        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenReturn(true);
        assertThat(decider.decide(worst).getFirst().withheldReason()).isEqualTo("CATEGORY_NOT_RECOVERABLE");

        when(settings.shareInForce(anyString(), any())).thenReturn(Optional.of(new BigDecimal("100.00")));
        assertThat(decider.decide(worst).getFirst().withheldReason()).isEqualTo("RATE_UNAVAILABLE");

        Movement checked = movement("150.00", "7.14", null, null, null, Movement.PLAUSIBLE, true, RECORDED);
        assertThat(decider.decide(checked).getFirst().withheldReason()).isEqualTo("EVIDENCE_MISSING");

        Movement evidenced = movement("150.00", "7.14", "Supplier", "R-1", null, Movement.PLAUSIBLE, true, RECORDED);
        assertThat(decider.decide(evidenced).getFirst().withheldReason()).isEqualTo("SUPPLIER_REGISTRATION_MISSING");
    }

    @ParameterizedTest(name = "plausibility {0} -> RATE_UNAVAILABLE")
    @CsvSource(
            value = {"RATE_UNAVAILABLE", "NULL"},
            nullValues = "NULL")
    @DisplayName("item 9: anything but PLAUSIBLE withholds RATE_UNAVAILABLE")
    void notPlausible(String plausibility) {
        onAt("100.00");

        assertThat(decider.decide(movement("40.00", "4.60", "Diner", "R-1", null, plausibility, null, RECORDED))
                        .getFirst()
                        .withheldReason())
                .isEqualTo("RATE_UNAVAILABLE");
    }

    @ParameterizedTest(name = "supplier {0}, receipt {1} -> EVIDENCE_MISSING")
    @CsvSource(
            value = {"NULL,R-1", "Diner,NULL", "' ',R-1"},
            nullValues = "NULL")
    @DisplayName("item 9: a missing supplier name or receipt reference withholds EVIDENCE_MISSING")
    void evidenceMissing(String supplier, String receipt) {
        onAt("100.00");

        assertThat(decider.decide(
                                movement("40.00", "4.60", supplier, receipt, null, Movement.PLAUSIBLE, null, RECORDED))
                        .getFirst()
                        .withheldReason())
                .isEqualTo("EVIDENCE_MISSING");
    }

    @Test
    @DisplayName("AC 6: a required number that is missing withholds SUPPLIER_REGISTRATION_MISSING; with it, recovered")
    void supplierRegistration() {
        onAt("100.00");

        assertThat(decider.decide(
                                movement("150.00", "7.14", "Supplier", "R-1", null, Movement.PLAUSIBLE, true, RECORDED))
                        .getFirst()
                        .withheldReason())
                .isEqualTo("SUPPLIER_REGISTRATION_MISSING");
        assertThat(decider.decide(movement(
                                "150.00",
                                "7.14",
                                "Supplier",
                                "R-1",
                                "123456789RT0001",
                                Movement.PLAUSIBLE,
                                true,
                                RECORDED))
                        .getFirst()
                        .recovered())
                .isEqualByComparingTo("7.14");
    }

    @Test
    @DisplayName("item 9: no stated tax decides nothing and asks nothing; a pre-S32d null list is empty")
    void nothingStated() {
        assertThat(decider.decide(eligible(null))).isEmpty();
        Movement preS32d = new Movement(
                UUID.randomUUID(),
                "PETTY_EXPENSE",
                "OUT",
                BigDecimal.TEN,
                "CAD",
                "STAFF_MEALS",
                null,
                null,
                "R",
                "clerk",
                null,
                null,
                RECORDED,
                null,
                null,
                null,
                null,
                null);
        assertThat(decider.decide(preS32d)).isEmpty();
        verifyNoInteractions(flags, settings);
    }

    @Test
    @DisplayName("AW49: a flag that cannot be obtained propagates, never read as off")
    void unavailablePropagates() {
        when(settings.shareInForce(anyString(), any())).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(flags.inputTaxRecovery(DATE, "GST_HST")).thenThrow(new TaxServiceUnavailableException("down"));

        assertThatThrownBy(() -> decider.decide(eligible("4.60"))).isInstanceOf(TaxServiceUnavailableException.class);
    }
}
