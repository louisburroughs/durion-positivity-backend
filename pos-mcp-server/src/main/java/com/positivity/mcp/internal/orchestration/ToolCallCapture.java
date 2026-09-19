package com.positivity.mcp.internal.orchestration;

import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Reports the name of every tool the model actually calls in one chat turn (#2075, the turn summary's
 * {@code tools_called}).
 *
 * <p>Applied per call by {@link SpringAiPosAssistant}, around the request-bound callbacks, so it works
 * in every profile: the alpha-only {@code ToolInvocationRecorder} cannot be used for a summary that
 * every environment stores. The name ({@code getToolDefinition().name()}, the same key as the eval
 * trace's tool calls) is reported <em>before</em> delegating, so a call that throws still counts. A
 * name the model invents is never matched to a callback (#1831), so it never reaches this wrapper and
 * is not reported.
 *
 * <p>The sink is captured by closure rather than a thread-local, so a tool executed on another thread
 * of the tool-calling loop still reports into the turn that offered it; the sink itself must therefore
 * be thread-safe. A failing sink is logged and swallowed: capture never fails a tool call.
 */
final class ToolCallCapture implements ToolCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallCapture.class);

    private final ToolCallback delegate;
    private final Consumer<String> sink;

    private ToolCallCapture(@NonNull ToolCallback delegate, @NonNull Consumer<String> sink) {
        this.delegate = delegate;
        this.sink = sink;
    }

    /**
     * Wraps each callback so its calls are reported to {@code sink}; an empty list is returned as-is.
     */
    static @NonNull List<ToolCallback> wrap(@NonNull List<ToolCallback> callbacks, @NonNull Consumer<String> sink) {
        if (callbacks.isEmpty()) {
            return callbacks;
        }
        return callbacks.stream()
                .map(callback -> (ToolCallback) new ToolCallCapture(callback, sink))
                .toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        report();
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        report();
        return delegate.call(toolInput, toolContext);
    }

    private void report() {
        try {
            sink.accept(delegate.getToolDefinition().name());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to capture a tool call for the turn summary; the call proceeds", exception);
        }
    }
}
