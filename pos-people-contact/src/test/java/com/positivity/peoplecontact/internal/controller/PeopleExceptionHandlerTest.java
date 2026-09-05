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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
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
 * ApiError, and simply lies about what happened. The table below is checked
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

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private ResponseEntity<ApiError> dispatch(Exception exception) {
        // Mirrors what @ExceptionHandler resolution does, without standing up an MVC context.
        // Since #1716 every handler answers the same ApiError envelope, so PeopleContactValidation
        // is reachable here too — the shape split that kept it out of this table is gone.
        MockHttpServletRequest request = request();
        return switch (exception) {
            case PersonNotFoundException e -> handler.handlePersonNotFound(e, request);
            case UserPersonLinkNotFoundException e -> handler.handleLinkNotFound(e, request);
            case PersonHasLinkedUsersException e -> handler.handlePersonHasLinkedUsers(e, request);
            case UserAlreadyLinkedException e -> handler.handleUserAlreadyLinked(e, request);
            case NotFoundException e -> handler.handleNotFound(e, request);
            case EntityNotFoundException e -> handler.handleEntityNotFound(e, request);
            case SemanticValidationException e -> handler.handleSemanticValidation(e, request);
            case AccessDeniedException e -> handler.handleAccessDenied(e, request);
            case PeopleContactValidationException e -> handler.handlePeopleContactValidation(e, request);
            case IllegalStateException e -> handler.handleIllegalState(e, request);
            default -> throw new IllegalStateException("Unmapped exception in test table: " + exception);
        };
    }

    @ParameterizedTest(name = "{0} → {2}")
    @FieldSource("mappings")
    @DisplayName("every handled exception maps to its documented status")
    void exceptionsMapToStatuses(String name, Exception exception, HttpStatus expected) {
        ResponseEntity<ApiError> response = dispatch(exception);

        assertThat(response.getStatusCode()).as(name).isEqualTo(expected);
        assertThat(response.getBody()).as(name).isNotNull();
        // #1716, ADR-0017 §3: every response is the ApiError envelope, so every one carries a
        // machine-readable code and the status twice — once in the envelope, once on the wire.
        assertThat(response.getBody().code()).as(name).isNotBlank();
        assertThat(response.getBody().status()).as(name).isEqualTo(expected.value());
        // Every response carries a timestamp from the injected Clock, not from wall time — the
        // reason the handler takes a Clock at all is so this is assertable.
        assertThat(response.getBody().timestamp()).as(name).isEqualTo(NOW.toString());
        // ADR-0017 §4: the correlation id is in the body AND the header, and is the same value.
        // Before #1716 these responses carried no correlation id at all, so a failure here
        // could not be tied to its log entry.
        assertThat(response.getBody().correlationId()).as(name).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .as(name)
                .isEqualTo(response.getBody().correlationId());
    }

    @Test
    @DisplayName("deleting a person who still has linked users is a 409 that says how to proceed")
    void personWithLinkedUsersIsAConflictWithNextAction() {
        ResponseEntity<ApiError> response =
                handler.handlePersonHasLinkedUsers(new PersonHasLinkedUsersException(PERSON_ID), request());

        // 409 rather than 404 or 400: the person exists and the request is well-formed, it is
        // the current state that forbids the delete. The nextAction is the difference between
        // a caller retrying blindly and a caller unlinking first. It was a ProblemDetail
        // extension property before #1716; the envelope has a first-class field for it.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PERSON_HAS_LINKED_USERS");
        assertThat(response.getBody().nextAction()).isEqualTo(PersonHasLinkedUsersException.NEXT_ACTION);
    }

    @Test
    @DisplayName("a semantically invalid but well-formed request is 422, not 400")
    void semanticValidationIsUnprocessable() {
        ResponseEntity<ApiError> response =
                handler.handleSemanticValidation(new SemanticValidationException("start after end"), request());

        // 400 says "I could not parse this"; 422 says "I understood it and it is wrong". A
        // client retrying a 400 changes its syntax, a client retrying a 422 changes its data.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SEMANTIC_VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("start after end");
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
        ResponseEntity<ApiError> response = handler.handleNoEndpoint(request());

        // Deliberate (issue #820 and SonarCloud S5131): without this handler every unknown path
        // fell through to the catch-all as a 500. The message stays constant because reflecting
        // a user-supplied path into the response body is an XSS vector. #1716 dropped the
        // ProblemDetail `instance` field along with the shape, so the path is now only in the
        // access log — an accepted loss, since echoing it was never safe here anyway.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("No endpoint for the requested path");
    }

    @Test
    @DisplayName("malformed JSON answers the same envelope as everything else, and drops the path")
    void malformedJsonIsABadRequestBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/people/persons");

        ResponseEntity<ApiError> response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("boom", null, null), (HttpServletRequest) request);

        // Before #1716 this was the one handler answering an ad-hoc Map with error/path keys — a
        // third error shape inside one advice. It is now the same envelope as every other
        // response, and the request path is no longer echoed (SonarCloud S5131, the same rule
        // the routing handlers already followed).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Malformed JSON request");
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(response.getBody().toString()).doesNotContain("/v1/people/persons");
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
        ResponseEntity<ApiError> response = handler.handleSecurityServiceException(
                new SecurityServiceException("downstream said no", downstream), request());

        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SECURITY_SERVICE_ERROR");
    }

    @Test
    @DisplayName("a ResponseStatusException keeps its status and prefers its reason over its message")
    void responseStatusExceptionKeepsItsStatus() {
        ResponseEntity<ApiError> withReason = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.GONE, "record purged"), request());
        assertThat(withReason.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(withReason.getBody()).isNotNull();
        assertThat(withReason.getBody().message()).isEqualTo("record purged");
        // The code is derived from the status Spring chose, so a caller still gets something
        // machine-readable for a status this advice never mapped by hand.
        assertThat(withReason.getBody().code()).isEqualTo("GONE");

        // With no reason the exception's own message is used, which includes the status text —
        // less useful, but it must not be null.
        ResponseEntity<ApiError> withoutReason =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.GONE), request());
        assertThat(withoutReason.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(withoutReason.getBody()).isNotNull();
        assertThat(withoutReason.getBody().message()).isNotBlank();
    }

    @Test
    @DisplayName("parameter problems name the offending parameter without echoing its value")
    void parameterProblemsNameTheParameterOnly() {
        ResponseEntity<ApiError> missing = handler.handleMissingParameter(
                new MissingServletRequestParameterException("personId", "UUID"), request());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).isNotNull();
        assertThat(missing.getBody().message()).isEqualTo("Missing required parameter 'personId'");

        ResponseEntity<ApiError> mismatch = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException(
                        "<script>alert(1)</script>",
                        UUID.class,
                        "personId",
                        null,
                        new IllegalArgumentException("bad uuid")),
                request());

        // The parameter *name* is safe to echo because it comes from our own method signature.
        // The submitted value is attacker-controlled and is deliberately left out — reflecting
        // it into the response body is the XSS vector SonarCloud S5131 flags.
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody()).isNotNull();
        assertThat(mismatch.getBody().message()).isEqualTo("Invalid value for parameter 'personId'");
        assertThat(mismatch.getBody().message()).doesNotContain("script");
    }

    @Test
    @DisplayName("validation failures report a fixed detail rather than the binding result")
    void validationReportsFixedDetail() throws Exception {
        MethodParameter parameter = new MethodParameter(
                PeopleExceptionHandlerTest.class.getDeclaredMethod("validationTarget", String.class), 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "person");
        binding.reject("person.email.invalid", "email must be a valid address");

        ResponseEntity<ApiError> response =
                handler.handleValidation(new MethodArgumentNotValidException(parameter, binding), request());

        // Whatever the binding failure was, the client is told "Validation failed" and nothing
        // about internal field names or message codes. That is a deliberate trade — it costs
        // the caller detail, and it keeps the module's internal property names out of a
        // response any unauthenticated caller can provoke. #1716 changed the envelope, not this
        // trade: fieldErrors stays empty here, unlike the modules with no such constraint.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
        assertThat(response.getBody().fieldErrors()).isNull();
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
    }

    @SuppressWarnings("unused")
    private void validationTarget(String value) {
        // Exists only to give MethodParameter above something real to point at.
    }
}
