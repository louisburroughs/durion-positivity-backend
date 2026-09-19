package com.positivity.mcp.internal.orchestration;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PosAssistant {

    @NonNull
    String chat(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext);

    /**
     * #2075: the reply plus how it was produced. Default: text only, source unreported, no
     * tools — an implementation that can report more overrides this instead of {@link
     * #chat(String, String, String)}.
     */
    default @NonNull Reply reply(@NonNull String memoryId, @NonNull String userMessage, @NonNull String userContext) {
        return new Reply(chat(memoryId, userMessage, userContext), null, List.of());
    }

    /**
     * @param text the assistant's answer
     * @param answerSource {@code CONTENT}, {@code RE_RENDERED}, {@code LADDER}, or the raw {@code
     *     ChatResponseText.Source} name when no ladder bean is wired; null when unreported
     * @param toolsCalled tool names in call order, duplicates kept, capped at {@link
     *     com.positivity.mcp.internal.domain.TurnSummary#MAX_TOOLS_CALLED}
     */
    record Reply(
            @NonNull String text,
            @Nullable String answerSource,
            @NonNull List<String> toolsCalled) {
        public Reply {
            toolsCalled = List.copyOf(toolsCalled);
        }
    }
}
