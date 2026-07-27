package com.positivity.order.internal.service;

import com.positivity.order.internal.client.CustomerLookupResult;
import com.positivity.order.internal.client.CustomerPort;
import com.positivity.order.internal.entity.ExtVehicle;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtVehicleRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Replica-backed {@link CustomerPort} (ADR-0044 domain wall, PR #1089 resolution): customer and
 * vehicle references are validated against local {@code ext_customer}/{@code ext_vehicle} replicas
 * fed by {@code customer.events.v1}/{@code vehicle.events.v1} — no synchronous REST toward domain
 * modules.
 *
 * <p>Cold-replica semantics (resolved Q8 shape): while the event feed is disabled
 * ({@code pos.order.kafka.enabled=false}) or a replica table has never been populated, lookups
 * return {@code UNAVAILABLE} so carts proceed as {@code VALIDATION_PENDING} rather than
 * hard-failing on data the module cannot yet know.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicaCustomerPortAdapter implements CustomerPort {

    private final ExtCustomerRepository extCustomerRepository;
    private final ExtVehicleRepository extVehicleRepository;

    @Value("${pos.order.kafka.enabled:false}")
    private boolean eventFeedEnabled;

    @Override
    public @NonNull CustomerLookupResult lookupCustomer(@NonNull UUID customerId) {
        if (replicaCold(extCustomerRepository.count())) {
            return CustomerLookupResult.UNAVAILABLE;
        }
        return extCustomerRepository.existsById(customerId)
                ? CustomerLookupResult.FOUND
                : CustomerLookupResult.NOT_FOUND;
    }

    @Override
    public @NonNull CustomerLookupResult lookupVehicle(@NonNull UUID customerId, @NonNull UUID vehicleId) {
        if (replicaCold(extVehicleRepository.count())) {
            return CustomerLookupResult.UNAVAILABLE;
        }
        Optional<ExtVehicle> vehicle = extVehicleRepository.findById(vehicleId);
        if (vehicle.isEmpty() || !vehicle.get().isActive()) {
            return CustomerLookupResult.NOT_FOUND;
        }
        if (!customerId.equals(vehicle.get().getAccountId())) {
            log.debug(
                    "Vehicle {} exists but is owned by {}, not {}",
                    vehicleId,
                    vehicle.get().getAccountId(),
                    customerId);
            return CustomerLookupResult.NOT_FOUND;
        }
        return CustomerLookupResult.FOUND;
    }

    private boolean replicaCold(long rowCount) {
        return !eventFeedEnabled || rowCount == 0;
    }
}
