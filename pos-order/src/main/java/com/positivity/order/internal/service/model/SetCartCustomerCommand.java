package com.positivity.order.internal.service.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Sets or changes a DRAFT cart's customer (CAP:550 S8): either a registered customer, optionally
 * with one of that customer's vehicles, or an explicit Walk-in selection — never both.
 *
 * @param customerId the registered customer to put on the cart; null when Walk-in is chosen
 * @param walkIn true to choose the tenant's CASH house account explicitly
 * @param vehicleId a vehicle of {@code customerId}; null clears the cart's vehicle
 */
public record SetCartCustomerCommand(
        @Nullable UUID customerId, boolean walkIn, @Nullable UUID vehicleId) {}
