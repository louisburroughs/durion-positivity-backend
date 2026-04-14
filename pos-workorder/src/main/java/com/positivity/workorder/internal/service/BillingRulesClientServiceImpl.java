package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.BillingRulesDTO;
import com.positivity.workorder.service.BillingRulesClientService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Client service for fetching billing rules from pos-invoice service.
 * CAP:092 Story #98 - PO enforcement requires checking if customer requires PO.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingRulesClientServiceImpl implements BillingRulesClientService {

    private final RestClient invoiceServiceRestClient;

    /**
     * Fetch billing rules for a customer/party.
     * Returns empty if rules don't exist or service call fails.
     *
     * @param partyId the party/customer identifier
     * @return billing rules if found
     */
    @Override
    @NonNull
    public Optional<BillingRulesDTO> getBillingRules(@NonNull String partyId) {
        try {
            log.debug("Fetching billing rules for partyId={}", partyId);

            BillingRulesDTO rules = invoiceServiceRestClient
                    .get()
                    .uri("/v1/billing/rules/{partyId}", partyId)
                    .retrieve()
                    .body(BillingRulesDTO.class);

            if (rules != null) {
                log.debug(
                        "Successfully fetched billing rules for partyId={}, purchaseOrderRequired={}",
                        partyId,
                        rules.isPurchaseOrderRequired());
                return Optional.of(rules);
            }

            log.debug("No billing rules found for partyId={}", partyId);
            return Optional.empty();

        } catch (Exception e) {
            // Log warning but don't fail - default to not requiring PO
            log.warn("Failed to fetch billing rules for partyId={}: {}", partyId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check if a customer requires purchase order for approvals.
     * Returns false if rules don't exist or service call fails (fail-safe).
     *
     * @param partyId the party/customer identifier
     * @return true if PO is required, false otherwise
     */
    @Override
    public boolean isPurchaseOrderRequired(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return false;
        }

        return getBillingRules(partyId)
                .map(BillingRulesDTO::isPurchaseOrderRequired)
                .orElse(false);
    }
}
