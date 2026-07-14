package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.service.BillingRulesService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Billing rules from the local {@code ext_customer_billing_rules} replica (ADR-0044 R3, #842) —
 * the synchronous call to pos-customer is retired. A replica miss returns the owner's documented
 * defaults: pos-customer answers defaults for any known party without explicit rules, and
 * validating party existence is not accounting's job under the domain walls. The replica may lag
 * the owner by seconds; drift is detected and repaired by the reconciliation manifests.
 */
@Service
@RequiredArgsConstructor
public class BillingRulesServiceImpl implements BillingRulesService {

    private final ExtCustomerBillingRulesRepository billingRulesRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public BillingRuleRefResponse getBillingRules(@NonNull UUID customerId) {
        return billingRulesRepository.findById(customerId).map(this::toResponse).orElseGet(this::defaults);
    }

    private BillingRuleRefResponse toResponse(ExtCustomerBillingRules replica) {
        BillingRuleRefResponse response = defaults();
        if (replica.getPoRequired() != null) {
            response.setPoRequired(replica.getPoRequired());
        }
        if (replica.getTaxExempt() != null) {
            response.setTaxExempt(replica.getTaxExempt());
        }
        if (replica.getCreditHold() != null) {
            response.setCreditHold(replica.getCreditHold());
        }
        if (replica.getAutoPayEnabled() != null) {
            response.setAutoPayEnabled(replica.getAutoPayEnabled());
        }
        if (replica.getPaymentTerms() != null) {
            response.setPaymentTerms(replica.getPaymentTerms());
        }
        response.setCreditLimit(replica.getCreditLimit());
        if (replica.getCurrency() != null) {
            response.setCurrency(replica.getCurrency());
        }
        if (replica.getInvoiceDeliveryMethod() != null) {
            response.setInvoiceDeliveryMethod(replica.getInvoiceDeliveryMethod());
        }
        response.setBillingAddressId(replica.getBillingAddressId());
        response.setDiscountPolicyRef(replica.getDiscountPolicyRef());
        return response;
    }

    /** Mirrors pos-customer's BillingRuleRef.defaults(). */
    private BillingRuleRefResponse defaults() {
        BillingRuleRefResponse response = new BillingRuleRefResponse();
        response.setPoRequired(false);
        response.setTaxExempt(false);
        response.setCreditHold(false);
        response.setAutoPayEnabled(false);
        response.setPaymentTerms("Due on Receipt");
        response.setInvoiceDeliveryMethod("EMAIL");
        response.setCurrency("USD");
        return response;
    }
}
