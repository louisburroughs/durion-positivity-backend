package com.positivity.accounting.archfixture.internal.service;

import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;

/** Violation: the period close reading a core service instead of the close-readiness read model (fixture, #2305). */
public class AccountingPeriodCloseReachingIntoCore {

    public String policyKey() {
        return BankRecPolicy.CLOSE_POLICY.concat(String.valueOf(BankRecPolicy.class.getSimpleName()));
    }
}
