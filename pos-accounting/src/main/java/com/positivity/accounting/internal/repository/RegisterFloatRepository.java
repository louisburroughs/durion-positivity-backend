package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.RegisterFloat;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RegisterFloatRepository extends JpaRepository<RegisterFloat, UUID> {

    @NonNull
    Optional<RegisterFloat> findByRegisterId(@NonNull String registerId);

    /** The register's float, row-locked to the end of the transaction: float commands serialize on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM RegisterFloat f WHERE f.registerId = :registerId")
    @NonNull
    Optional<RegisterFloat> lockByRegisterId(@NonNull String registerId);

    /** The float row, row-locked to the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM RegisterFloat f WHERE f.registerFloatId = :registerFloatId")
    @NonNull
    Optional<RegisterFloat> lockById(@NonNull UUID registerFloatId);

    /** Every register's float in register order, for the bootstrap republish. */
    @NonNull
    List<RegisterFloat> findAllByOrderByRegisterIdAsc();
}
