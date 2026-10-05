package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtCustomer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerRepository extends JpaRepository<ExtCustomer, UUID> {

    /**
     * The bound tenant's party of one house-account kind in one status. A derived query, so
     * Hibernate's tenant filter scopes it (ADR-0062): another tenant's house account is never
     * returned. Ordered so the answer is stable should a tenant ever hold more than one.
     */
    Optional<ExtCustomer> findFirstByHouseAccountAndStatusOrderByPartyIdAsc(String houseAccount, String status);
}
