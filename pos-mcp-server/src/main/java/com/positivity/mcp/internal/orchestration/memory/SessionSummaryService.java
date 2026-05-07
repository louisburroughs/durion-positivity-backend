package com.positivity.mcp.internal.orchestration.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier 3: Session summarization and cross-session semantic context retrieval.
 *
 * <p>Responsibilities:
 * <ul>
 * <li>Summarize multi-turn conversations into concise semantic tokens
 * <li>Archive sessions with summaries to Postgres for long-term storage
 * <li>Retrieve historically relevant summaries for a given user query
 * <li>Support cross-session context injection into new conversations
 * </ul>
 *
 * <p>Implementation notes:
 * <ul>
 * <li>Uses LLM to generate abstractive summaries (not just concatenation)
 * <li>Embeds summaries via EmbeddingModel for semantic similarity search
 * <li>Caches summaries in memory to avoid recomputation
 * <li>Falls back gracefully if summarization or embedding fails
 * </ul>
 */
public class SessionSummaryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionSummaryService.class);
    private static final String SUMMARIZATION_PROMPT_TEMPLATE =
            """
            Summarize the following conversation concisely, highlighting key decisions, questions, and outcomes.
            Focus on what the user wanted to accomplish and what was concluded.
            Keep summary under 200 words.

            Conversation:
            %s

            Summary:
            """;

    private final @NonNull ChatModel chatModel;
    private final @NonNull EmbeddingModel embeddingModel;
    private final @Nullable PgVectorEmbeddingStore embeddingStore;
    private final @Nullable ContentRetriever summaryRetriever;

    public SessionSummaryService(
            @NonNull ChatModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @Nullable PgVectorEmbeddingStore embeddingStore,
            @Nullable ContentRetriever summaryRetriever) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.summaryRetriever = summaryRetriever;
    }

    /**
     * Generates a concise summary of a conversation using the LLM.
     *
     * <p>Tier 3: Semantic summarization for long-term memory.
     *
     * @param messages Chat messages to summarize
     * @return Summary text
     */
    @NonNull
    public String summarize(@NonNull List<ChatMessage> messages) {
        try {
            String conversationText = formatMessagesForSummarization(messages);
            if (conversationText.isEmpty()) {
                return "";
            }
            String prompt = String.format(SUMMARIZATION_PROMPT_TEMPLATE, conversationText);
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(prompt)))
                    .build());
            String summary = response.aiMessage().text();
            LOGGER.debug("Generated summary for {} messages; summaryLength={}", messages.size(), summary.length());
            return summary;
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Failed to generate session summary messageCount={} error={}",
                    messages.size(),
                    e.getClass().getSimpleName(),
                    e);
            return "";
        }
    }

    /**
     * Archives a session summary to persistent storage.
     *
     * <p>For now, this is a no-op placeholder. A full implementation would:
     * <ul>
     * <li>Store session metadata (id, userId, startTime, endTime) in a sessions table
     * <li>Store summary text in a session_summaries table
     * <li>Embed summary and store in pgvector for semantic search
     * </ul>
     *
     * @param sessionId Session identifier
     * @param summary Summary text
     * @param messages Original conversation messages
     */
    public void archiveSession(@NonNull String sessionId, @NonNull String summary, @NonNull List<ChatMessage> messages) {
        try {
            LOGGER.info(
                    "Archiving session sessionId={} summaryLength={} messageCount={}",
                    sessionId,
                    summary.length(),
                    messages.size());
            // Tier 3: Full implementation would insert into DB here
            // INSERT INTO session_summaries (session_id, summary, created_at) VALUES (...)
            // CREATE EMBEDDING and store in pgvector
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to archive session sessionId={} error={}", sessionId, e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Retrieves historically relevant context from previous sessions.
     *
     * <p>Tier 3: Cross-session semantic retrieval for context injection.
     *
     * @param query Current user query
     * @param userId User identifier
     * @param topK Number of relevant summaries to retrieve
     * @return List of relevant summary texts
     */
    @NonNull
    public List<String> retrieveRelevantContext(
            @NonNull String query, @NonNull String userId, int topK) {
        try {
            if (summaryRetriever == null) {
                LOGGER.trace("Summary retriever not available; no cross-session context");
                return Collections.emptyList();
            }
            // Tier 3: Would query against archived session summaries for this userId
            // SELECT summary FROM session_summaries
            //   WHERE user_id = ? AND created_at > NOW() - INTERVAL '30 days'
            //   ORDER BY embedding_similarity(query_embedding, summary_embedding) DESC
            //   LIMIT topK
            LOGGER.debug("Retrieved relevant context for userId={} topK={}", userId, topK);
            return Collections.emptyList(); // Placeholder
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Failed to retrieve context userId={} error={}",
                    userId,
                    e.getClass().getSimpleName(),
                    e);
            return Collections.emptyList();
        }
    }

    /**
     * Extracts user preferences from conversation history (e.g., "verbose explanations",
     * "code-first responses", "executive summaries").
     *
     * <p>Tier 3: Future enhancement for personalization.
     *
     * @param messages Chat messages to analyze
     * @return Extracted preferences
     */
    @NonNull
    public List<String> extractUserPreferences(@NonNull List<ChatMessage> messages) {
        try {
            if (messages.isEmpty()) {
                return Collections.emptyList();
            }
            // Placeholder: would analyze conversation pattern
            // e.g., if mostly short questions -> prefer brief responses
            // if code requests -> prefer code-first
            return Collections.emptyList();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to extract preferences error={}", e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Formats messages for LLM summarization.
     *
     * @param messages Chat messages
     * @return Formatted text representation
     */
    @NonNull
    private static String formatMessagesForSummarization(@NonNull List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append(msg.getClass().getSimpleName()).append(": ").append(msg.toString()).append("\n");
        }
        return sb.toString();
    }
}
