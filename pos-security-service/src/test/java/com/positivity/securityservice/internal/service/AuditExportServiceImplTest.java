package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.config.AuditExportProperties;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditExportDownload;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.entity.AuditExportFile;
import com.positivity.securityservice.internal.entity.AuditExportJob;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.exception.AuditExportNotReadyException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookUnsupportedException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.AuditExportFileRepository;
import com.positivity.securityservice.internal.repository.AuditExportJobRepository;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("AuditExportServiceImpl - persisted, tenant-scoped audit exports (#2408)")
class AuditExportServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Map<UUID, AuditExportJob> jobs = new HashMap<>();
    private final Map<UUID, AuditExportFile> files = new HashMap<>();
    private final List<Runnable> queued = new ArrayList<>();

    private AuditExportJobRepository jobRepository;
    private AuditExportFileRepository fileRepository;
    private AuditLogEventRepository auditLogEventRepository;

    @BeforeEach
    void setUp() {
        jobRepository = mock(AuditExportJobRepository.class);
        fileRepository = mock(AuditExportFileRepository.class);
        auditLogEventRepository = mock(AuditLogEventRepository.class);
        when(jobRepository.save(any(AuditExportJob.class))).thenAnswer(inv -> {
            AuditExportJob job = inv.getArgument(0);
            if (job.getJobId() == null) {
                job.setJobId(UUID.randomUUID());
            }
            jobs.put(job.getJobId(), job);
            return job;
        });
        when(jobRepository.findByJobId(any())).thenAnswer(inv -> Optional.ofNullable(jobs.get(inv.getArgument(0))));
        when(fileRepository.save(any(AuditExportFile.class))).thenAnswer(inv -> {
            AuditExportFile file = inv.getArgument(0);
            files.put(file.getJobId(), file);
            return file;
        });
        when(fileRepository.findByJobId(any())).thenAnswer(inv -> Optional.ofNullable(files.get(inv.getArgument(0))));
    }

    private AuditExportServiceImpl service(Executor executor, Clock clock, int maxRows) {
        return new AuditExportServiceImpl(
                jobRepository,
                fileRepository,
                auditLogEventRepository,
                objectMapper,
                clock,
                new AuditExportProperties(maxRows, Duration.ofMinutes(30)),
                executor,
                mock(PlatformTransactionManager.class));
    }

    private AuditExportServiceImpl inlineService() {
        return service(Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), 100);
    }

    private AuditExportServiceImpl queuingService(Clock clock) {
        return service(queued::add, clock, 100);
    }

    @SuppressWarnings("unchecked")
    private void auditEvents(AuditLogEvent... events) {
        when(auditLogEventRepository.count(any(Specification.class))).thenReturn((long) events.length);
        when(auditLogEventRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(events));
    }

    private static AuditLogEvent event(String entityId, String newValue) {
        AuditLogEvent event = new AuditLogEvent();
        event.setEventId(UUID.fromString("01960000-0000-7000-8000-000000000001"));
        event.setTimestamp(Instant.parse("2026-10-01T08:00:00Z"));
        event.setEventType("ROLE_ASSIGNED");
        event.setActorId("jane.doe");
        event.setEntityId(entityId);
        event.setEntityType("USER");
        event.setOldValue("{}");
        event.setNewValue(newValue);
        return event;
    }

    private static AuditExportRequest request(AuditExportFormat format, AuditDeliveryMode mode) {
        return AuditExportRequest.builder()
                .format(format)
                .deliveryMode(mode)
                .filters(AuditEventSearchFilter.builder()
                        .eventType("ROLE_ASSIGNED")
                        .build())
                .build();
    }

    @Test
    @DisplayName("CSV DOWNLOAD job runs PENDING -> IN_PROGRESS -> COMPLETED and exposes the file")
    void csvExportCompletes() {
        auditEvents(event("user-1", "{\"roles\":[\"CLERK\"]}"));
        AuditExportServiceImpl service = inlineService();

        AuditExportJobResponse created =
                service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD));

        assertThat(created.getStatus()).isEqualTo(AuditExportStatus.PENDING);
        assertThat(created.getDownloadUrl()).isNull();
        AuditExportJobResponse polled = service.getExportJob(created.getJobId());
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
        assertThat(polled.getCompletedAt()).isEqualTo(NOW);
        assertThat(polled.getRowCount()).isEqualTo(1L);
        assertThat(polled.getErrorMessage()).isNull();
        assertThat(polled.getDownloadUrl())
                .isEqualTo("/security-service/v1/audit/exports/" + created.getJobId() + "/download");
        assertThat(jobs.get(created.getJobId()).getStartedAt()).isEqualTo(NOW);
        assertThat(jobs.get(created.getJobId()).getFilterEventType()).isEqualTo("ROLE_ASSIGNED");

        AuditExportDownload download = service.getExportFile(created.getJobId());
        assertThat(download.contentType()).isEqualTo("text/csv");
        assertThat(download.fileName()).isEqualTo("audit-export-" + created.getJobId() + ".csv");
        String csv = new String(download.content(), StandardCharsets.UTF_8);
        assertThat(csv)
                .startsWith(AuditExportCsv.HEADER + "\r\n")
                .contains("user-1,USER,{},\"{\"\"roles\"\":[\"\"CLERK\"\"]}\",");
    }

    @Test
    @DisplayName("JSON job renders a JSON array of the matching events")
    void jsonExportCompletes() {
        auditEvents(event("user-1", "{}"), event("user-2", "{}"));
        AuditExportServiceImpl service = inlineService();

        UUID jobId = service.requestExport(request(AuditExportFormat.JSON, AuditDeliveryMode.DOWNLOAD))
                .getJobId();

        AuditExportDownload download = service.getExportFile(jobId);
        assertThat(download.contentType()).isEqualTo("application/json");
        assertThat(download.fileName()).endsWith(".json");
        JsonNode array = objectMapper.readTree(download.content());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(1).get("entityId").asString()).isEqualTo("user-2");
        assertThat(array.get(0).get("timestamp").asString()).isEqualTo("2026-10-01T08:00:00Z");
    }

    @Test
    @DisplayName("more matching events than max-rows FAILS the job with a narrow-the-filters message")
    @SuppressWarnings("unchecked")
    void exceedingTheRowCapFails() {
        when(auditLogEventRepository.count(any(Specification.class))).thenReturn(101L);
        AuditExportServiceImpl service = inlineService();

        UUID jobId = service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).contains("101", "limit of 100", "narrow the filters");
        assertThat(polled.getDownloadUrl()).isNull();
        verify(auditLogEventRepository, never()).findAll(any(Specification.class), any(Sort.class));
        verify(fileRepository, never()).save(any());
    }

    @Test
    @DisplayName("a query failure FAILS the job with a sanitised message, never the exception text")
    @SuppressWarnings("unchecked")
    void queryFailureFailsSanitised() {
        when(auditLogEventRepository.count(any(Specification.class))).thenReturn(1L);
        when(auditLogEventRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenThrow(new IllegalStateException("org.postgresql.util.PSQLException: relation secret_table"));
        AuditExportServiceImpl service = inlineService();

        UUID jobId = service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage())
                .isEqualTo(AuditExportServiceImpl.GENERIC_FAILURE_MESSAGE)
                .doesNotContain("PSQL", "secret_table");
        assertThat(polled.getCompletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.getExportFile(jobId)).isInstanceOf(AuditExportNotReadyException.class);
    }

    @Test
    @DisplayName("WEBHOOK delivery is refused before anything is persisted")
    void webhookIsRejected() {
        AuditExportServiceImpl service = inlineService();

        assertThatThrownBy(() -> service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.WEBHOOK)))
                .isInstanceOf(AuditExportWebhookUnsupportedException.class);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("an inverted date window is a validation error, as on searchAuditEvents")
    void invertedDatesAreRejected() {
        AuditExportServiceImpl service = inlineService();
        AuditExportRequest request = AuditExportRequest.builder()
                .format(AuditExportFormat.CSV)
                .deliveryMode(AuditDeliveryMode.DOWNLOAD)
                .filters(AuditEventSearchFilter.builder()
                        .fromDate(Instant.parse("2026-10-02T00:00:00Z"))
                        .toDate(Instant.parse("2026-10-01T00:00:00Z"))
                        .build())
                .build();

        assertThatThrownBy(() -> service.requestExport(request)).isInstanceOf(SecurityValidationException.class);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("a job left PENDING past stale-after is marked FAILED as interrupted on read, and never runs")
    void staleJobIsInterruptedOnRead() {
        UUID jobId = queuingService(Clock.fixed(NOW, ZoneOffset.UTC))
                .requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();
        AuditExportServiceImpl later = queuingService(Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

        AuditExportJobResponse polled = later.getExportJob(jobId);

        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).isEqualTo(AuditExportServiceImpl.INTERRUPTED_MESSAGE);
        queued.forEach(Runnable::run);
        assertThat(jobs.get(jobId).getStatus())
                .as("the late task leaves it FAILED")
                .isEqualTo(AuditExportStatus.FAILED);
        verify(fileRepository, never()).save(any());
    }

    @Test
    @DisplayName("a job still within stale-after stays PENDING on read")
    void freshPendingJobStaysPending() {
        UUID jobId = queuingService(Clock.fixed(NOW, ZoneOffset.UTC))
                .requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();
        AuditExportServiceImpl later = queuingService(Clock.fixed(NOW.plus(Duration.ofMinutes(29)), ZoneOffset.UTC));

        assertThat(later.getExportJob(jobId).getStatus()).isEqualTo(AuditExportStatus.PENDING);
        assertThatThrownBy(() -> later.getExportFile(jobId))
                .isInstanceOf(AuditExportNotReadyException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("an executor that refuses the task FAILS the job instead of leaving it PENDING")
    void rejectedDispatchFails() {
        AuditExportServiceImpl service = service(
                task -> {
                    throw new RejectedExecutionException("queue full");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                100);

        UUID jobId = service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();

        assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(jobs.get(jobId).getErrorMessage()).isEqualTo(AuditExportServiceImpl.QUEUE_FULL_MESSAGE);
    }

    @Test
    @DisplayName("an unknown job id (or another tenant's, hidden by the tenant-scoped repository) is not found")
    void unknownJobIsNotFound() {
        AuditExportServiceImpl service = inlineService();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getExportJob(unknown)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.getExportFile(unknown)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("runExport leaves a job that is no longer PENDING alone")
    @SuppressWarnings("unchecked")
    void runExportIgnoresClaimedJob() {
        AuditExportJob job = new AuditExportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus(AuditExportStatus.COMPLETED);
        jobs.put(job.getJobId(), job);

        inlineService().runExport(job.getJobId());

        assertThat(job.getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
        verify(auditLogEventRepository, never()).count(any(Specification.class));
    }
}
