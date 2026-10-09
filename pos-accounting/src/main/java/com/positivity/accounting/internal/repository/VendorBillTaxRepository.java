package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillTax;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The tax a vendor bill's document states, by tax type (CAP:550 S32d item 10). */
public interface VendorBillTaxRepository extends JpaRepository<VendorBillTax, UUID> {

    /** The bill's stated tax by type, in tax-type order. */
    List<VendorBillTax> findByVendorBillIdOrderByTaxType(UUID vendorBillId);

    /** Removes the bill's stated tax by type, before an approval's {@code taxByType[]} replaces it. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from VendorBillTax t where t.vendorBillId = :vendorBillId")
    void deleteByVendorBillId(@Param("vendorBillId") UUID vendorBillId);
}
