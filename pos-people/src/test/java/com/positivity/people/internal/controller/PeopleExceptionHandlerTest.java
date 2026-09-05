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
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Status mapping for the People API's error envelope. Routing and binding failures must not
 * surface as 500s (#820), and no handler may echo user-controlled request data.
 */
@DisplayName("PeopleExceptionHandler")
class PeopleExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    private final PeopleExceptionHandler handler = new PeopleExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static void assertStatusAndTimestamp(ProblemDetail problem, HttpStatus expectedStatus) {
        assertThat(problem.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(problem.getProperties()).containsEntry("timestamp", NOW);
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @Test
    void mapsAMissingPersonToNotFound() {
        MockHttpServletResponse resp = response();
        ProblemDetail problem = handler.handlePersonNotFound(new PersonNotFoundException(PERSON_ID), request(), resp);

        assertStatusAndTimestamp(problem, HttpStatus.NOT_FOUND);
        assertThat(problem.getDetail()).contains(PERSON_ID.toString());
        assertThat(resp.getHeader(X_CORRELATION_ID)).isNotBlank();
    }

    @Test
    void mapsADomainNotFoundToNotFound() {
        assertStatusAndTimestamp(
                handler.handleNotFound(new NotFoundException("no such thing"), request(), response()),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsAMissingWorkSessionToNotFound() {
        assertStatusAndTimestamp(
                handler.handleWorkSessionNotFound(
                        new WorkSessionNotFoundException("no session"), request(), response()),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsAMissingEntityToNotFound() {
        assertStatusAndTimestamp(
                handler.handleEntityNotFound(new EntityNotFoundException("gone"), request(), response()),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsARequestValidationFailureToBadRequestWithCodeAndCorrelationId() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = response();

        ResponseEntity<ApiError> result =
                handler.handleRequestValidation(new RequestValidationException("bad input"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message()).isEqualTo("bad input");
        assertThat(body.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.correlationId()).isNotBlank();
        assertThat(response.getHeader(X_CORRELATION_ID)).isEqualTo(body.correlationId());
    }

    @Test
    void echoesAnInboundCorrelationIdOnARequestValidationFailure() {
        MockHttpServletRequest request = request();
        request.addHeader(X_CORRELATION_ID, "019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
        MockHttpServletResponse response = response();

        ResponseEntity<ApiError> result =
                handler.handleRequestValidation(new RequestValidationException("bad input"), request, response);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
        assertThat(response.getHeader(X_CORRELATION_ID)).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
    }

    /**
     * Pins the wire response for the reclassified lifecycle guards (issue #1694 follow-up):
     * status, code, envelope shape, and correlation id, so a future revert to 400 or to bare
     * {@code IllegalStateException} fails this suite.
     */
    @Test
    void mapsAResourceStateConflictToConflictWithCodeAndCorrelationId() {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = response();

        ResponseEntity<ApiError> result = handler.handleResourceStateConflict(
                new ResourceStateConflictException("Employee is already DISABLED or TERMINATED"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("RESOURCE_STATE_CONFLICT");
        assertThat(body.message()).isEqualTo("Employee is already DISABLED or TERMINATED");
        assertThat(body.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.correlationId()).isNotBlank();
        assertThat(response.getHeader(X_CORRELATION_ID)).isEqualTo(body.correlationId());
    }

    @Test
    void mapsAnIllegalStateToConflict() {
        assertStatusAndTimestamp(
                handler.handleIllegalState(new IllegalStateException("dup"), request(), response()),
                HttpStatus.CONFLICT);
    }

    @Test
    void mapsASemanticValidationFailureToUnprocessableContent() {
        assertStatusAndTimestamp(
                handler.handleSemanticValidation(new SemanticValidationException("bad dates"), request(), response()),
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void mapsAnAccessDenialToForbidden() {
        assertStatusAndTimestamp(
                handler.handleAccessDenied(new AccessDeniedException("nope"), request(), response()),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void mapsAnUnroutablePathToNotFoundWithoutEchoingIt() {
        ProblemDetail problem = handler.handleNoEndpoint(request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.NOT_FOUND);
        assertThat(problem.getDetail()).isEqualTo("No endpoint for the requested path");
    }

    @Test
    void mapsATypeMismatchToBadRequestNamingOnlyTheParameter() throws Exception {
        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "employeeId", methodParameter(), new IllegalArgumentException("nope"));

        ProblemDetail problem = handler.handleTypeMismatch(mismatch, request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Invalid value for parameter 'employeeId'");
        // The offending value is user input and must not be reflected back (S5131).
        assertThat(problem.getDetail()).doesNotContain("not-a-uuid");
    }

    @Test
    void mapsAMissingParameterToBadRequest() {
        ProblemDetail problem = handler.handleMissingParameter(
                new MissingServletRequestParameterException("locationId", "UUID"), request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Missing required parameter 'locationId'");
    }

    @Test
    void mapsABeanValidationFailureToBadRequest() throws Exception {
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                methodParameter(), new BeanPropertyBindingResult(new Object(), "request"));

        ProblemDetail problem = handler.handleValidation(invalid, request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void mapsAConstraintViolationToBadRequest() {
        ConstraintViolationException invalid = new ConstraintViolationException(Set.of());

        ProblemDetail problem = handler.handleConstraintViolation(invalid, request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void mapsMalformedJsonToABadRequestBodyCarryingTheRequestPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/people/employees");
        MockHttpServletResponse response = response();

        ResponseEntity<Map<String, Object>> result = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null),
                request,
                response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody())
                .containsEntry("timestamp", NOW)
                .containsEntry("status", HttpStatus.BAD_REQUEST.value())
                .containsEntry("error", "Bad Request")
                .containsEntry("path", "/v1/people/employees")
                .containsEntry("message", "Malformed JSON request");
        assertThat(result.getBody()).containsKey(CORRELATION_ID_PROPERTY);
        assertThat(response.getHeader(X_CORRELATION_ID))
                .isEqualTo(result.getBody().get(CORRELATION_ID_PROPERTY));
    }

    @Test
    void keepsTheStatusAndReasonOfAResponseStatusException() {
        ProblemDetail problem = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.CONFLICT, "already open"), request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.CONFLICT);
        assertThat(problem.getDetail()).isEqualTo("already open");
    }

    @Test
    void fallsBackToTheExceptionMessageWhenAResponseStatusExceptionCarriesNoReason() {
        ProblemDetail problem = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY), request(), response());

        assertStatusAndTimestamp(problem, HttpStatus.BAD_GATEWAY);
        assertThat(problem.getDetail()).isNotBlank();
    }

    /** Any real method parameter works: the handlers only read the exception's own fields. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(PeopleExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sample(String value) {
        // Signature-only holder for MethodParameter.
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the `problem`/`apiError` helpers
    // put the correlation id in both the body and the header for EVERY @ExceptionHandler
    // method, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            /** Invokes the handler and returns the correlation id carried in the response body. */
            String invoke(MockHttpServletRequest request, MockHttpServletResponse response);
        }

        private static String problemCorrelationId(ProblemDetail problem) {
            Object value = problem.getProperties() == null
                    ? null
                    : problem.getProperties().get(CORRELATION_ID_PROPERTY);
            return value == null ? null : value.toString();
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link PeopleExceptionHandler}. Uses
         * a standalone handler instance (not the outer test's {@code handler}) so this factory
         * method can stay static, as required by {@code @MethodSource} outside a {@code
         * PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
            PeopleExceptionHandler sut = new PeopleExceptionHandler(fixedClock);
            MethodParameter methodParameter = methodParameter();

            return Stream.of(
                    Named.of("handlePersonNotFound", (HandlerInvocation) (request, response) -> problemCorrelationId(
                            sut.handlePersonNotFound(new PersonNotFoundException(PERSON_ID), request, response))),
                    Named.of("handleNotFound", (HandlerInvocation) (request, response) -> problemCorrelationId(
                            sut.handleNotFound(new NotFoundException("no such thing"), request, response))),
                    Named.of("handleWorkSessionNotFound", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleWorkSessionNotFound(
                                    new WorkSessionNotFoundException("no session"), request, response))),
                    Named.of("handleEntityNotFound", (HandlerInvocation) (request, response) -> problemCorrelationId(
                            sut.handleEntityNotFound(new EntityNotFoundException("gone"), request, response))),
                    Named.of("handleRequestValidation", (HandlerInvocation) (request, response) ->
                            sut.handleRequestValidation(new RequestValidationException("bad input"), request, response)
                                    .getBody()
                                    .correlationId()),
                    Named.of("handleResourceStateConflict", (HandlerInvocation)
                            (request, response) -> sut.handleResourceStateConflict(
                                            new ResourceStateConflictException("Employee is already DISABLED"),
                                            request,
                                            response)
                                    .getBody()
                                    .correlationId()),
                    Named.of("handleIllegalState", (HandlerInvocation) (request, response) -> problemCorrelationId(
                            sut.handleIllegalState(new IllegalStateException("dup"), request, response))),
                    Named.of("handleSemanticValidation", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleSemanticValidation(
                                    new SemanticValidationException("bad dates"), request, response))),
                    Named.of("handleAccessDenied", (HandlerInvocation) (request, response) -> problemCorrelationId(
                            sut.handleAccessDenied(new AccessDeniedException("nope"), request, response))),
                    Named.of("handleNoEndpoint", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleNoEndpoint(request, response))),
                    Named.of("handleTypeMismatch", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleTypeMismatch(
                                    new MethodArgumentTypeMismatchException(
                                            "not-a-uuid",
                                            UUID.class,
                                            "employeeId",
                                            methodParameter,
                                            new IllegalArgumentException("nope")),
                                    request,
                                    response))),
                    Named.of("handleMissingParameter", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleMissingParameter(
                                    new MissingServletRequestParameterException("locationId", "UUID"),
                                    request,
                                    response))),
                    Named.of("handleValidation", (HandlerInvocation) (request, response) ->
                            problemCorrelationId(sut.handleValidation(
                                    new MethodArgumentNotValidException(
                                            methodParameter, new BeanPropertyBindingResult(new Object(), "request")),
                                    request,
                                    response))),
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleConstraintViolation(
                                    new ConstraintViolationException(Set.of()), request, response))),
                    Named.of("handleHttpMessageNotReadable", (HandlerInvocation) (request, response) ->
                            String.valueOf(sut.handleHttpMessageNotReadable(
                                            new HttpMessageNotReadableException(
                                                    "boom", (org.springframework.http.HttpInputMessage) null),
                                            request,
                                            response)
                                    .getBody()
                                    .get(CORRELATION_ID_PROPERTY))),
                    Named.of("handleResponseStatusException", (HandlerInvocation)
                            (request, response) -> problemCorrelationId(sut.handleResponseStatusException(
                                    new ResponseStatusException(HttpStatus.CONFLICT, "already open"),
                                    request,
                                    response))));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(X_CORRELATION_ID, "019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
            MockHttpServletResponse response = new MockHttpServletResponse();

            String bodyCorrelationId = invocation.invoke(request, response);

            assertThat(response.getHeader(X_CORRELATION_ID)).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
            assertThat(bodyCorrelationId).isEqualTo("019507b4-1f3a-7000-8e04-5c9d3a4f6e12");
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            String bodyCorrelationId = invocation.invoke(request, response);

            String header = response.getHeader(X_CORRELATION_ID);
            assertThat(header).isNotBlank();
            assertThat(bodyCorrelationId).isEqualTo(header);
        }

        @Test
        @DisplayName("every @ExceptionHandler method on PeopleExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() throws NoSuchMethodException {
            long handlerMethodCount = Arrays.stream(PeopleExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to PeopleExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in PeopleExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
