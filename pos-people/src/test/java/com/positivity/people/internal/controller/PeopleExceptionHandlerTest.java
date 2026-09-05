package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.ResourceStateConflictException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Status mapping for the People API's error envelope. Routing and binding failures must not
 * surface as 500s (#820), and no handler may echo user-controlled request data.
 *
 * <p>Since #1716 every handler answers the same {@link ApiError} envelope, so
 * {@link #assertEnvelope} checks the whole ADR-0017 §3/§4 contract in one place — status, a
 * machine-readable code, the fixed-clock timestamp, and the correlation id present identically
 * in the body and the {@code X-Correlation-Id} header. Before that, thirteen of these handlers
 * answered a bare ProblemDetail with no code and no correlation id at all.
 */
@DisplayName("PeopleExceptionHandler")
class PeopleExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");

    private final PeopleExceptionHandler handler = new PeopleExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    /** The whole ADR-0017 §3/§4 envelope contract, asserted once per handler. */
    private static ApiError assertEnvelope(
            ResponseEntity<ApiError> result, MockHttpServletResponse response, HttpStatus expectedStatus, String code) {
        assertThat(result.getStatusCode()).isEqualTo(expectedStatus);
        ApiError body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(code);
        assertThat(body.status()).isEqualTo(expectedStatus.value());
        assertThat(body.timestamp()).isEqualTo(NOW.toString());
        assertThat(body.correlationId()).isNotBlank();
        // Asserted on the ResponseEntity, not the servlet response: when a ResponseEntity declares a
        // header Spring replaces whatever the servlet response held, so the entity's value is the one
        // that reaches the client. Asserting the servlet write instead would pass even if the header
        // the caller actually receives were dropped (#1716).
        assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(body.correlationId());
        return body;
    }

    @Test
    void mapsAMissingPersonToNotFound() {
        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handlePersonNotFound(new PersonNotFoundException(PERSON_ID), request(), response),
                response,
                HttpStatus.NOT_FOUND,
                "PERSON_NOT_FOUND");

        assertThat(body.message()).contains(PERSON_ID.toString());
    }

    @Test
    void mapsADomainNotFoundToNotFound() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleNotFound(new NotFoundException("no such thing"), request(), response),
                response,
                HttpStatus.NOT_FOUND,
                "NOT_FOUND");
    }

    @Test
    void mapsAMissingWorkSessionToNotFound() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleWorkSessionNotFound(new WorkSessionNotFoundException("no session"), request(), response),
                response,
                HttpStatus.NOT_FOUND,
                "WORK_SESSION_NOT_FOUND");
    }

    @Test
    void mapsAMissingEntityToNotFound() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleEntityNotFound(new EntityNotFoundException("gone"), request(), response),
                response,
                HttpStatus.NOT_FOUND,
                "NOT_FOUND");
    }

    @Test
    void mapsARequestValidationFailureToBadRequestWithCodeAndCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ApiError> result =
                handler.handleRequestValidation(new RequestValidationException("bad input"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message()).isEqualTo("bad input");
        assertThat(body.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.correlationId()).isNotBlank();
        assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(body.correlationId());
    }

    @Test
    void echoesAnInboundCorrelationIdOnARequestValidationFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ApiError> result =
                handler.handleRequestValidation(new RequestValidationException("bad input"), request, response);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
        assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
    }

    /**
     * Pins the wire response for the reclassified lifecycle guards (issue #1694 follow-up):
     * status, code, envelope shape, and correlation id, so a future revert to 400 or to bare
     * {@code IllegalStateException} fails this suite.
     */
    @Test
    void mapsAResourceStateConflictToConflictWithCodeAndCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ApiError> result = handler.handleResourceStateConflict(
                new ResourceStateConflictException("Employee is already DISABLED or TERMINATED"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("RESOURCE_STATE_CONFLICT");
        assertThat(body.message()).isEqualTo("Employee is already DISABLED or TERMINATED");
        assertThat(body.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.correlationId()).isNotBlank();
        assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(body.correlationId());
    }

    @Test
    void mapsAnIllegalStateToConflict() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleIllegalState(new IllegalStateException("dup"), request(), response),
                response,
                HttpStatus.CONFLICT,
                "INVALID_STATE");
    }

    @Test
    void mapsASemanticValidationFailureToUnprocessableContent() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleSemanticValidation(new SemanticValidationException("bad dates"), request(), response),
                response,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "SEMANTIC_VALIDATION_ERROR");
    }

    @Test
    void mapsAnAccessDenialToForbidden() {
        MockHttpServletResponse response = response();
        assertEnvelope(
                handler.handleAccessDenied(new AccessDeniedException("nope"), request(), response),
                response,
                HttpStatus.FORBIDDEN,
                "FORBIDDEN");
    }

    @Test
    void mapsAnUnroutablePathToNotFoundWithoutEchoingIt() {
        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handleNoEndpoint(request(), response), response, HttpStatus.NOT_FOUND, "NO_ENDPOINT");

        assertThat(body.message()).isEqualTo("No endpoint for the requested path");
    }

    @Test
    void mapsATypeMismatchToBadRequestNamingOnlyTheParameter() throws Exception {
        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "employeeId", methodParameter(), new IllegalArgumentException("nope"));

        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handleTypeMismatch(mismatch, request(), response),
                response,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");

        assertThat(body.message()).isEqualTo("Invalid value for parameter 'employeeId'");
        // The offending value is user input and must not be reflected back (S5131).
        assertThat(body.message()).doesNotContain("not-a-uuid");
    }

    @Test
    void mapsAMissingParameterToBadRequest() {
        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handleMissingParameter(
                        new MissingServletRequestParameterException("locationId", "UUID"), request(), response),
                response,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");

        assertThat(body.message()).isEqualTo("Missing required parameter 'locationId'");
    }

    @Test
    void mapsABeanValidationFailureToBadRequest() throws Exception {
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                methodParameter(), new BeanPropertyBindingResult(new Object(), "request"));

        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handleValidation(invalid, request(), response),
                response,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");

        // The binding result is deliberately not exposed: it names this module's internal
        // property names and any caller can provoke this response. #1716 moved the envelope,
        // not that trade, so fieldErrors stays absent.
        assertThat(body.message()).isEqualTo("Validation failed");
        assertThat(body.fieldErrors()).isNull();
    }

    @Test
    void mapsMalformedJsonToABadRequestEnvelopeWithoutEchoingTheRequestPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/people/employees");
        MockHttpServletResponse response = response();

        // Before #1716 this was the one handler answering an ad-hoc Map with error/path keys — a
        // third error shape inside one advice. It is now the same envelope as everything else,
        // and the request path is no longer echoed (S5131, the rule the sibling handlers had).
        ApiError body = assertEnvelope(
                handler.handleHttpMessageNotReadable(
                        new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null),
                        request,
                        response),
                response,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");

        assertThat(body.message()).isEqualTo("Malformed JSON request");
        assertThat(body.toString()).doesNotContain("/v1/people/employees");
    }

    @Test
    void keepsTheStatusAndReasonOfAResponseStatusException() {
        MockHttpServletResponse response = response();

        // The code is derived from the status Spring chose, so a caller still gets something
        // machine-readable for a status this advice never mapped by hand.
        ApiError body = assertEnvelope(
                handler.handleResponseStatusException(
                        new ResponseStatusException(HttpStatus.CONFLICT, "already open"), request(), response),
                response,
                HttpStatus.CONFLICT,
                "CONFLICT");

        assertThat(body.message()).isEqualTo("already open");
    }

    @Test
    void fallsBackToTheExceptionMessageWhenAResponseStatusExceptionCarriesNoReason() {
        MockHttpServletResponse response = response();

        ApiError body = assertEnvelope(
                handler.handleResponseStatusException(
                        new ResponseStatusException(HttpStatus.BAD_GATEWAY), request(), response),
                response,
                HttpStatus.BAD_GATEWAY,
                "BAD_GATEWAY");

        assertThat(body.message()).isNotBlank();
    }

    /** Any real method parameter works: the handlers only read the exception's own fields. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(PeopleExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sample(String value) {
        // Signature-only holder for MethodParameter.
    }
}
