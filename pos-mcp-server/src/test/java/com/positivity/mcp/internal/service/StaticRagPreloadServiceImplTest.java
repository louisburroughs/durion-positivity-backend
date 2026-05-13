package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.entity.RagPreloadRecord;
import com.positivity.mcp.internal.enums.RagPreloadStatus;
import com.positivity.mcp.internal.repository.RagPreloadRecordRepository;
import com.positivity.mcp.service.DocumentIngestionJob;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import com.positivity.mcp.service.DocumentIngestionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

/**
 * Unit tests for {@link StaticRagPreloadServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class StaticRagPreloadServiceImplTest {

    private static final String TEST_DOC_ID = "test-doc";
    private static final String TEST_DOC_ID_2 = "test-doc-2";
    private static final String TEST_SOURCE_PATH = "rag-test/test-doc.md";
    private static final String TEST_RAG_SCOPE = "inventory";
    // Hardcoded stub UUID per project convention
    private static final UUID STUB_JOB_ID = UUID.fromString("00000000-0000-7000-8000-000000000300");

    @Mock
    private DocumentIngestionService documentIngestionService;

    @Mock
    private RagPreloadRecordRepository ragPreloadRecordRepository;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient()
                .when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private StaticRagPreloadServiceImpl createService(List<StaticRagPreloadProperties.StaticDocEntry> docs) {
        StaticRagPreloadProperties properties = new StaticRagPreloadProperties(docs);
        return new StaticRagPreloadServiceImpl(
                documentIngestionService, ragPreloadRecordRepository, properties, meterRegistry);
    }

    private static StaticRagPreloadProperties.StaticDocEntry docEntry(String docId) {
        return new StaticRagPreloadProperties.StaticDocEntry(docId, TEST_SOURCE_PATH, TEST_RAG_SCOPE);
    }

    private static String computeFileHash() throws Exception {
        ClassPathResource resource = new ClassPathResource(TEST_SOURCE_PATH);
        byte[] bytes = resource.getContentAsByteArray();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static DocumentIngestionJob stubJob(String docId) {
        return stubJobWithStatus(docId, DocumentIngestionJobStatus.PENDING);
    }

    private static DocumentIngestionJob stubJobWithStatus(String docId, DocumentIngestionJobStatus status) {
        return new DocumentIngestionJob(
                STUB_JOB_ID,
                docId,
                status,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null,
                null);
    }

    private static RagPreloadRecord loadedRecord(String docId, String hash) {
        return loadedRecord(docId, hash, TEST_RAG_SCOPE);
    }

    private static RagPreloadRecord loadedRecord(String docId, String hash, String ragScope) {
        RagPreloadRecord preloadRecord = new RagPreloadRecord();
        preloadRecord.setDocumentId(docId);
        preloadRecord.setContentHash(hash);
        preloadRecord.setSourcePath(TEST_SOURCE_PATH);
        preloadRecord.setRagScope(ragScope);
        preloadRecord.setStatus(RagPreloadStatus.LOADED);
        return preloadRecord;
    }

    private static RagPreloadRecord queuedRecord(String docId, String hash, UUID jobId) {
        return queuedRecord(docId, hash, TEST_RAG_SCOPE, jobId);
    }

    private static RagPreloadRecord queuedRecord(String docId, String hash, String ragScope, UUID jobId) {
        RagPreloadRecord preloadRecord = new RagPreloadRecord();
        preloadRecord.setDocumentId(docId);
        preloadRecord.setContentHash(hash);
        preloadRecord.setSourcePath(TEST_SOURCE_PATH);
        preloadRecord.setRagScope(ragScope);
        preloadRecord.setStatus(RagPreloadStatus.QUEUED);
        preloadRecord.setJobId(jobId);
        return preloadRecord;
    }

    // ─── preloadAll ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("preloadAll with empty doc list does nothing")
    void preloadAll_emptyDocList_doesNothing() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        verify(documentIngestionService, never()).submitDocument(anyString(), any());
        verify(ragPreloadRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("preloadAll skips document when prior record has same hash and LOADED status")
    void preloadAll_sameHash_noIngestion_skipsDocument() throws Exception {
        String hash = computeFileHash();
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, TEST_RAG_SCOPE, RagPreloadStatus.LOADED))
                .thenReturn(Optional.of(loadedRecord(TEST_DOC_ID, hash)));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of(docEntry(TEST_DOC_ID)));

        service.preloadAll();

        verify(documentIngestionService, never()).submitDocument(anyString(), any());
        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.SKIPPED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.skipped", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("preloadAll ignores loaded record in another rag scope")
    void preloadAll_loadedRecordInDifferentScope_submitsIngestionForCurrentScope() throws Exception {
        String hash = computeFileHash();
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, TEST_RAG_SCOPE, RagPreloadStatus.LOADED))
                .thenReturn(Optional.empty());
        lenient()
                .when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, "master", RagPreloadStatus.LOADED))
                .thenReturn(Optional.of(loadedRecord(TEST_DOC_ID, hash, "master")));
        when(documentIngestionService.submitDocument(anyString(), any())).thenReturn(stubJob(TEST_DOC_ID));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of(docEntry(TEST_DOC_ID)));

        service.preloadAll();

        verify(documentIngestionService).submitDocument(anyString(), any());
        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.QUEUED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        verify(ragPreloadRecordRepository, never())
                .findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, "master", RagPreloadStatus.LOADED);
    }

    @Test
    @DisplayName("preloadAll submits ingestion when prior record has different hash")
    void preloadAll_differentHash_submitsIngestion() {
        String differentHash = "0".repeat(64);
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, TEST_RAG_SCOPE, RagPreloadStatus.LOADED))
                .thenReturn(Optional.of(loadedRecord(TEST_DOC_ID, differentHash)));
        when(documentIngestionService.submitDocument(anyString(), any())).thenReturn(stubJob(TEST_DOC_ID));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of(docEntry(TEST_DOC_ID)));

        service.preloadAll();

        verify(documentIngestionService).submitDocument(anyString(), any());
        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.QUEUED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.loaded", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("preloadAll submits ingestion when no prior record exists")
    void preloadAll_noExistingRecord_submitsIngestion() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, TEST_RAG_SCOPE, RagPreloadStatus.LOADED))
                .thenReturn(Optional.empty());
        when(documentIngestionService.submitDocument(anyString(), any())).thenReturn(stubJob(TEST_DOC_ID));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of(docEntry(TEST_DOC_ID)));

        service.preloadAll();

        verify(documentIngestionService).submitDocument(anyString(), any());
        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.QUEUED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
    }

    @Test
    @DisplayName("preloadAll persists FAILED record for erroring doc and continues to next document")
    void preloadAll_ingestionThrows_persistsFailedRecord_continuesOtherDocs() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        anyString(), anyString(), eq(RagPreloadStatus.LOADED)))
                .thenReturn(Optional.empty());
        when(documentIngestionService.submitDocument(anyString(), any()))
                .thenThrow(new RuntimeException("Ingestion failed"))
                .thenReturn(stubJob(TEST_DOC_ID_2));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of(docEntry(TEST_DOC_ID), docEntry(TEST_DOC_ID_2)));

        service.preloadAll();

        verify(documentIngestionService, times(2)).submitDocument(anyString(), any());
        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository, times(2)).save(captor.capture());

        List<RagPreloadRecord> saved = captor.getAllValues();
        assertThat(saved)
                .extracting(RagPreloadRecord::getStatus)
                .containsExactlyInAnyOrder(RagPreloadStatus.FAILED, RagPreloadStatus.QUEUED);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.failed", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.loaded", "documentId", TEST_DOC_ID_2)
                        .count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("preloadAll submits normalized rag scope in document metadata")
    void preloadAll_submitsNormalizedRagScope() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of());
        when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, "inventory", RagPreloadStatus.LOADED))
                .thenReturn(Optional.empty());
        when(documentIngestionService.submitDocument(anyString(), any())).thenReturn(stubJob(TEST_DOC_ID));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(
                List.of(new StaticRagPreloadProperties.StaticDocEntry(TEST_DOC_ID, TEST_SOURCE_PATH, " INVENTORY ")));

        service.preloadAll();

        verify(documentIngestionService)
                .submitDocument(
                        anyString(),
                        eq(java.util.Map.of(
                                "document_id", TEST_DOC_ID,
                                "source_path", TEST_SOURCE_PATH,
                                "rag_scope", "inventory")));
    }

    @Test
    @DisplayName("reconcileQueuedRecords persists LOADED record when queued job succeeded")
    void reconcileQueuedRecords_jobSucceeded_persistsLoadedRecord() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of(queuedRecord(TEST_DOC_ID, "hash-1", STUB_JOB_ID)));
        lenient()
                .when(ragPreloadRecordRepository.findFirstByDocumentIdAndRagScopeAndStatusOrderByLoadedAtDesc(
                        TEST_DOC_ID, TEST_RAG_SCOPE, RagPreloadStatus.LOADED))
                .thenReturn(Optional.empty());
        when(documentIngestionService.getIngestionJob(STUB_JOB_ID))
                .thenReturn(Optional.of(stubJobWithStatus(TEST_DOC_ID, DocumentIngestionJobStatus.SUCCEEDED)));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.LOADED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.loaded", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reconcileQueuedRecords persists FAILED record when queued job failed")
    void reconcileQueuedRecords_jobFailed_persistsFailedRecord() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of(queuedRecord(TEST_DOC_ID, "hash-1", STUB_JOB_ID)));
        when(documentIngestionService.getIngestionJob(STUB_JOB_ID))
                .thenReturn(Optional.of(stubJobWithStatus(TEST_DOC_ID, DocumentIngestionJobStatus.FAILED)));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.FAILED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.failed", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.loaded", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("reconcileQueuedRecords leaves queued record unchanged when job is pending")
    void reconcileQueuedRecords_jobPending_leavesQueued() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of(queuedRecord(TEST_DOC_ID, "hash-1", STUB_JOB_ID)));
        when(documentIngestionService.getIngestionJob(STUB_JOB_ID))
                .thenReturn(Optional.of(stubJobWithStatus(TEST_DOC_ID, DocumentIngestionJobStatus.PENDING)));

        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        verify(ragPreloadRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("reconcileQueuedRecords persists FAILED record when queued job is not found")
    void reconcileQueuedRecords_jobNotFound_persistsFailedRecord() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of(queuedRecord(TEST_DOC_ID, "hash-1", STUB_JOB_ID)));
        when(documentIngestionService.getIngestionJob(STUB_JOB_ID)).thenReturn(Optional.empty());
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.FAILED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.failed", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reconcileQueuedRecords persists FAILED record when queued record has null jobId")
    void reconcileQueuedRecords_nullJobId_persistsFailedRecord() {
        when(ragPreloadRecordRepository.findAllByStatus(RagPreloadStatus.QUEUED))
                .thenReturn(List.of(queuedRecord(TEST_DOC_ID, "hash-1", null)));
        when(ragPreloadRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaticRagPreloadServiceImpl service = createService(List.of());

        service.preloadAll();

        ArgumentCaptor<RagPreloadRecord> captor = ArgumentCaptor.forClass(RagPreloadRecord.class);
        verify(ragPreloadRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RagPreloadStatus.FAILED);
        assertThat(captor.getValue().getRagScope()).isEqualTo(TEST_RAG_SCOPE);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.failed", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("mcp.rag.preload.loaded", "documentId", TEST_DOC_ID)
                        .count())
                .isEqualTo(0.0);
        verify(documentIngestionService, never()).getIngestionJob(any());
    }
}
