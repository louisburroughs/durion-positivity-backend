package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The address registry of the bound tenant (S27 ruling 8): one {@code ACTIVE} address per tenant,
 * issued on first request and replaced on rotation.
 */
public interface AddressRegistry {

    /** The tenant's {@code ACTIVE} address, issuing one when it has none. Concurrent first calls agree on one. */
    @NonNull
    IssuedAddressResponse issueOrGet();

    /**
     * Retires the tenant's {@code ACTIVE} address and issues a new one, in one transaction. Idempotent on
     * {@code requestId}: a replay returns the address that request issued, even if a later rotation has
     * retired it since, and never rotates twice.
     */
    @NonNull
    IssuedAddressResponse rotate(@NonNull UUID requestId);
}
