package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.SupplierAuthType;
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
 */
class AuthReferenceRulesTest {

    private static final String BASIC = "BASIC_PLUS_APIKEY";
    private static final String OAUTH2 = "OAUTH2_CLIENT_CREDENTIALS";
    private static final String BEARER = "BEARER";

    // ── Happy path per type ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} with exactly its required refs passes")
    @EnumSource(SupplierAuthType.class)
    void completeWellFormedRequestPassesPerType(SupplierAuthType type) {
        assertThatCode(() -> AuthReferenceRules.validate(valid(type))).doesNotThrowAnyException();
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

    // ── apiKeyHeader is configuration data, never a secret reference ────────────────

    @Test
    void apiKeyHeaderIsOptionalForBasicPlusApikey() {
        // null means "adapter default header name" — presence is NOT required.
        assertThatCode(() -> AuthReferenceRules.validate(withHeader(valid(BASIC), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void apiKeyHeaderIsNeverValidatedAsSecretReference() {
        // A plain header name has no scheme prefix and must not trip the scheme:key check.
        assertThatCode(() -> AuthReferenceRules.validate(withHeader(valid(BASIC), "x-api-key")))
                .doesNotThrowAnyException();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private static AuthConfigRequest valid(String type) {
        return valid(SupplierAuthType.valueOf(type));
    }

    private static AuthConfigRequest valid(SupplierAuthType type) {
        return switch (type) {
            case BASIC_PLUS_APIKEY -> new AuthConfigRequest(
                    "auth", type, "env:USER", "env:PASSWORD", "env:APIKEY", "apikey", null, null, null, null);
            case OAUTH2_CLIENT_CREDENTIALS -> new AuthConfigRequest(
                    "auth", type, null, null, null, null, "env:TOKEN_URL", "env:CLIENT_ID", "env:CLIENT_SECRET", null);
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
        assertThatThrownBy(() -> AuthReferenceRules.validate(request))
                .isInstanceOf(SupplierValidationException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }
}
