package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.domain.ScopeTrace;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * ADR-0069 §9 (shadow): notes which documents a turn's retrieval handed to the model, in rank
 * order with each chunk's {@code rag_scope}, so the trace can say how many of them the turn's scope
 * contained and the §9 gate can replay the {@code rag} filter rule offline against the fixtures.
 *
 * <p>It sits after the final top-K cut and <strong>returns the delegate's list untouched</strong>:
 * same instance, same order, nothing added or dropped. It observes only when a scope was published
 * for the request, so with {@code mcp.scope-graph.mode: off} it is a plain call-through. A failure
 * to record is logged and never reaches retrieval.
 */
final class ScopeRetrievalObserver implements QueryDocumentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeRetrievalObserver.class);

    /** The chunk metadata key {@code DocumentEmbeddingIngestor} writes the owning document's id under. */
    static final String DOCUMENT_ID = "document_id";

    /** The chunk metadata key the ingestors write the document's {@code rag_scope} under. */
    static final String RAG_SCOPE = "rag_scope";

    private final QueryDocumentRetriever delegate;
    private final @Nullable RequestScopedUserContext requestScopedUserContext;
    private final @Nullable ToolInvocationRecorder toolInvocationRecorder;

    ScopeRetrievalObserver(
            @NonNull QueryDocumentRetriever delegate,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable ToolInvocationRecorder toolInvocationRecorder) {
        this.delegate = delegate;
        this.requestScopedUserContext = requestScopedUserContext;
        this.toolInvocationRecorder = toolInvocationRecorder;
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<Document> documents = delegate.retrieve(queryText);
        if (requestScopedUserContext == null
                || toolInvocationRecorder == null
                || requestScopedUserContext.currentScope().isEmpty()) {
            return documents;
        }
        try {
            toolInvocationRecorder.recordRetrievedDocuments(distinctInRankOrder(documents));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Could not record the retrieved documents for the scope trace: {}",
                    exception.getClass().getSimpleName());
        }
        return documents;
    }

    /**
     * One entry per {@code document_id}, in the order the documents were handed to the model; the
     * chunks of one document collapse to the first (highest-ranked) one. A chunk without a document
     * id is not a document and is skipped.
     */
    static @NonNull List<ScopeTrace.RetrievedDocument> distinctInRankOrder(@NonNull List<Document> documents) {
        Map<String, ScopeTrace.RetrievedDocument> byId = new LinkedHashMap<>();
        for (Document document : documents) {
            String documentId = metadata(document, DOCUMENT_ID);
            if (documentId != null) {
                byId.putIfAbsent(
                        documentId, new ScopeTrace.RetrievedDocument(documentId, metadata(document, RAG_SCOPE)));
            }
        }
        return List.copyOf(byId.values());
    }

    private static @Nullable String metadata(@NonNull Document document, @NonNull String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @Override
    public String toString() {
        return "ScopeRetrievalObserver{" + delegate + '}';
    }
}
