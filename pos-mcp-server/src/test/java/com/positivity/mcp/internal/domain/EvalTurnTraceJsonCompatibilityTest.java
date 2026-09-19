package com.positivity.mcp.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * #1806: {@code serverBuild} was added to a record that is persisted as a JSON payload. Rows written
 * before the field existed must still read back — as a trace with no build, not as a failed read
 * that would hide every older turn from the gate.
 */
@DisplayName("EvalTurnTrace JSON payload — the serverBuild field is optional on read")
class EvalTurnTraceJsonCompatibilityTest {

    /** Built the way Spring Boot builds the bean the repository receives, not a bare mapper. */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** A pre-#1806 row as persisted: every field the record had then, and nothing else. */
    private static final String LEGACY_PAYLOAD = """
            {"turnId":"01991b8a-0000-7000-8000-000000000001",
             "startedAt":"2026-09-05T23:51:14Z","completedAt":"2026-09-05T23:51:21Z",
             "expiresAt":"2026-09-06T23:51:21Z",
             "userId":"01960010-0000-7000-8000-000000000002","username":"admin.alpha","role":"ROLE_ADMIN",
             "userMessage":"Which technicians had the most reopened work orders this quarter?",
             "simpleChat":false,"intent":"ANALYTICS","modelTier":null,"workflowState":"IDLE",
             "selectedTools":["DateWindowFacadeTool"],"systemPrompt":null,
             "offeredTools":[],"toolCalls":[],"finalResponse":"answer","error":null}
            """;

    @Test
    void aPayloadWrittenBeforeServerBuildExistedReadsBackWithANullBuild() throws Exception {
        EvalTurnTrace read = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.serverBuild()).isNull();
        assertThat(read.answerSource()).isNull();
        assertThat(read.username()).isEqualTo("admin.alpha");
        assertThat(read.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("#2075: a payload written before conversationId/messageId existed reads back with both null")
    void aPayloadWrittenBeforeConversationLinkExistedReadsBackWithNullIds() throws Exception {
        EvalTurnTrace read = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.conversationId()).isNull();
        assertThat(read.messageId()).isNull();
    }

    /** A pre-#1806 row (also pre-#2075): neither serverBuild/answerSource nor the message link exist. */
    private static final String PRE_1806_PAYLOAD = """
            {"turnId":"01991b8a-0000-7000-8000-000000000002",
             "startedAt":"2026-08-01T00:00:00Z","completedAt":"2026-08-01T00:00:07Z",
             "expiresAt":"2026-08-02T00:00:07Z",
             "userId":"01960010-0000-7000-8000-000000000002","username":"admin.alpha","role":"ROLE_ADMIN",
             "userMessage":"How many open work orders?",
             "simpleChat":false,"intent":"ANALYTICS","modelTier":null,"workflowState":"IDLE",
             "selectedTools":[],"systemPrompt":null,
             "offeredTools":[],"toolCalls":[],"finalResponse":"answer","error":null}
            """;

    @Test
    @DisplayName("a pre-#1806 payload (older still) also reads back with every #2075 field null")
    void aPre1806PayloadReadsBackWithEveryNewFieldNull() throws Exception {
        EvalTurnTrace read = mapper.readValue(PRE_1806_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.serverBuild()).isNull();
        assertThat(read.answerSource()).isNull();
        assertThat(read.conversationId()).isNull();
        assertThat(read.messageId()).isNull();
        assertThat(read.finalResponse()).isEqualTo("answer");
    }

    @Test
    void aCurrentPayloadRoundTripsTheBuild() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                "sha-81ff1e0",
                "RE_RENDERED");

        String json = mapper.writeValueAsString(stamped);

        assertThat(json).contains("\"serverBuild\":\"sha-81ff1e0\"");
        assertThat(mapper.readValue(json, EvalTurnTrace.class).serverBuild()).isEqualTo("sha-81ff1e0");
        assertThat(mapper.readValue(json, EvalTurnTrace.class).answerSource()).isEqualTo("RE_RENDERED");
    }

    @Test
    @DisplayName("#2075: a current payload round-trips conversationId and messageId")
    void aCurrentPayloadRoundTripsConversationAndMessageIds() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);
        java.util.UUID conversationId = java.util.UUID.fromString("0199b1be-7080-7000-8000-000000000abc");
        java.util.UUID messageId = java.util.UUID.fromString("0199b1be-7080-7000-8000-000000000def");
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                "sha-81ff1e0",
                "RE_RENDERED",
                conversationId,
                messageId);

        String json = mapper.writeValueAsString(stamped);
        EvalTurnTrace roundTripped = mapper.readValue(json, EvalTurnTrace.class);

        assertThat(json).contains("\"conversationId\"").contains("\"messageId\"");
        assertThat(roundTripped.conversationId()).isEqualTo(conversationId);
        assertThat(roundTripped.messageId()).isEqualTo(messageId);
    }
}
