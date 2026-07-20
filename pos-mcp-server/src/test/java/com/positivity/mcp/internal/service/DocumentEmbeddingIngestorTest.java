package com.positivity.mcp.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

class DocumentEmbeddingIngestorTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @Test
    void addsNormalizedRagScopeToSegmentMetadata() {
        Map<String, Object> metadata = Map.of("document_id", "inventory.stock", "rag_scope", " INVENTORY ");

                PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
                when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {1.0f}));
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, false, 2000, 200);

        assertEquals(1, ingestor.ingestDocument("stock policy", metadata));

        ArgumentCaptor<List<Document>> segmentCaptor = listCaptor();
        verify(embeddingStore).add(segmentCaptor.capture());
        assertTrue(segmentCaptor.getValue().stream()
                .allMatch(segment -> "inventory".equals(String.valueOf(segment.getMetadata().get("rag_scope")))));
    }

    @Test
    void replacesExistingDocumentWithinSameRagScopeOnly() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {1.0f}));
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, false, 2000, 200);

        ingestor.ingestDocument("stock policy", Map.of("document_id", "inventory.stock", "rag_scope", "inventory"));

        verify(embeddingStore)
                .delete(new FilterExpressionBuilder()
                        .and(
                                new FilterExpressionBuilder().eq("document_id", "inventory.stock"),
                                new FilterExpressionBuilder().eq("rag_scope", "inventory"))
                        .build());
    }
}
