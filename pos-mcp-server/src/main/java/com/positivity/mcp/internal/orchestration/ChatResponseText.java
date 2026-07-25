package com.positivity.mcp.internal.orchestration;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Extracts user-facing text from a chat model reply, guarding against blank output.
 *
 * <p>Spring AI maps only Ollama's {@code content} field to {@link AssistantMessage#getText()}; a
 * reasoning model that emits its answer into the {@code thinking} channel therefore surfaces as an
 * empty string. This helper prefers {@code content} (with any inline {@code <think>} block removed)
 * and, when that is blank, recovers the {@code thinking} channel so the caller never receives a
 * silent blank. Both empty is reported to the caller as a fixed, safe message.
 */
final class ChatResponseText {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatResponseText.class);
    private static final String THINKING_METADATA_KEY = "thinking";
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    static final String BLANK_RESPONSE_FALLBACK =
            "I couldn't produce a response for that. Please rephrase or try again.";

    private ChatResponseText() {}

    static @NonNull String extract(@Nullable AssistantMessage message) {
        if (message == null) {
            LOGGER.warn("Chat model returned no assistant message; using blank-response fallback");
            return BLANK_RESPONSE_FALLBACK;
        }
        String content = stripThinkBlocks(message.getText());
        if (!content.isBlank()) {
            return content;
        }
        // content was empty: the model routed its answer into the reasoning channel.
        String thinking = stripThinkBlocks(thinkingChannel(message));
        if (!thinking.isBlank()) {
            LOGGER.warn("Chat model returned blank content; recovered answer from thinking channel. "
                    + "Set OLLAMA_CHAT_THINK=false for reasoning models to return the answer in content.");
            return thinking;
        }
        LOGGER.warn("Chat model returned blank content and no thinking channel; using blank-response fallback");
        return BLANK_RESPONSE_FALLBACK;
    }

    private static @NonNull String stripThinkBlocks(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return THINK_BLOCK.matcher(text).replaceAll("").strip();
    }

    private static @Nullable String thinkingChannel(@NonNull AssistantMessage message) {
        Object thinking = message.getMetadata().get(THINKING_METADATA_KEY);
        return thinking != null ? thinking.toString() : null;
    }
}
