package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Unit tests for {@link ToolResultRanker} (Tier 3 result re-ranking).
 *
 * <p>
 * This sits between tool output and the LLM's context window, so its failure
 * modes decide what the model sees. Three properties carry the design:
 *
 * <ul>
 * <li><b>Small result sets skip the LLM entirely.</b> Scoring costs one model
 * call per result; when everything already fits in top-k there is nothing to
 * rank and the calls would be pure latency.</li>
 * <li><b>Ranking failure degrades to truncation, never to an error.</b> A chat
 * turn must not die because the scoring model hiccuped; the fallback keeps the
 * first top-k in original order.</li>
 * <li><b>An unparseable or failed score is 0.0, not a retry</b> — the result
 * drops to the bottom rather than blocking the batch, and ties break by
 * original position so equal scores preserve the tool's own ordering.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultRanker — LLM re-ranking with graceful degradation")
class ToolResultRankerTest {

    @Mock
    private ChatModel chatModel;

    private static ChatResponse score(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("returns small result sets untouched, without calling the model")
    void smallSetsSkipTheModel() {
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 5);
        List<String> results = List.of("a", "b", "c");

        assertThat(ranker.rankResults("inventory", "query", results)).isSameAs(results);
        assertThat(ranker.rankResults("inventory", "query", List.of())).isEmpty();
        // Every score is a model call; ranking three results into a top-5 buys nothing.
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("keeps the top-k results by score, best first")
    void ranksByScore() {
        when(chatModel.call(any(Prompt.class))).thenReturn(score("0.2"), score("0.9"), score("0.5"), score("0.7"));
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 2);

        assertThat(ranker.rankResults("inventory", "query", List.of("low", "best", "mid", "good")))
                .containsExactly("best", "good");
    }

    @Test
    @DisplayName("breaks score ties by the tool's original ordering")
    void tiesPreserveOriginalOrder() {
        when(chatModel.call(any(Prompt.class))).thenReturn(score("0.5"), score("0.5"), score("0.5"));
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 2);

        // With equal scores, the tool's own ordering is the only signal left; reshuffling it
        // would make ranking output nondeterministic run to run.
        assertThat(ranker.rankResults("inventory", "query", List.of("first", "second", "third")))
                .containsExactly("first", "second");
    }

    @Test
    @DisplayName("scores an unparseable or out-of-range answer as 0 and clamps valid ones")
    void scoreParsing() {
        when(chatModel.call(any(Prompt.class))).thenReturn(score("not-a-number"), score("42"), score("0.8"));
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 2);

        // "42" clamps to 1.0 and wins; "not-a-number" parses to 0.0 and drops off.
        assertThat(ranker.rankResults("inventory", "query", List.of("garbled", "clamped", "normal")))
                .containsExactly("clamped", "normal");
    }

    @Test
    @DisplayName("scores a result whose model call throws as 0 instead of failing the batch")
    void singleScoringFailureDropsThatResult() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(score("0.9"))
                .thenThrow(new IllegalStateException("model busy"))
                .thenReturn(score("0.7"));
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 2);

        assertThat(ranker.rankResults("inventory", "query", List.of("best", "failed", "good")))
                .containsExactly("best", "good");
    }

    @Test
    @DisplayName("clamps a nonsensical topK up to 1 rather than returning nothing")
    void topKFloor() {
        ToolResultRanker ranker = new ToolResultRanker(chatModel, 0);
        when(chatModel.call(any(Prompt.class))).thenReturn(score("0.9"), score("0.1"));

        assertThat(ranker.rankResults("inventory", "query", List.of("a", "b"))).hasSize(1);
    }
}
