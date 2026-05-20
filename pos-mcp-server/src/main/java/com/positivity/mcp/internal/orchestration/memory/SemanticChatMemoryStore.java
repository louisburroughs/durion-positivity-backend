package com.positivity.mcp.internal.orchestration.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier 3: Persistent semantic chat memory with session summaries and retrieval.
 *
 * <p>
 * Replaces MessageWindowChatMemory by:
 * <ul>
 * <li>Storing all messages in Postgres with optional embeddings (lazy)
 * <li>Generating session summaries on-demand or at session boundaries
 * <li>Retrieving relevant historical context via semantic similarity
 * <li>Supporting long-term user preference extraction
 * </ul>
 *
 * <p>
 * Architecture:
 * <ul>
 * <li>In-memory window: Fixed maxMessages (fast path, same as before)
 * <li>Persistent storage: All messages persisted to Postgres for archival +
 * replay
 * <li>Semantic index: Lazy-computed embeddings for cross-session retrieval
 * <li>Session lifecycle: Hooks for onSessionStart / onSessionEnd with
 * summarization
 * </ul>
 *
 * <p>
 * For now, we maintain backward compatibility with in-memory window + store
 * references to messages.
 * A full implementation would replace this with a true persistent layer.
 */
public class SemanticChatMemoryStore implements ChatMemory {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticChatMemoryStore.class);

    private final int maxMessages;
    private final @NonNull ChatModel chatModel;
    private final @NonNull EmbeddingModel embeddingModel;
    private final @NonNull PgVectorEmbeddingStore embeddingStore;
    private final @Nullable SessionSummary sessionSummary;
    private final String sessionId;
    private final List<ChatMessage> messages;
    private final Map<String, Object> sessionMetadata;
    private volatile boolean shouldGenerateSummaryOnClose;

    public SemanticChatMemoryStore(
            int maxMessages,
            @NonNull ChatModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @Nullable SessionSummary sessionSummary) {
        this.maxMessages = Math.max(1, maxMessages);
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.sessionSummary = sessionSummary;
        this.sessionId = UUID.randomUUID().toString();
        this.messages = Collections.synchronizedList(new ArrayList<>());
        this.sessionMetadata = new HashMap<>();
        this.shouldGenerateSummaryOnClose = true;
        LOGGER.debug("Created SemanticChatMemoryStore sessionId={} maxMessages={}", sessionId, maxMessages);
    }

    @Override
    public String id() {
        return sessionId;
    }

    @Override
    public void add(@NonNull ChatMessage message) {
        synchronized (messages) {
            messages.add(message);

            // Maintain window size (Tier 3: backward compat with message window)
            if (messages.size() > maxMessages) {
                messages.remove(0);
            }

            String messageType = message.getClass().getSimpleName();
            LOGGER.trace(
                    "Added to semantic memory sessionId={} type={} messageCount={} windowSize={}",
                    sessionId,
                    messageType,
                    messages.size(),
                    maxMessages);
        }
    }

    @Override
    public @NonNull List<ChatMessage> messages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public void clear() {
        LOGGER.debug("Clearing SemanticChatMemoryStore sessionId={} messageCount={}", sessionId, messages.size());
        messages.clear();
        sessionMetadata.clear();
    }

    /**
     * Generates a session summary using the LLM. Called at session close or
     * on-demand.
     *
     * <p>
     * Tier 3: Semantic memory + summarization for long-term context.
     *
     * @return Summary text, or empty string if summarization fails
     */
    @NonNull
    public String generateSummary() {
        if (messages.isEmpty()) {
            return "";
        }
        try {
            if (sessionSummary == null) {
                LOGGER.debug(
                        "SessionSummary not available; skipping summary generation sessionId={}", sessionId);
                return "";
            }
            String summary = sessionSummary.summarize(messages);
            LOGGER.info("Generated session summary sessionId={} summaryLength={}", sessionId, summary.length());
            return summary;
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Failed to generate session summary sessionId={} error={}",
                    sessionId,
                    e.getClass().getSimpleName(),
                    e);
            return "";
        }
    }

    /**
     * Archives the session and generates summary if enabled. Should be called when
     * session closes (user logs out, timeout, etc.).
     *
     * <p>
     * Tier 3: Lifecycle hook for semantic archival.
     */
    public void archiveSession() {
        if (!shouldGenerateSummaryOnClose) {
            LOGGER.debug("Session archive disabled for sessionId={}", sessionId);
            return;
        }
        try {
            if (sessionSummary == null) {
                LOGGER.debug("SessionSummary not available; skipping archive for sessionId={}", sessionId);
                return;
            }
            String summary = generateSummary();
            if (!summary.isEmpty()) {
                sessionSummary.archiveSession(sessionId, summary, messages);
                LOGGER.info("Archived session with summary sessionId={}", sessionId);
            }
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Failed to archive session sessionId={} error={}",
                    sessionId,
                    e.getClass().getSimpleName(),
                    e);
        }
    }

    /**
     * Retrieves semantically relevant context from historical sessions.
     *
     * <p>
     * Tier 3: Cross-session semantic context retrieval.
     *
     * @param query  User query or context
     * @param userId User identifier
     * @return Historical messages or summaries matching the query
     */
    @NonNull
    public List<String> retrieveRelevantContext(@NonNull String query, @NonNull String userId) {
        if (sessionSummary == null) {
            return Collections.emptyList();
        }
        try {
            List<String> context = sessionSummary.retrieveRelevantContext(query, userId, 3);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Retrieved relevant context userId={} queryPreview=\"{}\" contextCount={}",
                        userId,
                        query.substring(0, Math.min(50, query.length())),
                        context.size());
            }
            return context;
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
     * Sets metadata for the session (e.g., user preferences, intent
     * classification).
     *
     * @param key   Metadata key
     * @param value Metadata value
     */
    public void setMetadata(@NonNull String key, @Nullable Object value) {
        sessionMetadata.put(key, value);
        LOGGER.trace("Set session metadata sessionId={} key={}", sessionId, key);
    }

    /**
     * Gets session metadata.
     *
     * @param key Metadata key
     * @return Metadata value or null
     */
    @Nullable
    public Object getMetadata(@NonNull String key) {
        return sessionMetadata.get(key);
    }

    /**
     * Returns the session identifier.
     *
     * @return Session UUID
     */
    @NonNull
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Controls whether to generate a summary when archiveSession() is called.
     *
     * @param shouldGenerate If true, generateSummary() is called on close
     */
    public void setShouldGenerateSummaryOnClose(boolean shouldGenerate) {
        this.shouldGenerateSummaryOnClose = shouldGenerate;
    }

    @Override
    public String toString() {
        return "SemanticChatMemoryStore{" + "sessionId='" + sessionId + '\'' + ", messages=" + messages.size()
                + ", maxMessages=" + maxMessages + '}';
    }
}
