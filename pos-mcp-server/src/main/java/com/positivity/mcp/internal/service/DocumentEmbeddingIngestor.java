package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RagScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentEmbeddingIngestor {

    private static final String DOCUMENT_ID = "document_id";
    private static final String RAG_SCOPE = "rag_scope";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentEmbeddingIngestor.class);

        private final PgVectorStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final boolean chunkingEnabled;
        private final int maxSegmentSize;
        private final int maxOverlapSize;

    public DocumentEmbeddingIngestor(
            @NonNull PgVectorStore embeddingStore,
            @NonNull EmbeddingModel embeddingModel,
            @Value("${mcp.rag.chunking.enabled:true}") boolean chunkingEnabled,
            @Value("${mcp.rag.chunking.max-segment-size:2000}") int maxSegmentSize,
            @Value("${mcp.rag.chunking.max-overlap-size:200}") int maxOverlapSize) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chunkingEnabled = chunkingEnabled;
        this.maxSegmentSize = Math.max(1, maxSegmentSize);
        this.maxOverlapSize = Math.clamp(maxOverlapSize, 0, this.maxSegmentSize - 1);
    }

    public int ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata) {
        long totalStartNanos = System.nanoTime();
        Map<String, Object> normalizedMetadata = RagScope.normalizeInMetadata(metadata);
        Object providedDocumentId = normalizedMetadata.get(DOCUMENT_ID);
        boolean replaceExisting = providedDocumentId instanceof String documentIdValue && !documentIdValue.isBlank();
        String documentId = replaceExisting
                ? ((String) providedDocumentId).trim()
                : UUID.randomUUID().toString();

        try {
            List<Document> segments = segments(content, normalizedMetadata, documentId);
            long embeddingStartNanos = System.nanoTime();
            List<float[]> embeddings = embeddingModel.embed(segments.stream().map(Document::getText).toList());
            LOGGER.info(
                    "Embedded RAG document {} with {} segments in {} ms",
                    documentId,
                    segments.size(),
                    elapsedMs(embeddingStartNanos));
            if (embeddings.size() != segments.size()) {
                throw new IllegalStateException(
                        "Embedding count does not match segment count: embeddings=%d, segments=%d"
                                .formatted(embeddings.size(), segments.size()));
            }

            long storeStartNanos = System.nanoTime();
            if (replaceExisting) {
                var filter = new FilterExpressionBuilder()
                    .and(
                        new FilterExpressionBuilder().eq(DOCUMENT_ID, documentId),
                        new FilterExpressionBuilder().eq(RAG_SCOPE, normalizedMetadata.get(RAG_SCOPE)))
                    .build();
                embeddingStore.delete(filter);
            }
                embeddingStore.add(segments);
            LOGGER.info(
                    "Stored RAG document {} with {} segments in {} ms",
                    documentId,
                    segments.size(),
                    elapsedMs(storeStartNanos));
            LOGGER.info(
                    "Completed RAG document ingestion documentId={} segments={} replaceExisting={} totalMs={}",
                    documentId,
                    segments.size(),
                    replaceExisting,
                    elapsedMs(totalStartNanos));
            return segments.size();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "RAG document ingestion failed documentId=%s replaceExisting=%s totalMs=%d"
                            .formatted(documentId, replaceExisting, elapsedMs(totalStartNanos)),
                    exception);
        }
    }

    public void ingestDocuments(@NonNull List<String> contents, @NonNull List<Map<String, Object>> metadataList) {
        if (contents.size() != metadataList.size()) {
            throw new IllegalArgumentException(
                    "contents and metadataList must have equal size: contents=%d, metadataList=%d"
                            .formatted(contents.size(), metadataList.size()));
        }
        for (int index = 0; index < contents.size(); index++) {
            try {
                ingestDocument(contents.get(index), metadataList.get(index));
            } catch (Exception exception) {
                LOGGER.warn("Failed to ingest document at index {}", index, exception);
            }
        }
    }

    private @NonNull List<Document> segments(
            @NonNull String content, @NonNull Map<String, Object> metadata, @NonNull String documentId) {
        Map<String, Object> baseMetadata = new HashMap<>(metadata);
        baseMetadata.put(DOCUMENT_ID, documentId);

        List<String> rawSegments = chunkingEnabled ? split(content) : List.of(content);
        List<Document> enrichedSegments = new ArrayList<>(rawSegments.size());
        for (int index = 0; index < rawSegments.size(); index++) {
            Map<String, Object> segmentMetadata = new HashMap<>(baseMetadata);
            segmentMetadata.put("chunk_index", index);
            segmentMetadata.put("chunk_count", rawSegments.size());
            enrichedSegments.add(new Document(rawSegments.get(index), segmentMetadata));
        }
        return enrichedSegments;
    }

    private @NonNull List<String> split(@NonNull String content) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        int length = content.length();
        while (start < length) {
            int end = Math.min(length, start + maxSegmentSize);
            String segment = content.substring(start, end).trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
            if (end >= length) {
                break;
            }
            start = Math.max(start + 1, end - maxOverlapSize);
        }
        if (segments.isEmpty()) {
            segments.add(content);
        }
        return segments;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
