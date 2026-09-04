package com.positivity.mcp.internal.orchestration;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
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
        BLANK,
        /**
         * {@code content} held nothing but a serialised tool payload (#1708).
         *
         * <p>Observed on the 2026-09-04 gate run: q04 replied with
         * {@code {"startDate":"2026-03-01","endDate":"2026-03-31","rows":[…]}} and q05 with what
         * reads as a tool <em>request</em>. The source is {@code content} — the model emitted the
         * payload itself rather than composing an answer from it — so this is not the blank-content
         * path and is not the ladder leaking. It is nonetheless not an answer: a reader gets raw
         * JSON, and a grader reading the reply records a data failure for a turn whose tools all
         * ran correctly.
         */
        TOOL_PAYLOAD
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
            if (isBareToolPayload(content)) {
                // Deliberately does not say "tool payload": this helper also serves the tool-less
                // simple-chat path, where a JSON-shaped reply is not a tool result and naming one
                // would mislead whoever is triaging the log.
                LOGGER.warn("Chat model returned a bare JSON object or array as its answer; "
                        + "treating it as no direct answer (#1708)");
                return new Extracted(content, Source.TOOL_PAYLOAD);
            }
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

    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();
    private static final Pattern JSON_FENCE =
            Pattern.compile("(?s)\\A```(?:json)?\\s*(.*?)\\s*```\\z", Pattern.CASE_INSENSITIVE);

    /**
     * Whether {@code content} is nothing but a serialised JSON object or array (#1708).
     *
     * <p>Decided by an actual parse rather than by inspecting the first and last characters. The
     * character check produced a false positive on ordinary prose that merely opened and closed
     * with braces and contained a quote — {@code {Top vendor is "Cascade Parts" at $12,000}} — and
     * a false positive here is the worst outcome available: the reply is routed to the fallback
     * ladder, so a real answer is silently replaced. A parse cannot make that mistake.
     *
     * <p>A fenced block is unwrapped first. A model told not to reply with bare JSON is at least as
     * likely to fence it, and a fenced payload is the same defect with three backticks in front.
     *
     * <p>Scalars are deliberately out of scope: a bare {@code 42} is a plausible answer to "how
     * many open work orders?", and {@code "OK"} to a confirmation. Every payload observed in #1708
     * was an object.
     */
    private static boolean isBareToolPayload(@NonNull String content) {
        String trimmed = content.strip();
        Matcher fence = JSON_FENCE.matcher(trimmed);
        if (fence.matches()) {
            trimmed = fence.group(1).strip();
        }
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return false;
        }
        try {
            JsonNode parsed = PAYLOAD_MAPPER.readTree(trimmed);
            return parsed != null && (parsed.isObject() || parsed.isArray());
        } catch (JacksonException notJson) {
            return false;
        }
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
