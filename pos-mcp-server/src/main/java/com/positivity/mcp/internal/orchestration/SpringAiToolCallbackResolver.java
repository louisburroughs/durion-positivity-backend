package com.positivity.mcp.internal.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

final class SpringAiToolCallbackResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SpringAiToolCallbackResolver() {}

    static @NonNull List<ToolCallback> fromObjects(@NonNull List<Object> toolObjects) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            callbacks.addAll(fromObject(toolObject));
        }
        return List.copyOf(callbacks);
    }

    private static @NonNull List<ToolCallback> fromObject(@NonNull Object toolObject) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Method method : toolObject.getClass().getMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            String description = toolAnnotation.description().trim();
            if (description.isBlank()) {
                description = method.getName();
            }
            callbacks.add(new ReflectiveToolCallback(toolObject, method, description));
        }
        return callbacks;
    }

    private static final class ReflectiveToolCallback implements ToolCallback {

        private final Object target;
        private final Method method;
        private final ToolDefinition toolDefinition;

        private ReflectiveToolCallback(@NonNull Object target, @NonNull Method method, @NonNull String description) {
            this.target = target;
            this.method = method;
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(method.getName())
                    .description(description)
                    .inputSchema(buildInputSchema(method))
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return DefaultToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            try {
                Object result = method.invoke(target, resolveArguments(method, toolInput));
                return result == null ? "" : String.valueOf(result);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to access tool method " + method.getName(), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("Tool method failed: " + method.getName(), cause);
            }
        }

        private static Object[] resolveArguments(@NonNull Method method, @NonNull String toolInput) {
            Map<String, Object> arguments = parseArguments(toolInput);
            Parameter[] parameters = method.getParameters();
            Object[] resolved = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                Object rawValue = arguments.get(parameter.getName());
                resolved[i] = rawValue == null ? null : OBJECT_MAPPER.convertValue(rawValue, parameter.getType());
            }
            return resolved;
        }

        private static @NonNull Map<String, Object> parseArguments(@NonNull String toolInput) {
            if (toolInput.isBlank()) {
                return Map.of();
            }
            try {
                return OBJECT_MAPPER.readValue(toolInput, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid tool input JSON", e);
            }
        }

        private static @NonNull String buildInputSchema(@NonNull Method method) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", jsonType(parameter.getType()));
                ToolParam description = parameter.getAnnotation(ToolParam.class);
                if (description != null && !description.description().isBlank()) {
                    property.put("description", description.description());
                }
                properties.put(parameter.getName(), property);
                required.add(parameter.getName());
            }
            schema.put("properties", properties);
            schema.put("required", required);
            try {
                return OBJECT_MAPPER.writeValueAsString(schema);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize tool schema for " + method.getName(), e);
            }
        }

        private static @NonNull String jsonType(@NonNull Class<?> type) {
            if (Boolean.class.equals(type) || boolean.class.equals(type)) {
                return "boolean";
            }
            if (Integer.class.equals(type)
                    || int.class.equals(type)
                    || Long.class.equals(type)
                    || long.class.equals(type)) {
                return "integer";
            }
            if (Number.class.isAssignableFrom(type)
                    || double.class.equals(type)
                    || float.class.equals(type)) {
                return "number";
            }
            return "string";
        }
    }
}
