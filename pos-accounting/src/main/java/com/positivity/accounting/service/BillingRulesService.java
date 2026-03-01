package com.positivity.accounting.service;

import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;

public interface BillingRulesService {

    @NonNull
    BillingRuleRefResponse getBillingRules(@NonNull UUID customerId);
}
