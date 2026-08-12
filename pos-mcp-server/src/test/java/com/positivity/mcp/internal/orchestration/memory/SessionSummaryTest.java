package com.positivity.mcp.internal.orchestration.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Unit tests for {@link SessionSummary} (Tier 3 cross-session memory).
 *
 * <p>
 * Everything here is best-effort by design: memory is an enhancement, and a
 * memory failure must never fail the chat turn it decorates. So every public
 * method has an explicit degradation pinned — summarization returns an empty
 * string on model failure, retrieval returns an empty list with no retriever or
 * a throwing one, and blank retrieved documents are filtered rather than
 * injected as empty context blocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SessionSummary — best-effort cross-session memory")
class SessionSummaryTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private QueryDocumentRetriever summaryRetriever;

    private SessionSummary summaryWithRetriever() {
        return new SessionSummary(chatModel, embeddingModel, null, summaryRetriever);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("summarizes the formatted conversation through the model")
    void summarizes() {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("User wanted X; concluded Y."));

        String summary = summaryWithRetriever()
                .summarize(List.of(new UserMessage("How do I do X?"), new AssistantMessage("Do Y.")));

        assertThat(summary).isEqualTo("User wanted X; concluded Y.");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        // Both sides of the conversation must reach the summarizer, labeled by role.
        assertThat(prompt.getValue().getContents()).contains("How do I do X?").contains("Do Y.");
    }

    @Test
    @DisplayName("returns an empty summary for an empty conversation, without a model call")
    void emptyConversationSkipsTheModel() {
        assertThat(summaryWithRetriever().summarize(List.of())).isEmpty();

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("returns an empty summary when the model fails, never an exception")
    void modelFailureDegrades() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));

        // Memory is an enhancement; its failure must not fail the chat turn it decorates.
        assertThat(summaryWithRetriever().summarize(List.of(new UserMessage("hello"))))
                .isEmpty();
    }

    @Test
    @DisplayName("retrieves cross-session context, filtering blanks and honoring topK")
    void retrievesContext() {
        when(summaryRetriever.retrieve("query"))
                .thenReturn(List.of(
                        new Document("relevant summary one"),
                        new Document(" "),
                        new Document("relevant summary two"),
                        new Document("relevant summary three")));

        List<String> context = summaryWithRetriever().retrieveRelevantContext("query", "user-1", 2);

        // Blank documents would inject empty context blocks; they are dropped before the limit.
        assertThat(context).containsExactly("relevant summary one", "relevant summary two");
    }

    @Test
    @DisplayName("returns no context when no retriever is wired, rather than failing")
    void noRetrieverMeansNoContext() {
        SessionSummary summary = new SessionSummary(chatModel, embeddingModel, null, null);

        assertThat(summary.retrieveRelevantContext("query", "user-1", 3)).isEmpty();
    }

    @Test
    @DisplayName("returns no context when the retriever throws")
    void retrieverFailureDegrades() {
        when(summaryRetriever.retrieve(anyString())).thenThrow(new IllegalStateException("store down"));

        assertThat(summaryWithRetriever().retrieveRelevantContext("query", "user-1", 3))
                .isEmpty();
    }

    @Test
    @DisplayName("clamps a nonsensical topK up to one result")
    void topKFloor() {
        when(summaryRetriever.retrieve("query")).thenReturn(List.of(new Document("one"), new Document("two")));

        assertThat(summaryWithRetriever().retrieveRelevantContext("query", "user-1", 0))
                .hasSize(1);
    }

    @Test
    @DisplayName("archiveSession and extractUserPreferences are safe no-ops today")
    void placeholders() {
        SessionSummary summary = summaryWithRetriever();

        // Documented placeholders: they must be callable without error so callers can wire them
        // now and the implementations can land later without a signature change.
        summary.archiveSession("session-1", "summary", List.of(new UserMessage("hi")));
        assertThat(summary.extractUserPreferences(List.of(new UserMessage("hi"))))
                .isEmpty();
        assertThat(summary.extractUserPreferences(List.of())).isEmpty();
    }
}
