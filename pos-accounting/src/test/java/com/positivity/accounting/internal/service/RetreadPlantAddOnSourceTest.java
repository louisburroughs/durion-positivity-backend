package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The retread-plant add-on (AW30): never a default, and what it owns. */
@DisplayName("RetreadPlantAddOnSource")
class RetreadPlantAddOnSourceTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");

    private final AccountingConfigurationRepository configuration = mock(AccountingConfigurationRepository.class);
    private final RetreadPlantAddOnSource source = new RetreadPlantAddOnSource(configuration);

    @Test
    @DisplayName("off by default: a tenant with no recorded choice does not receive the add-on")
    void offWhenNoChoiceIsRecorded() {
        when(configuration.findByConfigKey("RETREAD_PLANT_ADD_ON")).thenReturn(Optional.empty());

        assertThat(source.appliesTo(TENANT)).isFalse();
    }

    @Test
    @DisplayName("applies once the tenant's choice is recorded on, and only then")
    void appliesWhenTheChoiceIsOn() {
        when(configuration.findByConfigKey("RETREAD_PLANT_ADD_ON")).thenReturn(Optional.of(choice("true")));
        assertThat(source.appliesTo(TENANT)).isTrue();

        when(configuration.findByConfigKey("RETREAD_PLANT_ADD_ON")).thenReturn(Optional.of(choice("false")));
        assertThat(source.appliesTo(TENANT)).isFalse();
    }

    @Test
    @DisplayName("owns the seven retread accounts and their Labor & Overhead lines, and nothing else")
    void ownsTheRetreadAccountsAndTheirLines() {
        assertThat(RetreadPlantAddOnSource.ACCOUNT_CODES)
                .containsExactly("6350", "6450", "6470", "6510", "6520", "6530", "6900");
        assertThat(RetreadPlantAddOnSource.ENTRY_KEYS).hasSize(14);

        assertThat(source.owns(account("6350"))).isTrue();
        assertThat(source.owns(line(StatementType.LABOR_OVERHEAD, "6900"))).isTrue();
        assertThat(source.owns(account("6340"))).isFalse();
        assertThat(source.owns(line(StatementType.LABOR_OVERHEAD, "6340"))).isFalse();
        assertThat(source.owns(line(StatementType.INCOME_STATEMENT, "6350")))
                .as("only the add-on's Labor & Overhead lines are its own")
                .isFalse();
    }

    private static AccountingConfiguration choice(String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(RetreadPlantAddOnSource.CONFIG_KEY);
        row.setConfigValue(value);
        return row;
    }

    private static AccountingTemplate.Account account(String code) {
        return new AccountingTemplate.Account(code, "Account " + code, AccountType.EXPENSE, null, false, null, null);
    }

    private static AccountingTemplate.StatementLine line(StatementType type, String code) {
        return new AccountingTemplate.StatementLine(type, code, "2.9.2", "2.9", "A line", 1, OperationType.SUM);
    }
}
