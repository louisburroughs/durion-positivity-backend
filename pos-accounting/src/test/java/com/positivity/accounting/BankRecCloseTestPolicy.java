package com.positivity.accounting;

import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;

/**
 * Sets the tenant's bank reconciliation close policy for tests that close periods for reasons unrelated to bank
 * reconciliation (story S6, #2305). The seeded {@code 1000 Cash} is a reconcilable {@code BANK_CASH} account, so
 * under the default {@code REQUIRED_WITH_EXCEPTION} policy an unreconciled tenant cannot close a period; these
 * tests switch the policy to {@code ADVISORY}, which keeps only the DRAFT-entry rule.
 */
public final class BankRecCloseTestPolicy {

    private BankRecCloseTestPolicy() {}

    /** Stores {@code BANK_REC_CLOSE_POLICY = ADVISORY} in the bound tenant unless it is already set. */
    public static void advisory(AccountingConfigurationRepository configuration) {
        set(configuration, BankRecClosePolicy.ADVISORY);
    }

    /** Stores {@code BANK_REC_CLOSE_POLICY} in the bound tenant. */
    public static void set(AccountingConfigurationRepository configuration, BankRecClosePolicy policy) {
        AccountingConfiguration row = configuration
                .findByConfigKey(BankRecPolicy.CLOSE_POLICY)
                .orElseGet(() -> {
                    AccountingConfiguration created = new AccountingConfiguration();
                    created.setConfigKey(BankRecPolicy.CLOSE_POLICY);
                    return created;
                });
        row.setConfigValue(policy.name());
        configuration.saveAndFlush(row);
    }
}
