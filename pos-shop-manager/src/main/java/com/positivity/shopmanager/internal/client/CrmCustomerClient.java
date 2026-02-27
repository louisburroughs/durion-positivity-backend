package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CrmCustomerClient {

    private final RestClient crmRestClient;

    @Value("${pos.crm.customer-base-url:http://localhost:8080/v1/customers}")
    private String customerBaseUrl;

    public CrmCustomerClient(@Qualifier("crmRestClient") RestClient crmRestClient) {
        this.crmRestClient = crmRestClient;
    }

    public @NonNull Map<String, Object> getCustomerById(@NonNull UUID customerId) {
        try {
            Map<?, ?> payload = crmRestClient.get()
                    .uri(customerBaseUrl + "/{customerId}", customerId)
                    .retrieve()
                    .body(Map.class);
            if (payload == null) {
                return Collections.emptyMap();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedPayload = (Map<String, Object>) payload;
            return typedPayload;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CrmCustomerNotFoundException(customerId);
        }
    }
}
