package com.positivity.bulkingest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Guards {@link AbstractBulkIngestController#rowFailure} directly.
 *
 * <p>Every bulk-ingest endpoint on the platform funnels its per-record catch through that one
 * method, but it was previously exercised only through the twelve modules that call it — so the
 * library could not be validated without a full reactor build, and the half of issue #1718's rule
 * that lives in the log (the exception goes to ERROR with its stack trace, and only there) was
 * asserted nowhere at all.
 */
class AbstractBulkIngestControllerTest {

    private static final String LEAKED = "could not extract ResultSet; SQL [select * from vehicle_record]";

    /** A module-owned exception its advice would answer as a 4xx. */
    private static class DomainRejectionException extends RuntimeException {
        DomainRejectionException(String message) {
            super(message);
        }
    }

    /** A controller that classifies nothing — the base-class default. */
    private static class UnclassifiedController extends AbstractBulkIngestController<Object> {
        @Override
        protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<Object> request) {
            throw new UnsupportedOperationException("not exercised");
        }

        BulkIngestResult failure(int rowIndex, Exception exception) {
            return rowFailure(rowIndex, exception);
        }
    }

    /** A controller that names its rejection type, as a converted module does. */
    private static class ClassifiedController extends UnclassifiedController {
        @Override
        protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
            return List.of(DomainRejectionException.class);
        }

        @Override
        protected String rowRejectionCode() {
            return "DOMAIN_INGEST_FAILED";
        }

        @Override
        protected String rowRejectionFallbackMessage() {
            return "Domain ingest failed";
        }

        BulkIngestResult rejection(int rowIndex, String errorCode, Exception exception) {
            return rowRejection(rowIndex, errorCode, exception);
        }
    }

    private ListAppender<ILoggingEvent> appender;
    private Logger classifiedLogger;
    private Logger unclassifiedLogger;

    @BeforeEach
    void captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        classifiedLogger = (Logger) LoggerFactory.getLogger(ClassifiedController.class);
        unclassifiedLogger = (Logger) LoggerFactory.getLogger(UnclassifiedController.class);
        classifiedLogger.addAppender(appender);
        unclassifiedLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        classifiedLogger.detachAppender(appender);
        unclassifiedLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("a classified rejection")
    class Rejection {

        @Test
        void keepsItsMessageUnderTheModulesOwnCode() {
            BulkIngestResult result =
                    new ClassifiedController().failure(2, new DomainRejectionException("vin must be 17 characters"));

            assertThat(result.getErrorCode()).isEqualTo("DOMAIN_INGEST_FAILED");
            assertThat(result.getErrorMessage()).isEqualTo("vin must be 17 characters");
            assertThat(result.getCorrelationId()).isNull();
        }

        /**
         * WARN, not ERROR: a row the service refused is the caller's data problem, and a large file
         * of them must not read as a server outage (ADR-0046 keeps ERROR actionable).
         */
        @Test
        void logsAtWarnWithoutAStackTrace() {
            new ClassifiedController().failure(2, new DomainRejectionException("vin must be 17 characters"));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    @Nested
    @DisplayName("an unclassified failure")
    class ServerFault {

        @Test
        void reportsGenericallyAndNeverEchoesTheException() {
            BulkIngestResult result = new ClassifiedController().failure(4, new IllegalStateException(LEAKED));

            assertThat(result.getErrorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(result.getErrorMessage()).doesNotContain("ResultSet").doesNotContain("vehicle_record");
            assertThat(result.getCorrelationId()).isNotBlank();
        }

        /**
         * The other half of ADR-0056: the detail withheld from the caller has to be somewhere, and
         * that somewhere is an ERROR entry carrying the throwable and the id the caller was given.
         */
        @Test
        void logsAtErrorWithTheThrowableAndTheSameCorrelationId() {
            BulkIngestResult result = new ClassifiedController().failure(4, new IllegalStateException(LEAKED));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getFormattedMessage()).contains(result.getCorrelationId());
        }

        /**
         * The safe default: a module that has named nothing reports everything generically rather
         * than guessing. An incomplete list costs detail; an over-broad one leaks.
         */
        @Test
        void isTheDefaultWhenAModuleHasNamedNoRejectionTypes() {
            BulkIngestResult result =
                    new UnclassifiedController().failure(0, new DomainRejectionException("vin must be 17 characters"));

            assertThat(result.getErrorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(result.getErrorMessage()).doesNotContain("vin must be 17 characters");
        }
    }

    @Nested
    @DisplayName("rowRejection")
    class ControllerClassifiedRejection {

        /** The supported way for a controller to report a second kind of refusal under its own code. */
        @Test
        void reportsUnderTheCallersCodeAndKeepsTheMessage() {
            BulkIngestResult result = new ClassifiedController()
                    .rejection(1, "PARENT_UNRESOLVED", new IllegalStateException("Parent 'Aisle 1' does not exist"));

            assertThat(result.getErrorCode()).isEqualTo("PARENT_UNRESOLVED");
            assertThat(result.getErrorMessage()).isEqualTo("Parent 'Aisle 1' does not exist");
            assertThat(appender.list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        }
    }
}
