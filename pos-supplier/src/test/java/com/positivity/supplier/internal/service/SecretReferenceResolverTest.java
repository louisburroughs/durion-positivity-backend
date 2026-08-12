package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Secret-reference contract (ADR-0050 §4): {@code scheme:key} shape, {@code env:} resolution
 * at call time (via the test seam and once against the real environment), typed failures for
 * malformed references / unknown schemes / unset variables — and secret hygiene: no failure
 * message ever carries a resolved value.
 */
class SecretReferenceResolverTest {

    private static final String SECRET_VALUE = "s3cr3t-value-never-logged";

    private final EnvSecretReferenceResolver resolver =
            new EnvSecretReferenceResolver(Map.of("SUPPLIER_TEST_PASSWORD", SECRET_VALUE, "EMPTY_VAR", "")::get);

    // ── SecretReference shape ───────────────────────────────────────────────────────

    @Test
    void parsesSchemePrefixedReference() {
        SecretReference reference = SecretReference.parse("env:SUPPLIER_TEST_PASSWORD");

        assertThat(reference.scheme()).isEqualTo("env");
        assertThat(reference.key()).isEqualTo("SUPPLIER_TEST_PASSWORD");
    }

    @Test
    void rejectsPlaintextLookingValueWithoutScheme() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SecretReference.parse("hunter2"))
                .withMessageContaining("scheme:key");
        assertThat(SecretReference.isWellFormed("hunter2")).isFalse();
    }

    @Test
    void rejectsBlankSchemeOrKey() {
        assertThat(SecretReference.isWellFormed(":VALUE")).isFalse();
        assertThat(SecretReference.isWellFormed("env:")).isFalse();
        assertThat(SecretReference.isWellFormed("bad scheme:VALUE")).isFalse();
    }

    // ── env: resolution ─────────────────────────────────────────────────────────────

    @Test
    void resolvesEnvReferenceThroughSeam() {
        assertThat(resolver.resolve("env:SUPPLIER_TEST_PASSWORD")).isEqualTo(SECRET_VALUE);
    }

    @Test
    void resolvesAgainstRealProcessEnvironment() {
        Assumptions.assumeTrue(System.getenv("PATH") != null, "PATH must be set for the round-trip check");

        assertThat(new EnvSecretReferenceResolver().resolve("env:PATH")).isEqualTo(System.getenv("PATH"));
    }

    // ── typed failures, secret-free messages ────────────────────────────────────────

    @Test
    void malformedReferenceFailsTyped() {
        assertThatThrownBy(() -> resolver.resolve("not-a-reference"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.SECRET_REFERENCE_INVALID);
    }

    @Test
    void unknownSchemeFailsTypedAndNamesTheScheme() {
        assertThatThrownBy(() -> resolver.resolve("vault:supplier/password"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.UNKNOWN_SECRET_SCHEME)
                .hasMessageContaining("vault")
                .hasMessageContaining("env");
    }

    @Test
    void unsetVariableFailsTyped() {
        assertThatThrownBy(() -> resolver.resolve("env:NO_SUCH_SUPPLIER_VAR"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.SECRET_NOT_FOUND)
                .hasMessageContaining("env:NO_SUCH_SUPPLIER_VAR");
    }

    @Test
    void emptyVariableBehavesAsUnset() {
        assertThatThrownBy(() -> resolver.resolve("env:EMPTY_VAR"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.SECRET_NOT_FOUND);
    }

    @Test
    void failureMessagesNeverCarryResolvedValues() {
        // Resolution succeeds for the seeded variable — force the failure paths and assert no
        // message can echo the (resolvable) secret value.
        for (String reference : new String[] {"vault:SUPPLIER_TEST_PASSWORD", "env:NO_SUCH_SUPPLIER_VAR", "raw"}) {
            assertThatThrownBy(() -> resolver.resolve(reference))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(SECRET_VALUE));
        }
    }
}
