package com.positivity.order.internal.service;

import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The tenant's CASH house account ("Walk-in customer") as this module knows it: the
 * {@code ext_customer} replica row whose {@code house_account} is {@code CASH_SALE} (CAP:550 S8,
 * decision AW12). pos-customer owns and provisions the account; pos-order only reads the copy
 * (ADR-0044, no synchronous CRM call).
 *
 * <p>The flag is the whole test. A customer number of {@code CASH} or a display name of "Walk-in
 * customer" proves nothing, and a replica that has not yet received the flag simply has no walk-in
 * customer until a party-fact replay fills it. Every read goes through a tenant-filtered
 * repository call (ADR-0062), so one tenant's house account is never resolved for another.
 */
@Component
@RequiredArgsConstructor
public class HouseAccountReplica {

    /** Party status a house account must hold to be chosen for a new walk-in sale. */
    static final String ACTIVE = "ACTIVE";

    private final ExtCustomerRepository extCustomerRepository;

    /** The bound tenant's usable walk-in customer, or empty when the replica holds none. */
    public @NonNull Optional<ExtCustomer> findActiveCashSale() {
        return extCustomerRepository.findFirstByHouseAccountAndStatusOrderByPartyIdAsc(
                CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE, ACTIVE);
    }

    /** Whether {@code customerId} is the bound tenant's CASH house account, in any status. */
    public boolean isCashSale(@Nullable UUID customerId) {
        return customerId != null
                && isCashSale(extCustomerRepository.findById(customerId).orElse(null));
    }

    /** Whether a replica row is a CASH house account. */
    public static boolean isCashSale(@Nullable ExtCustomer customer) {
        return customer != null && CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE.equals(customer.getHouseAccount());
    }

    /** The subset of {@code customerIds} that are CASH house accounts of the bound tenant. */
    public @NonNull Set<UUID> cashSaleIdsAmong(@NonNull Collection<UUID> customerIds) {
        Set<UUID> ids = customerIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Set.of();
        }
        return extCustomerRepository.findAllById(ids).stream()
                .filter(HouseAccountReplica::isCashSale)
                .map(ExtCustomer::getPartyId)
                .collect(Collectors.toSet());
    }
}
