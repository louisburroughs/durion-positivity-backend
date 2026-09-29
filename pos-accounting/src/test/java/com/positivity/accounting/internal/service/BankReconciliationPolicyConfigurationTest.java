package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.PostgresIntegrationTestBase;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank reconciliation policy settings on {@link AccountingConfigurationService} (SPEC-manual-bank-reconciliation
 * §5.2; story S6, #2305, AC 14): defaults when no row exists, one {@code BANK_REC_POLICY_SET} audit row per changed
 * setting, a null threshold clearing its row, the justification rule, and the core's readers seeing the values.
 */
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("Bank reconciliation policy settings (#2305)")
class BankReconciliationPolicyConfigurationTest extends PostgresIntegrationTestBase {

    private static final String ACTOR = "finance-controller";
    private static final String JUSTIFICATION = "Bank statements end mid-month at our bank";

    @Autowired
    private AccountingConfigurationService configurationService;

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    @Autowired
    private BankRecPolicy bankRecPolicy;

    @BeforeEach
    void setUp() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, null);
        authentication.setAuthenticated(true);
        authentication.setDetails(Map.of("username", ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static BankReconciliationPolicyRequest request(int lag, boolean selfApproval, String threshold) {
        BankReconciliationPolicyRequest request = new BankReconciliationPolicyRequest();
        request.setClosePolicy(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION);
        request.setCloseScope(BankRecCloseScope.BANK_CASH_SUBTYPE);
        request.setCloseCoverageLagDays(lag);
        request.setAllowSelfApproval(selfApproval);
        request.setOtherApprovalThreshold(threshold == null ? null : new BigDecimal(threshold));
        request.setJustification(JUSTIFICATION);
        return request;
    }

    private List<AccountingAuditLog> policyAuditRows() {
        return auditLogRepository.findAll().stream()
                .filter(a -> "BANK_REC_POLICY_SET".equals(a.getOperation()))
                .toList();
    }

    @Test
    @DisplayName("GET without rows returns the defaults and no change stamp")
    void defaults() {
        BankReconciliationPolicyResponse policy = configurationService.getBankReconciliationPolicy();

        assertThat(policy.getClosePolicy()).isEqualTo(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION);
        assertThat(policy.getCloseScope()).isEqualTo(BankRecCloseScope.BANK_CASH_SUBTYPE);
        assertThat(policy.getCloseCoverageLagDays()).isZero();
        assertThat(policy.getAllowSelfApproval()).isFalse();
        assertThat(policy.getOtherApprovalThreshold()).isNull();
        assertThat(policy.getCurrency()).isNotBlank();
        assertThat(policy.getUpdatedAt()).isNull();
        assertThat(policy.getUpdatedBy()).isNull();
    }

    @Test
    @DisplayName("[M] AC14: a PUT changing only the lag writes exactly one audit row with old, new and justification")
    void onlyChangedSettingIsAudited() {
        BankReconciliationPolicyResponse stored =
                configurationService.setBankReconciliationPolicy(request(31, false, null));

        assertThat(stored.getCloseCoverageLagDays()).isEqualTo(31);
        assertThat(stored.getUpdatedBy()).isEqualTo(ACTOR);
        assertThat(stored.getUpdatedAt()).isNotNull();
        List<AccountingAuditLog> rows = policyAuditRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getEntityType()).isEqualTo("ACCOUNTING_CONFIGURATION");
            assertThat(row.getEntityId())
                    .isEqualTo(configurationRepository
                            .findByConfigKey(BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS)
                            .map(AccountingConfiguration::getConfigId)
                            .orElseThrow());
            assertThat(row.getOldValue()).isEqualTo("0");
            assertThat(row.getNewValue()).isEqualTo("31");
            assertThat(row.getJustification()).isEqualTo(JUSTIFICATION);
            assertThat(row.getUserId()).isEqualTo(ACTOR);
        });
        assertThat(bankRecPolicy.coverageLagDays()).isEqualTo(31);
    }

    @Test
    @DisplayName("an identical PUT writes nothing")
    void unchangedWritesNothing() {
        configurationService.setBankReconciliationPolicy(request(0, false, null));

        assertThat(policyAuditRows()).isEmpty();
        assertThat(configurationRepository.findByConfigKey(BankRecPolicy.CLOSE_POLICY))
                .isEmpty();
    }

    @Test
    @DisplayName("AC14: a threshold is stored in the minor unit; null clears the row and is audited")
    void thresholdSetThenCleared() {
        configurationService.setBankReconciliationPolicy(request(0, true, "250"));
        assertThat(bankRecPolicy.otherApprovalThreshold()).contains(new BigDecimal("250.00"));
        assertThat(bankRecPolicy.allowSelfApproval()).isTrue();
        assertThat(policyAuditRows()).hasSize(2);

        BankReconciliationPolicyResponse cleared =
                configurationService.setBankReconciliationPolicy(request(0, true, null));

        assertThat(cleared.getOtherApprovalThreshold()).isNull();
        assertThat(configurationRepository.findByConfigKey(BankRecPolicy.OTHER_APPROVAL_THRESHOLD))
                .isEmpty();
        assertThat(bankRecPolicy.otherApprovalThreshold()).isEmpty();
        assertThat(policyAuditRows()).hasSize(3).anySatisfy(row -> {
            assertThat(row.getOldValue()).isEqualTo("250.00");
            assertThat(row.getNewValue()).isNull();
        });
    }

    @Test
    @DisplayName("a justification of 1-9 characters is 400 JUSTIFICATION_REQUIRED and changes nothing")
    void shortJustification() {
        BankReconciliationPolicyRequest request = request(5, false, null);
        request.setJustification("too short");

        assertThatThrownBy(() -> configurationService.setBankReconciliationPolicy(request))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        assertThat(policyAuditRows()).isEmpty();
    }

    @Test
    @DisplayName("a threshold finer than the functional currency's minor unit is 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY")
    void thresholdTooPrecise() {
        assertThatThrownBy(() -> configurationService.setBankReconciliationPolicy(request(0, false, "10.005")))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                    assertThat(e.code().httpStatus()).isEqualTo(422);
                    assertThat(e.fieldErrors()).containsOnlyKeys("otherApprovalThreshold");
                });
        assertThat(policyAuditRows()).isEmpty();
    }

    @Test
    @DisplayName("trailing zeros beyond the minor unit are not extra precision: 10.0000 is stored as 10.00")
    void trailingZerosAreNotPrecision() {
        configurationService.setBankReconciliationPolicy(request(0, false, "10.0000"));

        assertThat(configurationService.getBankReconciliationPolicy().getOtherApprovalThreshold())
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("an absent otherApprovalThreshold (not even null) is 400 VALIDATION_ERROR")
    void thresholdMustBeSent() {
        BankReconciliationPolicyRequest request = new BankReconciliationPolicyRequest();
        request.setClosePolicy(BankRecClosePolicy.ADVISORY);
        request.setCloseScope(BankRecCloseScope.BANK_CASH_SUBTYPE);
        request.setCloseCoverageLagDays(0);
        request.setAllowSelfApproval(false);
        request.setJustification(JUSTIFICATION);

        assertThatThrownBy(() -> configurationService.setBankReconciliationPolicy(request))
                .isInstanceOfSatisfying(
                        BankRecException.class, e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
        assertThat(policyAuditRows()).isEmpty();
    }
}
