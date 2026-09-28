package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Posting;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Window;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * E3 and the opening terms term by term (SPEC-manual-bank-reconciliation §3.6, §3.7, §8.1; story S4, #2303,
 * acceptance criteria 2–4). Every [M] case names the mutation it kills.
 */
@DisplayName("ReconciliationEquation — E3 and the opening terms (#2303)")
class ReconciliationEquationTest {

    private static final Window SEPTEMBER = new Window(STATEMENT_ID, START, END, amount("1000.00"), amount("1300.00"));

    @Nested
    @DisplayName("open at the end of a day (§3.6)")
    class OpenAtDate {

        @Test
        @DisplayName("an item is open from its date until the day before closedOn, never once RELEASED")
        void openAtDate() {
            BankReconciliationOutstandingItem deposit = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                    "500",
                    LocalDate.of(2026, 9, 28));
            assertThat(ReconciliationEquation.isOpenAt(deposit, LocalDate.of(2026, 9, 27)))
                    .isFalse();
            assertThat(ReconciliationEquation.isOpenAt(deposit, LocalDate.of(2026, 9, 28)))
                    .isTrue();

            deposit.setStatus(OutstandingItemStatus.CLEARED);
            deposit.setClosedOn(LocalDate.of(2026, 10, 2));
            assertThat(ReconciliationEquation.isOpenAt(deposit, LocalDate.of(2026, 10, 1)))
                    .as("closed on 10-02, it was still open at the end of 10-01")
                    .isTrue();
            assertThat(ReconciliationEquation.isOpenAt(deposit, LocalDate.of(2026, 10, 2)))
                    .isFalse();

