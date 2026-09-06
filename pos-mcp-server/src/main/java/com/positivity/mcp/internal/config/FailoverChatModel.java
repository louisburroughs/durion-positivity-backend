package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.telemetry.FallbackUsage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Primary-to-secondary failover for one chat model bean (#1691).
 *
 * <p>Wraps the primary executor: a call that fails with any runtime exception — after the primary's
 * own bounded retries (#1749) — is re-issued once against the secondary model with the same prompt.
 * The request options carry the primary's model name (the assistants build them from {@code
 * getOptions()} and the tier resolver sets a tier name), so the copy sent to the secondary has its
 * {@code model} rewritten to the secondary's; everything else — tool callbacks, tool context,
 * temperature, context window — travels unchanged.
 *
 * <p>Streaming fails over only while nothing has been emitted yet: once tokens have reached the
 * client, restarting on the secondary would append a second answer to a partial first one, so the
 * error propagates instead. A streamed failover is logged but not flagged in telemetry: the flag is
 * thread-local to the request thread and the stream's error is delivered on another.
 *
 * <p>Failover is deliberately not applied to tool-execution failures: the tool loop lives in the
 * {@code ChatClient} advisor above this wrapper, so a {@code ChatModel.call} here is one model HTTP
 * exchange and nothing else.
 */
public final class FailoverChatModel implements ChatModel, StreamingChatModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(FailoverChatModel.class);

    private final ChatModel primary;
    private final @Nullable StreamingChatModel primaryStream;
    private final ChatModel secondary;
    private final @Nullable StreamingChatModel secondaryStream;
    private final String secondaryModelName;

    public FailoverChatModel(
            @NonNull ChatModel primary, @NonNull ChatModel secondary, @NonNull String secondaryModelName) {
        this.primary = primary;
        this.primaryStream = primary instanceof StreamingChatModel streaming ? streaming : null;
        this.secondary = secondary;
        this.secondaryStream = secondary instanceof StreamingChatModel streaming ? streaming : null;
        this.secondaryModelName = secondaryModelName;
    }

    /** The wrapped primary model. */
    public @NonNull ChatModel primary() {
        return primary;
    }

    /** The secondary model's name, as sent in the failover request. */
    public @NonNull String secondaryModelName() {
        return secondaryModelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return primary.call(prompt);
        } catch (RuntimeException primaryFailure) {
            logFailover("call", primaryFailure);
            FallbackUsage.mark();
            return secondary.call(withSecondaryModel(prompt));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (primaryStream == null) {
            return Flux.error(new IllegalStateException("Primary chat model does not support streaming"));
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        return primaryStream.stream(prompt)
                .doOnNext(ignored -> emitted.set(true))
                .onErrorResume(RuntimeException.class, primaryFailure -> {
                    if (emitted.get() || secondaryStream == null) {
                        return Flux.error(primaryFailure);
                    }
                    // No FallbackUsage.mark() here: this runs on the thread delivering the error,
                    // not the request thread, so the flag would land on a stranger's request. The
                    // WARN line is the record for streams.
                    logFailover("stream", primaryFailure);
                    return secondaryStream.stream(withSecondaryModel(prompt));
                });
    }

    @Override
    public ChatOptions getOptions() {
        return primary.getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }

    private void logFailover(@NonNull String operation, @NonNull RuntimeException primaryFailure) {
        // The prompt is not logged: it carries the user's message and tool arguments.
        LOGGER.warn(
                "Primary model {} failed ({}: {}); failing over to {} (#1691)",
                operation,
                primaryFailure.getClass().getSimpleName(),
                primaryFailure.getMessage(),
                secondaryModelName);
    }

    /** The prompt with its options re-pointed at the secondary model; a prompt without options is sent as-is. */
    @NonNull
    Prompt withSecondaryModel(@NonNull Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            return prompt;
        }
        ChatOptions rewritten;
        if (options instanceof ToolCallingChatOptions toolCallingOptions) {
            rewritten = toolCallingOptions.mutate().model(secondaryModelName).build();
        } else {
            rewritten = ChatOptions.builder()
                    .model(secondaryModelName)
                    .temperature(options.getTemperature())
                    .topP(options.getTopP())
                    .topK(options.getTopK())
                    .maxTokens(options.getMaxTokens())
                    .frequencyPenalty(options.getFrequencyPenalty())
                    .presencePenalty(options.getPresencePenalty())
                    .stopSequences(options.getStopSequences())
                    .build();
        }
        return new Prompt(prompt.getInstructions(), rewritten);
    }
}
