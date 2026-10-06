package com.positivity.accounting.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link NormalSide}: the one normal-side rule (CAP:550 S35, #2524; SPEC-accounting-workspace §3 P1). */
class NormalSideTest {

    @ParameterizedTest(name = "{0} is {1}-normal; a stored balance of -100.00 reads {2}")
    @CsvSource({
        "ASSET,     DEBIT,  -100.00",
        "EXPENSE,   DEBIT,  -100.00",
        "LIABILITY, CREDIT,  100.00",
        "EQUITY,    CREDIT,  100.00",
        "REVENUE,   CREDIT,  100.00",
    })
    void everyAccountTypeHasItsSide(AccountType type, NormalSide expected, BigDecimal normalBalance) {
        NormalSide side = NormalSide.of(type);

        assertThat(side).isEqualTo(expected);
        assertThat(side.normalBalance(new BigDecimal("-100.00"))).isEqualByComparingTo(normalBalance);
    }

    @Test
    @DisplayName("An unknown type is debit-normal (the balance as stored); a missing balance is zero")
    void unknownTypeAndMissingBalance() {
        assertThat(NormalSide.of(null)).isEqualTo(NormalSide.DEBIT);
        assertThat(NormalSide.DEBIT.normalBalance(new BigDecimal("42.00"))).isEqualByComparingTo("42.00");
        assertThat(NormalSide.CREDIT.normalBalance(null)).isEqualByComparingTo("0");
        assertThat(NormalSide.DEBIT.normalBalance(null)).isEqualByComparingTo("0");
    }
}
