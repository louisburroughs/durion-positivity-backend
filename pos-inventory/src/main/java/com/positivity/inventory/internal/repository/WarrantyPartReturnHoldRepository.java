package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyPartReturnHoldRepository extends JpaRepository<WarrantyPartReturnHold, UUID> {

    /**
     * Part-return holds carrying a given serial number (odoo-parity E5, issue #1051): the serial
     * traceability join point. pos-warranty materializes one hold per defective-part return, keyed
     * on the same manufacturer serial the inventory serial unit carries.
     */
    List<WarrantyPartReturnHold> findBySerialNumber(String serialNumber);
}
