package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The policy reader (SPEC-manual-bank-reconciliation §5.2; story S6, #2305): {@link BankRecPolicy#settings()} is
 * one query over the five keys, so it never mixes the values of two PUTs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankRecPolicy")
class BankRecPolicyTest {

    @Mock
    private AccountingConfigurationRepository configuration;

    @InjectMocks
    private BankRecPolicy policy;

    private static AccountingConfiguration row(String key, String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(key);
        row.setConfigValue(value);
        return row;
    }

    @Test
    @DisplayName("[M] settings() reads the five keys in one query; a key without a row takes its default")
    void settingsAreOneSnapshot() {
        when(configuration.findByConfigKeyIn(BankRecPolicy.KEYS))
                .thenReturn(List.of(
                        row(BankRecPolicy.CLOSE_POLICY, "advisory"),
                        row(BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS, "5"),
                        row(BankRecPolicy.OTHER_APPROVAL_THRESHOLD, "250.00")));

        BankRecPolicy.Settings settings = policy.settings();

        assertThat(settings.closePolicy()).isEqualTo(BankRecClosePolicy.ADVISORY);
        assertThat(settings.closeScope()).isEqualTo(BankRecCloseScope.BANK_CASH_SUBTYPE);
        assertThat(settings.closeCoverageLagDays()).isEqualTo(5);
        assertThat(settings.allowSelfApproval()).isFalse();
        assertThat(settings.otherApprovalThreshold()).isEqualByComparingTo(new BigDecimal("250.00"));
        verify(configuration).findByConfigKeyIn(BankRecPolicy.KEYS);
        verify(configuration, never()).findByConfigKey(any());
    }

    @Test
    @DisplayName("settings() without any row is the defaults")
    void defaultsWithoutRows() {
        when(configuration.findByConfigKeyIn(BankRecPolicy.KEYS)).thenReturn(List.of());

        BankRecPolicy.Settings settings = policy.settings();

        assertThat(settings.closePolicy()).isEqualTo(BankRecPolicy.DEFAULT_CLOSE_POLICY);
        assertThat(settings.closeScope()).isEqualTo(BankRecPolicy.DEFAULT_CLOSE_SCOPE);
        assertThat(settings.closeCoverageLagDays()).isEqualTo(BankRecPolicy.DEFAULT_COVERAGE_LAG_DAYS);
        assertThat(settings.allowSelfApproval()).isFalse();
        assertThat(settings.otherApprovalThreshold()).isNull();
    }
}
