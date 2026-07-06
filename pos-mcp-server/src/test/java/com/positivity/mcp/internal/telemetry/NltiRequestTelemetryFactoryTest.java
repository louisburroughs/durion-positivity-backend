package com.positivity.mcp.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.PromptLayer;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class NltiRequestTelemetryFactoryTest {

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
}
