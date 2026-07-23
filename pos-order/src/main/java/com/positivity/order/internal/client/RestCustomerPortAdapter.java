package com.positivity.order.internal.client;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * REST adapter on pos-customer for customer/vehicle existence checks (plan story I2).
 *
 * <p>pos-customer guards its CRM reads with {@code crm:party:view} / {@code crm:vehicle:view};
 * this service-to-service call propagates the required authority via the gateway headers
 * (same pattern as pos-workorder's {@code TaxClient}).
 */
@Component
@Slf4j
public class RestCustomerPortAdapter implements CustomerPort {

    private final RestClient customerServiceRestClient;

    public RestCustomerPortAdapter(@Qualifier("customerServiceRestClient") RestClient customerServiceRestClient) {
        this.customerServiceRestClient = customerServiceRestClient;
    }

    @Override
    public @NonNull CustomerLookupResult lookupCustomer(@NonNull UUID customerId) {
        return lookup("/v1/crm/{id}", "crm:party:view", customerId);
    }

    @Override
    public @NonNull CustomerLookupResult lookupVehicle(@NonNull UUID customerId, @NonNull UUID vehicleId) {
        try {
            customerServiceRestClient
                    .get()
                    .uri("/v1/crm/{customerId}/vehicles/{vehicleId}", customerId, vehicleId)
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "crm:vehicle:view")
                    .retrieve()
                    .toBodilessEntity();
            return CustomerLookupResult.FOUND;
        } catch (HttpClientErrorException.NotFound notFound) {
            return CustomerLookupResult.NOT_FOUND;
        } catch (Exception e) {
            log.warn("pos-customer unreachable for vehicle lookup {}: {}", vehicleId, e.getMessage());
            return CustomerLookupResult.UNAVAILABLE;
        }
    }

    private CustomerLookupResult lookup(String uri, String authority, UUID id) {
        try {
            customerServiceRestClient
                    .get()
                    .uri(uri, id)
                    .header("X-User", "pos-order")
                    .header("X-Authorities", authority)
                    .retrieve()
                    .toBodilessEntity();
            return CustomerLookupResult.FOUND;
        } catch (HttpClientErrorException.NotFound notFound) {
            return CustomerLookupResult.NOT_FOUND;
        } catch (Exception e) {
            log.warn("pos-customer unreachable for lookup {}: {}", id, e.getMessage());
            return CustomerLookupResult.UNAVAILABLE;
        }
    }
}
