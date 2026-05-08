package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.entity.DocumentIngestionJobEntity;
import com.positivity.mcp.internal.entity.DocumentIngestionJobState;
import com.positivity.mcp.internal.repository.DocumentIngestionJobRepository;
import com.positivity.mcp.service.DocumentIngestionJob;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import com.positivity.mcp.service.DocumentIngestionService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final String DOCUMENT_ID = "document_id";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentEmbeddingIngestor documentEmbeddingIngestor;
    private final ObjectMapper objectMapper;
    private final Executor documentIngestionExecutor;
    private final Clock clock;

    public DocumentIngestionServiceImpl(
            @NonNull DocumentIngestionJobRepository jobRepository,
            @NonNull DocumentEmbeddingIngestor documentEmbeddingIngestor,
            @NonNull ObjectMapper objectMapper,
            @Qualifier("documentIngestionExecutor") @NonNull Executor documentIngestionExecutor,
            @NonNull Clock clock) {
        this.jobRepository = jobRepository;
        this.documentEmbeddingIngestor = documentEmbeddingIngestor;
        this.objectMapper = objectMapper;
        this.documentIngestionExecutor = documentIngestionExecutor;
        this.clock = clock;
    }

    @Override
    public @NonNull DocumentIngestionJob submitDocument(
            @NonNull String content, @NonNull Map<String, Object> metadata) {
        DocumentIngestionJobEntity job = new DocumentIngestionJobEntity();
        String documentId = resolveDocumentId(metadata);
        job.setDocumentId(documentId);
        job.setStatus(DocumentIngestionJobState.PENDING);
        job.setContent(content);
        job.setMetadataJson(serializeMetadata(metadataWithDocumentId(metadata, documentId)));

        DocumentIngestionJobEntity saved = jobRepository.save(job);
        DocumentIngestionJob acceptedJob = toJob(saved);
        LOGGER.info("Queued RAG document ingestion job {} for document {}", saved.getId(), documentId);
        documentIngestionExecutor.execute(() -> processJob(saved.getId()));
        return acceptedJob;
    }

    @Override
    public @NonNull Optional<DocumentIngestionJob> getIngestionJob(@NonNull UUID jobId) {
        return jobRepository.findById(jobId).map(DocumentIngestionServiceImpl::toJob);
    }

    @Override
    public void ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata) {
        documentEmbeddingIngestor.ingestDocument(content, DocumentEmbeddingIngestor.normalizeRagScope(metadata));
    }

    @Override
    public void ingestDocuments(@NonNull List<String> contents, @NonNull List<Map<String, Object>> metadataList) {
        documentEmbeddingIngestor.ingestDocuments(
                contents, metadataList.stream().map(DocumentEmbeddingIngestor::normalizeRagScope).toList());
    }

    public void resumeIncompleteJobs() {
        Collection<DocumentIngestionJobState> incompleteStatuses =
                List.of(DocumentIngestionJobState.PENDING, DocumentIngestionJobState.RUNNING);
        for (DocumentIngestionJobEntity job : jobRepository.findByStatusIn(incompleteStatuses)) {
            UUID jobId = job.getId();
            LOGGER.info("Resuming RAG document ingestion job {}", jobId);
            documentIngestionExecutor.execute(() -> processJob(jobId));
        }
    }

    private void processJob(@NonNull UUID jobId) {
        long startNanos = System.nanoTime();
        Optional<DocumentIngestionJobEntity> maybeJob = jobRepository.findById(jobId);
        if (maybeJob.isEmpty()) {
            LOGGER.warn("RAG document ingestion job {} disappeared before processing", jobId);
            return;
        }

        DocumentIngestionJobEntity job = maybeJob.get();
        try {
            markRunning(job);
            Map<String, Object> metadata = deserializeMetadata(job.getMetadataJson());
            int chunkCount = documentEmbeddingIngestor.ingestDocument(job.getContent(), metadata);
            markSucceeded(job, chunkCount);
            LOGGER.info(
                    "RAG document ingestion job {} succeeded for document {} in {} ms",
                    jobId,
                    job.getDocumentId(),
                    elapsedMs(startNanos));
        } catch (Exception exception) {
            markFailed(job, exception);
            LOGGER.warn(
                    "RAG document ingestion job {} failed for document {} after {} ms",
                    jobId,
                    job.getDocumentId(),
                    elapsedMs(startNanos));
        }
    }

    private void markRunning(@NonNull DocumentIngestionJobEntity job) {
        job.setStatus(DocumentIngestionJobState.RUNNING);
        job.setStartedAt(OffsetDateTime.now(clock));
        job.setCompletedAt(null);
        job.setErrorMessage(null);
        jobRepository.save(job);
    }

    private void markSucceeded(@NonNull DocumentIngestionJobEntity job, int chunkCount) {
        job.setStatus(DocumentIngestionJobState.SUCCEEDED);
        job.setChunkCount(chunkCount);
        job.setCompletedAt(OffsetDateTime.now(clock));
        job.setErrorMessage(null);
        jobRepository.save(job);
    }

    private void markFailed(@NonNull DocumentIngestionJobEntity job, @NonNull Exception exception) {
        LOGGER.warn("RAG document ingestion job {} failed", job.getId(), exception);
        job.setStatus(DocumentIngestionJobState.FAILED);
        job.setCompletedAt(OffsetDateTime.now(clock));
        job.setErrorMessage(truncateErrorMessage(exception));
        jobRepository.save(job);
    }

    private @NonNull String resolveDocumentId(@NonNull Map<String, Object> metadata) {
        Object providedDocumentId = metadata.get(DOCUMENT_ID);
        if (providedDocumentId instanceof String documentId && !documentId.isBlank()) {
            return documentId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private @NonNull Map<String, Object> metadataWithDocumentId(
            @NonNull Map<String, Object> metadata, @NonNull String documentId) {
        java.util.LinkedHashMap<String, Object> normalized =
                new java.util.LinkedHashMap<>(DocumentEmbeddingIngestor.normalizeRagScope(metadata));
        normalized.put(DOCUMENT_ID, documentId);
        return normalized;
    }

    private @NonNull String serializeMetadata(@NonNull Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Document metadata must be JSON-serializable", exception);
        }
    }

    private @NonNull Map<String, Object> deserializeMetadata(@NonNull String metadataJson)
            throws JsonProcessingException {
        return objectMapper.readValue(metadataJson, METADATA_TYPE);
    }

    private static @NonNull String truncateErrorMessage(@NonNull Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private static @NonNull DocumentIngestionJob toJob(@NonNull DocumentIngestionJobEntity entity) {
        return new DocumentIngestionJob(
                entity.getId(),
                entity.getDocumentId(),
                DocumentIngestionJobStatus.valueOf(entity.getStatus().name()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getChunkCount(),
                entity.getErrorMessage());
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
