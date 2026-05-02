package com.positivity.mcp.internal.classification;

import com.positivity.mcp.internal.enums.SimpleChatRuleType;
import org.jspecify.annotations.NonNull;

public record SimpleChatRuleSpec(
        @NonNull SimpleChatRuleType ruleType,
        @NonNull String phrase,
        @NonNull String languageCode,
        int priority) {}
