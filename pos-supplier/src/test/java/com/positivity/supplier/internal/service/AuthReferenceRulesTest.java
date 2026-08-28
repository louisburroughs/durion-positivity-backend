package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.service.model.AuthConfigRequest;
import com.positivity.supplier.internal.service.model.SupplierAuthType;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive per-{@link SupplierAuthType} reference matrix of {@link AuthReferenceRules}
 * (ADR-0050 §4): every required reference must be present, well-formed ({@code scheme:key})
 * and never plaintext-looking; every reference a type does not use must be absent — even when
 * well-formed. {@code apiKeyHeader} is deliberately outside the matrix: it is a plain header
 * NAME (configuration data with an adapter default, not a secret reference) and is never
 * validated as one.
 *
 * <p>Also pins the scheme allowlist: a reference is legal only when a registered resolver claims
 * its scheme. {@code user:hunter2} and {@code MYDOMAIN:hunter2} are the motivating cases — both
 * are well-formed {@code scheme:key} strings and both are plaintext credentials, so shape checks
 * alone would persist them and defer the failure to the first outbound call.
 */
class AuthReferenceRulesTest {

    /** The production allowlist today: exactly the schemes {@code SecretSchemeRegistry} exposes. */
    private static final Set<String> SUPPORTED_SCHEMES = Set.of("env");

    private static final String BASIC = "BASIC_PLUS_APIKEY";
    private static final String OAUTH2 = "OAUTH2_CLIENT_CREDENTIALS";
    private static final String BEARER = "BEARER";

    // ── Happy path per type ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} with exactly its required refs passes")
    @EnumSource(SupplierAuthType.class)
    void completeWellFormedRequestPassesPerType(SupplierAuthType type) {
        assertThatCode(() -> AuthReferenceRules.validate(valid(type), SUPPORTED_SCHEMES))
                .doesNotThrowAnyException();
    }

    // ── Required refs: absent, blank, plaintext-looking ─────────────────────────────

    /** Every (type, required reference field) pair of ADR-0050 §4. */
    static Stream<Arguments> requiredRefs() {
        return Stream.of(
                Arguments.of(BASIC, "usernameRef"),
                Arguments.of(BASIC, "passwordRef"),
                Arguments.of(BASIC, "apiKeyRef"),
                Arguments.of(OAUTH2, "tokenUrlRef"),
                Arguments.of(OAUTH2, "clientIdRef"),
                Arguments.of(OAUTH2, "clientSecretRef"),
                Arguments.of(BEARER, "bearerTokenRef"));
    }

