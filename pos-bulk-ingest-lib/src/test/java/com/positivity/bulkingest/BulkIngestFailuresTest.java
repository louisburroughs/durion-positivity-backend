package com.positivity.bulkingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Guards the disclosure rule from issue #1718: a row lost to a server-side fault must never carry
 * the exception's own text into the 200 body that reports it.
 */
class BulkIngestFailuresTest {

    private static final String REJECTION_CODE = "DOMAIN_INGEST_FAILED";
    private static final String FALLBACK = "Record rejected";

    /** A module-owned exception its advice answers as a 4xx. */
    private static class DomainValidationException extends RuntimeException {
        DomainValidationException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    private static class AnnotatedConflictException extends RuntimeException {
        AnnotatedConflictException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    private static class AnnotatedServerException extends RuntimeException {
        AnnotatedServerException(String message) {
            super(message);
        }
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    @DisplayName("isRowRejection")
    class IsRowRejection {

        @Test
        void treatsAModuleDeclaredTypeAsARejection() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new DomainValidationException("vin must be 17 characters"),
                            List.of(DomainValidationException.class)))
                    .isTrue();
        }

        @Test
        void treatsAFourHundredResponseStatusExceptionAsARejection() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new ResponseStatusException(HttpStatus.BAD_REQUEST, "firstName is required"), List.of()))
                    .isTrue();
        }

        @Test
        void treatsAnAnnotatedFourXxTypeAsARejection() {
            assertThat(BulkIngestFailures.isRowRejection(new AnnotatedConflictException("already exists"), List.of()))
                    .isTrue();
        }

        @Test
        void treatsAFiveHundredResponseStatusExceptionAsAServerFault() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "boom"), List.of()))
                    .isFalse();
        }

        @Test
        void treatsAnAnnotatedFiveXxTypeAsAServerFault() {
            assertThat(BulkIngestFailures.isRowRejection(new AnnotatedServerException("boom"), List.of()))
                    .isFalse();
        }

        @Test
        void treatsAnUnclassifiedExceptionAsAServerFault() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new IllegalStateException("could not extract ResultSet"),
                            List.of(DomainValidationException.class)))
                    .isFalse();
        }

        /**
         * The cause chain is deliberately not walked: it is the wrapper's message that would be
         * echoed, and nobody vouched for that one.
         */
        @Test
        void doesNotUnwrapARejectionBuriedInAnotherException() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new IllegalStateException(
                                    "persist failed", new DomainValidationException("vin must be 17 characters")),
                            List.of(DomainValidationException.class)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("rejected")
    class Rejected {

        @Test
        void passesTheDomainMessageThroughUnderTheModulesOwnCode() {
            BulkIngestResult result = BulkIngestFailures.rejected(
                    3, REJECTION_CODE, new DomainValidationException("vin must be 17 characters"), FALLBACK);

            assertThat(result.getRowIndex()).isEqualTo(3);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(REJECTION_CODE);
            assertThat(result.getErrorMessage()).isEqualTo("vin must be 17 characters");
        }

        /**
         * {@code ResponseStatusException.getMessage()} prefixes the status line — reporting it
         * whole would put {@code 400 BAD_REQUEST "…"} in the row result.
         */
        @Test
        void reportsAResponseStatusExceptionsReasonWithoutItsStatusLine() {
            BulkIngestResult result = BulkIngestFailures.rejected(
                    0,
                    REJECTION_CODE,
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "firstName is required"),
                    FALLBACK);

            assertThat(result.getErrorMessage()).isEqualTo("firstName is required");
        }

        @Test
        void fallsBackWhenTheExceptionCarriesNoMessage() {
            BulkIngestResult result =
                    BulkIngestFailures.rejected(0, REJECTION_CODE, new DomainValidationException(null), FALLBACK);

            assertThat(result.getErrorMessage()).isEqualTo(FALLBACK);
        }
    }

    @Nested
    @DisplayName("internalError")
    class InternalError {

        @Test
        void carriesOnlyTheCorrelationId() {
            BulkIngestResult result = BulkIngestFailures.internalError(7, "corr-42");

            assertThat(result.getRowIndex()).isEqualTo(7);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(BulkIngestFailures.INTERNAL_ERROR_CODE);
            assertThat(result.getErrorMessage()).contains("corr-42").doesNotContain("Exception");
        }
    }

    @Nested
    @DisplayName("correlationId")
    class CorrelationId {

        @Test
        void echoesTheInboundHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "  from-caller  ");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            assertThat(BulkIngestFailures.correlationId()).isEqualTo("from-caller");
        }

        @Test
        void generatesOneWhenTheCallerSentNone() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

            assertThat(BulkIngestFailures.correlationId()).isNotBlank();
        }

        @Test
        void generatesOneOffARequestThread() {
            assertThat(BulkIngestFailures.correlationId()).isNotBlank();
        }
    }
}
