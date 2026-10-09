package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accounting's copy of the pos-supplier vendor master (CAP:550 S24, #2517). Written only by the vendor-fact consumer. */
public interface ExtSupplierVendorRepository extends JpaRepository<ExtSupplierVendor, UUID> {

    /**
     * The vendors whose display name contains {@code name} (case-insensitive; null or blank: every vendor) and whose
     * status is {@code status} (null: either), ordered by name: the vendor typeahead.
     */
    @Query("select v from ExtSupplierVendor v"
            + " where (:name is null or lower(v.displayName) like lower(concat('%', :name, '%')) escape '\\')"
            + " and (:status is null or v.status = :status)"
            + " order by v.displayName, v.vendorId")
    @NonNull
    List<ExtSupplierVendor> search(@Param("name") String name, @Param("status") String status, @NonNull Pageable page);

    /**
     * The vendor's copy row, locked: the vendor commands (remit-to confirmation, AP settings) serialise on it, so two
     * first writes of one vendor's settings never race on the insert.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ExtSupplierVendor v where v.vendorId = :vendorId")
    @NonNull
    Optional<ExtSupplierVendor> lockByVendorId(@Param("vendorId") @NonNull UUID vendorId);
}
