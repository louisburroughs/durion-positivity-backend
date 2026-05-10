package com.positivity.mcp.internal.service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class DocumentEmbeddingIngestorTest {

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static <T> ArgumentCaptor<List<T>> listCaptor() {
                return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        }

        @Test
        void addsNormalizedRagScopeToSegmentMetadata() {
                Map<String, Object> metadata = Map.of("document_id", "inventory.stock", "rag_scope", " INVENTORY ");

                PgVectorEmbeddingStore embeddingStore = mock(PgVectorEmbeddingStore.class);
                EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
                when(embeddingModel.embedAll(anyList()))
                                .thenReturn(Response.from(List.of(Embedding.from(new float[] { 1.0f }))));
                DocumentEmbeddingIngestor ingestor = new DocumentEmbeddingIngestor(embeddingStore, embeddingModel,
                                false, 2000,
                                200);

                assertEquals(1, ingestor.ingestDocument("stock policy", metadata));

                ArgumentCaptor<List<TextSegment>> segmentCaptor = listCaptor();
                verify(embeddingStore).addAll(anyList(), segmentCaptor.capture());
                assertTrue(
                                segmentCaptor.getValue().stream()
                                                .allMatch(segment -> "inventory"
                                                                .equals(segment.metadata().getString("rag_scope"))));
        }

        @Test
        void replacesExistingDocumentWithinSameRagScopeOnly() {
                PgVectorEmbeddingStore embeddingStore = mock(PgVectorEmbeddingStore.class);
                EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
                when(embeddingModel.embedAll(anyList()))
                                .thenReturn(Response.from(List.of(Embedding.from(new float[] { 1.0f }))));
                DocumentEmbeddingIngestor ingestor = new DocumentEmbeddingIngestor(embeddingStore, embeddingModel,
                                false, 2000,
                                200);

                ingestor.ingestDocument("stock policy",
                                Map.of("document_id", "inventory.stock", "rag_scope", "inventory"));

                verify(embeddingStore)
                                .removeAll(new And(
                                                metadataKey("document_id").isEqualTo("inventory.stock"),
                                                metadataKey("rag_scope").isEqualTo("inventory")));
        }
}
