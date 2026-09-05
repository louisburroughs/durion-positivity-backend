package com.positivity.peoplecontact.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.client.SecurityServiceException;
import com.positivity.peoplecontact.internal.exception.NotFoundException;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.PersonHasLinkedUsersException;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests for {@link PeopleExceptionHandler}, the module's whole error contract.
 *
 * <p>
 * Every failure this service can produce passes through one of these methods, so
 * the exception-to-status table <em>is</em> the API's error surface — a caller
 * branches on 404 versus 409 versus 422 and cannot see anything else. The
 * mapping is also the easiest thing in the module to get subtly wrong: the
 * handlers are near-identical four-line blocks, so a copy-paste that leaves the
 * wrong {@code HttpStatus} behind still compiles, still returns a well-formed
 * ProblemDetail, and simply lies about what happened. The table below is checked
 * exhaustively for that reason.
 *
 * <p>
 * Three distinctions in it carry real meaning and are called out individually
 * below: 409 versus 404 on delete, 422 versus 400 on validation, and the routing
 * and binding handlers' rule of naming the offending parameter but never the
 * submitted value. A fourth — an unmapped exception never echoing its own
 * message — now belongs to {@code pos-web-common}'s platform-wide fallback
 * (issue #1694): this advice deliberately has no {@code Exception.class}
 * catch-all of its own, and {@code PeopleContactValidationException}'s 400
 * contract plus that fallback's 500 are proven end-to-end in {@code
 * PersonAccessControllerErrorHandlingTest}, not here.
 */
