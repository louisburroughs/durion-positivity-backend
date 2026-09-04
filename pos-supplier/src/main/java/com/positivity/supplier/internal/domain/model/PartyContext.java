package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The canonical commercial-party context a caller supplies with a supplier exchange
 * (ADR-0050 §5, architecture doc §6.1).
 *
 * <p>Canonical code uses the generic roles <em>billing</em> and <em>delivery</em>; vendor
 * vocabulary ({@code billTo}/{@code shipTo}, {@code BuyerParty}/{@code Consignee}) exists only
 * inside adapters. The delivery account number for {@link #deliveryLocationId} is resolved
 * from the vendor profile's delivery mapping before any network call; a missing mapping is a
 * configuration error.
 *
 * @param billingAccountNumber invoicing/settlement account number (legal-entity level)
 * @param billingAgencyCode identification agency code for the billing account (e.g. EAN/GLN),
 *     when the vendor requires one
 * @param deliveryLocationId pos-location UUID of the receiving location; required for
 *     delivery-bearing capabilities (order, stock), {@code null} for account-level batch reads
 *     (PRICAT, invoice fetch)
 */
public record PartyContext(
        @NonNull String billingAccountNumber,
        @Nullable String billingAgencyCode,
        @Nullable UUID deliveryLocationId) {

    // Left as IllegalArgumentException (#1694): built internally from the resolved billing/
    // delivery account entity, never from client input directly. A violation here is this
    // module's own defect and belongs on the platform 500 fallback, not a client 4xx.
    public PartyContext {
        Objects.requireNonNull(billingAccountNumber, "billingAccountNumber must not be null");
        if (billingAccountNumber.isBlank()) {
            throw new IllegalArgumentException("billingAccountNumber must not be blank");
        }
    }
}
