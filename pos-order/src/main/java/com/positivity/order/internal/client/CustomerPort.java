package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Existence checks against pos-customer (plan story I2). */
public interface CustomerPort {

    @NonNull
    CustomerLookupResult lookupCustomer(@NonNull UUID customerId);

    /** Vehicles are validated in the context of their owning customer (CRM vehicle model). */
    @NonNull
    CustomerLookupResult lookupVehicle(@NonNull UUID customerId, @NonNull UUID vehicleId);
}