@DisplayName("PeopleExceptionHandler — the module's error contract")
class PeopleExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T09:15:00Z");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final PeopleExceptionHandler handler = new PeopleExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * Each case is a supplier of one exception plus the status it must produce.
     * Held as a field so the mapping reads as a table rather than as prose.
     */
    static final List<org.junit.jupiter.params.provider.Arguments> mappings = List.of(
            args("PersonNotFoundException", new PersonNotFoundException(PERSON_ID), HttpStatus.NOT_FOUND),
            args(
                    "UserPersonLinkNotFoundException",
                    new UserPersonLinkNotFoundException("jsmith"),
                    HttpStatus.NOT_FOUND),
            args("NotFoundException", new NotFoundException("no such thing"), HttpStatus.NOT_FOUND),
            args("EntityNotFoundException", new EntityNotFoundException("gone"), HttpStatus.NOT_FOUND),
            args("PersonHasLinkedUsersException", new PersonHasLinkedUsersException(PERSON_ID), HttpStatus.CONFLICT),
            args("UserAlreadyLinkedException", new UserAlreadyLinkedException("jsmith"), HttpStatus.CONFLICT),
            args("IllegalStateException", new IllegalStateException("wrong state"), HttpStatus.CONFLICT),
            args(
                    "SemanticValidationException",
                    new SemanticValidationException("start after end"),
                    HttpStatus.UNPROCESSABLE_CONTENT),
            args("AccessDeniedException", new AccessDeniedException("nope"), HttpStatus.FORBIDDEN));

    private static org.junit.jupiter.params.provider.Arguments args(
            String name, Exception exception, HttpStatus expected) {
        return org.junit.jupiter.params.provider.Arguments.of(name, exception, expected);
    }

    private static MockHttpServletRequest requestWithoutHeader() {
        return new MockHttpServletRequest();
    }

    private ProblemDetail dispatch(Exception exception) {
        // Mirrors what @ExceptionHandler resolution does, without standing up an MVC context.
        // PeopleContactValidationException is deliberately not reachable from here: unlike every
        // exception in this table it answers an ApiError envelope, not a ProblemDetail — it has
        // its own dedicated test below, the same way SecurityServiceException does.
        MockHttpServletRequest request = requestWithoutHeader();
        ResponseEntity<ProblemDetail> response =
                switch (exception) {
                    case PersonNotFoundException e -> handler.handlePersonNotFound(e, request);
                    case UserPersonLinkNotFoundException e -> handler.handleLinkNotFound(e, request);
                    case PersonHasLinkedUsersException e -> handler.handlePersonHasLinkedUsers(e, request);
                    case UserAlreadyLinkedException e -> handler.handleUserAlreadyLinked(e, request);
                    case NotFoundException e -> handler.handleNotFound(e, request);
                    case EntityNotFoundException e -> handler.handleEntityNotFound(e, request);
                    case SemanticValidationException e -> handler.handleSemanticValidation(e, request);
                    case AccessDeniedException e -> handler.handleAccessDenied(e, request);
                    case IllegalStateException e -> handler.handleIllegalState(e, request);
                    default -> throw new IllegalStateException("Unmapped exception in test table: " + exception);
                };
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @ParameterizedTest(name = "{0} → {2}")
    @FieldSource("mappings")
    @DisplayName("every handled exception maps to its documented status")
    void exceptionsMapToStatuses(String name, Exception exception, HttpStatus expected) {
        ProblemDetail problem = dispatch(exception);

        assertThat(problem.getStatus()).as(name).isEqualTo(expected.value());
        // Every response carries a timestamp from the injected Clock, not from wall time — the
        // reason the handler takes a Clock at all is so this is assertable.
        assertThat(problem.getProperties()).containsEntry("timestamp", NOW);
    }

    @Test
    @DisplayName("deleting a person who still has linked users is a 409 that says how to proceed")
    void personWithLinkedUsersIsAConflictWithNextAction() {
        ResponseEntity<ProblemDetail> response = handler.handlePersonHasLinkedUsers(
                new PersonHasLinkedUsersException(PERSON_ID), requestWithoutHeader());
        ProblemDetail problem = response.getBody();

        // 409 rather than 404 or 400: the person exists and the request is well-formed, it is
        // the current state that forbids the delete. The nextAction is the difference between
        // a caller retrying blindly and a caller unlinking first.
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("nextAction", PersonHasLinkedUsersException.NEXT_ACTION);
    }

    @Test
    @DisplayName("a semantically invalid but well-formed request is 422, not 400")
    void semanticValidationIsUnprocessable() {
        ResponseEntity<ProblemDetail> response = handler.handleSemanticValidation(
                new SemanticValidationException("start after end"), requestWithoutHeader());
        ProblemDetail problem = response.getBody();

        // 400 says "I could not parse this"; 422 says "I understood it and it is wrong". A
        // client retrying a 400 changes its syntax, a client retrying a 422 changes its data.
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getDetail()).isEqualTo("start after end");
    }

    @Test
    @DisplayName("a genuine client-validation failure is 400 VALIDATION_ERROR, echoing its own message")
    void peopleContactValidationIsABadRequestCarryingItsOwnMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "  ");

        ResponseEntity<ApiError> response = handler.handlePeopleContactValidation(
                new PeopleContactValidationException("roleCode is required"), request);

        // Unlike an unmapped exception, this one is this module's own, well-formed contract
        // failure: the caller gets its exact message back, not a generic one.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("roleCode is required");
        // A blank inbound header is treated as absent: a generated correlation id, not blank,
        // and it must appear identically in both the body and the response header (ADR-0017 §4).
        assertThat(response.getBody().correlationId()).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(response.getBody().correlationId());
    }

    @Test
    @DisplayName("an inbound correlation id is echoed rather than replaced")
    void peopleContactValidationEchoesInboundCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "01234567-89ab-7000-8000-0123456789ab");

        ResponseEntity<ApiError> response =
                handler.handlePeopleContactValidation(new PeopleContactValidationException("bad input"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("01234567-89ab-7000-8000-0123456789ab");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("01234567-89ab-7000-8000-0123456789ab");
    }

    @Test
    @DisplayName("an unknown path is a 404 that does not echo the path back")
    void unknownEndpointIsNotFoundWithoutEchoingInput() {
        ResponseEntity<ProblemDetail> response = handler.handleNoEndpoint(requestWithoutHeader());
        ProblemDetail problem = response.getBody();

        // Deliberate (issue #820 and SonarCloud S5131): without this handler every unknown path
        // fell through to the catch-all as a 500. The detail stays constant because reflecting
        // a user-supplied path into the response body is an XSS vector; the path is already in
        // ProblemDetail's `instance` field.
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getDetail()).isEqualTo("No endpoint for the requested path");
    }

    @Test
    @DisplayName("malformed JSON answers a body-shaped error naming the request path")
    void malformedJsonIsABadRequestBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/people/persons");

        ResponseEntity<Map<String, Object>> response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("boom", null, null), (HttpServletRequest) request);

        // This is the one handler that answers with a plain map instead of a ProblemDetail, so
        // its shape is pinned separately — a client parsing it sees different field names.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("message", "Malformed JSON request")
                .containsEntry("path", "/v1/people/persons")
                .containsEntry("status", HttpStatus.BAD_REQUEST.value())
                .containsEntry("timestamp", NOW);
    }

    @ParameterizedTest(name = "security-service {0} → {1}")
    @CsvSource({
        "400, 400",
        "401, 401",
        "403, 403",
        "404, 404",
        "409, 409",
        "503, 503",
        // Unlisted 4xx collapses to 400, unlisted 5xx to 500 — a downstream status we do not
        // recognise must never be passed through as-is, or pos-security-service's internals
        // become this API's contract.
        "418, 400",
        "451, 400",
        "502, 500",
        "504, 500",
        // Anything outside 4xx/5xx is treated as a server fault rather than trusted.
        "200, 500",
        "302, 500"
    })
    @DisplayName("a failure from pos-security-service is translated, never passed through blindly")
    void securityServiceStatusesAreTranslated(int downstream, int expected) {
        ResponseEntity<ProblemDetail> response = handler.handleSecurityServiceException(
                new SecurityServiceException("downstream said no", downstream), requestWithoutHeader());
        ProblemDetail problem = response.getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a ResponseStatusException keeps its status and prefers its reason over its message")
    void responseStatusExceptionKeepsItsStatus() {
        ProblemDetail withReason = handler.handleResponseStatusException(
                        new ResponseStatusException(HttpStatus.GONE, "record purged"), requestWithoutHeader())
                .getBody();
        assertThat(withReason).isNotNull();
        assertThat(withReason.getStatus()).isEqualTo(HttpStatus.GONE.value());
        assertThat(withReason.getDetail()).isEqualTo("record purged");

        // With no reason the exception's own message is used, which includes the status text —
        // less useful, but it must not be null.
        ProblemDetail withoutReason = handler.handleResponseStatusException(
                        new ResponseStatusException(HttpStatus.GONE), requestWithoutHeader())
                .getBody();
        assertThat(withoutReason).isNotNull();
        assertThat(withoutReason.getStatus()).isEqualTo(HttpStatus.GONE.value());
        assertThat(withoutReason.getDetail()).isNotBlank();
    }

    @Test
    @DisplayName("parameter problems name the offending parameter without echoing its value")
    void parameterProblemsNameTheParameterOnly() {
        ProblemDetail missing = handler.handleMissingParameter(
                        new MissingServletRequestParameterException("personId", "UUID"), requestWithoutHeader())
                .getBody();

        assertThat(missing).isNotNull();
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(missing.getDetail()).isEqualTo("Missing required parameter 'personId'");

        ProblemDetail mismatch = handler.handleTypeMismatch(
                        new MethodArgumentTypeMismatchException(
                                "<script>alert(1)</script>",
                                UUID.class,
                                "personId",
                                null,
                                new IllegalArgumentException("bad uuid")),
                        requestWithoutHeader())
                .getBody();

        // The parameter *name* is safe to echo because it comes from our own method signature.
        // The submitted value is attacker-controlled and is deliberately left out — reflecting
        // it into the response body is the XSS vector SonarCloud S5131 flags.
        assertThat(mismatch).isNotNull();
        assertThat(mismatch.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(mismatch.getDetail()).isEqualTo("Invalid value for parameter 'personId'");
        assertThat(mismatch.getDetail()).doesNotContain("script");
    }

    @Test
    @DisplayName("validation failures report a fixed detail rather than the binding result")
    void validationReportsFixedDetail() throws Exception {
        MethodParameter parameter = new MethodParameter(
                PeopleExceptionHandlerTest.class.getDeclaredMethod("validationTarget", String.class), 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "person");
        binding.reject("person.email.invalid", "email must be a valid address");

        ProblemDetail problem = handler.handleValidation(
                        new MethodArgumentNotValidException(parameter, binding), requestWithoutHeader())
                .getBody();

        // Whatever the binding failure was, the client is told "Validation failed" and nothing
        // about internal field names or message codes. That is a deliberate trade — it costs
        // the caller detail, and it keeps the module's internal property names out of a
        // response any unauthenticated caller can provoke.
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties()).containsEntry("timestamp", NOW);
    }

    @SuppressWarnings("unused")
    private void validationTarget(String value) {
        // Exists only to give MethodParameter above something real to point at.
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the shared `respond`/
    // `respondProblem` helpers put the correlation id in the response header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        private static final String FIXED_CORRELATION_ID = "01234567-89ab-7000-8000-0123456789ab";

        /** Header value plus, when the body exposes a correlation id property, that value too. */
        private record HandlerResult(String header, String bodyCorrelationId) {}

        @FunctionalInterface
        interface HandlerInvocation {
            HandlerResult invoke(HttpServletRequest request);
        }

        private static HandlerResult asResult(ResponseEntity<?> response) {
            String bodyCorrelationId =
                    response.getBody() instanceof ProblemDetail problem && problem.getProperties() != null
                            ? String.valueOf(problem.getProperties().get("correlationId"))
                            : null;
            return new HandlerResult(response.getHeaders().getFirst("X-Correlation-Id"), bodyCorrelationId);
        }

        private static MethodArgumentTypeMismatchException typeMismatchException() {
            return new MethodArgumentTypeMismatchException(
                    "<script>alert(1)</script>",
                    UUID.class,
                    "personId",
                    null,
                    new IllegalArgumentException("bad uuid"));
        }

        private static MethodArgumentNotValidException validationException() throws NoSuchMethodException {
            MethodParameter parameter = new MethodParameter(
                    PeopleExceptionHandlerTest.class.getDeclaredMethod("validationTarget", String.class), 0);
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "person");
            binding.reject("person.email.invalid", "email must be a valid address");
            return new MethodArgumentNotValidException(parameter, binding);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link PeopleExceptionHandler}. Uses
         * a standalone handler instance (not the outer test's {@code handler}) so this factory
         * method can stay static, as required by {@code @MethodSource} outside a
         * {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            PeopleExceptionHandler h = new PeopleExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));
            MethodArgumentNotValidException validationEx = validationException();

            return Stream.of(
                    Named.of("handlePersonNotFound", (HandlerInvocation) request ->
                            asResult(h.handlePersonNotFound(new PersonNotFoundException(PERSON_ID), request))),
                    Named.of("handleLinkNotFound", (HandlerInvocation) request ->
                            asResult(h.handleLinkNotFound(new UserPersonLinkNotFoundException("jsmith"), request))),
                    Named.of("handlePersonHasLinkedUsers", (HandlerInvocation) request -> asResult(
                            h.handlePersonHasLinkedUsers(new PersonHasLinkedUsersException(PERSON_ID), request))),
                    Named.of("handleUserAlreadyLinked", (HandlerInvocation) request ->
                            asResult(h.handleUserAlreadyLinked(new UserAlreadyLinkedException("jsmith"), request))),
                    Named.of("handleNotFound", (HandlerInvocation)
                            request -> asResult(h.handleNotFound(new NotFoundException("no such thing"), request))),
                    Named.of("handleEntityNotFound", (HandlerInvocation)
                            request -> asResult(h.handleEntityNotFound(new EntityNotFoundException("gone"), request))),
                    Named.of("handlePeopleContactValidation", (HandlerInvocation) request -> {
                        ResponseEntity<ApiError> response = h.handlePeopleContactValidation(
                                new PeopleContactValidationException("bad input"), request);
                        return new HandlerResult(
                                response.getHeaders().getFirst("X-Correlation-Id"),
                                response.getBody() == null
                                        ? null
                                        : response.getBody().correlationId());
                    }),
                    Named.of("handleIllegalState", (HandlerInvocation) request ->
                            asResult(h.handleIllegalState(new IllegalStateException("wrong state"), request))),
                    Named.of("handleSemanticValidation", (HandlerInvocation) request -> asResult(
                            h.handleSemanticValidation(new SemanticValidationException("start after end"), request))),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> asResult(h.handleAccessDenied(new AccessDeniedException("nope"), request))),
                    Named.of("handleNoEndpoint", (HandlerInvocation) request -> asResult(h.handleNoEndpoint(request))),
                    Named.of("handleTypeMismatch", (HandlerInvocation)
                            request -> asResult(h.handleTypeMismatch(typeMismatchException(), request))),
                    Named.of("handleMissingParameter", (HandlerInvocation) request -> asResult(h.handleMissingParameter(
                            new MissingServletRequestParameterException("personId", "UUID"), request))),
                    Named.of("handleValidation", (HandlerInvocation)
                            request -> asResult(h.handleValidation(validationEx, request))),
                    Named.of("handleHttpMessageNotReadable", (HandlerInvocation) request -> {
                        ResponseEntity<Map<String, Object>> response = h.handleHttpMessageNotReadable(
                                new HttpMessageNotReadableException("boom", null, null), request);
                        return new HandlerResult(response.getHeaders().getFirst("X-Correlation-Id"), null);
                    }),
                    Named.of("handleSecurityServiceException", (HandlerInvocation)
                            request -> asResult(h.handleSecurityServiceException(
                                    new SecurityServiceException("downstream said no", 400), request))),
                    Named.of("handleResponseStatusException", (HandlerInvocation)
                            request -> asResult(h.handleResponseStatusException(
                                    new ResponseStatusException(HttpStatus.GONE, "record purged"), request))));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in the header (and body, where exposed)")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", FIXED_CORRELATION_ID);

            HandlerResult result = invocation.invoke(request);

            assertThat(result.header()).isEqualTo(FIXED_CORRELATION_ID);
            if (result.bodyCorrelationId() != null) {
                assertThat(result.bodyCorrelationId()).isEqualTo(FIXED_CORRELATION_ID);
            }
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent with the body where exposed, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            HandlerResult result = invocation.invoke(new MockHttpServletRequest());

            assertThat(result.header()).isNotBlank();
            if (result.bodyCorrelationId() != null) {
                assertThat(result.bodyCorrelationId()).isEqualTo(result.header());
            }
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
