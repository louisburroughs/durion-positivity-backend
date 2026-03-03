package com.positivity.price.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = VehicleDataProvider.class, ignored = StubVehicleDataProvider.class)
public class StubVehicleDataProvider implements VehicleDataProvider {
    @Override
    public Optional<VehicleContext> getVehicleContext(UUID vehicleId) {
        return Optional.empty();
    }
}
