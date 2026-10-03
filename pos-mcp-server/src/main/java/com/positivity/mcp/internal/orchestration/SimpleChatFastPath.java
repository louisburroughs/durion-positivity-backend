package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Shared Tier-0 (rule fast-path) support for both the blocking and the streaming session agent
 * managers (Gate 4, closing the Gate 2A divergence: the T0 short-circuit was previously
 * blocking-only). Classification is rule-based ({@link SimpleChatClassifier}) — no LLM and no tool
 * selection are involved; the reply prompt is the master system prompt plus the caller context.
 * Since ADR-0068 the decision is read from the turn's {@link QuestionTags} record.
 */
@Component
class SimpleChatFastPath {

    private final SimpleChatClassifier simpleChatClassifier;
    private final RolePromptResolver rolePromptResolver;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;

    SimpleChatFastPath(
            @NonNull SimpleChatClassifier simpleChatClassifier,
            @NonNull RolePromptResolver rolePromptResolver,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport) {
        this.simpleChatClassifier = simpleChatClassifier;
        this.rolePromptResolver = rolePromptResolver;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
    }

    /**
     * True when the message is pure social chat (greeting/thanks/capability) — the T0 rule path.
     * Pre-ADR-0068 shape: classifies the message directly. The managers pass the turn's tag record to
     * {@link #isSimpleChat(String, QuestionTags)} instead, so the classifier runs once per turn.
     */
    boolean isSimpleChat(@NonNull String message) {
        return simpleChatClassifier.isSimpleChat(message);
    }

    /**
     * ADR-0068 §1: the T0 decision read from the turn's {@code simple_chat} tag: its acting value is
     * the heuristic classifier's answer in {@code off} and {@code shadow}, and in {@code enforce} the
     * model's where the tag is listed and met its threshold (spec §2.6). An absent record ({@link
     * QuestionTags#none()}: warm-up, a caller that never tagged) behaves exactly as {@code off}: the
     * classifier decides.
     *
     * <p>Spec §2.6, the override: an enforced {@code follows_previous_turn} answered {@code true}
     * forces {@code false} whatever {@code simple_chat} says, because the T0 path answers without
     * conversation history. Only an enforced answer overrides: the heuristic cue check is already
     * folded into the heuristic {@code simple_chat}, and applying it again would change {@code shadow}
     * ("thanks again" is an exact catalog hit that also carries a cue).
     */
    boolean isSimpleChat(@NonNull String message, @NonNull QuestionTags tags) {
        if (tags.isNone()) {
            return simpleChatClassifier.isSimpleChat(message);
        }
        if (tags.enforced(TagName.FOLLOWS_PREVIOUS_TURN) && tags.followsPreviousTurn()) {
            return false;
        }
        return tags.simpleChat();
    }

    /**
     * The no-tool, no-RAG prompt answering a T0 message: master prompt + caller context + the
     * default IDENTIFIER layer (#2415), since the caller context carries the userId.
     */
    @NonNull
    Prompt prompt(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
        String systemPrompt = rolePromptResolver.resolvePrompt(SystemPromptDefaults.MASTER_PROMPT_NAME)
                + System.lineSeparator()
                + sharedOrchestrationSupport.formatUserContext(currentUserContext)
                + System.lineSeparator()
                + SystemPromptDefaults.IDENTIFIER_LAYER_TEXT;
        return new Prompt(new SystemMessage(systemPrompt), new UserMessage(message));
    }
}
