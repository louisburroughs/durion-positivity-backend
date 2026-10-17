package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0067 A8 (#2662, Order ruling §2, AC (j)): startup validation names the key; the map binds from properties. */
class CashIncrementsTest {

    @Test
    void bindsFromTheEnvironmentAndAnswersPerCurrency() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("pos.order.cash-increments.CAD", "0.05")
                .withProperty("pos.order.cash-increments.chf", "0.05");

        CashIncrements increments = new CashIncrements(env);

        assertThat(increments.of("CAD")).contains(new BigDecimal("0.05"));
        assertThat(increments.of("chf")).isPresent();
        assertThat(increments.of("USD")).isEmpty();
    }

    @Test
    void noPropertyMeansNoCurrencyRounds() {
        assertThat(new CashIncrements(new MockEnvironment()).of("CAD")).isEmpty();
    }

    @Test
    void refusesAnIncrementThatIsNotACoin() {
        assertThatThrownBy(() -> new CashIncrements(Map.of("CAD", new BigDecimal("0.07"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.order.cash-increments.CAD")
                .hasMessageContaining("coin");
        assertThatThrownBy(() -> new CashIncrements(Map.of("SEK", new BigDecimal("2.50"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coin");
    }

    @Test
    void refusesAnIncrementThatIsNotAMultipleOfTheMinorUnit() {
        assertThatThrownBy(() -> new CashIncrements(Map.of("CAD", new BigDecimal("0.005"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.order.cash-increments.CAD")
                .hasMessageContaining("minor unit");
    }

    @Test
    void acceptsTheUsualCoins() {
        assertThat(new CashIncrements(Map.of("CAD", new BigDecimal("0.25"))).of("CAD")).isPresent();
        assertThat(new CashIncrements(Map.of("SEK", new BigDecimal("1"))).of("SEK")).isPresent();
        assertThat(new CashIncrements(Map.of("CHF", new BigDecimal("0.05"))).of("CHF")).isPresent();
    }

    @Test
    void refusesAnIncrementEqualToTheMinorUnit() {
        assertThatThrownBy(() -> new CashIncrements(Map.of("CAD", new BigDecimal("0.01"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.order.cash-increments.CAD")
                .hasMessageContaining("minor unit");
    }

    @Test
    void refusesANonIsoKey() {
        assertThatThrownBy(() -> new CashIncrements(Map.of("XXX", new BigDecimal("0.05"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.order.cash-increments.XXX");
    }

    @Test
    void refusesANonPositiveIncrement() {
        assertThatThrownBy(() -> new CashIncrements(Map.of("CAD", BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void acceptsAWholeUnitIncrementForAZeroExponentCurrency() {
        assertThat(new CashIncrements(Map.of("JPY", new BigDecimal("5"))).of("JPY"))
                .contains(new BigDecimal("5"));
    }
}
