package com.positivity.mcp.internal.orchestration;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class SharedOrchestrationSupport {

    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    public @NonNull List<Object> mergeTools(
            @NonNull Collection<Object> roleTools, @NonNull Collection<Object> fallbackTools) {
        return ToolSelectionSupport.mergeWithoutDuplicateToolNames(roleTools, fallbackTools.toArray(Object[]::new));
    }

    public @NonNull String toolCacheKey(@NonNull Collection<Object> tools) {
        TreeSet<String> names = new TreeSet<>(Comparator.naturalOrder());
        tools.forEach(tool -> names.add(toolName(tool)));
        return names.isEmpty() ? "none" : String.join("+", names);
    }

    public @NonNull List<String> toolNames(@NonNull Collection<Object> tools) {
        return tools.stream().map(this::toolName).toList();
    }

    public @NonNull String toolName(@NonNull Object tool) {
        return ClassUtils.getUserClass(tool).getSimpleName();
    }

    public @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }
}
