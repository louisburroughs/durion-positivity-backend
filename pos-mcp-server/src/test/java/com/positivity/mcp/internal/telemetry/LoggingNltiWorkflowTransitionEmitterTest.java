package com.positivity.mcp.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.WorkflowState;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingNltiWorkflowTransitionEmitterTest {

    private static final String TELEMETRY_LOGGER = "nlti.telemetry";
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");

    private static NltiWorkflowTransitionTelemetry sampleEvent() {
        return NltiWorkflowTransitionTelemetryFactory.forTransition(
                "00000000-0000-7000-8000-000000000902",
                "2026-08-18T12:00:00Z",
                SESSION_ID,
                "alice",
                WorkflowState.IDLE,
                WorkflowState.PROCESSING_RETURN);
    }

    @Test
    @DisplayName("emit writes one JSON line to the dedicated nlti.telemetry stream")
    void emit_writesJsonLineToTelemetryStream() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        NltiWorkflowTransitionTelemetry event = sampleEvent();
        ListAppender<ILoggingEvent> appender = attachTelemetryAppender();
        try {
            new LoggingNltiWorkflowTransitionEmitter(objectMapper).emit(event);

            assertThat(appender.list).hasSize(1);
            String line = appender.list.getFirst().getFormattedMessage();
            assertThat(line).isEqualTo(objectMapper.writeValueAsString(event));
            assertThat(line)
                    .contains("\"eventType\":\"nlti.workflow.transition\"")
                    .contains("\"schemaVersion\":1")
                    .contains("\"correlationId\":\"00000000-0000-7000-8000-000000000902\"")
                    .contains("\"sessionId\":\"" + SESSION_ID + "\"")
                    .contains("\"timestamp\":\"2026-08-18T12:00:00Z\"")
                    .contains("\"subjectId\":\"alice\"")
                    .contains("\"fromState\":\"IDLE\"")
                    .contains("\"toState\":\"PROCESSING_RETURN\"")
                    .contains("\"changed\":true");
        } finally {
            detachTelemetryAppender(appender);
        }
    }

    @Test
    @DisplayName("a serialization failure is swallowed and never thrown into the request path")
    void emit_serializationFailure_isSwallowed() throws JsonProcessingException {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        ListAppender<ILoggingEvent> appender = attachTelemetryAppender();
        try {
            assertThatCode(() -> new LoggingNltiWorkflowTransitionEmitter(failingMapper).emit(sampleEvent()))
                    .doesNotThrowAnyException();

            // Nothing reaches the telemetry stream: the failure is reported on the class logger instead.
            assertThat(appender.list).isEmpty();
        } finally {
            detachTelemetryAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachTelemetryAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TELEMETRY_LOGGER);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachTelemetryAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TELEMETRY_LOGGER);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
