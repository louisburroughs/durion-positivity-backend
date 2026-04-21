package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.agent.tool.Tool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.util.ClassUtils;

final class ToolSelectionSupport {

    private ToolSelectionSupport() {}

    static @NonNull List<Object> mergeWithoutDuplicateToolNames(
            @NonNull Collection<Object> roleTools, @NonNull Object... fallbackTools) {
        List<Object> selected = new ArrayList<>();
        Set<String> selectedToolNames = new HashSet<>();

        for (Object tool : roleTools) {
            addIfNoDuplicateToolName(selected, selectedToolNames, tool);
        }
        for (Object tool : fallbackTools) {
            addIfNoDuplicateToolName(selected, selectedToolNames, tool);
        }

        return selected;
    }

    private static void addIfNoDuplicateToolName(
            @NonNull List<Object> selected, @NonNull Set<String> selectedToolNames, @NonNull Object tool) {
        Set<String> toolNames = toolNames(tool);
        if (toolNames.stream().noneMatch(selectedToolNames::contains)) {
            selected.add(tool);
            selectedToolNames.addAll(toolNames);
        }
    }

    private static @NonNull Set<String> toolNames(@NonNull Object tool) {
        Class<?> userClass = ClassUtils.getUserClass(tool);
        Set<String> names = new HashSet<>();
        for (Method method : userClass.getMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String explicitName = annotation.name();
                names.add(explicitName.isBlank() ? method.getName() : explicitName);
            }
        }
        if (names.isEmpty()) {
            names.add(userClass.getName());
        }
        return names;
    }
}