            deposit.setStatus(OutstandingItemStatus.RELEASED);
            deposit.setClosedOn(null);
            assertThat(ReconciliationEquation.isOpenAt(deposit, LocalDate.of(2026, 10, 1)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("E3 (criteria 1–3)")
    class ClosingTerms {

        @Test
        @DisplayName("a deposit in transit +500 and an outstanding check −200 add 300 to the bank side [M]")
        void timingItemsAdjustTheBankSide() {
            BankReconciliationOutstandingItem deposit = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                    "500",
                    LocalDate.of(2026, 9, 29));
            BankReconciliationOutstandingItem check = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.OUTSTANDING_CHECK,
                    "-200",
                    LocalDate.of(2026, 9, 25));
            // The ledger holds both lines: book 1300 + 500 − 200 = 1600.
            Terms terms = ReconciliationEquation.compute(
                    SEPTEMBER, List.of(deposit, check), List.of(), amount("1600.00"), amount("1000.00"));

            assertThat(terms.sumOutstandingLedgerItems()).isEqualByComparingTo("300");
            assertThat(terms.adjustedBankBalance()).isEqualByComparingTo("1600.00");
            assertThat(terms.difference()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a bank-side item the bank will reverse is subtracted from the bank side [M]")
        void bankErrorIsSubtracted() {
            BankReconciliationOutstandingItem bankError = item(
                    OutstandingItemSide.BANK, OutstandingItemKind.BANK_ERROR_PENDING, "75", LocalDate.of(2026, 9, 10));
            Terms terms = ReconciliationEquation.compute(
                    SEPTEMBER, List.of(bankError), List.of(), amount("1225.00"), amount("1000.00"));

            assertThat(terms.sumOutstandingBankItems()).isEqualByComparingTo("75");
            assertThat(terms.adjustedBankBalance()).isEqualByComparingTo("1225.00");
            assertThat(terms.difference()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an item dated after the window end is not in its closing terms")
        void laterItemIsOutsideTheWindow() {
            BankReconciliationOutstandingItem october = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                    "500",
                    LocalDate.of(2026, 10, 1));
            Terms terms = ReconciliationEquation.compute(
                    SEPTEMBER, List.of(october), List.of(), amount("1300.00"), amount("1000.00"));
            assertThat(terms.sumOutstandingLedgerItems()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a closed-month fee posted in the open month counts once in each window [M]")
        void lateAdjustmentCountedOnceAcrossWindows() {
            UUID august = UUIDv7Generator.generate();
            UUID september = UUIDv7Generator.generate();
            // An August fee (−15) dated 2026-09-05 because August was CLOSED; owned by the August window.
            Posting fee = posting(august, LocalDate.of(2026, 8, 31), null, LocalDate.of(2026, 9, 5), "-15");
            Window augustWindow = new Window(
                    UUIDv7Generator.generate(),
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31),
                    amount("1015"),
                    amount("985"));
            // August ledger has no fee yet: 1000; bank closed at 985 with the fee.
            Terms augustTerms = ReconciliationEquation.compute(
                    augustWindow, List.of(), List.of(fee), amount("1000"), amount("1015"));
            assertThat(augustTerms.sumLateAdjustments()).isEqualByComparingTo("-15");
            assertThat(augustTerms.difference()).isEqualByComparingTo("0");

            // September: the fee is inside the live balance (985) and is not late again.
            Window septemberWindow = new Window(STATEMENT_ID, START, END, amount("985"), amount("985"));
            Terms septemberTerms = ReconciliationEquation.compute(
                    septemberWindow, List.of(), List.of(fee), amount("985"), amount("1000"));
            assertThat(septemberTerms.sumLateAdjustments()).isEqualByComparingTo("0");
            assertThat(septemberTerms.difference()).isEqualByComparingTo("0");
            assertThat(septemberTerms.sumOpeningAdjustments())
                    .as("the predecessor's late adjustment enters the successor's opening terms")
                    .isEqualByComparingTo("-15");
            assertThat(septemberTerms.openingDifference()).isEqualByComparingTo("0");
            assertThat(september).isNotNull();
        }

        @Test
        @DisplayName("an August fee dated in October is late for September too, and counted once in October")
        void twoWindowsReconciledAfterBothClosed() {
            UUID august = UUIDv7Generator.generate();
            Posting fee = posting(august, LocalDate.of(2026, 8, 31), null, LocalDate.of(2026, 10, 5), "-15");
            Window septemberWindow = new Window(STATEMENT_ID, START, END, amount("985"), amount("985"));
            Terms september = ReconciliationEquation.compute(
                    septemberWindow, List.of(), List.of(fee), amount("1000"), amount("1000"));
            assertThat(september.sumLateAdjustments()).isEqualByComparingTo("-15");
            assertThat(september.difference()).isEqualByComparingTo("0");

            Window octoberWindow = new Window(
                    UUIDv7Generator.generate(),
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 31),
                    amount("985"),
                    amount("985"));
            Terms october = ReconciliationEquation.compute(
                    octoberWindow, List.of(), List.of(fee), amount("985"), amount("1000"));
            assertThat(october.sumLateAdjustments()).isEqualByComparingTo("0");
            assertThat(october.difference()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a reversed adjustment contributes nothing from its reversal's date on")
        void reversedAdjustmentNetsOut() {
            UUID owner = UUIDv7Generator.generate();
            Posting original = posting(owner, END, null, LocalDate.of(2026, 10, 5), "-15");
            Posting reversal = new Posting(
                    original.adjustmentId(),
                    owner,
                    UUIDv7Generator.generate(),
                    END,
                    null,
                    LocalDate.of(2026, 10, 9),
                    amount("15"),
                    true);
            assertThat(ReconciliationEquation.sumPostings(
                            ReconciliationEquation.latePostings(List.of(original, reversal), END)))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("opening terms (criterion 4, D2)")
    class OpeningTerms {

        @Test
        @DisplayName("a deposit registered before and cleared in this window still counts at its start [M]")
        void clearedDepositCountsAtTheStart() {
            BankReconciliationOutstandingItem deposit = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                    "500",
                    LocalDate.of(2026, 8, 30));
            deposit.setStatus(OutstandingItemStatus.CLEARED);
            deposit.setClosedOn(LocalDate.of(2026, 9, 2));
            // Bank opened at 1000 without the deposit; the book held it: 1500.
            Terms terms = ReconciliationEquation.compute(
                    SEPTEMBER, List.of(deposit), List.of(), amount("1300"), amount("1500"));

            assertThat(terms.openingLedgerItems()).isEqualByComparingTo("500");
            assertThat(terms.openingDifference()).isEqualByComparingTo("0");
            assertThat(terms.sumOutstandingLedgerItems())
                    .as("cleared on 09-02, it is not in the closing terms")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("this statement's gap bridge enters sumOpeningAdjustments; another statement's does not")
        void bridgeEntersTheOpeningTerms() {
            UUID owner = UUIDv7Generator.generate();
            // A bridge of −45.67 dated in the earliest OPEN period, after the window start.
            Posting bridge = posting(owner, END, STATEMENT_ID, LocalDate.of(2026, 9, 3), "-45.67");
            Posting otherBridge = posting(owner, END, UUIDv7Generator.generate(), LocalDate.of(2026, 9, 3), "-10.00");
            Window window = new Window(STATEMENT_ID, START, END, amount("954.33"), amount("954.33"));
            Terms terms = ReconciliationEquation.compute(
                    window, List.of(), List.of(bridge, otherBridge), amount("944.33"), amount("1000"));

            assertThat(terms.sumOpeningAdjustments()).isEqualByComparingTo("-45.67");
            assertThat(terms.openingDifference()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a missing opening balance gives no opening difference")
        void noOpeningBalance() {
            Window window = new Window(STATEMENT_ID, START, END, null, amount("10"));
            Terms terms = ReconciliationEquation.compute(window, List.of(), List.of(), amount("10"), amount("0"));
            assertThat(terms.openingDifference()).isNull();
        }
    }

    @Test
    @DisplayName("tolerance is inclusive at one minor unit")
    void tolerance() {
        BigDecimal cent = amount("0.01");
        assertThat(ReconciliationEquation.withinTolerance(amount("0.0100"), cent))
                .isTrue();
        assertThat(ReconciliationEquation.withinTolerance(amount("-0.0100"), cent))
                .isTrue();
        assertThat(ReconciliationEquation.withinTolerance(amount("0.0101"), cent))
                .isFalse();
        assertThat(ReconciliationEquation.withinTolerance(null, cent)).isTrue();
    }

    private static Posting posting(
            UUID reconciliationId, LocalDate ownerEnd, UUID bridgesStatementId, LocalDate date, String amount) {
        return new Posting(
                UUIDv7Generator.generate(),
                reconciliationId,
                UUIDv7Generator.generate(),
                ownerEnd,
                bridgesStatementId,
                date,
                amount(amount),
                false);
    }
}