    @ParameterizedTest(name = "{0} without {1} is incomplete")
    @MethodSource("requiredRefs")
    void missingRequiredRefIsIncomplete(String type, String field) {
        assertRule(withRef(valid(type), field, null), SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    @ParameterizedTest(name = "{0} with blank {1} is incomplete")
    @MethodSource("requiredRefs")
    void blankRequiredRefIsIncomplete(String type, String field) {
        assertRule(withRef(valid(type), field, "  "), SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    @ParameterizedTest(name = "{0} with plaintext-looking {1} is malformed")
    @MethodSource("requiredRefs")
    void plaintextLookingRequiredRefIsMalformed(String type, String field) {
        assertRule(withRef(valid(type), field, "hunter2"), SupplierValidationException.SECRET_REF_MALFORMED);
    }

    // ── Forbidden refs: present although the type does not use them ─────────────────

    /** Every (type, reference field the type does not use) pair of ADR-0050 §4. */
    static Stream<Arguments> forbiddenRefs() {
        return Stream.of(
                Arguments.of(BASIC, "tokenUrlRef"),
                Arguments.of(BASIC, "clientIdRef"),
                Arguments.of(BASIC, "clientSecretRef"),
                Arguments.of(BASIC, "bearerTokenRef"),
                Arguments.of(OAUTH2, "usernameRef"),
                Arguments.of(OAUTH2, "passwordRef"),
                Arguments.of(OAUTH2, "apiKeyRef"),
                Arguments.of(OAUTH2, "bearerTokenRef"),
                Arguments.of(BEARER, "usernameRef"),
                Arguments.of(BEARER, "passwordRef"),
                Arguments.of(BEARER, "apiKeyRef"),
                Arguments.of(BEARER, "tokenUrlRef"),
                Arguments.of(BEARER, "clientIdRef"),
                Arguments.of(BEARER, "clientSecretRef"));
    }

    @ParameterizedTest(name = "{0} with extra {1} is incomplete even when well-formed")
    @MethodSource("forbiddenRefs")
    void wellFormedButNonApplicableRefIsRejected(String type, String field) {
        assertRule(withRef(valid(type), field, "env:EXTRA"), SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    /**
     * A blank value on a non-applicable field is rejected at write time, rather than being read as
     * "absent" and left to fail when the reference is resolved mid-exchange.
     *
     * <p>Pinned because the asymmetry invites the opposite reading: {@code requireRef} rejects
     * null-or-blank while {@code forbidRef} rejects any non-null, so it looks as though a blank slips
     * through the forbidden case. It does not — and the distinction is the whole point of this class,
     * which exists to move credential-shape failures from call time to write time.
     */
    @ParameterizedTest(name = "{0} with a blank {1} is rejected at write time, not at call time")
    @MethodSource("forbiddenRefs")
    void blankNonApplicableRefIsRejectedAtWriteTime(String type, String field) {
        assertRule(withRef(valid(type), field, ""), SupplierValidationException.AUTH_REFS_INCOMPLETE);
        assertRule(withRef(valid(type), field, "   "), SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    // ── Scheme allowlist: well-formed but unresolvable schemes are plaintext ────────

    /**
     * Every scheme no resolver claims. {@code user:hunter2} is the review's motivating case: a
     * plaintext password that merely happens to contain a colon.
     */
    static Stream<Arguments> unsupportedSchemeValues() {
        return Stream.of(
                Arguments.of("user:hunter2"),
                Arguments.of("MYDOMAIN:hunter2"),
                Arguments.of("vault:secret/data/michelin#password"),
                Arguments.of("aws:arn:secret:password"),
                Arguments.of("ENV:UPPERCASE_IS_A_DIFFERENT_SCHEME"),
                Arguments.of("http://example.test/secret"));
    }

    /** Every (type, required field) pair crossed with every unsupported scheme value. */
    static Stream<Arguments> requiredRefsWithUnsupportedSchemes() {
        return requiredRefs()
                .flatMap(pair -> unsupportedSchemeValues()
                        .map(value -> Arguments.of(pair.get()[0], pair.get()[1], value.get()[0])));
    }

    @ParameterizedTest(name = "{0} with {1} valued {2} is rejected: no resolver supports the scheme")
    @MethodSource("requiredRefsWithUnsupportedSchemes")
    void requiredRefInUnsupportedSchemeIsRejected(String type, String field, String value) {
        assertRule(withRef(valid(type), field, value), SupplierValidationException.SECRET_REF_MALFORMED);
    }

    @Test
    void unsupportedSchemeRejectionNamesTheFieldAndTheAllowlist() {
        assertThatThrownBy(() -> AuthReferenceRules.validate(
                        withRef(valid(BASIC), "passwordRef", "user:hunter2"), SUPPORTED_SCHEMES))
                .isInstanceOf(SupplierValidationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierValidationException.SECRET_REF_MALFORMED)
                .hasMessageContaining("passwordRef")
                .hasMessageContaining("env:");
    }

    /**
     * The rejected "scheme" is the leading fragment of whatever the operator typed. For a
     * plaintext password containing a colon that fragment IS part of the credential, so it must
     * not reach the message -- which goes to the startup log and the API error envelope.
     */
    @Test
    void unsupportedSchemeRejectionNeverNamesTheOffendingSchemeBecauseItMayBeCredentialText() {
        assertThatThrownBy(() -> AuthReferenceRules.validate(
                        withRef(valid(BASIC), "passwordRef", "Passw0rd:2026"), SUPPORTED_SCHEMES))
                .isInstanceOf(SupplierValidationException.class)
                .hasMessageNotContaining("Passw0rd")
                .hasMessageNotContaining("2026");
    }

    @Test
    void unsupportedSchemeRejectionNeverEchoesTheCredentialValue() {
        // The rejected value is very often the credential itself; it must not reach logs or the
        // API error envelope (ADR-0050 §4).
        assertThatThrownBy(() -> AuthReferenceRules.validate(
                        withRef(valid(BASIC), "passwordRef", "user:hunter2"), SUPPORTED_SCHEMES))
                .isInstanceOf(SupplierValidationException.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("user");
    }

    @Test
    void widerAllowlistAcceptsTheAdditionalSchemeWithoutCodeChange() {
        // Proves the allowlist is data supplied by the resolver registry, so adding a resolver
        // bean is the only thing needed to support a new scheme.
        assertThatCode(() -> AuthReferenceRules.validate(
                        withRef(valid(BEARER), "bearerTokenRef", "vault:kv/michelin#token"), Set.of("env", "vault")))
                .doesNotThrowAnyException();
    }

    @Test
    void nonApplicableRefIsStillRejectedRegardlessOfScheme() {
        // Forbidden-ref checks are scheme-independent: presence alone is the violation.
        assertRule(
                withRef(valid(BEARER), "usernameRef", "user:hunter2"),
                SupplierValidationException.AUTH_REFS_INCOMPLETE);
    }

    // ── apiKeyHeader is configuration data, never a secret reference ────────────────

    @Test
    void apiKeyHeaderIsOptionalForBasicPlusApikey() {
        // null means "adapter default header name" — presence is NOT required.
        assertThatCode(() -> AuthReferenceRules.validate(withHeader(valid(BASIC), null), SUPPORTED_SCHEMES))
                .doesNotThrowAnyException();
    }

    @Test
    void apiKeyHeaderIsNeverValidatedAsSecretReference() {
        // A plain header name has no scheme prefix and must not trip the scheme:key check.
        assertThatCode(() -> AuthReferenceRules.validate(withHeader(valid(BASIC), "x-api-key"), SUPPORTED_SCHEMES))
                .doesNotThrowAnyException();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private static AuthConfigRequest valid(String type) {
        return valid(SupplierAuthType.valueOf(type));
    }

    private static AuthConfigRequest valid(SupplierAuthType type) {
        return switch (type) {
            case BASIC_PLUS_APIKEY ->
                new AuthConfigRequest(
                        "auth", type, "env:USER", "env:PASSWORD", "env:APIKEY", "apikey", null, null, null, null);
            case OAUTH2_CLIENT_CREDENTIALS ->
                new AuthConfigRequest(
                        "auth",
                        type,
                        null,
                        null,
                        null,
                        null,
                        "env:TOKEN_URL",
                        "env:CLIENT_ID",
                        "env:CLIENT_SECRET",
                        null);
            case BEARER -> new AuthConfigRequest("auth", type, null, null, null, null, null, null, null, "env:BEARER");
        };
    }

    /** Rebuilds the record with a single {@code *Ref} component replaced. */
    private static AuthConfigRequest withRef(AuthConfigRequest base, String field, String value) {
        return new AuthConfigRequest(
                base.name(),
                base.type(),
                "usernameRef".equals(field) ? value : base.usernameRef(),
                "passwordRef".equals(field) ? value : base.passwordRef(),
                "apiKeyRef".equals(field) ? value : base.apiKeyRef(),
                base.apiKeyHeader(),
                "tokenUrlRef".equals(field) ? value : base.tokenUrlRef(),
                "clientIdRef".equals(field) ? value : base.clientIdRef(),
                "clientSecretRef".equals(field) ? value : base.clientSecretRef(),
                "bearerTokenRef".equals(field) ? value : base.bearerTokenRef());
    }

    private static AuthConfigRequest withHeader(AuthConfigRequest base, String header) {
        return new AuthConfigRequest(
                base.name(),
                base.type(),
                base.usernameRef(),
                base.passwordRef(),
                base.apiKeyRef(),
                header,
                base.tokenUrlRef(),
                base.clientIdRef(),
                base.clientSecretRef(),
                base.bearerTokenRef());
    }

    private static void assertRule(AuthConfigRequest request, String expectedCode) {
        assertThatThrownBy(() -> AuthReferenceRules.validate(request, SUPPORTED_SCHEMES))
                .isInstanceOf(SupplierValidationException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }
}
