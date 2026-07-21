package com.positivity.mcp.internal.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

/**
 * Unit tests for {@link DocumentEmbeddingIngestor}.
 *
 * <p>
 * The implementation is {@code @Profile("!test")} and is instantiated
 * directly. The public
 * The ingestor is instantiated directly so the slow embedding/store mechanics
 * stay covered separately from asynchronous job orchestration.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock
    private PgVectorStore embeddingStore;

    @Mock
    private EmbeddingModel embeddingModel;

    private DocumentEmbeddingIngestor service;

    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        service = new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, false, 2000, 200);
    }

    @Test
    @DisplayName("ingestDocument embeds and stores a single segment when chunking is disabled")
    void ingestDocument_callsEmbeddingModelAndStore() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("some text", Map.of("source", "test"));

        verify(embeddingModel, times(1))
            .embed(argThat((List<String> segments) ->
                segments.size() == 1 && segments.get(0).equals("some text")));
        verify(embeddingStore, times(1)).add(any());
    }

    @Test
    @DisplayName("ingestDocument with empty metadata does not throw")
    void ingestDocument_withEmptyMetadata_usesEmptyMap() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("content", Map.of());

        verify(embeddingStore, times(1)).add(any());
    }

    @Test
    @DisplayName("ingestDocuments calls embeddingStore.addAll once per document")
    void ingestDocuments_batchCallsStoreForEachItem() {
        mockEmbeddingsForAnySegments();

        service.ingestDocuments(
                List.of("doc0", "doc1", "doc2"), List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

        verify(embeddingStore, times(3)).add(any());
    }

    @Test
    @DisplayName("ingestDocuments continues processing remaining docs when one embed fails")
    void ingestDocuments_singleFailure_doesNotAbortBatch() {
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any())).thenAnswer(invocation -> {
            List<String> segments = invocation.getArgument(0);
            if (segments.get(0).equals("doc0")) {
                throw new RuntimeException("embed failed");
            }
            return embeddingsFor(segments);
        });

        service.ingestDocuments(
                List.of("doc0", "doc1", "doc2"), List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

        // doc0 failed, so store should be called only twice
        verify(embeddingStore, times(2)).add(any());
        verify(embeddingStore, never()).add(argThat(segments -> containsText(segments, "doc0")));
    }

    @Test
    @DisplayName("ingestDocuments throws IllegalArgumentException when contents and metadataList sizes differ")
    void ingestDocuments_mismatchedSizes_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.ingestDocuments(List.of("doc0", "doc1"), List.of(Map.of("i", 0))));

        verify(embeddingStore, never()).add(any());
    }

    @Test
    @DisplayName("ingestDocument chunks long content and enriches each chunk with document metadata")
    void ingestDocument_chunkingEnabled_splitsAndEnrichesMetadata() {
        service = new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 40, 5);
        mockEmbeddingsForAnySegments();

        service.ingestDocument(
                "Paragraph one has enough text to split.\n\nParagraph two has enough text to split too.",
                Map.of("document_id", "policy-1", "source", "manual"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).delete(any(Filter.Expression.class));
        verify(embeddingStore).add(segmentsCaptor.capture());
        List<Document> segments = segmentsCaptor.getValue();

        org.assertj.core.api.Assertions.assertThat(segments).hasSizeGreaterThan(1);
        for (int index = 0; index < segments.size(); index++) {
            org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(segments.get(index).getMetadata().get("document_id")))
                    .isEqualTo("policy-1");
            org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(segments.get(index).getMetadata().get("source")))
                    .isEqualTo("manual");
            org.assertj.core.api.Assertions.assertThat(
                        ((Number) segments.get(index).getMetadata().get("chunk_index")).intValue())
                    .isEqualTo(index);
            org.assertj.core.api.Assertions.assertThat(
                        ((Number) segments.get(index).getMetadata().get("chunk_count")).intValue())
                    .isEqualTo(segments.size());
        }
    }

    @Test
    @DisplayName("ingestDocument replaces existing chunks when document_id is provided")
    void ingestDocument_withDocumentId_removesExistingChunksBeforeAdd() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("replacement content", Map.of("document_id", "faq-123"));

        verify(embeddingStore).delete(any(Filter.Expression.class));
        verify(embeddingStore).add(any());
    }

    @Test
    @DisplayName("ingestDocument without document_id appends a generated document_id without removing existing chunks")
    void ingestDocument_withoutDocumentId_doesNotRemoveExistingChunks() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("new content", Map.of("source", "manual"));

        verify(embeddingStore, never()).delete(any(Filter.Expression.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).add(segmentsCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(
                String.valueOf(segmentsCaptor.getValue().get(0).getMetadata().get("document_id")))
                .isNotBlank();
    }

    private void mockEmbeddingsForAnySegments() {
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any())).thenAnswer(invocation -> {
            List<String> segments = invocation.getArgument(0);
            return embeddingsFor(segments);
        });
    }

    private static List<float[]> embeddingsFor(List<String> segments) {
        return segments.stream().map(ignored -> VECTOR).toList();
    }

    private static boolean containsText(List<Document> segments, String text) {
        return segments.stream().anyMatch(segment -> text.equals(segment.getText()));
    }
}
