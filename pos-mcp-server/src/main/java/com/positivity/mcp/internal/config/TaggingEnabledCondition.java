package com.positivity.mcp.internal.config;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * ADR-0068 §6: true when {@code mcp.tagging.mode} is {@code shadow} or {@code enforce}. The
 * decision-model beans ({@code JevClient}, {@code JevQuestionTagger}) exist only then, so {@code off}
 * builds no provider client, registers no meter and writes no tagging log line.
 */
public class TaggingEnabledCondition implements Condition {

    @Override
    public boolean matches(@NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("mcp.tagging.mode", "off");
        // YAML reads a bare off as the boolean false; both spellings mean off.
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return !(normalized.isEmpty() || "off".equals(normalized) || "false".equals(normalized));
    }
}
