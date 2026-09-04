package com.positivity.supplier.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * ApiError mapping for every {@code pos-supplier} controller advice (ADR-0017). Every failure
 * this module's controllers can produce passes through one of these handlers, so the
 * exception-to-status table pinned here <em>is</em> the module's public error contract — a
 * status or code drifting here is a breaking change for every caller that branches on it.
 */
@DisplayName("SupplierExceptionHandler — ApiError mapping")
class SupplierExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    private final SupplierExceptionHandler handler = new SupplierExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearAmbientScope() {
        // Belt and braces: a test that opens a correlation scope and fails before closing it must
        // not leak that scope into the next test in this class.
        SupplierCorrelationContext.current().ifPresent(id -> {
            throw new AssertionError("test left an open correlation scope: " + id);
        });
    }

    private static void assertEnvelope(ResponseEntity<ApiError> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().status()).isEqualTo(status.value());
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(response.getHeaders().getFirst(CORRELATION_HEADER))
                .isEqualTo(response.getBody().correlationId());
    }

    @Test
    @DisplayName("maps a not-found domain resource to 404, preserving its typed code")
    void notFoundMapsTo404() {
        assertEnvelope(
                handler.handleNotFound(
                        new SupplierNotFoundException(SupplierNotFoundException.PROFILE_NOT_FOUND, "no such profile"),
                        null),
                HttpStatus.NOT_FOUND,
                SupplierNotFoundException.PROFILE_NOT_FOUND);
    }

    @Test
    @DisplayName("maps semantically invalid profile data to 400, preserving its typed code")
    void validationMapsTo400() {
        assertEnvelope(
                handler.handleValidation(
                        new SupplierValidationException(
                                SupplierValidationException.UNKNOWN_CAPABILITY, "bad capability"),
                        null),
                HttpStatus.BAD_REQUEST,
                SupplierValidationException.UNKNOWN_CAPABILITY);
    }

    @Test
    @DisplayName("maps a marketing-catalogue import failure to 422, distinct from an empty catalogue")
    void mktCatImportFailureMapsTo422() {
        assertEnvelope(
                handler.handleMktCatImportFailure(new MktCatImportException("catalogue unreadable"), null),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SUPPLIER_MKTCAT_IMPORT_FAILED");
    }

    @Test
    @DisplayName("maps an unanswerable fleet lookup to 422, not a 502")
    void fleetLookupFailureMapsTo422() {
        assertEnvelope(
                handler.handleFleetLookupFailure(new FleetLookupException("vendor unreachable"), null),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SUPPLIER_FLEET_LOOKUP_FAILED");
    }

    @Test
    @DisplayName("maps an unfetchable invoice window to 422")
    void invoiceFetchFailureMapsTo422() {
        assertEnvelope(
                handler.handleInvoiceFetch(new InvoiceFetchException("window fetch failed"), null),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SUPPLIER_INVOICE_FETCH_FAILED");
    }

    @Test
    @DisplayName("maps a configuration-state collision to 409, preserving its typed code")
    void conflictMapsTo409() {
        assertEnvelope(
                handler.handleConflict(
                        new SupplierConflictException(SupplierConflictException.SUPPLIER_REF_CONFLICT, "ref taken"),
                        null),
                HttpStatus.CONFLICT,
                SupplierConflictException.SUPPLIER_REF_CONFLICT);
    }

    @Nested
    @DisplayName("handleConfiguration — code-to-status table (ADR-0050 §3/§4/§5)")
    class ConfigurationExceptionMapping {

        @Test
        @DisplayName("a caller-actionable code maps to its table status and keeps the domain message")
        void callerActionableCodeKeepsDomainMessage() {
            ResponseEntity<ApiError> response = handler.handleConfiguration(
                    new SupplierConfigurationException(
                            SupplierConfigurationException.PROFILE_DISABLED, "profile disabled"),
                    null);

            assertEnvelope(response, HttpStatus.CONFLICT, SupplierConfigurationException.PROFILE_DISABLED);
            assertThat(response.getBody().message()).isEqualTo("profile disabled");
        }

        @Test
        @DisplayName("a deployment-defect code maps to 500 with the generic message, never the raw detail")
        void deploymentDefectCodeHidesItsDetail() {
            ResponseEntity<ApiError> response = handler.handleConfiguration(
                    new SupplierConfigurationException(
                            SupplierConfigurationException.SECRET_REFERENCE_INVALID, "env var FOO_SECRET unset"),
                    null);

            assertEnvelope(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    SupplierConfigurationException.SECRET_REFERENCE_INVALID);
            assertThat(response.getBody().message()).isEqualTo(SupplierExceptionHandler.CONFIGURATION_DEFECT_MESSAGE);
            assertThat(response.getBody().message()).doesNotContain("FOO_SECRET");
        }

        @Test
        @DisplayName("a code with no table entry fails closed to 500 rather than inheriting a wrong status")
        void unmappedCodeFailsClosedTo500() {
            ResponseEntity<ApiError> response = handler.handleConfiguration(
                    new SupplierConfigurationException("SUPPLIER_NOT_YET_CATALOGUED", "?"), null);

            assertEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, "SUPPLIER_NOT_YET_CATALOGUED");
            assertThat(response.getBody().message()).isEqualTo(SupplierExceptionHandler.CONFIGURATION_DEFECT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("handlePayloadUnreadable — decryption failures (ADR-0050 §7)")
    class PayloadUnreadableMapping {

        @Test
        @DisplayName("a failed authentication tag maps to 500 with the generic message, code preserved for the log")
        void authenticationFailureMapsTo500() {
            ResponseEntity<ApiError> response = handler.handlePayloadUnreadable(
                    new PayloadUnreadableException(
                            PayloadUnreadableException.AUTHENTICATION_FAILED, "GCM tag mismatch"),
                    null);

            assertEnvelope(
                    response, HttpStatus.INTERNAL_SERVER_ERROR, PayloadUnreadableException.AUTHENTICATION_FAILED);
            assertThat(response.getBody().message()).isEqualTo(SupplierExceptionHandler.PAYLOAD_UNREADABLE_MESSAGE);
        }

        @Test
        @DisplayName("an unknown key id also maps to 500 with the generic message, on the routine-log path")
        void unknownKeyIdMapsTo500() {
            ResponseEntity<ApiError> response = handler.handlePayloadUnreadable(
                    new PayloadUnreadableException(PayloadUnreadableException.UNKNOWN_KEY_ID, "no key for id 7"), null);

            assertEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, PayloadUnreadableException.UNKNOWN_KEY_ID);
            assertThat(response.getBody().message()).isEqualTo(SupplierExceptionHandler.PAYLOAD_UNREADABLE_MESSAGE);
        }
    }

    @Test
    @DisplayName("body validation lists every rejected field, defaulting a null message rather than emitting null")
    void bodyValidationListsFieldErrors() throws NoSuchMethodException {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "supplierRef", "must not be blank"));
        binding.addError(new FieldError("request", "capability", null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        SupplierExceptionHandlerTest.class.getDeclaredMethod("bodyValidationListsFieldErrors"), -1),
                binding);

        ResponseEntity<ApiError> response = handler.handleBodyValidation(ex, null);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .containsExactly(tuple("supplierRef", "must not be blank"), tuple("capability", "invalid value"));
    }

    @Test
    @DisplayName("a constraint violation maps to 400 VALIDATION_ERROR with the violation detail")
    void constraintViolationMapsTo400() {
        assertEnvelope(
                handler.handleConstraintViolation(
                        new jakarta.validation.ConstraintViolationException("must be positive", java.util.Set.of()),
                        null),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a missing request parameter names only the parameter, not attacker-controlled input")
    void missingParameterNamesTheParameter() {
        ResponseEntity<ApiError> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("supplierRef", "String"), null);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "MISSING_PARAMETER");
        assertThat(response.getBody().message()).isEqualTo("Required request parameter 'supplierRef' is missing");
    }

    @Test
    @DisplayName("a parameter type mismatch names only the parameter, never the submitted value")
    void typeMismatchNamesTheParameterOnly() {
        ResponseEntity<ApiError> response = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException(
                        "<script>alert(1)</script>",
                        UUID.class,
                        "supplierProfileId",
                        null,
                        new IllegalArgumentException("bad uuid")),
                null);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
        assertThat(response.getBody().message()).isEqualTo("Parameter 'supplierProfileId' has an invalid value");
        assertThat(response.getBody().message()).doesNotContain("script");
    }

    @Nested
    @DisplayName("handleUnreadableBody — unwraps a canonical-constructor rejection from Jackson's wrapper")
    class UnreadableBodyMapping {

        @Test
        @DisplayName("an IllegalArgumentException cause surfaces as VALIDATION_ERROR with its own message")
        void illegalArgumentCauseIsUnwrapped() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                    new org.springframework.http.converter.HttpMessageNotReadableException(
                            "JSON parse error", new IllegalArgumentException("supplierRef must not be blank"), null);

            ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, null);

            assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(response.getBody().message()).isEqualTo("supplierRef must not be blank");
        }

        @Test
        @DisplayName("a NullPointerException cause is unwrapped the same way as an IllegalArgumentException")
        void nullPointerCauseIsUnwrapped() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                    new org.springframework.http.converter.HttpMessageNotReadableException(
                            "JSON parse error", new NullPointerException(), null);

            ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, null);

            assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        }

        @Test
        @DisplayName("a SupplierValidationException cause keeps its own typed code, message and field errors (#1694)")
        void supplierValidationExceptionCauseIsUnwrapped() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                    new org.springframework.http.converter.HttpMessageNotReadableException(
                            "JSON parse error",
                            new SupplierValidationException(
                                    SupplierValidationException.VALIDATION_ERROR, "supplierRef must not be blank"),
                            null);

            ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, null);

            assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(response.getBody().message()).isEqualTo("supplierRef must not be blank");
        }

        @Test
        @DisplayName(
                "a cause chain with no IllegalArgumentException or NullPointerException falls back to a generic parse failure")
        void unrelatedCauseFallsBackToGenericMessage() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                    new org.springframework.http.converter.HttpMessageNotReadableException(
                            "JSON parse error", new RuntimeException("truncated stream"), null);

            ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, null);

            assertEnvelope(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(response.getBody().message()).isEqualTo("Request body could not be parsed");
        }

        @Test
        @DisplayName("no cause at all also falls back to the generic parse failure")
        void noCauseFallsBackToGenericMessage() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                    new org.springframework.http.converter.HttpMessageNotReadableException(
                            "JSON parse error", (Throwable) null, null);

            ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, null);

            assertEnvelope(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
    }

    // The blanket IllegalArgumentException and Exception handlers are gone (#1694) — see the
    // class javadoc. There is no longer a handleIllegalArgument/handleUnexpected method to unit
    // test; the resulting behavior (a bare IllegalArgumentException or any other unmapped
    // RuntimeException now falls through to pos-web-common's GlobalApiExceptionHandler for a
    // generic 500 INTERNAL_ERROR) is proven end-to-end by
    // SupplierExceptionHandlerErrorHandlingTest, which exercises the real advice chain.

    @Test
    @DisplayName("a permission denial maps to 403, never the 500 catch-all")
    void accessDeniedMapsTo403() {
        assertEnvelope(
                handler.handleAccessDenied(new AccessDeniedException("denied"), null),
                HttpStatus.FORBIDDEN,
                "FORBIDDEN");
    }

    @Test
    @DisplayName("a Spring optimistic-lock failure on a @Version-ed row maps to a retryable 409")
    void objectOptimisticLockingFailureMapsTo409() {
        assertEnvelope(
                handler.handleOptimisticLockConflict(
                        new ObjectOptimisticLockingFailureException(SupplierExceptionHandlerTest.class, "id"), null),
                HttpStatus.CONFLICT,
                "CONFLICT");
    }

    @Test
    @DisplayName("a JPA optimistic-lock exception maps to the same retryable 409")
    void jpaOptimisticLockExceptionMapsTo409() {
        assertEnvelope(
                handler.handleOptimisticLockConflict(new OptimisticLockException("stale"), null),
                HttpStatus.CONFLICT,
                "CONFLICT");
    }

    @Nested
    @DisplayName("correlation id resolution — ambient scope, then inbound header, then generated")
    class CorrelationIdResolution {

        @Test
        @DisplayName("an ambient scope wins even when the request carries a different header value")
        void ambientScopeTakesPrecedenceOverTheHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "header-value-should-be-ignored");

            try (SupplierCorrelationContext.CorrelationScope scope = SupplierCorrelationContext.open("scope-value")) {
                ResponseEntity<ApiError> response =
                        handler.handleNotFound(new SupplierNotFoundException("X", "not found"), request);

                assertThat(response.getBody().correlationId()).isEqualTo(scope.correlationId());
                assertThat(response.getBody().correlationId()).isEqualTo("scope-value");
            }
        }

        @Test
        @DisplayName("with no ambient scope, a non-blank inbound header is reused")
        void inboundHeaderIsReusedWithNoAmbientScope() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "inbound-trace-id");

            ResponseEntity<ApiError> response =
                    handler.handleNotFound(new SupplierNotFoundException("X", "not found"), request);

            assertThat(response.getBody().correlationId()).isEqualTo("inbound-trace-id");
        }

        @Test
        @DisplayName("with no ambient scope and no request, a fresh id is generated rather than left blank")
        void generatesAFreshIdWithNoScopeAndNoRequest() {
            ResponseEntity<ApiError> first =
                    handler.handleNotFound(new SupplierNotFoundException("X", "not found"), (HttpServletRequest) null);
            ResponseEntity<ApiError> second =
                    handler.handleNotFound(new SupplierNotFoundException("X", "not found"), (HttpServletRequest) null);

            assertThat(first.getBody().correlationId()).isNotBlank();
            assertThat(second.getBody().correlationId()).isNotBlank();
            // Independently generated per call -- nothing carries state between two unrelated requests.
            assertThat(first.getBody().correlationId())
                    .isNotEqualTo(second.getBody().correlationId());
        }

        @Test
        @DisplayName("a blank inbound header counts as absent and a fresh id is generated instead")
        void blankInboundHeaderIsTreatedAsAbsent() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "   ");

            ResponseEntity<ApiError> response =
                    handler.handleNotFound(new SupplierNotFoundException("X", "not found"), request);

            assertThat(response.getBody().correlationId()).isNotBlank();
            assertThat(response.getBody().correlationId()).isNotEqualTo("   ");
        }
    }
}
