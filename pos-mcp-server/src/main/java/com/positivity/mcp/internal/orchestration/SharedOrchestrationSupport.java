package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CurrentUserContext;
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

    /** The caller-context suffix appended to system prompts by both managers and the T0 fast path. */
    public @NonNull String formatUserContext(@NonNull CurrentUserContext userContext) {
        return "Authenticated user context: username=" + userContext.username()
                + ", userId=" + userContext.userId() + ", primaryRole="
                + userContext.primaryRole() + ", roles="
                + userContext.roles() + ", authorityCount="
                + userContext.authorities().size()
                + ". Interpret references to 'me', 'my', or 'current user' as this authenticated user."
                + " If a question depends on the user's exact permissions, prefer a self-service permissions tool before asking for identifiers.";
    }

    public @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }
}
