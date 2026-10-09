package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ApVendorSettings;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A vendor's accounting-side settings: the remit-to confirmation and the AP defaults (CAP:550 S24, #2517). */
public interface ApVendorSettingsRepository extends JpaRepository<ApVendorSettings, UUID> {

    @NonNull
    Optional<ApVendorSettings> findByVendorId(@NonNull UUID vendorId);

    /** The vendor's row locked for a write, so two writers of one vendor's settings queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ApVendorSettings s where s.vendorId = :vendorId")
    @NonNull
    Optional<ApVendorSettings> lockByVendorId(@Param("vendorId") @NonNull UUID vendorId);

    @NonNull
    List<ApVendorSettings> findByVendorIdIn(@NonNull Collection<UUID> vendorIds);
}
