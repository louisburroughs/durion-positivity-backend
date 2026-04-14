package com.positivity.price.internal.client;

import java.util.List;
import java.util.UUID;

public record VehicleContext(UUID vehicleId, List<String> tags) {}
