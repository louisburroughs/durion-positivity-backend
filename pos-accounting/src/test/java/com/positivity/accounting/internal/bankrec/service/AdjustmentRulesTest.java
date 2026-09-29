package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.service.AdjustmentRules.Links;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The adjustment link rule, the {@code OTHER} authority matrix and the D7 date rule (SPEC §3.5, §4.7, §8.3;
 * story S4, #2303, criteria 10, 13, 15). Every [M] case names the mutation it kills.
 */
@DisplayName("AdjustmentRules — links, authority and date (#2303)")
class AdjustmentRulesTest {

    private static final UUID ID = UUID.randomUUID();

    private static void assertLinkRequired(BankAdjustmentType type, Links links) {
        assertThatThrownBy(() -> AdjustmentRules.requireLinks(type, links))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_REQUIRED));
    }

    @Nested
    @DisplayName("link rule (criteria 10, 15)")
    class LinkRule {

        @Test
        @DisplayName("an OTHER without a link, or with two, is ADJUSTMENT_LINK_REQUIRED [M]")
        void otherNeedsExactlyOneLink() {
            assertLinkRequired(BankAdjustmentType.OTHER, new Links(null, null, null, null));
            assertLinkRequired(BankAdjustmentType.OTHER, new Links(ID, ID, null, null));
            assertLinkRequired(BankAdjustmentType.OTHER, new Links(ID, null, ID, null));
            for (Links one : Set.of(
                    new Links(ID, null, null, null),
                    new Links(null, ID, null, null),
                    new Links(null, null, ID, null))) {
                assertThatCode(() -> AdjustmentRules.requireLinks(BankAdjustmentType.OTHER, one))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("settlesMatchId or bridgesStatementId on a typed adjustment is ADJUSTMENT_LINK_REQUIRED")
        void residualAndBridgeAreOtherOnly() {
            assertLinkRequired(BankAdjustmentType.BANK_FEE, new Links(null, ID, null, null));
            assertLinkRequired(BankAdjustmentType.INTEREST_EARNED, new Links(null, null, ID, null));
        }

        @Test
        @DisplayName("a TRANSFER needs counterGlAccountId and no other type may carry one [M]")
        void transferCounter() {
            assertLinkRequired(BankAdjustmentType.TRANSFER, new Links(null, null, null, null));
            assertLinkRequired(BankAdjustmentType.BANK_FEE, new Links(null, null, null, ID));
            assertThatCode(() ->
                            AdjustmentRules.requireLinks(BankAdjustmentType.TRANSFER, new Links(ID, null, null, ID)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a typed adjustment may be linked to a bank transaction or left unlinked")
        void typedLinksAreOptional() {
            assertThatCode(() -> AdjustmentRules.requireLinks(
                            BankAdjustmentType.BANK_FEE, new Links(null, null, null, null)))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                            AdjustmentRules.requireLinks(BankAdjustmentType.NSF_FEE, new Links(ID, null, null, null)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("OTHER authority (criterion 13)")
    class Authority {

        @Test
        @DisplayName("while the threshold is unset every OTHER but a residual needs approve [M]")
        void unsetThreshold() {
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("5.00"), Optional.empty(), false))
                    .isTrue();
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("-0.01"), Optional.empty(), true))
                    .isFalse();
        }

        @Test
        @DisplayName("with the key at 50.00: 50.00 needs adjust only, 50.01 needs approve, either sign")
        void thresholdSet() {
            Optional<BigDecimal> fifty = Optional.of(new BigDecimal("50.00"));
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("50.00"), fifty, false))
                    .isFalse();
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("-50.00"), fifty, false))
                    .isFalse();
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("50.01"), fifty, false))
                    .isTrue();
            assertThat(AdjustmentRules.otherNeedsApproval(new BigDecimal("-50.01"), fifty, true))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("date rule (D7)")
    class DateRule {

        private final LocalDate bankDate = LocalDate.of(2026, 8, 28);
        private final LocalDate requested = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("the explaining date wins while its period is open, whatever the request")
        void explainingDateWhenOpen() {
            assertThat(AdjustmentRules.postingDate(bankDate, requested, d -> false))
                    .isEqualTo(bankDate);
        }

        @Test
        @DisplayName("a closed explaining date gives way to the request date")
        void requestDateWhenClosed() {
            assertThat(AdjustmentRules.postingDate(bankDate, requested, d -> d.getMonthValue() == 8))
                    .isEqualTo(requested);
        }

        @Test
        @DisplayName("without a request date the explaining date is kept for the gate to refuse or override")
        void explainingDateForTheGate() {
            assertThat(AdjustmentRules.postingDate(bankDate, null, d -> true)).isEqualTo(bankDate);
        }
    }
}
