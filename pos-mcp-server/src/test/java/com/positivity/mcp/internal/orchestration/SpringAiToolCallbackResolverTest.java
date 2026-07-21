package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

class SpringAiToolCallbackResolverTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        assertThat(required).containsExactlyElementsOf(properties.keySet());

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

        assertThatThrownBy(() -> callback.call("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
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
}