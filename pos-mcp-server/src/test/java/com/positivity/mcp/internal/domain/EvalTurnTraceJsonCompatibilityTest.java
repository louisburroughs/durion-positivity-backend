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
}
