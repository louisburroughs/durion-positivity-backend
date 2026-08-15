package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Status mapping for the People API's error envelope. Routing and binding failures must not
 * surface as 500s (#820), and no handler may echo user-controlled request data.
 */
@DisplayName("PeopleExceptionHandler")
class PeopleExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");

    private final PeopleExceptionHandler handler = new PeopleExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static void assertStatusAndTimestamp(ProblemDetail problem, HttpStatus expectedStatus) {
        assertThat(problem.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(problem.getProperties()).containsEntry("timestamp", NOW);
    }

    @Test
    void mapsAMissingPersonToNotFound() {
        ProblemDetail problem = handler.handlePersonNotFound(new PersonNotFoundException(PERSON_ID));

        assertStatusAndTimestamp(problem, HttpStatus.NOT_FOUND);
        assertThat(problem.getDetail()).contains(PERSON_ID.toString());
    }

    @Test
    void mapsADomainNotFoundToNotFound() {
        assertStatusAndTimestamp(handler.handleNotFound(new NotFoundException("no such thing")), HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsAMissingWorkSessionToNotFound() {
        assertStatusAndTimestamp(
                handler.handleWorkSessionNotFound(new WorkSessionNotFoundException("no session")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsAMissingEntityToNotFound() {
        assertStatusAndTimestamp(
                handler.handleEntityNotFound(new EntityNotFoundException("gone")), HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsAnIllegalArgumentToBadRequest() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("bad input");
    }

    @Test
    void mapsAnIllegalStateToConflict() {
        assertStatusAndTimestamp(handler.handleIllegalState(new IllegalStateException("dup")), HttpStatus.CONFLICT);
    }

    @Test
    void mapsASemanticValidationFailureToUnprocessableContent() {
        assertStatusAndTimestamp(
                handler.handleSemanticValidation(new SemanticValidationException("bad dates")),
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void mapsAnAccessDenialToForbidden() {
        assertStatusAndTimestamp(handler.handleAccessDenied(new AccessDeniedException("nope")), HttpStatus.FORBIDDEN);
    }

    @Test
    void mapsAnUnroutablePathToNotFoundWithoutEchoingIt() {
        ProblemDetail problem = handler.handleNoEndpoint();

        assertStatusAndTimestamp(problem, HttpStatus.NOT_FOUND);
        assertThat(problem.getDetail()).isEqualTo("No endpoint for the requested path");
    }

    @Test
    void mapsATypeMismatchToBadRequestNamingOnlyTheParameter() throws Exception {
        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "employeeId", methodParameter(), new IllegalArgumentException("nope"));

        ProblemDetail problem = handler.handleTypeMismatch(mismatch);

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Invalid value for parameter 'employeeId'");
        // The offending value is user input and must not be reflected back (S5131).
        assertThat(problem.getDetail()).doesNotContain("not-a-uuid");
    }

    @Test
    void mapsAMissingParameterToBadRequest() {
        ProblemDetail problem =
                handler.handleMissingParameter(new MissingServletRequestParameterException("locationId", "UUID"));

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Missing required parameter 'locationId'");
    }

    @Test
    void mapsABeanValidationFailureToBadRequest() throws Exception {
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                methodParameter(), new BeanPropertyBindingResult(new Object(), "request"));

        ProblemDetail problem = handler.handleValidation(invalid);

        assertStatusAndTimestamp(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
    }

    @Test
    void mapsMalformedJsonToABadRequestBodyCarryingTheRequestPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/people/employees");

        ResponseEntity<Map<String, Object>> response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("timestamp", NOW)
                .containsEntry("status", HttpStatus.BAD_REQUEST.value())
                .containsEntry("error", "Bad Request")
                .containsEntry("path", "/v1/people/employees")
                .containsEntry("message", "Malformed JSON request");
    }

    @Test
    void keepsTheStatusAndReasonOfAResponseStatusException() {
        ProblemDetail problem =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.CONFLICT, "already open"));

        assertStatusAndTimestamp(problem, HttpStatus.CONFLICT);
        assertThat(problem.getDetail()).isEqualTo("already open");
    }

    @Test
    void fallsBackToTheExceptionMessageWhenAResponseStatusExceptionCarriesNoReason() {
        ProblemDetail problem =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

        assertStatusAndTimestamp(problem, HttpStatus.BAD_GATEWAY);
        assertThat(problem.getDetail()).isNotBlank();
    }

    @Test
    void hidesTheDetailOfAnUnexpectedFailure() {
        ProblemDetail problem = handler.handleUnexpectedException(new RuntimeException("connection string leaked"));

        assertStatusAndTimestamp(problem, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getDetail()).isEqualTo("Internal server error");
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
