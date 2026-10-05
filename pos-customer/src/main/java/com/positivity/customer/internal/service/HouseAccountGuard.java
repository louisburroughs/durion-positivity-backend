package com.positivity.customer.internal.service;

import com.positivity.customer.internal.exception.HouseAccountImmutableException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The one check that keeps a system house account (the tenant's CASH walk-in account) immutable
 * (CAP:550 S7, #2505; accounting workspace spec §4.4 item 2).
 *
 * <p>Every pos-customer write that would change, merge, delete or attach data to a party calls
 * {@link #requireNotHouseAccount(UUID)} first — from the service method, not the controller, so a
 * command handler or any future caller of that method inherits the refusal. The check runs before
 * any change is made and before any fact is queued.
 *
 * <p>Tenant isolation (ADR-0062): the lookup runs under the bound tenant, so another tenant's
 * house account id matches no row and passes the guard — the caller then answers its ordinary
 * not-found, and nothing reveals that the id is a house account elsewhere.
 */
@Component
@RequiredArgsConstructor
public class HouseAccountGuard {

    private final CommercialPartyRepository commercialPartyRepository;

    /**
     * Refuse the write when {@code partyId} is a house account of the bound tenant.
     *
     * @throws HouseAccountImmutableException mapped to {@code 409 HOUSE_ACCOUNT_IMMUTABLE}
     */
    public void requireNotHouseAccount(@NonNull UUID partyId) {
        if (commercialPartyRepository.existsByPartyIdAndHouseAccountIsNotNull(partyId)) {
            throw new HouseAccountImmutableException(partyId);
        }
    }
}
