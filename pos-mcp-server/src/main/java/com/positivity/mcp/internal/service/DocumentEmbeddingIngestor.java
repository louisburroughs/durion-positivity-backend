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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentEmbeddingIngestor {

    private static final String DOCUMENT_ID = "document_id";
    private static final String RAG_SCOPE = "rag_scope";
    private static final double MAX_OVERLAP_RATIO = 0.5;
    private static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 2_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentEmbeddingIngestor.class);

    private final PgVectorStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final boolean chunkingEnabled;
    private final int maxSegmentSize;
    private final int maxOverlapSize;
    private final int segmentStepSize;
    private final int maxChunksPerDocument;

    @Autowired
    public DocumentEmbeddingIngestor(
            @NonNull PgVectorStore embeddingStore,
            @NonNull EmbeddingModel embeddingModel,
            @Value("${mcp.rag.chunking.enabled:true}") boolean chunkingEnabled,
            @Value("${mcp.rag.chunking.max-segment-size:2000}") int maxSegmentSize,
            @Value("${mcp.rag.chunking.max-overlap-size:200}") int maxOverlapSize,
            @Value("${mcp.rag.chunking.max-chunks-per-document:2000}") int maxChunksPerDocument) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chunkingEnabled = chunkingEnabled;
        this.maxSegmentSize = Math.max(1, maxSegmentSize);
        int overlapCap = Math.max(0, Math.min(this.maxSegmentSize - 1, (int) (this.maxSegmentSize * MAX_OVERLAP_RATIO)));
        this.maxOverlapSize = Math.clamp(maxOverlapSize, 0, overlapCap);
        this.segmentStepSize = Math.max(1, this.maxSegmentSize - this.maxOverlapSize);
        this.maxChunksPerDocument = Math.max(1, maxChunksPerDocument);
    }

    public DocumentEmbeddingIngestor(
            @NonNull PgVectorStore embeddingStore,
            @NonNull EmbeddingModel embeddingModel,
            boolean chunkingEnabled,
            int maxSegmentSize,
            int maxOverlapSize) {
        this(
                embeddingStore,
                embeddingModel,
                chunkingEnabled,
                maxSegmentSize,
                maxOverlapSize,
                DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
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

    /**
     * Splits document content into embeddable segments.
     *
     * <p>Markdown documents (glossary/reference docs under {@code src/main/resources/rag/}) are
     * structured as many independent {@code ##} sub-sections, each describing a single concept or
     * identifier format. Splitting purely by a fixed character window (the previous behavior) ignores
     * those boundaries and can cut a section's defining fact in half or bury it in the middle of a
     * chunk dominated by an unrelated neighboring section — diluting the embedding so a query about
     * that one fact no longer retrieves the chunk that actually contains it, or retrieves it without
     * enough surrounding context for the model to ground on. See issue #1124/#1125: dense retrieval
     * surfaced the right document (`glossary.identifiers`) but the specific PO/GL/invoice fact wasn't
     * reliably present in the chunk actually returned.
     *
     * <p>To keep each fact-bearing section intact and independently retrievable, content is first
     * split at top-level markdown headings ({@code ## }); each resulting section becomes its own
     * chunk when it fits within {@code maxSegmentSize}. A section that is still too large (or content
     * with no {@code ##} headings at all, e.g. prose documents) falls back to the original
     * fixed-size sliding-window split so behavior for non-markdown content is unchanged.
     */
    private @NonNull List<String> split(@NonNull String content) {
        List<String> sections = splitByMarkdownHeadings(content);
        if (sections.size() <= 1) {
            return slidingWindowSplit(content);
        }
        List<String> segments = new ArrayList<>();
        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            if (section.length() <= maxSegmentSize) {
                segments.add(section.trim());
            } else {
                segments.addAll(slidingWindowSplit(section));
            }
            if (segments.size() > maxChunksPerDocument) {
                throw new IllegalArgumentException(
                        "Document exceeds configured chunk limit: maxChunks=%d, segmentSize=%d, overlap=%d, contentLength=%d"
                                .formatted(maxChunksPerDocument, maxSegmentSize, maxOverlapSize, content.length()));
            }
        }
        if (segments.isEmpty()) {
            segments.add(content);
        }
        return segments;
    }

    /**
     * Splits {@code content} at top-level markdown headings ({@code ## Heading}), keeping each
     * heading with its following body text as one section. Any preamble before the first {@code ## }
     * heading (e.g. a document title or purpose blurb) becomes its own leading section. Returns a
     * single-element list containing the original content unchanged when no {@code ## } heading is
     * present, so plain-prose documents are unaffected.
     */
    private static @NonNull List<String> splitByMarkdownHeadings(@NonNull String content) {
        List<String> sections = new ArrayList<>();
        java.util.regex.Matcher headingMatcher =
                java.util.regex.Pattern.compile("(?m)^## .*$").matcher(content);
        int previousStart = 0;
        boolean foundHeading = false;
        while (headingMatcher.find()) {
            foundHeading = true;
            int headingStart = headingMatcher.start();
            if (headingStart > previousStart) {
                sections.add(content.substring(previousStart, headingStart));
            }
            previousStart = headingStart;
        }
        if (!foundHeading) {
            return List.of(content);
        }
        sections.add(content.substring(previousStart));
        return sections;
    }

    private @NonNull List<String> slidingWindowSplit(@NonNull String content) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        int length = content.length();
        while (start < length) {
            if (segments.size() >= maxChunksPerDocument) {
                throw new IllegalArgumentException(
                        "Document exceeds configured chunk limit: maxChunks=%d, segmentSize=%d, overlap=%d, contentLength=%d"
                                .formatted(maxChunksPerDocument, maxSegmentSize, maxOverlapSize, length));
            }
            int end = Math.min(length, start + maxSegmentSize);
            String segment = content.substring(start, end).trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
            if (end >= length) {
                break;
            }
            start = start + segmentStepSize;
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
