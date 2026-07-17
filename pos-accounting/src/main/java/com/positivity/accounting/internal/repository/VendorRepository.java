package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.Vendor;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the AP vendor directory (Issue #816).
 */
public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    /**
     * Case-insensitive name-contains search, ordered by name, for typeahead
     * queries. Pass an unsorted {@link Pageable} to cap the result size.
     */
    List<Vendor> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    /**
     * List all vendors ordered by name (used when no search term is given).
     */
    List<Vendor> findAllByOrderByNameAsc(Pageable pageable);
}
