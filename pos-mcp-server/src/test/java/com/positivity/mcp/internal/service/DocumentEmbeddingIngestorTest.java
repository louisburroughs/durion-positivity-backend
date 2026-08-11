package com.positivity.mcp.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                .allMatch(segment ->
                        "inventory".equals(String.valueOf(segment.getMetadata().get("rag_scope")))));
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

    @Test
    void chunking_capsOverlapToHalfSegmentSizeToAvoidExplosiveChunkCounts() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        // With segment size 10 and configured overlap 9, effective overlap is capped to 5.
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                        .stream().map(ignored -> new float[] {1.0f}).toList());
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 10, 9, 100);

        int chunks = ingestor.ingestDocument("abcdefghijklmnopqrstuvwxyz", Map.of("rag_scope", "inventory"));

        assertEquals(5, chunks);
    }

    @Test
    void chunking_enforcesMaxChunksPerDocumentLimit() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 10, 0, 2);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ingestor.ingestDocument("abcdefghijklmnopqrstuvwxyz", Map.of("rag_scope", "inventory")));

        assertTrue(exception.getMessage().contains("RAG document ingestion failed"));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Document exceeds configured chunk limit"));
        verify(embeddingModel, never()).embed(anyList());
    }

    /**
     * #1124/#1125: a markdown document with several {@code ##} sub-sections must split into one
     * chunk per sub-section (each independently embeddable/retrievable) rather than fixed
     * character-window slices that can straddle two unrelated sections and dilute or truncate the
     * fact a query is looking for. Regression coverage for the gap-harness alpha finding where
     * glossary.identifiers was retrieved as the correct document but the specific PO/GL/invoice fact
     * was not reliably present in the returned chunk.
     */
    @Test
    void chunking_splitsMarkdownDocumentPerHeadingSection() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                        .stream().map(ignored -> new float[] {1.0f}).toList());
        // Large segment size so no section needs further sliding-window splitting; only the
        // heading-based split should apply.
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 2000, 200);

        String content = """
                # Glossary

                ## Purpose

                This document defines identifiers.

                ## PO number

                POs are owned by pos-inventory. Format: 8-character zero-padded base-36 code.

                ## GL account code

                Format: ^\\d{4}(-\\d{3})?$
                """;

        int chunkCount = ingestor.ingestDocument(content, Map.of("rag_scope", "master"));

        // Preamble ("# Glossary" title, before the first "## " heading) plus one chunk per
        // "## " section (Purpose, PO number, GL account code) = 4 chunks.
        assertEquals(4, chunkCount);

        ArgumentCaptor<List<Document>> segmentCaptor = listCaptor();
        verify(embeddingStore).add(segmentCaptor.capture());
        List<Document> segments = segmentCaptor.getValue();
        assertTrue(segments.stream()
                .anyMatch(segment -> segment.getText().contains("## PO number")
                        && segment.getText().contains("pos-inventory")
                        && !segment.getText().contains("GL account code")));
        assertTrue(segments.stream()
                .anyMatch(segment -> segment.getText().contains("## GL account code")
                        && segment.getText().contains("\\d{4}")
                        && !segment.getText().contains("PO number")));
    }

    /**
     * A section that exceeds {@code maxSegmentSize} still falls back to the sliding-window split
     * within that section, so oversized sub-sections don't silently bypass the chunk-size contract.
     */
    @Test
    void chunking_fallsBackToSlidingWindowForOversizedSection() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                        .stream().map(ignored -> new float[] {1.0f}).toList());
        DocumentEmbeddingIngestor ingestor = new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 20, 0);

        String content = "## Big section\n" + "x".repeat(100);

        int chunkCount = ingestor.ingestDocument(content, Map.of("rag_scope", "master"));

        assertTrue(chunkCount > 1);
    }

    /** Plain prose with no {@code ##} headings is unaffected and still uses the sliding window. */
    @Test
    void chunking_plainProseWithNoHeadingsUsesSlidingWindow() {
        PgVectorStore embeddingStore = mock(PgVectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                        .stream().map(ignored -> new float[] {1.0f}).toList());
        DocumentEmbeddingIngestor ingestor =
                new DocumentEmbeddingIngestor(embeddingStore, embeddingModel, true, 10, 9, 100);

        int chunks = ingestor.ingestDocument("abcdefghijklmnopqrstuvwxyz", Map.of("rag_scope", "inventory"));

        assertEquals(5, chunks);
    }
}
