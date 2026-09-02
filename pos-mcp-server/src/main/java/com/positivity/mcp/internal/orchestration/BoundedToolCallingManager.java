package com.positivity.mcp.internal.orchestration;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Caps how many tool round trips a single chat turn may perform.
 *
 * <p>{@code ToolCallingAdvisor} loops {@code do { call model } while (isToolCall)} with no iteration
 * limit, and the streaming variant recurses with no depth limit. The configured Ollama timeout
 * bounds one model call, not the loop. While {@code ChatModel.call} silently discarded tool calls
 * this could not bite — every turn was exactly one model call — but once tools actually execute a
 * model that keeps requesting them would burn unbounded model calls against the per-question cost
 * budgets, holding a servlet thread for the duration on the non-streaming path.
 *
 * <p>At the cap the tool results already gathered are returned directly instead of being sent back
 * for another round, which ends the loop without discarding work.
 */
final class BoundedToolCallingManager implements ToolCallingManager {

    /**
     * Enough for a compose-then-summarise chain plus headroom; well under any budget in the
     * analytics capability plan.
     */
    static final int MAX_TOOL_TURNS = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedToolCallingManager.class);

    private final ToolCallingManager delegate;

    BoundedToolCallingManager(@NonNull ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
        if (result.returnDirect()) {
            return result;
        }
        int completedTurns = completedToolTurns(prompt);
        if (completedTurns + 1 < MAX_TOOL_TURNS) {
            return result;
        }
        LOGGER.warn(
                "Chat turn hit the tool round-trip cap ({}); returning the tool results gathered so far "
                        + "instead of continuing the loop",
                MAX_TOOL_TURNS);
        return DefaultToolExecutionResult.builder()
                .conversationHistory(result.conversationHistory())
                .returnDirect(true)
                .build();
    }

    /** Each completed round trip leaves exactly one {@link ToolResponseMessage} in the history. */
    private static int completedToolTurns(Prompt prompt) {
        return (int) prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .count();
    }
}
