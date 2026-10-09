package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillTaxRecovery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** What each vendor bill's posting did with its tax (CAP:550 S32d item 10). */
public interface VendorBillTaxRecoveryRepository extends JpaRepository<VendorBillTaxRecovery, UUID> {

    /** The bill's recovery rows, tax type order, the unsplit row first. */
    List<VendorBillTaxRecovery> findByVendorBillIdOrderByTaxTypeAsc(UUID vendorBillId);
}
