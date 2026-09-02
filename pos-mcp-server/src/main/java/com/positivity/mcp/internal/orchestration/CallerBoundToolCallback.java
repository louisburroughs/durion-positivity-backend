package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Re-binds the calling user for the duration of a tool execution that may run off the request
 * thread.
 *
 * <p>{@code ToolCallingAdvisor.adviseStream} executes tools on
 * {@code Schedulers.boundedElastic()}, and {@code StreamingSessionAgentManager} clears the
 * request-scoped {@link RequestScopedUserContext} ThreadLocal in a {@code finally} on the assembly
 * thread before the first token is emitted. Anything that reads the caller at execution time — the
 * #1422 invocation recorder and the MDC correlation id — would therefore attribute every streamed
 * tool call to {@code unknown}.
 *
 * <p>The caller must be captured <strong>per request</strong>, which is why this wraps the assembled
 * callback list inside {@code chat(..)} rather than at callback-resolution time. Facade callbacks
 * are resolved once in the assistant's constructor and the agent is cached per role, so binding
 * there would stamp the first caller's identity onto every later caller's audit rows — a worse
 * failure than {@code unknown}.
 */
final class CallerBoundToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final RequestScopedUserContext userContext;
    private final CurrentUserContext caller;
    private final @Nullable String authHeader;

    private CallerBoundToolCallback(
            ToolCallback delegate,
            RequestScopedUserContext userContext,
            CurrentUserContext caller,
            @Nullable String authHeader) {
        this.delegate = delegate;
        this.userContext = userContext;
        this.caller = caller;
        this.authHeader = authHeader;
    }

    /**
     * Binds each callback to the caller currently on this thread. Returns {@code callbacks}
     * unchanged when no caller is bound, so a context-free path keeps its existing behaviour.
     */
    static @NonNull List<ToolCallback> bindCurrentCaller(
            @NonNull List<ToolCallback> callbacks, @Nullable RequestScopedUserContext userContext) {
        if (userContext == null || callbacks.isEmpty()) {
            return callbacks;
        }
        Optional<CurrentUserContext> caller = userContext.current();
        if (caller.isEmpty()) {
            return callbacks;
        }
        String authHeader = userContext.currentAuthHeader().orElse(null);
        return callbacks.stream()
                .map(callback ->
                        (ToolCallback) new CallerBoundToolCallback(callback, userContext, caller.get(), authHeader))
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
        return withCallerBound(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return withCallerBound(() -> delegate.call(toolInput, toolContext));
    }

    /**
     * Restores whatever was previously bound rather than clearing unconditionally: on the
     * non-streaming path the tool runs on the request thread, where {@code clear()} would also drop
     * the discovered-tool and write-capability signals that the session manager reads after the
     * turn.
     */
    private String withCallerBound(java.util.function.Supplier<String> execution) {
        Optional<CurrentUserContext> previous = userContext.current();
        Optional<String> previousAuthHeader = userContext.currentAuthHeader();
        userContext.set(caller, authHeader);
        try {
            return execution.get();
        } finally {
            previous.ifPresentOrElse(
                    context -> userContext.set(context, previousAuthHeader.orElse(null)), userContext::clear);
        }
    }
}
