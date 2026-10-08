package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The AP approval policy's keys, defaults and rules (CAP:550 S13, #2510; AW5, AW33; rulings 2, 6038336284 and 9):
 * absent and unreadable values read as the stricter default, the tier on the absolute billed gross, and the
 * automatic-approval limit as min(automatic, clerk).
 */
@DisplayName("ApApprovalPolicy: defaults, tier and automatic limit (#2510)")
class ApApprovalPolicyTest {

    private final AccountingConfigurationRepository configuration = mock();
    private final ApApprovalPolicy policy =
            new ApApprovalPolicy(configuration, new FunctionalCurrency(new LedgerCurrency("USD")));

    private static AccountingConfiguration row(String key, String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(key);
        row.setConfigValue(value);
        return row;
    }

    @Test
    @DisplayName("No rows: limits 0.00, switches off, NET30; the decision read share-locks the same snapshot")
    void defaults() {
        when(configuration.findByConfigKeyIn(anyCollection())).thenReturn(List.of());
        when(configuration.findWithShareLockByConfigKeyIn(anyCollection())).thenReturn(List.of());

        ApApprovalPolicy.Settings expected =
                new ApApprovalPolicy.Settings(new BigDecimal("0.00"), new BigDecimal("0.00"), false, false, "NET30");
        assertThat(policy.settings()).isEqualTo(expected);
        assertThat(policy.forDecision()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Stored values win; unreadable ones read as the stricter default (0, false, NET30)")
    void storedAndUnreadable() {
        when(configuration.findByConfigKeyIn(anyCollection()))
                .thenReturn(List.of(
                        row("AP_CLERK_APPROVAL_LIMIT", "2500"),
                        row("AP_AUTO_APPROVAL_LIMIT", "lots"),
                        row("AP_ALLOW_CREATOR_APPROVAL", "TRUE"),
                        row("AP_ALLOW_APPROVER_PAYMENT", "yes"),
                        row("AP_DEFAULT_TERMS", "NET 30")));

        assertThat(policy.settings())
                .isEqualTo(new ApApprovalPolicy.Settings(
                        new BigDecimal("2500.00"), new BigDecimal("0.00"), true, false, "NET30"));
        assertThat(ApApprovalPolicy.parseAmount("AP_CLERK_APPROVAL_LIMIT", "-1"))
                .isZero();
    }

    @ParameterizedTest(name = "total {0} against limit {1}: {2}")
    @CsvSource({
        "2500.00, 2500.00, CLERK",
        "2500.01, 2500.00, OVER_LIMIT",
        "-2500.00, 2500.00, CLERK",
        "-2500.01, 2500.00, OVER_LIMIT",
        "0.01, 0.00, OVER_LIMIT",
        "10.00, 0.00, OVER_LIMIT"
    })
    @DisplayName("AC1/AC2: CLERK only when the limit is above 0 and |total| is at most it")
    void tier(String total, String limit, VendorBillReview.RequiredTier expected) {
        assertThat(ApApprovalPolicy.tier(new BigDecimal(total), new BigDecimal(limit)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("A bill without a total is OVER_LIMIT")
    void noTotal() {
        assertThat(ApApprovalPolicy.tier(null, new BigDecimal("2500.00")))
                .isEqualTo(VendorBillReview.RequiredTier.OVER_LIMIT);
    }

    @Test
    @DisplayName("AC5: automatic approval applies min(automatic 500.00, clerk 300.00) = 300.00; 0 turns it off")
    void automaticLimit() {
        ApApprovalPolicy.Settings settings = new ApApprovalPolicy.Settings(
                new BigDecimal("300.00"), new BigDecimal("500.00"), false, false, "NET30");
        assertThat(settings.automaticLimitApplied()).isEqualByComparingTo("300.00");
        assertThat(settings.automaticallyApprovable(new BigDecimal("250.00"))).isTrue();
        assertThat(settings.automaticallyApprovable(new BigDecimal("300.00"))).isTrue();
        assertThat(settings.automaticallyApprovable(new BigDecimal("400.00"))).isFalse();
        assertThat(new ApApprovalPolicy.Settings(
                                new BigDecimal("300.00"), new BigDecimal("0.00"), false, false, "NET30")
                        .automaticallyApprovable(new BigDecimal("10.00")))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUE_ON_RECEIPT", "NET1", "NET30", "NET120"})
    @DisplayName("Ruling 9: the terms vocabulary")
    void termsInTheVocabulary(String terms) {
        assertThat(CashAndPayablesSettings.isTerms(terms)).isTrue();
        assertThat(CashAndPayablesSettings.parseTerms(terms)).isEqualTo(terms);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NET_30", "NET 30", "net30", "NET0", "NET121", "NET030", "NET", "", " ", "DUE ON RECEIPT"})
    @DisplayName("Ruling 9: anything else is not terms, and reads as NET30")
    void termsOutsideTheVocabulary(String terms) {
        assertThat(CashAndPayablesSettings.isTerms(terms)).isFalse();
        assertThat(CashAndPayablesSettings.parseTerms(terms)).isEqualTo("NET30");
    }
}
