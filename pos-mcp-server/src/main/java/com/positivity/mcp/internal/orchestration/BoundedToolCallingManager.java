package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Caps how many tool round trips a single chat turn may perform, and answers a call to a tool that
 * was never offered instead of letting it end the turn.
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
 *
 * <p>Unknown tool names (#1831): {@code DefaultToolCallingManager} throws {@code
 * IllegalStateException} when the model names a tool that is not among the prompt's callbacks, before
 * any exception processor runs, so the whole turn fell to the answer ladder. A wrong tool name is the
 * same kind of model slip as a wrong argument (#1711, #1829) and gets the same treatment here: the
 * offered calls execute through the delegate, each unknown call is answered with a tool response that
 * says so, and the model continues from that history. A model that keeps inventing names still runs
 * into the round-trip cap, because the synthesized response counts as a completed round; if that last
 * round produced no real result the turn degrades to the ladder rather than showing the correction to
 * the user as the answer.
 */
final class BoundedToolCallingManager implements ToolCallingManager {

    /**
     * Enough for a compose-then-summarise chain plus headroom; well under any budget in the
     * analytics capability plan.
     */
    static final int MAX_TOOL_TURNS = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedToolCallingManager.class);

    private final ToolCallingManager delegate;
    private final @Nullable ToolInvocationRecorder invocationRecorder;

    BoundedToolCallingManager(@NonNull ToolCallingManager delegate) {
        this(delegate, null);
    }

    BoundedToolCallingManager(
            @NonNull ToolCallingManager delegate, @Nullable ToolInvocationRecorder invocationRecorder) {
        this.delegate = delegate;
        this.invocationRecorder = invocationRecorder;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Round round = executeAnsweringUnknownTools(prompt, chatResponse);
        ToolExecutionResult result = round.result();
        if (result.returnDirect()) {
            return result;
        }
        int completedTurns = completedToolTurns(prompt);
        if (completedTurns + 1 < MAX_TOOL_TURNS) {
            return result;
        }
        if (round.unknownCount() > 0 && round.executed().isEmpty()) {
            // Nothing in this round is a result: returning it directly would hand the model's
            // correction to the user as the answer. Degrade to the ladder instead.
            throw new UnknownToolLoopException(MAX_TOOL_TURNS);
        }
        LOGGER.warn(
                "Chat turn hit the tool round-trip cap ({}); returning the tool results gathered so far "
                        + "instead of continuing the loop",
                MAX_TOOL_TURNS);
        List<Message> history = result.conversationHistory();
        if (round.unknownCount() > 0) {
            // Direct return renders the last round's responses as the answer; keep only the ones a
            // tool actually produced.
            history = new ArrayList<>(history.subList(0, history.size() - 1));
            history.add(
                    ToolResponseMessage.builder().responses(round.executed()).build());
        }
        return DefaultToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(true)
                .build();
    }

    /**
     * Runs the offered tool calls through the delegate and answers the rest with a correction.
     *
     * <p>Mirrors {@code DefaultToolCallingManager}: the first generation carrying tool calls is the
     * one executed, and the history it returns is the prompt, the assistant message and one {@link
     * ToolResponseMessage} holding a response per call in call order.
     */
    private Round executeAnsweringUnknownTools(Prompt prompt, ChatResponse chatResponse) {
        Optional<Generation> toolCallGeneration = chatResponse.getResults().stream()
                .filter(generation -> !generation.getOutput().getToolCalls().isEmpty())
                .findFirst();
        if (toolCallGeneration.isEmpty()) {
            return Round.delegated(delegate.executeToolCalls(prompt, chatResponse));
        }
        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();
        Set<String> offered = offeredToolNames(prompt);
        List<AssistantMessage.ToolCall> unknownCalls = assistantMessage.getToolCalls().stream()
                .filter(toolCall -> !offered.contains(toolCall.name()))
                .toList();
        if (unknownCalls.isEmpty()) {
            return Round.delegated(delegate.executeToolCalls(prompt, chatResponse));
        }
        List<String> unknownNames =
                unknownCalls.stream().map(AssistantMessage.ToolCall::name).toList();
        LOGGER.warn(
                "Model called unknown tool(s) {} with {} tools offered; answering the call with a correction "
                        + "instead of failing the turn (#1831)",
                unknownNames,
                offered.size());

        List<AssistantMessage.ToolCall> knownCalls = assistantMessage.getToolCalls().stream()
                .filter(toolCall -> offered.contains(toolCall.name()))
                .toList();
        boolean returnDirect = false;
        Iterator<ToolResponseMessage.ToolResponse> knownResponses =
                List.<ToolResponseMessage.ToolResponse>of().iterator();
        if (!knownCalls.isEmpty()) {
            AssistantMessage knownOnly = AssistantMessage.builder()
                    .content(assistantMessage.getText())
                    .properties(assistantMessage.getMetadata())
                    .toolCalls(knownCalls)
                    .build();
            ChatResponse knownOnlyResponse = new ChatResponse(
                    List.of(new Generation(knownOnly, toolCallGeneration.get().getMetadata())),
                    chatResponse.getMetadata());
            ToolExecutionResult knownResult = delegate.executeToolCalls(prompt, knownOnlyResponse);
            returnDirect = knownResult.returnDirect();
            knownResponses = lastToolResponses(knownResult).iterator();
        }

        List<ToolResponseMessage.ToolResponse> responses =
                new ArrayList<>(assistantMessage.getToolCalls().size());
        List<ToolResponseMessage.ToolResponse> executed = new ArrayList<>(knownCalls.size());
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (offered.contains(toolCall.name()) && knownResponses.hasNext()) {
                ToolResponseMessage.ToolResponse executedResponse = knownResponses.next();
                executed.add(executedResponse);
                responses.add(executedResponse);
            } else {
                String correction = unknownToolMessage(toolCall.name(), offered.size());
                recordUnknownCall(toolCall, correction);
                responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), correction));
            }
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(ToolResponseMessage.builder().responses(responses).build());
        ToolExecutionResult result = DefaultToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(returnDirect)
                .build();
        return new Round(result, List.copyOf(executed), unknownCalls.size());
    }

    static @NonNull String unknownToolMessage(@NonNull String toolName, int offeredCount) {
        return "Unknown tool '" + toolName + "': it is not one of the " + offeredCount
                + " tools offered in this turn. Call an offered tool by its exact name, or answer from the "
                + "results you already have.";
    }

    /** The tool names the model was allowed to call: the callbacks carried in the prompt options. */
    private static Set<String> offeredToolNames(Prompt prompt) {
        Set<String> names = new LinkedHashSet<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                names.add(callback.getToolDefinition().name());
            }
        }
        return names;
    }

    private static List<ToolResponseMessage.ToolResponse> lastToolResponses(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        if (!history.isEmpty() && history.getLast() instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses();
        }
        return List.of();
    }

    private void recordUnknownCall(AssistantMessage.ToolCall toolCall, String correction) {
        if (invocationRecorder != null) {
            invocationRecorder.recordUnknownToolCall(toolCall.name(), toolCall.arguments(), correction);
        }
    }

    /** Each completed round trip leaves exactly one {@link ToolResponseMessage} in the history. */
    private static int completedToolTurns(Prompt prompt) {
        return (int) prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .count();
    }

    /** One tool round: its result, the responses real tools produced, and how many calls were unknown. */
    private record Round(
            ToolExecutionResult result, List<ToolResponseMessage.ToolResponse> executed, int unknownCount) {
        static Round delegated(ToolExecutionResult result) {
            return new Round(result, lastToolResponses(result), 0);
        }
    }

    /**
     * The model named tools it was not offered on every round until the cap. Caught by the chat turn
     * and degraded to the answer ladder; there is no result to show the user.
     */
    static final class UnknownToolLoopException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownToolLoopException(int rounds) {
            super("Model kept calling tools it was not offered for " + rounds + " rounds; giving up on this turn");
        }
    }
}
