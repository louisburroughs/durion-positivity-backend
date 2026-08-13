package com.positivity.peoplecontact.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.client.SecurityServiceException;
import com.positivity.peoplecontact.internal.exception.NotFoundException;
import com.positivity.peoplecontact.internal.exception.PersonHasLinkedUsersException;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.FieldSource;
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
 * below: 409 versus 404 on delete, 422 versus 400 on validation, and the
 * catch-all's refusal to echo the underlying message.
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
            args("IllegalArgumentException", new IllegalArgumentException("bad input"), HttpStatus.BAD_REQUEST),
            args(
                    "SemanticValidationException",
                    new SemanticValidationException("start after end"),
                    HttpStatus.UNPROCESSABLE_CONTENT),
            args("AccessDeniedException", new AccessDeniedException("nope"), HttpStatus.FORBIDDEN));

    private static org.junit.jupiter.params.provider.Arguments args(
            String name, Exception exception, HttpStatus expected) {
        return org.junit.jupiter.params.provider.Arguments.of(name, exception, expected);
    }

    private ProblemDetail dispatch(Exception exception) {
        // Mirrors what @ExceptionHandler resolution does, without standing up an MVC context.
        return switch (exception) {
            case PersonNotFoundException e -> handler.handlePersonNotFound(e);
            case UserPersonLinkNotFoundException e -> handler.handleLinkNotFound(e);
            case PersonHasLinkedUsersException e -> handler.handlePersonHasLinkedUsers(e);
            case UserAlreadyLinkedException e -> handler.handleUserAlreadyLinked(e);
            case NotFoundException e -> handler.handleNotFound(e);
            case EntityNotFoundException e -> handler.handleEntityNotFound(e);
            case SemanticValidationException e -> handler.handleSemanticValidation(e);
            case AccessDeniedException e -> handler.handleAccessDenied(e);
            case IllegalStateException e -> handler.handleIllegalState(e);
            case IllegalArgumentException e -> handler.handleIllegalArgument(e);
            default -> handler.handleUnexpectedException(exception);
        };
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
        ProblemDetail problem = handler.handlePersonHasLinkedUsers(new PersonHasLinkedUsersException(PERSON_ID));

        // 409 rather than 404 or 400: the person exists and the request is well-formed, it is
        // the current state that forbids the delete. The nextAction is the difference between
        // a caller retrying blindly and a caller unlinking first.
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("nextAction", PersonHasLinkedUsersException.NEXT_ACTION);
    }

    @Test
    @DisplayName("a semantically invalid but well-formed request is 422, not 400")
    void semanticValidationIsUnprocessable() {
        ProblemDetail problem = handler.handleSemanticValidation(new SemanticValidationException("start after end"));

        // 400 says "I could not parse this"; 422 says "I understood it and it is wrong". A
        // client retrying a 400 changes its syntax, a client retrying a 422 changes its data.
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getDetail()).isEqualTo("start after end");
    }

    @Test
    @DisplayName("the catch-all reports a generic message rather than the exception's own")
    void unexpectedExceptionDoesNotLeakItsMessage() {
        ProblemDetail problem =
                handler.handleUnexpectedException(new RuntimeException("jdbc://user:hunter2@db/people failed"));

        // An unhandled exception's message is written by code that never expected a client to
        // read it, so it routinely contains connection strings, SQL, or internal identifiers.
        // It goes to the log; the client gets a fixed string.
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("Internal server error");
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("an unknown path is a 404 that does not echo the path back")
    void unknownEndpointIsNotFoundWithoutEchoingInput() {
        ProblemDetail problem = handler.handleNoEndpoint();

        // Deliberate (issue #820 and SonarCloud S5131): without this handler every unknown path
        // fell through to the catch-all as a 500. The detail stays constant because reflecting
        // a user-supplied path into the response body is an XSS vector; the path is already in
        // ProblemDetail's `instance` field.
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
        ProblemDetail problem =
                handler.handleSecurityServiceException(new SecurityServiceException("downstream said no", downstream));

        assertThat(problem.getStatus()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a ResponseStatusException keeps its status and prefers its reason over its message")
    void responseStatusExceptionKeepsItsStatus() {
        ProblemDetail withReason =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.GONE, "record purged"));
        assertThat(withReason.getStatus()).isEqualTo(HttpStatus.GONE.value());
        assertThat(withReason.getDetail()).isEqualTo("record purged");

        // With no reason the exception's own message is used, which includes the status text —
        // less useful, but it must not be null.
        ProblemDetail withoutReason =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.GONE));
        assertThat(withoutReason.getStatus()).isEqualTo(HttpStatus.GONE.value());
        assertThat(withoutReason.getDetail()).isNotBlank();
    }

    @Test
    @DisplayName("parameter problems name the offending parameter without echoing its value")
    void parameterProblemsNameTheParameterOnly() {
        ProblemDetail missing =
                handler.handleMissingParameter(new MissingServletRequestParameterException("personId", "UUID"));

        assertThat(missing.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(missing.getDetail()).isEqualTo("Missing required parameter 'personId'");

        ProblemDetail mismatch = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                "<script>alert(1)</script>", UUID.class, "personId", null, new IllegalArgumentException("bad uuid")));

        // The parameter *name* is safe to echo because it comes from our own method signature.
        // The submitted value is attacker-controlled and is deliberately left out — reflecting
        // it into the response body is the XSS vector SonarCloud S5131 flags.
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

        ProblemDetail problem = handler.handleValidation(new MethodArgumentNotValidException(parameter, binding));

        // Whatever the binding failure was, the client is told "Validation failed" and nothing
        // about internal field names or message codes. That is a deliberate trade — it costs
        // the caller detail, and it keeps the module's internal property names out of a
        // response any unauthenticated caller can provoke.
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties()).containsEntry("timestamp", NOW);
    }

    @SuppressWarnings("unused")
    private void validationTarget(String value) {
        // Exists only to give MethodParameter above something real to point at.
    }
}
