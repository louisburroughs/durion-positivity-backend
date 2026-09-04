package com.positivity.mcp.internal.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.exception.InvalidToolArgumentException;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.ClassUtils;

final class SpringAiToolCallbackResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SpringAiToolCallbackResolver() {}

    static @NonNull List<ToolCallback> fromObjects(@NonNull List<Object> toolObjects) {
        return fromObjects(toolObjects, null);
    }

    /**
     * #1422: when a recorder is supplied, every facade callback is wrapped so each execution logs
     * an {@code mcp_tool_invocation_log} row. The lookup key is the owning facade's user-class
     * simple name (e.g. {@code OrderFacadeTool}) — that is how facade rows are keyed in
     * {@code mcp_tool.name}; the callback's own name is the {@code @Tool} method name.
     */
    static @NonNull List<ToolCallback> fromObjects(
            @NonNull List<Object> toolObjects, @Nullable ToolInvocationRecorder invocationRecorder) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            callbacks.addAll(fromObject(toolObject, invocationRecorder));
        }
        return List.copyOf(callbacks);
    }

    private static @NonNull List<ToolCallback> fromObject(
            @NonNull Object toolObject, @Nullable ToolInvocationRecorder invocationRecorder) {
        List<ToolCallback> callbacks = new ArrayList<>();
        String facadeName = ClassUtils.getUserClass(toolObject).getSimpleName();
        for (Method method : toolObject.getClass().getMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            String description = toolAnnotation.description().trim();
            if (description.isBlank()) {
                description = method.getName();
            }
            ToolCallback callback = new ReflectiveToolCallback(toolObject, method, description);
            callbacks.add(invocationRecorder != null ? invocationRecorder.wrap(callback, facadeName) : callback);
        }
        return callbacks;
    }

    private static final class ReflectiveToolCallback implements ToolCallback {

        private final Object target;
        private final Method method;
        private final List<ParameterBinding> parameterBindings;
        private final ToolDefinition toolDefinition;

        private ReflectiveToolCallback(@NonNull Object target, @NonNull Method method, @NonNull String description) {
            this.target = target;
            this.method = method;
            this.parameterBindings = parameterBindings(method);
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(method.getName())
                    .description(description)
                    .inputSchema(buildInputSchema(parameterBindings, method.getName()))
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
            List<ParameterBinding> bindings = parameterBindings(method);
            Parameter[] parameters = method.getParameters();
            Object[] resolved = new Object[parameters.length];
            for (ParameterBinding binding : bindings) {
                Object rawValue = firstPresent(arguments, binding.candidateNames());
                resolved[binding.index()] =
                        rawValue == null ? null : OBJECT_MAPPER.convertValue(rawValue, binding.parameterType());
            }
            return resolved;
        }

        private static @Nullable Object firstPresent(
                @NonNull Map<String, Object> arguments, @NonNull List<String> candidateNames) {
            for (String candidateName : candidateNames) {
                if (arguments.containsKey(candidateName)) {
                    return arguments.get(candidateName);
                }
            }
            return null;
        }

        private static @NonNull Map<String, Object> parseArguments(@NonNull String toolInput) {
            if (toolInput.isBlank()) {
                return Map.of();
            }
            try {
                return OBJECT_MAPPER.readValue(toolInput, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new InvalidToolArgumentException("Invalid tool input JSON", e);
            }
        }

        private static @NonNull String buildInputSchema(
                @NonNull List<ParameterBinding> parameterBindings, @NonNull String methodName) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ParameterBinding binding : parameterBindings) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", jsonType(binding.parameterType()));
                if (!binding.description().isBlank()) {
                    property.put("description", binding.description());
                }
                properties.put(binding.schemaName(), property);
                required.add(binding.schemaName());
            }
            schema.put("properties", properties);
            schema.put("required", required);
            try {
                return OBJECT_MAPPER.writeValueAsString(schema);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize tool schema for " + methodName, e);
            }
        }

        private static @NonNull List<ParameterBinding> parameterBindings(@NonNull Method method) {
            Parameter[] parameters = method.getParameters();
            List<ParameterBinding> bindings = new ArrayList<>(parameters.length);
            for (int index = 0; index < parameters.length; index++) {
                Parameter parameter = parameters[index];
                ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
                String schemaName = schemaName(parameter, toolParam, index);
                String description = toolParam == null ? "" : toolParam.description();
                List<String> candidateNames = new ArrayList<>();
                candidateNames.add(schemaName);
                if (parameter.isNamePresent()) {
                    candidateNames.add(parameter.getName());
                }
                candidateNames.add("arg" + index);
                bindings.add(new ParameterBinding(
                        index, parameter.getType(), schemaName, description, List.copyOf(candidateNames)));
            }
            return List.copyOf(bindings);
        }

        private static @NonNull String schemaName(
                @NonNull Parameter parameter, ToolParam toolParam, int parameterIndex) {
            String explicitName = explicitToolParamName(toolParam);
            if (!explicitName.isBlank()) {
                return explicitName;
            }
            if (parameter.isNamePresent()) {
                return parameter.getName();
            }
            return "param" + (parameterIndex + 1);
        }

        private static @NonNull String explicitToolParamName(ToolParam toolParam) {
            if (toolParam == null) {
                return "";
            }
            for (String candidateMethodName : List.of("name", "value")) {
                try {
                    Method accessor = toolParam.annotationType().getMethod(candidateMethodName);
                    Object value = accessor.invoke(toolParam);
                    if (value instanceof String stringValue && !stringValue.isBlank()) {
                        return stringValue;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // ToolParam variants may not expose a parameter-name field.
                }
            }
            return "";
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
            if (Number.class.isAssignableFrom(type) || double.class.equals(type) || float.class.equals(type)) {
                return "number";
            }
            return "string";
        }

        private record ParameterBinding(
                int index,
                @NonNull Class<?> parameterType,
                @NonNull String schemaName,
                @NonNull String description,
                @NonNull List<String> candidateNames) {
            private ParameterBinding {
                candidateNames = Collections.unmodifiableList(candidateNames);
            }
        }
    }
}
