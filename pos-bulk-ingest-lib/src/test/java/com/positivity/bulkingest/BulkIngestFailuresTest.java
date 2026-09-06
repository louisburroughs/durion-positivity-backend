package com.positivity.bulkingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
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

    /** The positional form, where the status is {@code value} rather than {@code code}. */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class AnnotatedWithValueAliasException extends RuntimeException {
        AnnotatedWithValueAliasException(String message) {
            super(message);
        }
    }

    /** Annotation on a supertype: the shape a module gets by subclassing its own base type. */
    private static class SubclassOfAnnotatedException extends AnnotatedConflictException {
        SubclassOfAnnotatedException(String message) {
            super(message);
        }
    }

    /** The shape {@code InventoryValidationException} takes: a named subclass of a JDK type. */
    private static class SubclassOfDomainValidationException extends DomainValidationException {
        SubclassOfDomainValidationException(String message) {
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

        /**
         * Load-bearing: {@code InventoryValidationException} is listed by four pos-inventory
         * controllers and reaches them as itself, but a module may equally list a base type and
         * throw a subclass. {@code isInstance} is what makes that work.
         */
        @Test
        void matchesASubclassOfAListedType() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new SubclassOfDomainValidationException("vin must be 17 characters"),
                            List.of(DomainValidationException.class)))
                    .isTrue();
        }

        /**
         * {@code @ResponseStatus} is not {@code @Inherited}, so {@code getAnnotation} would miss
         * this; {@code findMergedAnnotation} is what makes an annotated base type work.
         */
        @Test
        void treatsATypeAnnotatedOnItsSupertypeAsARejection() {
            assertThat(BulkIngestFailures.isRowRejection(new SubclassOfAnnotatedException("already exists"), List.of()))
                    .isTrue();
        }

        /**
         * {@code code} is an alias for {@code value}, so the positional form must resolve too — a
         * naive {@code annotation.code()} off an unmerged annotation would read 500 here.
         */
        @Test
        void treatsTheValueAliasAnnotationFormAsARejection() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new AnnotatedWithValueAliasException("no such role"), List.of()))
                    .isTrue();
        }

        /** The module's own list wins outright; the annotation is only consulted after it. */
        @Test
        void aListedTypeIsARejectionEvenWhenItAnnotatesItselfAsAServerFault() {
            assertThat(BulkIngestFailures.isRowRejection(
                            new AnnotatedServerException("refused"), List.of(AnnotatedServerException.class)))
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

        /** A {@code ResponseStatusException} may carry no reason at all; the fallback covers it. */
        @Test
        void fallsBackWhenAResponseStatusExceptionCarriesNoReason() {
            BulkIngestResult result = BulkIngestFailures.rejected(
                    0, REJECTION_CODE, new ResponseStatusException(HttpStatus.BAD_REQUEST), FALLBACK);

            assertThat(result.getErrorMessage()).isEqualTo(FALLBACK);
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
        void carriesTheCorrelationIdAsAFieldAndAFixedMessage() {
            BulkIngestResult result = BulkIngestFailures.internalError(7, "corr-42");

            assertThat(result.getRowIndex()).isEqualTo(7);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(result.getCorrelationId()).isEqualTo("corr-42");
            // Fixed text, so there is no path by which anything about the failure reaches it. The
            // guarantee is structural — internalError cannot be handed a Throwable — and this
            // pins the text so a future edit cannot start interpolating one.
            assertThat(result.getErrorMessage())
                    .isEqualTo("Record could not be ingested because of a server-side error");
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
        void generatesAUuidV7WhenTheCallerSentNone() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

            // ADR-0013/ADR-0027: the generated id is a UUID v7, the same as every other identifier
            // the platform mints. isNotBlank() would survive replacing the generator with a literal.
            assertThat(UUID.fromString(BulkIngestFailures.correlationId()).version())
                    .isEqualTo(7);
        }

        /**
         * One id per request, not per call. Without this a batch arriving with no inbound header
         * would answer a five-hundred-failure file with five hundred unrelated ids, which is the
         * opposite of a diagnostic handle.
         */
        @Test
        void generatesOneIdForTheWholeRequest() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

            assertThat(BulkIngestFailures.correlationId()).isEqualTo(BulkIngestFailures.correlationId());
        }

        @Test
        void generatesAUuidV7OffARequestThread() {
            assertThat(UUID.fromString(BulkIngestFailures.correlationId()).version())
                    .isEqualTo(7);
        }
    }
}
