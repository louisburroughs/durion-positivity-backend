package com.positivity.mcp.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1806: {@code serverBuild} was added to a record that is persisted as a JSON payload. Rows written
 * before the field existed must still read back — as a trace with no build, not as a failed read
 * that would hide every older turn from the gate.
 */
@DisplayName("EvalTurnTrace JSON payload — the serverBuild field is optional on read")
class EvalTurnTraceJsonCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void aPayloadWrittenBeforeServerBuildExistedReadsBackWithANullBuild() throws Exception {
        Instant now = Instant.parse("2026-09-05T23:51:14Z");
        EvalTurnTrace current = new EvalTurnTrace(
                UUID.fromString("01991b8a-0000-7000-8000-000000000001"),
                now,
                now,
                now.plusSeconds(3600),
                UUID.fromString("01960010-0000-7000-8000-000000000002"),
                "admin.alpha",
                "ROLE_ADMIN",
                "Which technicians had the most reopened work orders this quarter?",
                false,
                "ANALYTICS",
                null,
                "IDLE",
                List.of("DateWindowFacadeTool"),
                null,
                List.of(),
                List.of(),
                "answer",
                null,
                "sha-81ff1e0");

        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(current));
        legacy.remove("serverBuild");

        EvalTurnTrace read = mapper.readValue(mapper.writeValueAsString(legacy), EvalTurnTrace.class);

        assertThat(read.serverBuild()).isNull();
        assertThat(read.userMessage()).isEqualTo(current.userMessage());
        assertThat(mapper.readValue(mapper.writeValueAsString(current), EvalTurnTrace.class)
                        .serverBuild())
                .isEqualTo("sha-81ff1e0");
    }
}
