package com.positivity.mcp.internal.service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.positivity.mcp.internal.domain.RagScope;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentEmbeddingIngestor {

    private static final String DOCUMENT_ID = "document_id";
    private static final String RAG_SCOPE = "rag_scope";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentEmbeddingIngestor.class);

    private final PgVectorEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentSplitter documentSplitter;
    private final boolean chunkingEnabled;

    public DocumentEmbeddingIngestor(
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @NonNull EmbeddingModel embeddingModel,
            @Value("${mcp.rag.chunking.enabled:true}") boolean chunkingEnabled,
            @Value("${mcp.rag.chunking.max-segment-size:2000}") int maxSegmentSize,
            @Value("${mcp.rag.chunking.max-overlap-size:200}") int maxOverlapSize) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chunkingEnabled = chunkingEnabled;
        int sanitizedMaxSegmentSize = Math.max(1, maxSegmentSize);
        int sanitizedMaxOverlapSize = Math.clamp(maxOverlapSize, 0, sanitizedMaxSegmentSize - 1);
        this.documentSplitter = DocumentSplitters.recursive(sanitizedMaxSegmentSize, sanitizedMaxOverlapSize);
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
            List<TextSegment> segments = segments(content, normalizedMetadata, documentId);
            long embeddingStartNanos = System.nanoTime();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
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
                embeddingStore.removeAll(new And(
                        metadataKey(DOCUMENT_ID).isEqualTo(documentId),
                        metadataKey(RAG_SCOPE).isEqualTo((String) normalizedMetadata.get(RAG_SCOPE))));
            }
            embeddingStore.addAll(embeddings, segments);
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

    private @NonNull List<TextSegment> segments(
            @NonNull String content, @NonNull Map<String, Object> metadata, @NonNull String documentId) {
        Metadata documentMetadata = Metadata.from(metadata).put(DOCUMENT_ID, documentId);
        Document document = Document.from(content, documentMetadata);
        List<TextSegment> rawSegments =
                chunkingEnabled ? documentSplitter.split(document) : List.of(document.toTextSegment());
        List<TextSegment> enrichedSegments = new ArrayList<>(rawSegments.size());
        for (int index = 0; index < rawSegments.size(); index++) {
            TextSegment segment = rawSegments.get(index);
            Metadata segmentMetadata = segment.metadata()
                    .copy()
                    .put(DOCUMENT_ID, documentId)
                    .put("chunk_index", index)
                    .put("chunk_count", rawSegments.size());
            enrichedSegments.add(TextSegment.from(segment.text(), segmentMetadata));
        }
        return enrichedSegments;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
