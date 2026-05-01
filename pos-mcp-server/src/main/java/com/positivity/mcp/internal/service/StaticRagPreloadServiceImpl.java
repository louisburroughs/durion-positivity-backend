package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.entity.RagPreloadRecord;
import com.positivity.mcp.internal.enums.RagPreloadStatus;
import com.positivity.mcp.internal.repository.RagPreloadRecordRepository;
import com.positivity.mcp.service.DocumentIngestionService;
import com.positivity.mcp.service.StaticRagPreloadService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@Profile("alpha")
public class StaticRagPreloadServiceImpl implements StaticRagPreloadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(StaticRagPreloadServiceImpl.class);
  private static final String TAG_DOCUMENT_ID = "documentId";

  private final DocumentIngestionService documentIngestionService;
  private final RagPreloadRecordRepository ragPreloadRecordRepository;
  private final StaticRagPreloadProperties preloadProperties;
  private final MeterRegistry meterRegistry;

  public StaticRagPreloadServiceImpl(
      @NonNull DocumentIngestionService documentIngestionService,
      @NonNull RagPreloadRecordRepository ragPreloadRecordRepository,
      @NonNull StaticRagPreloadProperties preloadProperties,
      @NonNull MeterRegistry meterRegistry) {
    this.documentIngestionService = documentIngestionService;
    this.ragPreloadRecordRepository = ragPreloadRecordRepository;
    this.preloadProperties = preloadProperties;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void preloadAll() {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      List<StaticRagPreloadProperties.StaticDocEntry> docs = preloadProperties.docs() == null ? List.of()
          : preloadProperties.docs();
      for (StaticRagPreloadProperties.StaticDocEntry entry : docs) {
        try {
          preloadDocument(entry.id(), entry.sourcePath());
        } catch (Exception exception) {
          String hash = resolveHashOrNull(entry.sourcePath());
          persistFailedRecord(entry.id(), hash, entry.sourcePath());
          LOGGER.warn(
              "Preload failed for document_id={} error={}",
              entry.id(),
              exception.getMessage(),
              exception);
        }
      }
    } finally {
      sample.stop(Timer.builder("mcp.rag.preload.duration")
          .description("Total time to preload all static RAG documents")
          .register(meterRegistry));
    }
  }

  private void preloadDocument(@NonNull String documentId, @NonNull String sourcePath) throws IOException {
    Resource resource = new ClassPathResource(resourcePath(sourcePath));
    byte[] bytes = resource.getContentAsByteArray();
    String content = new String(bytes, StandardCharsets.UTF_8);

    String hash = computeHash(bytes);

    Optional<RagPreloadRecord> prior = ragPreloadRecordRepository
        .findFirstByDocumentIdAndStatusOrderByLoadedAtDesc(documentId, RagPreloadStatus.LOADED);
    if (prior.isPresent()
        && prior.get().getContentHash().equals(hash)) {
      LOGGER.info("Skipping unchanged RAG document document_id={}", documentId);
      persistRecord(documentId, hash, sourcePath, RagPreloadStatus.SKIPPED);
      meterRegistry.counter("mcp.rag.preload.skipped", TAG_DOCUMENT_ID, documentId).increment();
      return;
    }

    Map<String, Object> metadata = Map.of("document_id", documentId, "source_path", sourcePath);
    documentIngestionService.submitDocument(content, metadata);
    LOGGER.info("Submitted RAG preload document_id={} hash={}", documentId, hash);
    persistRecord(documentId, hash, sourcePath, RagPreloadStatus.LOADED);
    meterRegistry.counter("mcp.rag.preload.loaded", TAG_DOCUMENT_ID, documentId).increment();
  }

  private void persistFailedRecord(
      @NonNull String documentId, @Nullable String hash, @NonNull String sourcePath) {
    persistRecord(documentId, hash != null ? hash : "", sourcePath, RagPreloadStatus.FAILED);
    meterRegistry.counter("mcp.rag.preload.failed", TAG_DOCUMENT_ID, documentId).increment();
  }

  private void persistRecord(
      @NonNull String documentId,
      @NonNull String hash,
      @NonNull String sourcePath,
      @NonNull RagPreloadStatus status) {
    var preloadRecord = new RagPreloadRecord();
    preloadRecord.setDocumentId(documentId);
    preloadRecord.setContentHash(hash);
    preloadRecord.setSourcePath(sourcePath);
    preloadRecord.setStatus(status);
    ragPreloadRecordRepository.save(preloadRecord);
  }

  private @Nullable String resolveHashOrNull(@NonNull String sourcePath) {
    try {
      Resource resource = new ClassPathResource(resourcePath(sourcePath));
      return computeHash(resource.getContentAsByteArray());
    } catch (Exception _) {
      return null;
    }
  }

  private static @NonNull String computeHash(@NonNull byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(bytes);
      StringBuilder sb = new StringBuilder();
      for (byte b : hashBytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 not available", exception);
    }
  }

  private static @NonNull String resourcePath(@NonNull String sourcePath) {
    return sourcePath.startsWith("classpath:") ? sourcePath.substring("classpath:".length()) : sourcePath;
  }
}