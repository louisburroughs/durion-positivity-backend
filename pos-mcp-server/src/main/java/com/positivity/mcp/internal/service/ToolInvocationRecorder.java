package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * #1422: records one {@code mcp_tool_invocation_log} row per individual tool execution, carrying a
 * real {@code tool_id}. Before this, only the chat managers logged — one request-level row per chat
 * with a hardcoded {@code null} toolId — so {@code ToolPriorityTuningService}'s stats query
 * ({@code WHERE tool_id IS NOT NULL}) matched nothing and the nightly tuner computed zero proposals
 * forever.
 *
 * <p>Two tool families resolve to different {@code mcp_tool.name} keys: a facade tool callback is
 * named after its {@code @Tool} <em>method</em> while the {@code mcp_tool} row is keyed by the
 * facade <em>class</em> simple name (e.g. {@code OrderFacadeTool}), so facade wrapping passes the
 * owning class name as the lookup key; discovered (source=openapi) callbacks are keyed by their
 * discovered tool name directly. An unresolvable name still logs (with a null id, WARN once) —
 * a visible gap beats a silent one.
 *
 * <p>Failure to record never fails the tool call: resolution and logging are both guarded, and
 * the underlying {@code ToolAuditService} already swallows write failures with a WARN.
 */
@Component
@Profile("!test")
public class ToolInvocationRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolInvocationRecorder.class);

    /**
     * Width of {@code mcp_tool_invocation_log.error_type} (V6). Overflowing it would fail the audit
     * insert, so a long cause message is truncated rather than allowed to break the write.
     */
    private static final int MAX_ERROR_TYPE_LENGTH = 200;

    /** Guards the cause walk against a self-referential or cyclic chain. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private final ToolAuditService auditService;
    private final ToolMetadataRepository toolMetadataRepository;
    private final RequestScopedUserContext userContext;
    private final Map<String, Optional<UUID>> toolIdCache = new ConcurrentHashMap<>();

    public ToolInvocationRecorder(
            @NonNull ToolAuditService auditService,
            @NonNull ToolMetadataRepository toolMetadataRepository,
            @NonNull RequestScopedUserContext userContext) {
        this.auditService = auditService;
        this.toolMetadataRepository = toolMetadataRepository;
        this.userContext = userContext;
    }

    /**
     * Wraps a tool callback so each {@code call} logs one invocation row. {@code toolLookupName} is
     * the {@code mcp_tool.name} key — the facade class simple name for facade tools, the discovered
     * tool name for openapi tools.
     */
    public @NonNull ToolCallback wrap(@NonNull ToolCallback delegate, @NonNull String toolLookupName) {
        return new RecordingToolCallback(delegate, toolLookupName);
    }

    /**
     * Records a tool execution that does not go through a {@link ToolCallback} — the NLTI confirmed
     * write-plan path executes discovered operations directly.
     */
    public void record(
            @NonNull String toolLookupName, boolean success, int executionTimeMs, @Nullable String errorType) {
        try {
            auditService.logToolExecution(
                    resolveToolId(toolLookupName), currentUsername(), success, false, executionTimeMs, errorType);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record tool invocation for '{}'", toolLookupName, exception);
        }
    }

    private @Nullable UUID resolveToolId(String toolLookupName) {
        Optional<UUID> resolved = toolIdCache.computeIfAbsent(toolLookupName, name -> {
            Optional<UUID> id;
            try {
                id = toolMetadataRepository.findToolIdByName(name);
            } catch (RuntimeException exception) {
                LOGGER.warn("mcp_tool id lookup failed for '{}'", name, exception);
                // Do not negative-cache a transient lookup failure.
                throw exception;
            }
            if (id.isEmpty()) {
                LOGGER.warn(
                        "No mcp_tool row named '{}'; its invocations will log with a null tool_id "
                                + "and stay invisible to priority tuning (#1422)",
                        name);
            }
            return id;
        });
        return resolved.orElse(null);
    }

    /**
     * The {@code error_type} value for a failed invocation (#1660).
     *
     * <p>Previously this was {@code exception.getClass().getSimpleName()}, which recorded
     * {@code IllegalStateException} for every reflective tool failure —
     * {@code ReflectiveToolCallback} wraps each one in that same type, so the stored value
     * identified nothing and gate q04's failure could not be diagnosed from the table at all. The
     * root cause is the part that differs between one failure and the next, so that is what is
     * kept, with its message, bounded to the column width.
     */
    static @NonNull String describeFailure(@NonNull Throwable thrown) {
        Throwable rootCause = thrown;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = rootCause.getCause();
            if (next == null || next == rootCause) {
                break;
            }
            rootCause = next;
        }
        String type = rootCause.getClass().getSimpleName();
        String message = rootCause.getMessage();
        String described = message == null || message.isBlank() ? type : type + ": " + message;
        return described.length() <= MAX_ERROR_TYPE_LENGTH
                ? described
                : described.substring(0, MAX_ERROR_TYPE_LENGTH - 3) + "...";
    }

    private @NonNull String currentUsername() {
        return userContext.current().map(CurrentUserContext::username).orElse("unknown");
    }

    private final class RecordingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String toolLookupName;

        private RecordingToolCallback(ToolCallback delegate, String toolLookupName) {
            this.delegate = delegate;
            this.toolLookupName = toolLookupName;
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
            long startNanos = System.nanoTime();
            try {
                String result = delegate.call(toolInput);
                record(toolLookupName, true, elapsedMs(startNanos), null);
                return result;
            } catch (RuntimeException exception) {
                int elapsedMs = elapsedMs(startNanos);
                record(toolLookupName, false, elapsedMs, describeFailure(exception));
                // The row alone cannot hold a stack, and nothing else on this path logged the
                // failure at all, so the throwable is emitted here with its whole cause chain.
                // The tool input is deliberately not logged: arguments carry customer identifiers.
                LOGGER.warn(
                        "MCP tool invocation failed tool={} caller={} elapsedMs={}",
                        toolLookupName,
                        currentUsername(),
                        elapsedMs,
                        exception);
                throw exception;
            }
        }

        private int elapsedMs(long startNanos) {
            return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }
    }
}
