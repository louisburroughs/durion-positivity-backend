package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cash and payables settings (#2511 AC 2)")
class CashAndPayablesSettingsTest {

    private final AccountingConfigurationRepository configuration = mock(AccountingConfigurationRepository.class);
    private final CashAndPayablesSettings settings = new CashAndPayablesSettings(configuration);

    @Test
    @DisplayName("a fresh tenant: AP_DEFAULT_TERMS is NET30 and CASH_SAFETY_CUSHION is unset, with no row seeded")
    void defaultsStandInForAbsentRows() {
        when(configuration.findByConfigKeyIn(anyCollection())).thenReturn(List.of());

        assertThat(settings.settings()).isEqualTo(new CashAndPayablesSettings.Settings("NET30", null));
        assertThat(settings.apDefaultTerms()).isEqualTo("NET30");
        assertThat(settings.cashSafetyCushion()).isEmpty();
    }

    @Test
    @DisplayName("stored rows win; an unreadable or negative cushion reads as unset")
    void storedRowsWin() {
        when(configuration.findByConfigKeyIn(anyCollection()))
                .thenReturn(List.of(row("AP_DEFAULT_TERMS", "NET15"), row("CASH_SAFETY_CUSHION", "2500.00")));
        assertThat(settings.settings())
                .isEqualTo(new CashAndPayablesSettings.Settings("NET15", new BigDecimal("2500.00")));

        assertThat(CashAndPayablesSettings.parseCushion("lots")).isEmpty();
        assertThat(CashAndPayablesSettings.parseCushion("-1")).isEmpty();
        assertThat(CashAndPayablesSettings.parseTerms(" ")).isEqualTo("NET30");
    }

    private static AccountingConfiguration row(String key, String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(key);
        row.setConfigValue(value);
        return row;
    }
}
