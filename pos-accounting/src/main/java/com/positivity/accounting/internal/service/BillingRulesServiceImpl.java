package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.CustomerBillingRulesClient;
import com.positivity.accounting.internal.client.CustomerServiceException;
import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.service.BillingRulesService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BillingRulesServiceImpl implements BillingRulesService {

    private final CustomerBillingRulesClient customerBillingRulesClient;

    @Override
    @NonNull
    public BillingRuleRefResponse getBillingRules(@NonNull UUID customerId) {
        try {
            return customerBillingRulesClient.getBillingRules(customerId);
        } catch (CustomerServiceException e) {
            HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
            if (status == null) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
            }
            throw new ResponseStatusException(status, e.getMessage(), e);
        }
    }
}
