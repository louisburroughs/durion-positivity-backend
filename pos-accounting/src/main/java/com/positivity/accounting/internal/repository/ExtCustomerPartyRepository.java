package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtCustomerParty;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@code ext_customer_party} replica (issue #1779). Batch display resolution
 * uses the inherited {@code findAllById}, which issues a single {@code IN} query — list responses
 * must not resolve names one row at a time.
 */
public interface ExtCustomerPartyRepository extends JpaRepository<ExtCustomerParty, UUID> {

    /**
     * The bound tenant's parties flagged with {@code houseAccount} (#2508): for {@code CASH_SALE}, the
     * CASH walk-in account — one per tenant once pos-customer's party facts have been replayed, none before.
     *
     * @param houseAccount the house-account kind, e.g. {@code CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE}
     * @return the flagged parties (unordered)
     */
    @NonNull
    List<ExtCustomerParty> findByHouseAccount(@NonNull String houseAccount);
}
