package com.positivity.order.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command for creating a sales-order cart (plan stories A2/I1).
 *
 * @param clerkId clerk creating the cart
 * @param terminalId terminal the cart is created at
 * @param customerId optional customer reference (UUID string; validated against pos-customer)
 * @param vehicleId optional vehicle reference (UUID string; validated when a customer is present)
 * @param locationId shop location; drives order-number assignment
 * @param label optional parking label for resumable drafts
 * @param idempotencyKey optional client idempotency key; replays return the original cart
 */
public record CreateCartCommand(
        @NonNull String clerkId,
        @NonNull String terminalId,
        @Nullable String customerId,
        @Nullable String vehicleId,
        @NonNull UUID locationId,
        @Nullable String label,
        @Nullable String idempotencyKey) {

    public CreateCartCommand {
        Objects.requireNonNull(clerkId, "clerkId must not be null");
        Objects.requireNonNull(terminalId, "terminalId must not be null");
        Objects.requireNonNull(locationId, "locationId must not be null");
    }
}
