package com.positivity.mcp.internal.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private PgVectorEmbeddingStore embeddingStore;

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
                .embedAll(argThat(segments ->
                        segments.size() == 1 && segments.get(0).text().equals("some text")));
        verify(embeddingStore, times(1)).addAll(any(), any());
    }

    @Test
    @DisplayName("ingestDocument with empty metadata does not throw")
    void ingestDocument_withEmptyMetadata_usesEmptyMap() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("content", Map.of());

        verify(embeddingStore, times(1)).addAll(any(), any());
    }

    @Test
    @DisplayName("ingestDocuments calls embeddingStore.addAll once per document")
    void ingestDocuments_batchCallsStoreForEachItem() {
        mockEmbeddingsForAnySegments();

        service.ingestDocuments(
                List.of("doc0", "doc1", "doc2"), List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

        verify(embeddingStore, times(3)).addAll(any(), any());
    }

    @Test
    @DisplayName("ingestDocuments continues processing remaining docs when one embed fails")
    void ingestDocuments_singleFailure_doesNotAbortBatch() {
        when(embeddingModel.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            if (segments.get(0).text().equals("doc0")) {
                throw new RuntimeException("embed failed");
            }
            return embeddingsFor(segments);
        });

        service.ingestDocuments(
                List.of("doc0", "doc1", "doc2"), List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

        // doc0 failed, so store should be called only twice
        verify(embeddingStore, times(2)).addAll(any(), any());
        verify(embeddingStore, never()).addAll(any(), argThat(segments -> containsText(segments, "doc0")));
    }

    @Test
    @DisplayName("ingestDocuments throws IllegalArgumentException when contents and metadataList sizes differ")
    void ingestDocuments_mismatchedSizes_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.ingestDocuments(List.of("doc0", "doc1"), List.of(Map.of("i", 0))));

        verify(embeddingStore, never()).addAll(any(), any());
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
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).removeAll(any(Filter.class));
        verify(embeddingStore).addAll(any(), segmentsCaptor.capture());
        List<TextSegment> segments = segmentsCaptor.getValue();

        org.assertj.core.api.Assertions.assertThat(segments).hasSizeGreaterThan(1);
        for (int index = 0; index < segments.size(); index++) {
            org.assertj.core.api.Assertions.assertThat(
                            segments.get(index).metadata().getString("document_id"))
                    .isEqualTo("policy-1");
            org.assertj.core.api.Assertions.assertThat(
                            segments.get(index).metadata().getString("source"))
                    .isEqualTo("manual");
            org.assertj.core.api.Assertions.assertThat(
                            segments.get(index).metadata().getInteger("chunk_index"))
                    .isEqualTo(index);
            org.assertj.core.api.Assertions.assertThat(
                            segments.get(index).metadata().getInteger("chunk_count"))
                    .isEqualTo(segments.size());
        }
    }

    @Test
    @DisplayName("ingestDocument replaces existing chunks when document_id is provided")
    void ingestDocument_withDocumentId_removesExistingChunksBeforeAdd() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("replacement content", Map.of("document_id", "faq-123"));

        verify(embeddingStore).removeAll(any(Filter.class));
        verify(embeddingStore).addAll(any(), any());
    }

    @Test
    @DisplayName("ingestDocument without document_id appends a generated document_id without removing existing chunks")
    void ingestDocument_withoutDocumentId_doesNotRemoveExistingChunks() {
        mockEmbeddingsForAnySegments();

        service.ingestDocument("new content", Map.of("source", "manual"));

        verify(embeddingStore, never()).removeAll(any(Filter.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(any(), segmentsCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(
                        segmentsCaptor.getValue().get(0).metadata().getString("document_id"))
                .isNotBlank();
    }

    private void mockEmbeddingsForAnySegments() {
        when(embeddingModel.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return embeddingsFor(segments);
        });
    }

    private static Response<List<Embedding>> embeddingsFor(List<TextSegment> segments) {
        return Response.from(
                segments.stream().map(ignored -> Embedding.from(VECTOR)).toList());
    }

    private static boolean containsText(List<TextSegment> segments, String text) {
        return segments.stream().anyMatch(segment -> segment.text().equals(text));
    }
}
