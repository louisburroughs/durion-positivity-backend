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

    /** Where the extracted text came from — lets callers detect a turn that produced no direct answer. */
    enum Source {
        /** The model's {@code content} field — a genuine direct answer. */
        CONTENT,
        /** Recovered from the {@code thinking} channel because {@code content} was blank. */
        THINKING,
        /** Neither channel had text; the fixed fallback string is used. */
        BLANK
    }

    /** The extracted user-facing text plus its {@link Source}. */
    record Extracted(@NonNull String text, @NonNull Source source) {}

    static @NonNull String extract(@Nullable AssistantMessage message) {
        return extractDetailed(message).text();
    }

    /**
     * Like {@link #extract} but reports the {@link Source}. A source other than {@link Source#CONTENT}
     * means the model did not produce a direct answer — the caller (e.g. the answer resolution ladder)
     * may choose a fallback rather than surface the thinking channel.
     */
    static @NonNull Extracted extractDetailed(@Nullable AssistantMessage message) {
        if (message == null) {
            LOGGER.warn("Chat model returned no assistant message; using blank-response fallback");
            return new Extracted(BLANK_RESPONSE_FALLBACK, Source.BLANK);
        }
        String content = stripThinkBlocks(message.getText());
        if (!content.isBlank()) {
            return new Extracted(content, Source.CONTENT);
        }
        // content was empty: the model routed its answer into the reasoning channel.
        String thinking = stripThinkBlocks(thinkingChannel(message));
        if (!thinking.isBlank()) {
            LOGGER.warn("Chat model returned blank content; recovered answer from thinking channel. "
                    + "Set OLLAMA_CHAT_THINK=false for reasoning models to return the answer in content.");
            return new Extracted(thinking, Source.THINKING);
        }
        LOGGER.warn("Chat model returned blank content and no thinking channel; using blank-response fallback");
        return new Extracted(BLANK_RESPONSE_FALLBACK, Source.BLANK);
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
