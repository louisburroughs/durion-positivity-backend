package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.RegisterCashMovementTaxRecovery;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-regime recovery records of petty expenses posted at close (CAP:550 S32d item 9). */
public interface RegisterCashMovementTaxRecoveryRepository
        extends JpaRepository<RegisterCashMovementTaxRecovery, UUID> {

    /** A movement's records, one per stated regime. */
    @NonNull
    List<RegisterCashMovementTaxRecovery> findByMovementIdOrderByRegimeAsc(@NonNull UUID movementId);
}
