package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.DocumentIngestionService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);

  private final PgVectorEmbeddingStore embeddingStore;
  private final EmbeddingModel embeddingModel;

  public DocumentIngestionServiceImpl(
      @NonNull PgVectorEmbeddingStore embeddingStore,
      @NonNull EmbeddingModel embeddingModel) {
    this.embeddingStore = embeddingStore;
    this.embeddingModel = embeddingModel;
  }

  @Override
  public void ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata) {
    Embedding embedding = embeddingModel.embed(content).content();
    TextSegment segment = TextSegment.from(content, Metadata.from(metadata));
    embeddingStore.add(embedding, segment);
  }

  @Override
  public void ingestDocuments(
      @NonNull List<String> contents,
      @NonNull List<Map<String, Object>> metadataList) {
    int count = Math.min(contents.size(), metadataList.size());
    for (int index = 0; index < count; index++) {
      try {
        ingestDocument(contents.get(index), metadataList.get(index));
      } catch (Exception exception) {
        LOGGER.warn("Failed to ingest document at index {}", index, exception);
      }
    }

    if (contents.size() != metadataList.size()) {
      LOGGER.warn(
          "Ingestion input size mismatch: contents={}, metadataList={}",
          contents.size(),
          metadataList.size());
    }
  }
}
