package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountStatus;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerRequirementsService {

    private final CommercialPartyServiceImpl commercialPartyService;
    private final PersonPartyServiceImpl personPartyService;

    @Transactional(readOnly = true)
    public Optional<Boolean> requirementsMet(@NonNull UUID customerId) {
        return commercialPartyService
                .findPartyById(customerId)
                .<AbstractParty>map(party -> party)
                .or(() -> personPartyService.findPartyById(customerId).map(party -> party))
                .map(this::requirementsMet);
    }

    private boolean requirementsMet(@NonNull AbstractParty party) {
        if (party.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }

        if (party instanceof CommercialParty commercialParty) {
            BillingRulesEmbeddable billingRules = commercialParty.getBillingRules();
            return billingRules == null || !Boolean.TRUE.equals(billingRules.getCreditHold());
        }

        return true;
    }
}
