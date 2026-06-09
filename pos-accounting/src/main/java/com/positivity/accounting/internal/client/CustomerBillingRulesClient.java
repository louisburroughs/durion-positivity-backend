package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST client for retrieving billing rules from pos-customer.
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CustomerBillingRulesClient {

    @Qualifier("invoiceServiceRestClient")
    private final RestClient restClient;

    @Value("${pos.customer.service-id:customer}")
    private String customerServiceId;

    CustomerBillingRulesClient(RestClient restClient, String customerServiceId) {
        this.restClient = restClient;
        this.customerServiceId = customerServiceId;
    }

    public BillingRuleRefResponse getBillingRules(UUID customerId) {
        try {
            BillingRuleRefResponse response = restClient
                    .get()
                    .uri("http://" + customerServiceId + "/v1/crm/snapshot/party/{partyId}/billing-rules", customerId)
                    .header("X-User", "pos-accounting")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        throw new CustomerServiceException(
                                "Customer service returned " + status + " while loading billing rules for customer "
                                        + customerId,
                                status);
                    })
                    .body(BillingRuleRefResponse.class);

            if (response == null) {
                throw new CustomerServiceException(
                        "Customer service returned empty response for customer " + customerId, 500);
            }
            return response;
        } catch (CustomerServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load billing rules for customer {}: {}", customerId, e.getMessage(), e);
            throw new CustomerServiceException(
                    "Customer service unavailable while loading billing rules for customer " + customerId, 503, e);
        }
    }
}
