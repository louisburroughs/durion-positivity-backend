package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovementStatedTax;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The stated taxes of drawer movements (CAP:550 S32d); the tenant filter and RLS scope every read. */
public interface CashMovementStatedTaxRepository extends JpaRepository<CashMovementStatedTax, UUID> {

    /** The stated taxes of one movement, in regime order. */
    List<CashMovementStatedTax> findByMovementIdOrderByRegimeAsc(UUID movementId);

    /** The stated taxes of several movements, for a list, a report or the close fact. */
    List<CashMovementStatedTax> findByMovementIdInOrderByRegimeAsc(Collection<UUID> movementIds);
}
