package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Only a violation of the vendor-number key means "number taken" (#2516 review): any other
 * integrity failure on create is a defect and must surface as one, not as a 409.
 */
@DisplayName("SupplierVendorServiceImpl — which integrity violation is a taken number")
class VendorNumberTakenMappingTest {

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("duplicate", new SQLException("23505"), constraint));
    }

    @Test
    void theVendorNumberKeyIsATakenNumber() {
        assertThat(SupplierVendorServiceImpl.isVendorNumberTaken(violation("supplier_vendor_number_key")))
                .isTrue();
    }

    @Test
    void anyOtherConstraintIsNot() {
        assertThat(SupplierVendorServiceImpl.isVendorNumberTaken(violation("chk_svendor_currency")))
                .isFalse();
        assertThat(SupplierVendorServiceImpl.isVendorNumberTaken(new DataIntegrityViolationException("no cause")))
                .isFalse();
    }
}
