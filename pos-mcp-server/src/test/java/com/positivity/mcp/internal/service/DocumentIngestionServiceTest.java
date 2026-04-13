package com.positivity.mcp.internal.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DocumentIngestionServiceImpl}.
 *
 * <p>
 * The implementation is {@code @Profile("!test")} and is instantiated
 * directly. The public
 * {@link com.positivity.mcp.service.DocumentIngestionService}
 * interface is used to call the service under test.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

  @Mock
  private PgVectorEmbeddingStore embeddingStore;

  @Mock
  private EmbeddingModel embeddingModel;

  private DocumentIngestionServiceImpl service;

  private static final float[] VECTOR = { 0.1f, 0.2f, 0.3f };
  private static final Response<Embedding> EMBEDDING_RESPONSE = Response.from(Embedding.from(VECTOR));

  @BeforeEach
  void setUp() {
    service = new DocumentIngestionServiceImpl(embeddingStore, embeddingModel);
  }

  @Test
  @DisplayName("ingestDocument calls embeddingModel.embed and embeddingStore.add once")
  void ingestDocument_callsEmbeddingModelAndStore() {
    when(embeddingModel.embed("some text")).thenReturn(EMBEDDING_RESPONSE);

    service.ingestDocument("some text", Map.of("source", "test"));

    verify(embeddingModel, times(1)).embed("some text");
    verify(embeddingStore, times(1)).add(any(Embedding.class), any(TextSegment.class));
  }

  @Test
  @DisplayName("ingestDocument with empty metadata does not throw")
  void ingestDocument_withEmptyMetadata_usesEmptyMap() {
    when(embeddingModel.embed(anyString())).thenReturn(EMBEDDING_RESPONSE);

    service.ingestDocument("content", Map.of());

    verify(embeddingStore, times(1)).add(any(Embedding.class), any(TextSegment.class));
  }

  @Test
  @DisplayName("ingestDocuments calls embeddingStore.add once per document")
  void ingestDocuments_batchCallsStoreForEachItem() {
    when(embeddingModel.embed(anyString())).thenReturn(EMBEDDING_RESPONSE);

    service.ingestDocuments(
        List.of("doc0", "doc1", "doc2"),
        List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

    verify(embeddingStore, times(3)).add(any(Embedding.class), any(TextSegment.class));
  }

  @Test
  @DisplayName("ingestDocuments continues processing remaining docs when one embed fails")
  void ingestDocuments_singleFailure_doesNotAbortBatch() {
    when(embeddingModel.embed("doc0")).thenThrow(new RuntimeException("embed failed"));
    when(embeddingModel.embed("doc1")).thenReturn(EMBEDDING_RESPONSE);
    when(embeddingModel.embed("doc2")).thenReturn(EMBEDDING_RESPONSE);

    service.ingestDocuments(
        List.of("doc0", "doc1", "doc2"),
        List.of(Map.of("i", 0), Map.of("i", 1), Map.of("i", 2)));

    // doc0 failed, so store should be called only twice
    verify(embeddingStore, times(2)).add(any(Embedding.class), any(TextSegment.class));
    verify(embeddingStore, never()).add(any(Embedding.class),
        argThat(seg -> seg.text().equals("doc0")));
  }

  @Test
  @DisplayName("ingestDocuments throws IllegalArgumentException when contents and metadataList sizes differ")
  void ingestDocuments_mismatchedSizes_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () ->
        service.ingestDocuments(
            List.of("doc0", "doc1"),
            List.of(Map.of("i", 0))));

    verify(embeddingStore, never()).add(any(Embedding.class), any(TextSegment.class));
  }
}
