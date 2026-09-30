package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * ADR-0069 §9 (shadow): notes which documents a turn's retrieval handed to the model, so the trace
 * can say how many of them the turn's scope contained.
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
            Set<String> documentIds = new LinkedHashSet<>();
            for (Document document : documents) {
                Object documentId = document.getMetadata().get(DOCUMENT_ID);
                if (documentId != null && !String.valueOf(documentId).isBlank()) {
                    documentIds.add(String.valueOf(documentId));
                }
            }
            toolInvocationRecorder.recordRetrievedDocuments(documentIds);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Could not record the retrieved documents for the scope trace: {}",
                    exception.getClass().getSimpleName());
        }
        return documents;
    }

    @Override
    public String toString() {
        return "ScopeRetrievalObserver{" + delegate + '}';
    }
}
