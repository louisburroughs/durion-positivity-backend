package com.positivity.domainevents.bankfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SettlementState;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SourceKind;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Payload rules of the bank-feed transactions batch (SPEC-manual-bank-reconciliation §6.5; #2301). */
@DisplayName("BankTransactionsObservedV1 — payload rules")
class BankTransactionsObservedV1Test {

    private static final Instant OBSERVED = Instant.parse("2026-09-28T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    static BankTransactionObserved added(String description, String amount) {
        return new BankTransactionObserved(
                null,
                1,
                Change.ADDED,
                SettlementState.POSTED,
                DAY,
                null,
                new BigDecimal(amount),
                null,
                description,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    static BankTransactionsObservedV1 batch(List<BankTransactionObserved> transactions) {
        return new BankTransactionsObservedV1(
                SourceKind.MANUAL_ENTRY, null, null, null, null, "USD", OBSERVED, null, null, transactions);
    }

    @Test
    void aValidBatchConstructsAndCopiesItsList() {
        List<BankTransactionObserved> rows = new ArrayList<>(List.of(added("FEE", "-5.00")));
        BankTransactionsObservedV1 batch = batch(rows);
        rows.clear();
        assertThat(batch.transactions()).hasSize(1);
        assertThat(BankTransactionsObservedV1.EVENT_TYPE).isEqualTo("bankfeed.transactions.observed");
        assertThat(BankTransactionsObservedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Nested
    class Batch {

        @Test
        void sourceKindIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionsObservedV1(
                            null, null, null, null, null, "USD", OBSERVED, null, null, List.of(added("X", "1"))))
                    .withMessageContaining("sourceKind");
        }

        @Test
        void currencyMustBeAnIso4217Code() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionsObservedV1(
                            SourceKind.MANUAL_ENTRY,
                            null,
                            null,
                            null,
                            null,
                            null,
                            OBSERVED,
                            null,
                            null,
                            List.of(added("X", "1"))));
            // Shaped like a code but not one: a pattern would accept it, the ISO list does not (ADR-0067 R-3).
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionsObservedV1(
                            SourceKind.MANUAL_ENTRY,
                            null,
                            null,
                            null,
                            null,
                            "ZZZ",
                            OBSERVED,
                            null,
                            null,
                            List.of(added("X", "1"))));
        }

        @Test
        void observedAtIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionsObservedV1(
                            SourceKind.MANUAL_ENTRY,
                            null,
                            null,
                            null,
                            null,
                            "USD",
                            null,
                            null,
                            null,
                            List.of(added("X", "1"))))
                    .withMessageContaining("observedAt");
        }

        @Test
        void aBatchWithoutTransactionsIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> batch(List.of()))
                    .withMessageContaining("empty");
            assertThatIllegalArgumentException().isThrownBy(() -> batch(null));
        }
    }

    @Nested
    class Element {

        @Test
        void changeIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            "T1",
                            1,
                            null,
                            SettlementState.POSTED,
                            DAY,
                            null,
                            BigDecimal.ONE,
                            null,
                            "X",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("change");
        }

        @Test
        void anAddedElementMayOmitTheSourceIdButAModifiedOneMayNot() {
            assertThatCode(() -> added("X", "1")).doesNotThrowAnyException();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            " ",
                            1,
                            Change.MODIFIED,
                            SettlementState.POSTED,
                            DAY,
                            null,
                            BigDecimal.ONE,
                            null,
                            "X",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("sourceTransactionId");
        }

        @Test
        void addedAndModifiedElementsNeedSettlementDateNonZeroAmountAndDescription() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            null,
                            1,
                            Change.ADDED,
                            null,
                            DAY,
                            null,
                            BigDecimal.ONE,
                            null,
                            "X",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("settlementState");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            null,
                            1,
                            Change.ADDED,
                            SettlementState.POSTED,
                            null,
                            null,
                            BigDecimal.ONE,
                            null,
                            "X",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("transactionDate");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> added("X", "0.00"))
                    .withMessageContaining("signedAmount");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> added("  ", "1"))
                    .withMessageContaining("description");
        }

        @Test
        void aRemovedElementCarriesOnlyItsSourceIdAndChange() {
            assertThatCode(() -> new BankTransactionObserved(
                            "T1",
                            null,
                            Change.REMOVED,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .doesNotThrowAnyException();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            "T1",
                            null,
                            Change.REMOVED,
                            null,
                            DAY,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("REMOVED");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            null,
                            null,
                            Change.REMOVED,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("sourceTransactionId");
        }

        @Test
        void aRemovedElementMayNotCarryASourceRowNumber() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            "T1",
                            7,
                            Change.REMOVED,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("REMOVED");
        }

        @Test
        void aRemovedElementMayNotCarryACurrency() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BankTransactionObserved(
                            "T1",
                            null,
                            Change.REMOVED,
                            null,
                            null,
                            null,
                            null,
                            "USD",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .withMessageContaining("REMOVED");
        }
    }

    @Nested
    class Header {

        @Test
        void startMustNotFollowEnd() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StatementHeader(null, DAY.plusDays(1), DAY, BigDecimal.ZERO, BigDecimal.ONE));
            assertThatCode(() -> new StatementHeader(null, DAY, DAY, BigDecimal.ZERO, BigDecimal.ONE))
                    .doesNotThrowAnyException();
        }

        @Test
        void balancesAreRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StatementHeader(null, DAY, DAY, null, BigDecimal.ONE));
        }
    }

    @Test
    @DisplayName("no class of the bankfeed package references a provider")
    void theContractNamesNoProvider() throws IOException {
        Path sources = Path.of("src/main/java/com/positivity/domainevents/bankfeed");
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                String text = Files.readString(file).toLowerCase(java.util.Locale.ROOT);
                assertThat(text)
                        .as(file.getFileName().toString())
                        .doesNotContain("import com.plaid")
                        .doesNotContain("plaid")
                        .doesNotContain("stripe")
                        .doesNotContain("yodlee");
            }
        }
    }
}
