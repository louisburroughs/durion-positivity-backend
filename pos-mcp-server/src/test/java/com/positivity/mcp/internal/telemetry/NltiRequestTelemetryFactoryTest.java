package com.positivity.mcp.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.PromptLayer;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NltiRequestTelemetryFactoryTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000201");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000202");

    @Test
    void forChatRequest_toolPath_populatesActorToolsLayersLatencyOutcome() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forChatRequest(
                "corr-1",
                "2026-07-01T00:00:00Z",
                "ROLE_ADMIN",
                676,
                List.of("WorkorderFacadeTool", "InventoryFacadeTool"),
                List.of("event-receiver_getactiveeventtypes"),
                List.of("BASE", "ROLE", "DOMAIN", "TOOL_USE"),
                false,
                null,
                "CREATING_PO",
                1234L,
                "SUCCESS",
                null);

        assertThat(event.schemaVersion()).isEqualTo(NltiRequestTelemetry.SCHEMA_VERSION);
        assertThat(event.eventType()).isEqualTo(NltiRequestTelemetry.EVENT_TYPE);
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.actor().primaryRole()).isEqualTo("ROLE_ADMIN");
        assertThat(event.actor().permissionCodeCount()).isEqualTo(676);
        assertThat(event.tools()).isNotNull();
        assertThat(event.tools().selected()).containsExactly("WorkorderFacadeTool", "InventoryFacadeTool");
        assertThat(event.tools().discoveredOpenapi()).containsExactly("event-receiver_getactiveeventtypes");
        assertThat(event.tools().candidateCount()).isEqualTo(2);
        assertThat(event.rag()).isNotNull();
        assertThat(event.rag().promptLayers())
                .containsExactly(PromptLayer.BASE, PromptLayer.ROLE, PromptLayer.DOMAIN, PromptLayer.TOOL_USE);
        assertThat(event.latency().totalMs()).isEqualTo(1234L);
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
        assertThat(event.outcome().errorCode()).isNull();
        // Tool path carries the workflow state (Gate 2C) but no tier yet.
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().workflowState()).isEqualTo("CREATING_PO");
        assertThat(event.routing().tier()).isNull();
    }

    @Test
    void forChatRequest_fallbackUsed_isReportedEvenWithoutTierRouting_andConsumed() {
        // #1691: a failover on a turn with no tier routing (tiering off, or simple chat) still
        // surfaces, and the per-request flag never outlives the event that reports it.
        FallbackUsage.mark();

        NltiRequestTelemetry withFailover = NltiRequestTelemetryFactory.forChatRequest(
                "corr-fb",
                "2026-09-06T00:00:00Z",
                "ROLE_USER",
                3,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                42L,
                "SUCCESS",
                null);
        NltiRequestTelemetry next = NltiRequestTelemetryFactory.forChatRequest(
                "corr-fb-2",
                "2026-09-06T00:00:01Z",
                "ROLE_USER",
                3,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                42L,
                "SUCCESS",
                null);

        assertThat(withFailover.model()).isNotNull();
        assertThat(withFailover.model().fallbackUsed()).isTrue();
        assertThat(withFailover.model().tierModel()).isNull();
        assertThat(next.model()).as("flag consumed by the previous event").isNull();
    }

    @Test
    void forChatRequest_simpleChat_setsTier0AndRuleNoTools() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forChatRequest(
                "corr-2",
                "2026-07-01T00:00:00Z",
                "ROLE_USER",
                3,
                List.of(),
                List.of(),
                List.of(),
                true,
                "greeting",
                null,
                42L,
                "SUCCESS",
                null);

        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().tier()).isEqualTo(Tier.T0_RULE);
        assertThat(event.routing().simpleChatRule()).isEqualTo("greeting");
        assertThat(event.tools()).isNull();
        assertThat(event.rag()).isNull();
        assertThat(event.latency().totalMs()).isEqualTo(42L);
    }

    @Test
    void forChatRequest_error_carriesErrorCodeAndStatus() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forChatRequest(
                "corr-3",
                "2026-07-01T00:00:00Z",
                "ROLE_ADMIN",
                676,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                10L,
                "ERROR",
                "RuntimeException");

        assertThat(event.outcome().status()).isEqualTo("ERROR");
        assertThat(event.outcome().errorCode()).isEqualTo("RuntimeException");
        assertThat(event.tools()).isNull();
    }

    @Test
    void forChatRequest_unknownPromptLayerIsDropped() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forChatRequest(
                "corr-4",
                "2026-07-01T00:00:00Z",
                "ROLE_ADMIN",
                676,
                List.of("WorkorderFacadeTool"),
                List.of(),
                List.of("BASE", "NOT_A_LAYER", "role"),
                false,
                null,
                null,
                5L,
                "SUCCESS",
                null);

        // Unknown names dropped; known names case-insensitively mapped.
        assertThat(event.rag().promptLayers()).containsExactly(PromptLayer.BASE, PromptLayer.ROLE);
    }

    // ─── #1397: the NLTI pipeline builder ────────────────────────────────────

    @Test
    void forNltiRequest_writeSubmit_carriesIdsIntentRiskAndWriteSignal() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forNltiRequest(
                "corr-5",
                "2026-08-19T00:00:00Z",
                SESSION_ID,
                REQUEST_ID,
                "ROLE_MANAGER",
                12,
                "ACTION",
                "MEDIUM",
                new NltiRequestTelemetryFactory.WriteSignal(true, null, null),
                77L,
                "SUCCESS",
                null);

        assertThat(event.schemaVersion()).isEqualTo(NltiRequestTelemetry.SCHEMA_VERSION);
        assertThat(event.eventType()).isEqualTo(NltiRequestTelemetry.EVENT_TYPE);
        // The NLTI path is the only one that knows these two — the chat path leaves them null.
        assertThat(event.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(event.requestId()).isEqualTo(REQUEST_ID.toString());
        assertThat(event.actor().primaryRole()).isEqualTo("ROLE_MANAGER");
        assertThat(event.actor().permissionCodeCount()).isEqualTo(12);
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().intentType()).isEqualTo("ACTION");
        assertThat(event.routing().riskLevel()).isEqualTo("MEDIUM");
        assertThat(event.write()).isNotNull();
        assertThat(event.write().isWrite()).isTrue();
        assertThat(event.write().confirmationOutcome()).isNull();
        assertThat(event.latency().totalMs()).isEqualTo(77L);
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
        // No model tier, prompt composition, RAG retrieval or tool selection runs on this path,
        // so those blocks are omitted rather than zero-filled.
        assertThat(event.model()).isNull();
        assertThat(event.tools()).isNull();
        assertThat(event.rag()).isNull();
        assertThat(event.quality()).isNull();
    }

    @Test
    void forNltiRequest_confirmation_carriesOutcomeAndProvenanceCounts() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forNltiRequest(
                "corr-6",
                "2026-08-19T00:00:00Z",
                SESSION_ID,
                REQUEST_ID,
                "ROLE_MANAGER",
                12,
                null,
                "HIGH",
                new NltiRequestTelemetryFactory.WriteSignal(
                        true, "confirmed", Map.of("USER_TEXT", 2, "USER_CONTEXT", 1)),
                21L,
                "SUCCESS",
                null);

        assertThat(event.write().confirmationOutcome()).isEqualTo("confirmed");
        assertThat(event.write().planArgsProvenance())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "USER_TEXT", 2,
                        "USER_CONTEXT", 1));
        // Risk alone is enough to warrant a routing block; intent was classified on the submit leg.
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().intentType()).isNull();
        assertThat(event.routing().riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void forNltiRequest_withoutLatencyOrRouting_omitsBothBlocks() {
        NltiRequestTelemetry event = NltiRequestTelemetryFactory.forNltiRequest(
                "corr-7",
                "2026-08-19T00:00:00Z",
                null,
                null,
                "ROLE_USER",
                1,
                null,
                null,
                null,
                null,
                "ERROR",
                "RateLimitExceededException");

        // A superseded plan is not a call of its own, so it reports no latency; a request that
        // failed before classification has no routing signals to report.
        assertThat(event.latency()).isNull();
        assertThat(event.routing()).isNull();
        assertThat(event.write()).isNull();
        assertThat(event.sessionId()).isNull();
        assertThat(event.requestId()).isNull();
        assertThat(event.outcome().status()).isEqualTo("ERROR");
        assertThat(event.outcome().errorCode()).isEqualTo("RateLimitExceededException");
    }

    // ── ADR-0069: schema version 2 ──────────────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static NltiRequestTelemetry chatEvent(NltiRequestTelemetryFactory.ScopeSignal scope) {
        return NltiRequestTelemetryFactory.forChatRequest(
                "corr-scope",
                "2026-09-30T12:00:00Z",
                "ROLE_ADMIN",
                12,
                List.of("WorkorderFacadeTool"),
                List.of(),
                List.of("BASE", "ROLE"),
                false,
                null,
                "IDLE",
                321L,
                "SUCCESS",
                null,
                null,
                false,
                scope);
    }

    @Test
    void schemaVersionIsTwo() {
        assertThat(NltiRequestTelemetry.SCHEMA_VERSION).isEqualTo(2);
        assertThat(chatEvent(null).schemaVersion()).isEqualTo(2);
    }

    @Test
    void forChatRequest_withScope_carriesTheEightScopeFields() throws Exception {
        NltiRequestTelemetry event = chatEvent(
                new NltiRequestTelemetryFactory.ScopeSignal("SHADOW", "c878c7206d2ed660", "HIGH", 3, 5, 4, 0, false));

        assertThat(event.scopeMode()).isEqualTo("SHADOW");
        assertThat(event.scopeGraphHash()).isEqualTo("c878c7206d2ed660");
        assertThat(event.scopeConfidence()).isEqualTo("HIGH");
        assertThat(event.scopeEntityCount()).isEqualTo(3);
        assertThat(event.scopeToolCount()).isEqualTo(5);
        assertThat(event.scopeDocCount()).isEqualTo(4);
        assertThat(event.scopeAddedToolCount()).isZero();
        assertThat(event.scopeRagFilterApplied()).isFalse();

        com.fasterxml.jackson.databind.JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(event));
        assertThat(json.get("schemaVersion").intValue()).isEqualTo(2);
        assertThat(json.get("scopeMode").textValue()).isEqualTo("SHADOW");
        assertThat(json.get("scopeGraphHash").textValue()).isEqualTo("c878c7206d2ed660");
        assertThat(json.get("scopeConfidence").textValue()).isEqualTo("HIGH");
        assertThat(json.get("scopeEntityCount").intValue()).isEqualTo(3);
        assertThat(json.get("scopeToolCount").intValue()).isEqualTo(5);
        assertThat(json.get("scopeDocCount").intValue()).isEqualTo(4);
        assertThat(json.get("scopeAddedToolCount").intValue()).isZero();
        assertThat(json.get("scopeRagFilterApplied").booleanValue()).isFalse();
    }

    @Test
    void forChatRequest_withoutScope_omitsEveryScopeFieldFromTheJson() throws Exception {
        String json = MAPPER.writeValueAsString(chatEvent(null));

        // Mode off, simple chat and failed requests resolve no scope: the fields are absent, not zero.
        assertThat(json).doesNotContain("\"scope");
        // The Loki queries carve the event out of the log line by this exact prefix.
        assertThat(json).startsWith("{\"schemaVersion\":2,");
    }

    @Test
    void theOlderChatOverloadsAndTheNltiPathCarryNoScope() throws Exception {
        NltiRequestTelemetry chat = NltiRequestTelemetryFactory.forChatRequest(
                "corr-1",
                "2026-07-01T00:00:00Z",
                "ROLE_ADMIN",
                1,
                List.of("WorkorderFacadeTool"),
                List.of(),
                List.of(),
                false,
                null,
                null,
                1L,
                "SUCCESS",
                null);
        NltiRequestTelemetry nlti = NltiRequestTelemetryFactory.forNltiRequest(
                "corr-2",
                "2026-07-01T00:00:00Z",
                SESSION_ID,
                REQUEST_ID,
                "ROLE_ADMIN",
                1,
                "ACTION",
                "LOW",
                null,
                5L,
                "SUCCESS",
                null);

        assertThat(chat.scopeMode()).isNull();
        assertThat(MAPPER.writeValueAsString(chat)).doesNotContain("\"scope");
        assertThat(nlti.schemaVersion()).isEqualTo(2);
        assertThat(MAPPER.writeValueAsString(nlti)).doesNotContain("\"scope");
    }

    @Test
    void aVersionOneReaderThatIgnoresUnknownFieldsStillReadsEveryOldField() throws Exception {
        String json = MAPPER.writeValueAsString(chatEvent(
                new NltiRequestTelemetryFactory.ScopeSignal("SHADOW", "c878c7206d2ed660", "LOW", 1, 0, 0, 0, false)));

        com.fasterxml.jackson.databind.JsonNode event = MAPPER.readTree(json);
        assertThat(event.get("eventType").textValue()).isEqualTo("nlti.request.telemetry");
        assertThat(event.at("/actor/primaryRole").textValue()).isEqualTo("ROLE_ADMIN");
        assertThat(event.at("/tools/selected/0").textValue()).isEqualTo("WorkorderFacadeTool");
        assertThat(event.at("/routing/workflowState").textValue()).isEqualTo("IDLE");
        assertThat(event.at("/latency/totalMs").longValue()).isEqualTo(321L);
        assertThat(event.at("/outcome/status").textValue()).isEqualTo("SUCCESS");
    }
}
