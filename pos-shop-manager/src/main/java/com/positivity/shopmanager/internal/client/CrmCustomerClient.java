package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmUnavailableException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CrmCustomerClient {

    private final RestClient crmRestClient;

    public CrmCustomerClient(@Qualifier("crmRestClient") RestClient crmRestClient) {
        this.crmRestClient = crmRestClient;
    }

    public @NonNull Map<String, Object> getCustomerById(@NonNull UUID customerId) {
        try {
            Map<?, ?> payload = crmRestClient
                    .get()
                    .uri("/v1/crm/{customerId}", customerId)
                    .header("X-User", "pos-shop-manager")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Map.class);
            if (payload == null) {
                return Collections.emptyMap();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedPayload = (Map<String, Object>) payload;
            return typedPayload;
        } catch (HttpClientErrorException.NotFound _) {
            throw new CrmCustomerNotFoundException(customerId);
        } catch (Exception e) {
            throw new CrmUnavailableException("CRM is unavailable: " + e.getMessage(), e);
        }
    }
}
