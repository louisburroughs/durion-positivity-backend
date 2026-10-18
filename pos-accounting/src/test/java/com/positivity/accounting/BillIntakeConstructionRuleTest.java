package com.positivity.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.billintake.service.BillIntakePortImpl;
import com.positivity.accounting.internal.entity.VendorBill;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ArchitectureTest#vendor_bills_are_created_only_by_the_bill_intake_port} catches a second creator of a
 * {@code VendorBill}, lets the id-only reference constructor through and passes the port's implementation (#2518,
 * ruling 10).
 */
@DisplayName("ArchUnit: a VendorBill born outside BillIntakePortImpl is caught (#2518)")
class BillIntakeConstructionRuleTest {

    /** The shape of the construction block the story moved out of VendorBillServiceImpl. */
    static final class CreatesABill {
        VendorBill create() {
            return new VendorBill();
        }
    }

    /** A constructor reference does not call the constructor where it is written, but it builds the bill all the same. */
    static final class CreatesABillByReference {
        java.util.function.Supplier<VendorBill> factory() {
            return VendorBill::new;
        }
    }

    static final class NamesABillById {
        VendorBill reference(UUID id) {
            return new VendorBill(id);
        }
    }

    private static boolean violates(Class<?>... types) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importClasses(types);
        return ArchitectureTest.vendor_bills_are_created_only_by_the_bill_intake_port
                .evaluate(classes)
                .hasViolation();
    }

    @Test
    @DisplayName("new VendorBill() in another class violates; VendorBill(UUID) does not")
    void catchesASecondCreator() {
        assertThat(violates(CreatesABill.class)).isTrue();
        assertThat(violates(CreatesABillByReference.class)).isTrue();
        assertThat(violates(NamesABillById.class)).isFalse();
    }

    @Test
    @DisplayName("BillIntakePortImpl, the single writer, passes")
    void passesThePort() {
        assertThat(violates(BillIntakePortImpl.class, NamesABillById.class)).isFalse();
    }
}
