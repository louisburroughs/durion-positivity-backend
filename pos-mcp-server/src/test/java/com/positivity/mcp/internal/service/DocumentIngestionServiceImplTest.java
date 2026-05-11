package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.entity.DocumentIngestionJobEntity;
import com.positivity.mcp.internal.entity.DocumentIngestionJobState;
import com.positivity.mcp.internal.repository.DocumentIngestionJobRepository;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("018f0000-0000-7000-8000-000000000010");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-04-19T12:00:00Z");

    @Mock
    private DocumentIngestionJobRepository jobRepository;

    @Mock
    private DocumentEmbeddingIngestor documentEmbeddingIngestor;

    private final AtomicReference<DocumentIngestionJobEntity> storedJob = new AtomicReference<>();

    private DocumentIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        Clock clock = Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneOffset.UTC);
        service = new DocumentIngestionServiceImpl(
                jobRepository, documentEmbeddingIngestor, new ObjectMapper(), directExecutor, clock);

        lenient()
                .when(jobRepository.save(any(DocumentIngestionJobEntity.class)))
                .thenAnswer(invocation -> {
                    DocumentIngestionJobEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(JOB_ID);
                        entity.setCreatedAt(NOW);
                    }
                    entity.setUpdatedAt(NOW);
                    storedJob.set(entity);
                    return entity;
                });
        lenient().when(jobRepository.findById(JOB_ID)).thenAnswer(invocation -> Optional.ofNullable(storedJob.get()));
    }

    @Test
    @DisplayName("submitDocument persists pending job and processes it in the background")
    void submitDocument_persistsPendingJobAndProcesses() {
        when(documentEmbeddingIngestor.ingestDocument(
                        eq("policy text"),
                        eq(Map.of("source", "manual", "document_id", "policy-1", "rag_scope", "master"))))
                .thenReturn(2);

        var accepted = service.submitDocument("policy text", Map.of("source", "manual", "document_id", "policy-1"));

        assertThat(accepted.jobId()).isEqualTo(JOB_ID);
        assertThat(accepted.documentId()).isEqualTo("policy-1");
        assertThat(accepted.status()).isEqualTo(DocumentIngestionJobStatus.PENDING);

        DocumentIngestionJobEntity finalJob = storedJob.get();
        assertThat(finalJob.getStatus()).isEqualTo(DocumentIngestionJobState.SUCCEEDED);
        assertThat(finalJob.getChunkCount()).isEqualTo(2);
        assertThat(finalJob.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("submitDocument generates document_id when metadata omits one")
    void submitDocument_withoutDocumentId_generatesDocumentId() {
        when(documentEmbeddingIngestor.ingestDocument(eq("content"), any())).thenReturn(1);

        var accepted = service.submitDocument("content", Map.of("source", "manual"));

        assertThat(accepted.documentId()).isNotBlank();
        verify(documentEmbeddingIngestor)
                .ingestDocument(
                        "content",
                        Map.of("source", "manual", "document_id", accepted.documentId(), "rag_scope", "master"));
    }

    @Test
    @DisplayName("submitDocument normalizes rag scope before background ingestion")
    void submitDocument_normalizesRagScope() {
        when(documentEmbeddingIngestor.ingestDocument(
                        eq("content"), eq(Map.of("document_id", "doc-1", "rag_scope", "inventory"))))
                .thenReturn(1);

        service.submitDocument("content", Map.of("document_id", "doc-1", "rag_scope", " INVENTORY "));

        verify(documentEmbeddingIngestor)
                .ingestDocument("content", Map.of("document_id", "doc-1", "rag_scope", "inventory"));
    }

    @Test
    @DisplayName("ingestDocuments passes metadata through to the ingestor")
    void ingestDocuments_normalizesRagScope() {
        List<String> contents = List.of("one", "two");
        List<Map<String, Object>> metadata =
                List.of(Map.of("document_id", "doc-1", "rag_scope", " INVENTORY "), Map.of("document_id", "doc-2"));

        service.ingestDocuments(contents, metadata);

        verify(documentEmbeddingIngestor)
                .ingestDocuments(
                        contents,
                        List.of(
                                Map.of("document_id", "doc-1", "rag_scope", " INVENTORY "),
                                Map.of("document_id", "doc-2")));
    }

    @Test
    @DisplayName("background processing marks job failed when embedding ingestion fails")
    void submitDocument_whenIngestFails_marksFailed() {
        when(documentEmbeddingIngestor.ingestDocument(eq("content"), any()))
                .thenThrow(new RuntimeException("embed failed"));

        service.submitDocument("content", Map.of("document_id", "doc-9"));

        DocumentIngestionJobEntity finalJob = storedJob.get();
        assertThat(finalJob.getStatus()).isEqualTo(DocumentIngestionJobState.FAILED);
        assertThat(finalJob.getErrorMessage()).isEqualTo("embed failed");
        assertThat(finalJob.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("resumeIncompleteJobs requeues pending and running jobs")
    void resumeIncompleteJobs_requeuesIncompleteJobs() {
        DocumentIngestionJobEntity pending = new DocumentIngestionJobEntity();
        pending.setId(JOB_ID);
        pending.setDocumentId("doc-1");
        pending.setStatus(DocumentIngestionJobState.PENDING);
        pending.setContent("content");
        pending.setMetadataJson("{\"document_id\":\"doc-1\"}");
        pending.setCreatedAt(NOW);
        pending.setUpdatedAt(NOW);
        storedJob.set(pending);
        when(jobRepository.findByStatusIn(any())).thenReturn(List.of(pending));
        when(documentEmbeddingIngestor.ingestDocument("content", Map.of("document_id", "doc-1")))
                .thenReturn(1);

        service.resumeIncompleteJobs();

        assertThat(storedJob.get().getStatus()).isEqualTo(DocumentIngestionJobState.SUCCEEDED);
    }

    @Test
    @DisplayName("getIngestionJob maps entity to public job DTO")
    void getIngestionJob_mapsEntity() {
        DocumentIngestionJobEntity entity = new DocumentIngestionJobEntity();
        entity.setId(JOB_ID);
        entity.setDocumentId("doc-1");
        entity.setStatus(DocumentIngestionJobState.RUNNING);
        entity.setContent("content");
        entity.setMetadataJson("{}");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        storedJob.set(entity);

        var job = service.getIngestionJob(JOB_ID);

        assertThat(job).isPresent();
        assertThat(job.orElseThrow().status()).isEqualTo(DocumentIngestionJobStatus.RUNNING);
    }
}
