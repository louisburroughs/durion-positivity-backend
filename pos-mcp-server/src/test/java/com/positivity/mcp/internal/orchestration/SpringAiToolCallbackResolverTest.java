package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.exception.InvalidToolArgumentException;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolExecutionException;

class SpringAiToolCallbackResolverTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fromObjects_decoratorReceivesOwningFacadeClassSimpleName() {
        // #1422: mcp_tool facade rows are keyed by the facade CLASS name, not the @Tool
        // method
        // name, so the recorder wrap must be handed the owning class simple name as
        // lookup key.
        java.util.List<String> lookupNames = new java.util.ArrayList<>();
        com.positivity.mcp.internal.service.ToolAuditService auditService =
                org.mockito.Mockito.mock(com.positivity.mcp.internal.service.ToolAuditService.class);
        com.positivity.mcp.internal.repository.ToolMetadataRepository repository =
                org.mockito.Mockito.mock(com.positivity.mcp.internal.repository.ToolMetadataRepository.class);
        org.mockito.Mockito.when(repository.findToolIdByName(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        com.positivity.mcp.internal.service.ToolInvocationRecorder recorder =
                new com.positivity.mcp.internal.service.ToolInvocationRecorder(
                        auditService,
                        repository,
                        new com.positivity.mcp.internal.service.RequestScopedUserContext(),
                        null) {
                    @Override
                    public ToolCallback wrap(ToolCallback delegate, String toolLookupName) {
                        lookupNames.add(toolLookupName);
                        return delegate;
                    }
                };

        List<ToolCallback> callbacks = SpringAiToolCallbackResolver.fromObjects(List.of(new SampleTool()), recorder);

        assertThat(callbacks).isNotEmpty();
        assertThat(lookupNames).containsOnly("SampleTool");
    }

    @Test
    void call_bindsArgumentsUsingArgIndexFallbackNames() {
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new SampleTool()))
                .getFirst();

        String output = callback.call("{\"arg0\":\"SKU-1\",\"arg1\":3,\"arg2\":true}");

        assertThat(output).isEqualTo("SKU-1|3|true");
    }

    @Test
    void schemaNames_bindSuccessfullyWithoutDependingOnReflectedParameterNames() throws Exception {
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new SampleTool()))
                .getFirst();

        Map<String, Object> schema =
                OBJECT_MAPPER.readValue(callback.getToolDefinition().inputSchema(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        assertThat(properties).hasSize(3);
        assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());

        List<String> names = properties.keySet().stream().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(names.get(0), "SKU-9");
        payload.put(names.get(1), 11);
        payload.put(names.get(2), false);

        String output = callback.call(OBJECT_MAPPER.writeValueAsString(payload));
        assertThat(output).isEqualTo("SKU-9|11|false");
    }

    @Test
    void call_rejectsInvalidJson() {
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new SampleTool()))
                .getFirst();

        // #1829: wrapped like every other binding failure so DefaultToolCallingManager hands it back
        // to the model as a result it can retry from (#1711); unwrapped it killed the turn.
        assertThatThrownBy(() -> callback.call("not-json"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Invalid tool input JSON");
    }

    static final class SampleTool {
        @Tool(description = "Test parameter binding")
        public String lookup(
                @ToolParam(description = "Product lookup key") String lookupKey,
                Integer quantity,
                boolean includeInactive) {
            return lookupKey + "|" + quantity + "|" + includeInactive;
        }
    }

    // ── #1711: a tool failure must reach the model as a correctable result ───

    static final class FailingTool {
        @Tool(description = "rejects its argument")
        public String rejectsArgument(String period) {
            throw new IllegalArgumentException("Unsupported period 'Q2-2026': pass YYYY-MM or YYYY");
        }
    }

    @Test
    @DisplayName("a tool that throws surfaces as ToolExecutionException, the type Spring AI can hand back to the model")
    void call_toolThrows_raisesToolExecutionException() {
        // Spring AI's DefaultToolCallingManager only converts ToolExecutionException into a result
        // message the model can read and retry from. An IllegalStateException escapes that hook
        // entirely, so the turn dies and the model never learns its argument was wrong (#1711).
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new FailingTool()), null)
                .get(0);

        assertThatThrownBy(() -> callback.call("{\"period\":\"Q2-2026\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Unsupported period");
    }

    @Test
    @DisplayName("the original failure is preserved as the cause, not flattened to a generic message")
    void call_toolThrows_keepsTheOriginalCause() {
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new FailingTool()), null)
                .get(0);

        assertThatThrownBy(() -> callback.call("{\"period\":\"Q2-2026\"}"))
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass YYYY-MM or YYYY");
    }

    @Test
    @DisplayName("a missing argument for a primitive parameter is a ToolExecutionException naming it, not a "
            + "NullPointerException (#1829)")
    void missingPrimitiveArgumentIsNamed() {
        ToolCallback callback = SpringAiToolCallbackResolver.fromObjects(List.of(new SampleTool()))
                .getFirst();

        // SampleTool's third parameter is a primitive boolean; {} used to reach Method.invoke with a
        // null there and unbox into a JVM stack trace the model could not act on.
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Missing argument");
    }

    private static ToolCallback dateWindowCallback(String name) {
        DateWindowFacadeTool tool =
                new DateWindowFacadeTool(Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC));
        return SpringAiToolCallbackResolver.fromObjects(List.of(tool)).stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("resolveDateWindow with no arguments answers a correctable error through the real callback, not a "
            + "NullPointerException (#1829)")
    void emptyResolveDateWindowCallIsAnArgumentError() {
        // Alpha 2026-09-06, gpt-oss:20b on q04: the model called this tool with {} and got "Cannot
        // invoke Number.intValue() because ... is null" back — and gave up.
        assertThatThrownBy(() -> dateWindowCallback("resolveDateWindow").call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Missing argument")
                .satisfies(e -> assertThat(e.getCause().getMessage()).doesNotContain("NullPointer"));
    }

    @Test
    @DisplayName("resolveNamedPeriod with no arguments is named the same way (#1829)")
    void emptyResolveNamedPeriodCallIsAnArgumentError() {
        assertThatThrownBy(() -> dateWindowCallback("resolveNamedPeriod").call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Missing argument 'period'");
    }
}
