package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface BillingRulesService {

    @NonNull
    BillingRuleRefResponse getBillingRules(@NonNull UUID customerId);
}
