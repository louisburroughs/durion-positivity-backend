package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read-only client for pos-customer (CRM) lookups.
 *
 * <p>Reads degrade to {@link Optional#empty()} on 404 or transport failure.
 */
public interface CustomerClient {

    /**
     * Fetch a customer by CRM customer id ({@code GET /v1/crm/{id}}).
     */
    @NonNull
    Optional<CustomerInfo> getCustomer(@NonNull UUID customerId);

    /**
     * Customer fields warranty claims need.
     *
     * @param displayName composed "firstName lastName" (whichever parts are present)
     */
    record CustomerInfo(UUID id, UUID partyId, String displayName, String customerNumber) {}
}
