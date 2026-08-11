package com.positivity.supplier.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Guards the {@link SupplierConfigurationException} code → status table (ADR-0017, ADR-0050 §3).
 *
 * <p>Until slice 3, {@code SupplierConfigurationException} had no {@code @ExceptionHandler} at
 * all, so every typed pre-flight configuration failure fell into the generic 500 catch-all —
 * {@code SUPPLIER_CAPABILITY_NOT_CONFIGURED}, a perfectly ordinary "this supplier has no binding
 * for that capability" state, would have surfaced as an internal server error, the leak ADR-0050
 * §3 forbids.
 *
 * <p>The mapping is only durable if it cannot silently fall behind the exception. This test walks
 * the exception's declared code constants by reflection and fails when any is unmapped, so adding
 * a code forces an explicit HTTP decision rather than inheriting 500 by default.
 */
class SupplierConfigurationCodeMappingTest {

    @Test
    void everyDeclaredConfigurationCodeHasAnExplicitStatus() {
        Set<String> declared = declaredCodes();

        assertThat(SupplierExceptionHandler.CONFIGURATION_STATUS.keySet())
                .as("every SupplierConfigurationException code must have a decided HTTP status;"
                        + " an unmapped code falls into the 500 catch-all (ADR-0050 §3)")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void everyCodeIsPrefixedWithItsOwningDomain() {
        assertThat(declaredCodes())
                .as("wire codes must name their domain so a caller can attribute the failure")
                .allSatisfy(code -> assertThat(code).startsWith("SUPPLIER_"));
    }

    @Test
    void capabilityNotConfiguredIsAConflictNotAServerError() {
        // The specific regression this table exists to prevent.
        assertThat(SupplierExceptionHandler.CONFIGURATION_STATUS.get(
                        SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED)
                .isEqualTo("SUPPLIER_CAPABILITY_NOT_CONFIGURED");
    }

    @Test
    void secretResolutionFailuresAreServerSideDefects() {
        // A caller cannot fix an unset env var; these must not read as client errors.
        assertThat(SupplierExceptionHandler.CONFIGURATION_STATUS)
                .containsEntry(
                        SupplierConfigurationException.SECRET_REFERENCE_INVALID, HttpStatus.INTERNAL_SERVER_ERROR)
                .containsEntry(SupplierConfigurationException.UNKNOWN_SECRET_SCHEME, HttpStatus.INTERNAL_SERVER_ERROR)
                .containsEntry(SupplierConfigurationException.SECRET_NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void callerActionableConfigurationStatesAreClientErrors() {
        assertThat(SupplierExceptionHandler.CONFIGURATION_STATUS)
                .containsEntry(SupplierConfigurationException.UNKNOWN_SUPPLIER, HttpStatus.NOT_FOUND)
                .containsEntry(SupplierConfigurationException.PROFILE_DISABLED, HttpStatus.CONFLICT)
                .containsEntry(SupplierConfigurationException.MISSING_BILLING_ACCOUNT, HttpStatus.CONFLICT)
                .containsEntry(SupplierConfigurationException.MISSING_DELIVERY_MAPPING, HttpStatus.CONFLICT)
                .containsEntry(SupplierConfigurationException.AUTH_CONFIG_MISSING, HttpStatus.CONFLICT);
    }

    /** Every {@code public static final String} code constant on the exception. */
    private static Set<String> declaredCodes() {
        Set<String> codes = new TreeSet<>();
        for (Field field : SupplierConfigurationException.class.getDeclaredFields()) {
            boolean isConstant = Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class;
            if (!isConstant) {
                continue;
            }
            try {
                codes.add((String) field.get(null));
            } catch (IllegalAccessException ex) {
                throw new AssertionError("code constant " + field.getName() + " is not readable", ex);
            }
        }
        assertThat(codes)
                .as("the exception must declare its codes as constants")
                .isNotEmpty();
        return codes;
    }
}
