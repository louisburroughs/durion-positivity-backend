package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * {@link SecretSchemeRegistry} is both the call-time dispatcher and the write-time scheme
 * allowlist (ADR-0050 §4/§6), so these tests pin three things: the allowlist is exactly the set
 * of registered resolver schemes (never a configured superset), dispatch reaches the resolver
 * claiming the scheme, and an unclaimed scheme fails typed rather than falling through.
 *
 * <p>Duplicate scheme registration must fail startup, not silently shadow one resolver — with two
 * resolvers claiming {@code env} the credential a supplier authenticates with would depend on
 * bean ordering.
 */
class SecretSchemeRegistryTest {

    private static final String SECRET_VALUE = "s3cr3t";

    private final SecretSchemeRegistry registry = new SecretSchemeRegistry(
            List.of(new EnvSecretReferenceResolver(Map.of("SUPPLIER_TEST_PASSWORD", SECRET_VALUE)::get)));

    // ── Allowlist ───────────────────────────────────────────────────────────────────

    @Test
    void supportedSchemesIsExactlyTheRegisteredResolverSchemes() {
        assertThat(registry.supportedSchemes())
                .as("the allowlist must be derived from the deployed resolvers, never configured separately")
                .containsExactly("env");
    }

    @Test
    void envIsTheOnlySupportedSchemeInTheCurrentDeployment() {
        assertThat(registry.supports("env")).isTrue();
        assertThat(registry.supports("vault")).isFalse();
        assertThat(registry.supports("user")).isFalse();
        assertThat(registry.supports("ENV"))
                .as("scheme matching is case-sensitive")
                .isFalse();
    }

    @Test
    void describeSupportedSchemesRendersOperatorFacingText() {
        assertThat(registry.describeSupportedSchemes()).isEqualTo("env:");
        assertThat(new SecretSchemeRegistry(List.of(new FakeResolver("vault"), new FakeResolver("env")))
                        .describeSupportedSchemes())
                .as("stable alphabetical order regardless of bean ordering")
                .isEqualTo("env:, vault:");
    }

    @Test
    void addingAResolverBeanWidensTheAllowlistWithNoOtherChange() {
        SecretSchemeRegistry widened = new SecretSchemeRegistry(
                List.of(new EnvSecretReferenceResolver(Map.<String, String>of()::get), new FakeResolver("vault")));

        assertThat(widened.supportedSchemes()).containsExactly("env", "vault");
    }

    // ── Dispatch ────────────────────────────────────────────────────────────────────

    @Test
    void dispatchesToTheResolverClaimingTheScheme() {
        assertThat(registry.resolve("env:SUPPLIER_TEST_PASSWORD")).isEqualTo(SECRET_VALUE);
    }

    @Test
    void unclaimedSchemeFailsTypedWithoutTouchingAnyResolver() {
        assertThatThrownBy(() -> registry.resolve("vault:kv/michelin#password"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.UNKNOWN_SECRET_SCHEME)
                .hasMessageContaining("vault")
                .hasMessageContaining("env:");
    }

    @Test
    void malformedReferenceFailsTypedBeforeDispatch() {
        assertThatThrownBy(() -> registry.resolve("hunter2"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.SECRET_REFERENCE_INVALID);
    }

    @Test
    void unknownSchemeMessageNeverEchoesTheReferenceKey() {
        // "user:hunter2" is a plaintext credential, not a reference; the key must not be logged.
        assertThatThrownBy(() -> registry.resolve("user:hunter2"))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasMessageNotContaining("hunter2");
    }

    // ── Registration invariants ─────────────────────────────────────────────────────

    @Test
    void duplicateSchemeRegistrationFailsStartup() {
        assertThatThrownBy(() -> new SecretSchemeRegistry(List.of(new FakeResolver("env"), new FakeResolver("env"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate secret reference scheme 'env'");
    }

    @Test
    void blankSchemeRegistrationFailsStartup() {
        assertThatThrownBy(() -> new SecretSchemeRegistry(List.of(new FakeResolver("  "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank scheme");
    }

    @Test
    void noResolversAtAllFailsStartup() {
        assertThatThrownBy(() -> new SecretSchemeRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SecretReferenceResolver beans registered");
    }

    /** Minimal resolver used to exercise registration rules; never resolves anything. */
    private record FakeResolver(String schemeToken) implements SecretReferenceResolver {

        @Override
        @NonNull
        public String scheme() {
            return schemeToken;
        }

        @Override
        @NonNull
        public String resolve(@NonNull String reference) {
            throw new UnsupportedOperationException("fixture resolver");
        }
    }
}
