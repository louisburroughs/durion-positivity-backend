package com.positivity.mcp.internal.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

/**
 * Applies the {@link ChatResponseText} classification to a streamed reply (#1838).
 *
 * <p>The blocking path classifies the whole reply before anything reaches the user; a stream cannot
 * wait for the end without giving up streaming. The compromise: ordinary prose starts with a letter
 * or digit and is passed through token by token, while a reply whose first non-blank characters are
 * the start of harmony protocol markup ({@code <|}), a JSON payload ({@code {} / {@code [}), a fenced
 * block or a {@code <think>} block is held back and classified once complete. A held reply that
 * classifies as {@code CONTENT} (a recovered harmony {@code final} channel, or prose after a think
 * block) is emitted in one piece; anything else is replaced by the safe fallback text — the streaming
 * path has no ladder and no re-render, so a bare payload becomes the fallback rather than raw JSON. A
 * stream that ends having emitted nothing also yields the fallback, so the client never gets a silent
 * blank.
 *
 * <p>The decision is made on the first non-blank characters; a prefix that could still become either
 * ({@code <}, a lone backtick) waits for a few more characters or the end of the stream, so a prose
 * reply that opens with an HTML tag is not held back for long. Markup that appears after a prose
 * lead-in stops the stream at that point: what already went out cannot be retracted, but nothing more
 * is emitted until the whole reply is classified. Every reply, passthrough included, is classified in
 * full at the end so the reported source matches what the blocking path would say.
 */
final class StreamingAnswerGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingAnswerGuard.class);
    /** Enough characters to tell {@code <|channel|>} / {@code <think>} / a fence from prose. */
    private static final int AMBIGUOUS_PREFIX_LIMIT = 8;

    private StreamingAnswerGuard() {}

    /**
     * Wraps {@code tokens}; {@code onClassified} receives the reply's {@link ChatResponseText.Source}
     * exactly once, when the stream completes.
     */
    static @NonNull Flux<String> guard(
            @NonNull Flux<String> tokens, @NonNull Consumer<ChatResponseText.Source> onClassified) {
        return Flux.defer(() -> {
            State state = new State();
            return tokens.concatMap(token -> Flux.fromIterable(state.accept(token)))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(state.finish(onClassified))));
        });
    }

    private enum Mode {
        UNDECIDED,
        PASSTHROUGH,
        HOLD
    }

    private static final class State {
        private final StringBuilder buffered = new StringBuilder();
        private final StringBuilder emitted = new StringBuilder();
        private Mode mode = Mode.UNDECIDED;

        @NonNull
        List<String> accept(@NonNull String token) {
            buffered.append(token);
            if (mode == Mode.HOLD) {
                return List.of();
            }
            if (mode == Mode.PASSTHROUGH) {
                if (token.contains("<|")) {
                    // Markup after a prose lead-in ("Sure <|channel|>…"): what is out is out, but
                    // nothing more goes until the whole reply has been classified.
                    mode = Mode.HOLD;
                    return List.of();
                }
                emitted.append(token);
                return token.isEmpty() ? List.of() : List.of(token);
            }
            String prefix = buffered.toString().stripLeading();
            if (prefix.isEmpty()) {
                return List.of();
            }
            char first = prefix.charAt(0);
            if (first == '{' || first == '[') {
                mode = Mode.HOLD;
                return List.of();
            }
            if (first == '<' || first == '`') {
                if (prefix.startsWith("<|") || prefix.startsWith("<think") || prefix.startsWith("```")) {
                    mode = Mode.HOLD;
                    return List.of();
                }
                if (prefix.length() < AMBIGUOUS_PREFIX_LIMIT) {
                    return List.of();
                }
            }
            if (prefix.contains("<|")) {
                mode = Mode.HOLD;
                return List.of();
            }
            mode = Mode.PASSTHROUGH;
            emitted.append(buffered);
            return List.of(buffered.toString());
        }

        @NonNull
        List<String> finish(@NonNull Consumer<ChatResponseText.Source> onClassified) {
            if (mode == Mode.UNDECIDED && buffered.toString().isBlank()) {
                LOGGER.warn("Streamed reply carried no text; using blank-response fallback (#1838)");
                onClassified.accept(ChatResponseText.Source.BLANK);
                return List.of(ChatResponseText.BLANK_RESPONSE_FALLBACK);
            }
            // Every reply is classified in full at the end, passthrough included, so the recorded
            // source is the same the blocking path would report even when text already went out.
            ChatResponseText.Extracted extracted =
                    ChatResponseText.extractDetailed(new AssistantMessage(buffered.toString()));
            onClassified.accept(extracted.source());
            if (extracted.source() == ChatResponseText.Source.CONTENT) {
                String text = extracted.text();
                String alreadyOut = emitted.toString();
                if (alreadyOut.isEmpty()) {
                    return List.of(text);
                }
                // Passthrough: what remains of the classified text after what already went out.
                String remainder = text.startsWith(alreadyOut.strip())
                        ? text.substring(alreadyOut.strip().length())
                        : text;
                return remainder.isEmpty() ? List.of() : List.of(remainder);
            }
            // The content is not logged: a payload carries data, markup carries tool arguments.
            LOGGER.warn(
                    "Streamed reply classified as {}; replaced by the blank-response fallback (#1838)",
                    extracted.source());
            return List.of(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        }
    }

    /** Test seam: the emissions for a fixed token list, in order. */
    static @NonNull List<String> collect(
            @NonNull List<String> tokens, @NonNull Consumer<ChatResponseText.Source> onClassified) {
        List<String> out = new ArrayList<>();
        guard(Flux.fromIterable(tokens), onClassified).doOnNext(out::add).blockLast();
        return out;
    }
}
