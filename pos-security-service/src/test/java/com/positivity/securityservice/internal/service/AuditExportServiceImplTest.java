package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.client.AuditExportWebhookSender;
import com.positivity.securityservice.internal.client.LocalWebhookServer;
import com.positivity.securityservice.internal.config.AuditExportProperties;
import com.positivity.securityservice.internal.config.AuditExportWebhookProperties;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditExportDownload;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.entity.AuditExportFile;
import com.positivity.securityservice.internal.entity.AuditExportJob;
import com.positivity.securityservice.internal.entity.AuditExportWebhook;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportDeliveryStatus;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.exception.AuditExportNotReadyException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.AuditExportFileRepository;
import com.positivity.securityservice.internal.repository.AuditExportJobRepository;
import com.positivity.securityservice.internal.repository.AuditExportWebhookRepository;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import jakarta.persistence.EntityNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor.SpecificationFluentQuery;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The repositories are in-memory fakes that honour the status-conditional updates the way the
 * JPQL does, and the audit-event "table" hands out keyset pages of at most the requested limit, so
 * the paging, the caps and the worker/sweep races are exercised as the database would see them.
 */
@DisplayName("AuditExportServiceImpl - persisted, tenant-scoped audit exports (#2408)")
class AuditExportServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final AuditExportProperties DEFAULTS = new AuditExportProperties(
            100, 1_000_000, Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5));
    private static final AuditExportWebhookProperties WEBHOOK_DEFAULTS =
            new AuditExportWebhookProperties(3, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10));

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Map<UUID, AuditExportJob> jobs = new HashMap<>();
    private final Map<UUID, AuditExportFile> files = new HashMap<>();
    private final List<Runnable> queued = new ArrayList<>();
    /** The audit_log_events rows in event-id order, and how far the export's keyset cursor has read. */
    private final List<AuditLogEvent> events = new ArrayList<>();

    private final List<Integer> requestedLimits = new ArrayList<>();
    private int cursor;
    private Runnable afterEachPage = () -> {};

    private AuditExportJobRepository jobRepository;
    private AuditExportFileRepository fileRepository;
    private AuditLogEventRepository auditLogEventRepository;

    /** #2497: the tenant's one registered destination (the webhook "table"), and the waits between attempts. */
    private AuditExportWebhookRepository webhookRepository;

    private AuditExportWebhook registered;
    private final List<Duration> sleeps = new ArrayList<>();
    /** #2497: a mock unless a test wires the real sender against a local receiver. */
    private AuditExportWebhookSender webhookSender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobRepository = mock(AuditExportJobRepository.class);
        fileRepository = mock(AuditExportFileRepository.class);
        auditLogEventRepository = mock(AuditLogEventRepository.class);
        webhookRepository = mock(AuditExportWebhookRepository.class);
        webhookSender = mock(AuditExportWebhookSender.class);
        when(webhookRepository.findFirstByOrderByRegisteredAtDesc()).thenAnswer(inv -> Optional.ofNullable(registered));
        when(jobRepository.recordDelivery(any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuditExportJob job = jobs.get(inv.getArgument(0));
                    if (job == null) {
                        return 0;
                    }
                    job.setDeliveryStatus(inv.getArgument(1));
                    job.setDeliveryAttempts(inv.getArgument(2));
                    job.setDeliveredAt(inv.getArgument(3));
                    job.setDeliveryError(inv.getArgument(4));
                    return 1;
                });
        when(jobRepository.save(any(AuditExportJob.class))).thenAnswer(inv -> {
            AuditExportJob job = inv.getArgument(0);
            if (job.getJobId() == null) {
                job.setJobId(UUID.randomUUID());
            }
            jobs.put(job.getJobId(), job);
            return job;
        });
        when(jobRepository.findByJobId(any())).thenAnswer(inv -> Optional.ofNullable(jobs.get(inv.getArgument(0))));
        when(jobRepository.markInProgress(any(), any()))
                .thenAnswer(inv -> transition(inv.getArgument(0), List.of(AuditExportStatus.PENDING), job -> {
                    job.setStatus(AuditExportStatus.IN_PROGRESS);
                    job.setStartedAt(inv.getArgument(1));
                }));
        when(jobRepository.markCompleted(any(), anyLong(), any()))
                .thenAnswer(inv -> transition(inv.getArgument(0), List.of(AuditExportStatus.IN_PROGRESS), job -> {
                    job.setStatus(AuditExportStatus.COMPLETED);
                    job.setRowCount(inv.getArgument(1));
                    job.setCompletedAt(inv.getArgument(2));
                }));
        when(jobRepository.markFailed(any(), anyCollection(), anyString(), any()))
                .thenAnswer(inv -> transition(
                        inv.getArgument(0),
                        inv.getArgument(1),
                        job -> fail(job, inv.getArgument(2), inv.getArgument(3))));
        when(jobRepository.failRequestedBefore(anyCollection(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    Collection<AuditExportStatus> from = inv.getArgument(0);
                    Instant cutoff = inv.getArgument(1);
                    int changed = 0;
                    for (AuditExportJob job : jobs.values()) {
                        if (from.contains(job.getStatus())
                                && job.getRequestedAt().isBefore(cutoff)) {
                            fail(job, inv.getArgument(2), inv.getArgument(3));
                            changed++;
                        }
                    }
                    return changed;
                });
        when(jobRepository.deleteExpired(any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0);
            List<UUID> expired = jobs.values().stream()
                    .filter(job -> (job.getCompletedAt() != null ? job.getCompletedAt() : job.getRequestedAt())
                            .isBefore(cutoff))
                    .map(AuditExportJob::getJobId)
                    .toList();
            expired.forEach(jobs::remove);
            return expired.size();
        });
        when(fileRepository.save(any(AuditExportFile.class))).thenAnswer(inv -> {
            AuditExportFile file = inv.getArgument(0);
            files.put(file.getJobId(), file);
            return file;
        });
        when(fileRepository.findByJobId(any())).thenAnswer(inv -> Optional.ofNullable(files.get(inv.getArgument(0))));
        when(fileRepository.deleteForExpiredJobs(any())).thenAnswer(inv -> {
            Instant cutoff = inv.getArgument(0);
            List<UUID> doomed = files.keySet().stream()
                    .filter(id -> jobs.containsKey(id)
                            && (jobs.get(id).getCompletedAt() != null
                                            ? jobs.get(id).getCompletedAt()
                                            : jobs.get(id).getRequestedAt())
                                    .isBefore(cutoff))
                    .toList();
            doomed.forEach(files::remove);
            return doomed.size();
        });
        doAnswer(inv -> {
                    SpecificationFluentQuery<AuditLogEvent> query = mock(SpecificationFluentQuery.class, RETURNS_SELF);
                    List<List<AuditLogEvent>> served = new ArrayList<>();
                    doAnswer(limitInv -> {
                                int limit = limitInv.getArgument(0);
                                requestedLimits.add(limit);
                                served.add(
                                        List.copyOf(events.subList(cursor, Math.min(events.size(), cursor + limit))));
                                cursor += served.getLast().size();
                                afterEachPage.run();
                                return query;
                            })
                            .when(query)
                            .limit(anyInt());
                    doAnswer(allInv -> served.getLast()).when(query).all();
                    Function<SpecificationFluentQuery<AuditLogEvent>, ?> fn = inv.getArgument(1);
                    return fn.apply(query);
                })
                .when(auditLogEventRepository)
                .findBy(any(Specification.class), any(Function.class));
    }

    private int transition(
            UUID jobId, Collection<AuditExportStatus> from, java.util.function.Consumer<AuditExportJob> to) {
        AuditExportJob job = jobs.get(jobId);
        if (job == null || !from.contains(job.getStatus())) {
            return 0;
        }
        to.accept(job);
        return 1;
    }

    private static void fail(AuditExportJob job, String message, Instant at) {
        job.setStatus(AuditExportStatus.FAILED);
        job.setErrorMessage(message);
        job.setCompletedAt(at);
    }

    private AuditExportServiceImpl service(Executor executor, Clock clock, AuditExportProperties properties) {
        return service(executor, clock, properties, sleeps::add);
    }

    private AuditExportServiceImpl service(
            Executor executor, Clock clock, AuditExportProperties properties, AuditExportServiceImpl.Sleeper sleeper) {
        return new AuditExportServiceImpl(
                jobRepository,
                fileRepository,
                auditLogEventRepository,
                webhookRepository,
                webhookSender,
                objectMapper,
                clock,
                properties,
                WEBHOOK_DEFAULTS,
                executor,
                mock(PlatformTransactionManager.class),
                sleeper);
    }

    private AuditExportServiceImpl inlineService(AuditExportProperties properties) {
        return service(Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }

    private AuditExportServiceImpl inlineService() {
        return inlineService(DEFAULTS);
    }

    private AuditExportServiceImpl queuingService(Instant at) {
        return service(queued::add, Clock.fixed(at, ZoneOffset.UTC), DEFAULTS);
    }

    private static AuditExportProperties caps(int maxRows, long maxBytes) {
        return new AuditExportProperties(
                maxRows, maxBytes, Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5));
    }

    private void auditEvents(int count, String newValue) {
        for (int i = 0; i < count; i++) {
            events.add(event("user-" + i, newValue));
        }
    }

    private static AuditLogEvent event(String entityId, String newValue) {
        AuditLogEvent event = new AuditLogEvent();
        event.setEventId(UUID.randomUUID());
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

    private UUID requestCsv(AuditExportServiceImpl service) {
        return service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.DOWNLOAD))
                .getJobId();
    }

    @Test
    @DisplayName("CSV DOWNLOAD job runs PENDING -> IN_PROGRESS -> COMPLETED and exposes the file")
    void csvExportCompletes() {
        events.add(event("user-1", "{\"roles\":[\"CLERK\"]}"));
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
        assertThat(files.get(created.getJobId()).getSizeBytes()).isEqualTo(download.content().length);
    }

    @Test
    @DisplayName("events are read in keyset pages of at most PAGE_SIZE and all land in the file")
    void exportPagesThroughEvents() {
        auditEvents(AuditExportServiceImpl.PAGE_SIZE * 2 + 3, "{}");
        AuditExportServiceImpl service = inlineService(caps(10_000, 10_000_000));

        UUID jobId = requestCsv(service);

        assertThat(service.getExportJob(jobId).getRowCount()).isEqualTo(AuditExportServiceImpl.PAGE_SIZE * 2L + 3);
        assertThat(requestedLimits).hasSize(3).allMatch(limit -> limit == AuditExportServiceImpl.PAGE_SIZE);
        String csv = new String(service.getExportFile(jobId).content(), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(AuditExportServiceImpl.PAGE_SIZE * 2 + 3 + 1);
    }

    @Test
    @DisplayName("JSON job renders a JSON array of the matching events")
    void jsonExportCompletes() {
        events.add(event("user-1", "{}"));
        events.add(event("user-2", "{}"));
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
    @DisplayName("an empty match completes with an empty JSON array")
    void emptyJsonExport() {
        AuditExportServiceImpl service = inlineService();

        UUID jobId = service.requestExport(request(AuditExportFormat.JSON, AuditDeliveryMode.DOWNLOAD))
                .getJobId();

        assertThat(new String(service.getExportFile(jobId).content(), StandardCharsets.UTF_8))
                .isEqualTo("[]");
        assertThat(service.getExportJob(jobId).getRowCount()).isZero();
    }

    @Test
    @DisplayName("more matching events than max-rows FAILS the job; at most max-rows + 1 are read")
    void exceedingTheRowCapFails() {
        auditEvents(8, "{}");
        AuditExportServiceImpl service = inlineService(caps(5, 1_000_000));

        UUID jobId = requestCsv(service);

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).contains("more than 5", "narrow the filters");
        assertThat(polled.getDownloadUrl()).isNull();
        assertThat(requestedLimits).containsExactly(6);
        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("exactly max-rows events completes")
    void exactlyTheRowCapCompletes() {
        auditEvents(5, "{}");
        AuditExportServiceImpl service = inlineService(caps(5, 1_000_000));

        UUID jobId = requestCsv(service);

        assertThat(service.getExportJob(jobId).getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
        assertThat(service.getExportJob(jobId).getRowCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("events inserted while the export pages still count against max-rows (no stale count)")
    void rowsInsertedMidExportStillHitTheCap() {
        int cap = AuditExportServiceImpl.PAGE_SIZE + 10;
        auditEvents(AuditExportServiceImpl.PAGE_SIZE, "{}");
        // After the first page is served, more matching events arrive than the cap allows.
        afterEachPage = () -> {
            if (events.size() == AuditExportServiceImpl.PAGE_SIZE) {
                auditEvents(20, "{}");
            }
        };
        AuditExportServiceImpl service = inlineService(caps(cap, 10_000_000));

        UUID jobId = requestCsv(service);

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).contains("more than " + cap);
        assertThat(requestedLimits).containsExactly(AuditExportServiceImpl.PAGE_SIZE, 11);
        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("a file larger than max-bytes FAILS the job while rendering")
    void exceedingTheByteCapFails() {
        auditEvents(3, "x".repeat(400));
        AuditExportServiceImpl service = inlineService(caps(100, 1_000));

        UUID jobId = requestCsv(service);

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).contains("larger than 1000 bytes", "narrow the filters");
        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("the byte count is UTF-8, not chars")
    void utf8LengthCountsEncodedBytes() {
        String text = "aé€😀";
        assertThat(AuditExportServiceImpl.utf8Length(text)).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("a query failure FAILS the job with a sanitised message, never the exception text")
    @SuppressWarnings("unchecked")
    void queryFailureFailsSanitised() {
        doAnswer(inv -> {
                    throw new IllegalStateException("org.postgresql.util.PSQLException: relation secret_table");
                })
                .when(auditLogEventRepository)
                .findBy(any(Specification.class), any(Function.class));
        AuditExportServiceImpl service = inlineService();

        UUID jobId = requestCsv(service);

        AuditExportJobResponse polled = service.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage())
                .isEqualTo(AuditExportServiceImpl.GENERIC_FAILURE_MESSAGE)
                .doesNotContain("PSQL", "secret_table");
        assertThat(polled.getCompletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.getExportFile(jobId)).isInstanceOf(AuditExportNotReadyException.class);
    }

    @Test
    @DisplayName("#2497: WEBHOOK with no registered destination is refused before anything is persisted")
    void webhookWithoutDestinationIsRefused() {
        AuditExportServiceImpl service = inlineService();

        assertThatThrownBy(() -> service.requestExport(request(AuditExportFormat.CSV, AuditDeliveryMode.WEBHOOK)))
                .isInstanceOf(AuditExportWebhookNotRegisteredException.class)
                .satisfies(e -> assertThat(((AuditExportWebhookNotRegisteredException) e).exportRequest())
                        .isTrue());
        verify(jobRepository, never()).save(any());
        verify(webhookSender, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("#2497: a DOWNLOAD job never reads the destination or sends anything")
    void downloadNeverSends() {
        registered = webhook(URI.create("https://siem.example.com/hook"), "secret");
        auditEvents(1, "{}");

        UUID jobId = requestCsv(inlineService());

        assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
        assertThat(jobs.get(jobId).getWebhookUrl()).isNull();
        assertThat(jobs.get(jobId).getDeliveryStatus()).isNull();
        verify(webhookSender, never()).send(any(), any(), any(), any(), any());
    }

    private static AuditExportWebhook webhook(URI url, String secret) {
        AuditExportWebhook webhook = new AuditExportWebhook();
        webhook.setWebhookId(UUID.fromString("01960000-0000-7000-8000-00000000b001"));
        webhook.setUrl(url.toString());
        webhook.setSigningSecret(secret);
        webhook.setRegisteredBy("admin");
        webhook.setRegisteredAt(NOW.minus(Duration.ofDays(1)));
        return webhook;
    }

    /**
     * #2497: WEBHOOK delivery through the real {@link AuditExportWebhookSender} against a local HTTPS
     * receiver: signing, retries with a doubling backoff, exhaustion, no redirects, the destination
     * pinned at request time and re-checked before every attempt.
     */
    @Nested
    @DisplayName("WEBHOOK delivery to a local HTTPS receiver (#2497)")
    class WebhookDelivery {

        private static final String SECRET = "registered-signing-secret-0000000000000000";

        private LocalWebhookServer receiver;

        @BeforeEach
        void receiver() throws Exception {
            receiver = LocalWebhookServer.start();
            webhookSender = receiver.sender(WEBHOOK_DEFAULTS, Clock.fixed(NOW, ZoneOffset.UTC));
            registered = webhook(receiver.url("/hooks/audit"), SECRET);
            events.add(event("user-1", "{}"));
        }

        @AfterEach
        void stop() {
            receiver.close();
        }

        private UUID requestWebhook(AuditExportServiceImpl service) {
            return service.requestExport(request(AuditExportFormat.JSON, AuditDeliveryMode.WEBHOOK))
                    .getJobId();
        }

        private String signature(String secret, String timestamp, byte[] body) throws Exception {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        }

        @Test
        @DisplayName("the job pins the destination and, delivered on the first attempt, completes with its file")
        void deliveredFirstTime() throws Exception {
            AuditExportServiceImpl service = inlineService();

            AuditExportJobResponse created =
                    service.requestExport(request(AuditExportFormat.JSON, AuditDeliveryMode.WEBHOOK));

            assertThat(created.getWebhookUrl()).isEqualTo(registered.getUrl());
            AuditExportJob job = jobs.get(created.getJobId());
            assertThat(job.getWebhookId()).isEqualTo(registered.getWebhookId());
            AuditExportJobResponse polled = service.getExportJob(created.getJobId());
            assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
            assertThat(polled.getDeliveryMode()).isEqualTo(AuditDeliveryMode.WEBHOOK);
            assertThat(polled.getDeliveryStatus()).isEqualTo(AuditExportDeliveryStatus.DELIVERED);
            assertThat(polled.getDeliveryAttempts()).isEqualTo(1);
            assertThat(polled.getDeliveredAt()).isEqualTo(NOW);
            assertThat(polled.getDeliveryError()).isNull();
            assertThat(polled.getDownloadUrl())
                    .as("a delivered file stays downloadable")
                    .isNotNull();
            assertThat(sleeps).isEmpty();

            assertThat(receiver.received()).hasSize(1);
            LocalWebhookServer.Received delivered = receiver.received().getFirst();
            byte[] file = service.getExportFile(created.getJobId()).content();
            assertThat(delivered.body()).isEqualTo(file);
            assertThat(delivered.header("Content-Type")).startsWith("application/json");
            assertThat(delivered.header(AuditExportWebhookSender.JOB_ID_HEADER))
                    .isEqualTo(created.getJobId().toString());
            String timestamp = delivered.header(AuditExportWebhookSender.TIMESTAMP_HEADER);
            assertThat(timestamp).isEqualTo(Long.toString(NOW.getEpochSecond()));
            assertThat(delivered.header(AuditExportWebhookSender.SIGNATURE_HEADER))
                    .isEqualTo(signature(SECRET, timestamp, file));
        }

        @Test
        @DisplayName("a failed attempt is retried after the backoff, and a later 2xx completes the job")
        void retryThenSuccess() {
            receiver.replyWith(503, 200);

            UUID jobId = requestWebhook(inlineService());

            AuditExportJob job = jobs.get(jobId);
            assertThat(job.getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
            assertThat(job.getDeliveryStatus()).isEqualTo(AuditExportDeliveryStatus.DELIVERED);
            assertThat(job.getDeliveryAttempts()).isEqualTo(2);
            assertThat(job.getDeliveryError()).isNull();
            assertThat(receiver.received()).hasSize(2);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
            assertThat(files).containsKey(jobId);
        }

        @Test
        @DisplayName("every attempt failing FAILS the job: attempts and last error recorded, doubling waits, no file")
        void retryExhaustion() {
            receiver.replyWith(503, 502, 503);
            AuditExportServiceImpl service = inlineService();

            UUID jobId = requestWebhook(service);

            AuditExportJobResponse polled = service.getExportJob(jobId);
            assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
            assertThat(polled.getDeliveryStatus()).isEqualTo(AuditExportDeliveryStatus.FAILED);
            assertThat(polled.getDeliveryAttempts()).isEqualTo(3);
            assertThat(polled.getDeliveryError()).isEqualTo("HTTP 503");
            assertThat(polled.getDeliveredAt()).isNull();
            assertThat(polled.getErrorMessage())
                    .isEqualTo(AuditExportServiceImpl.UNDELIVERED_MESSAGE.formatted(3, "HTTP 503"))
                    .doesNotContain("receiver body");
            assertThat(polled.getDownloadUrl()).isNull();
            assertThat(receiver.received()).hasSize(3);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
            assertThat(files).as("an undelivered export keeps no file").isEmpty();
            assertThatThrownBy(() -> service.getExportFile(jobId)).isInstanceOf(AuditExportNotReadyException.class);
        }

        @Test
        @DisplayName("a redirect is not followed: the Location is never requested and the job FAILS")
        void redirectNotFollowed() {
            String elsewhere = receiver.url("/elsewhere").toString();
            receiver.redirectTo(302, elsewhere).redirectTo(302, elsewhere).redirectTo(302, elsewhere);

            UUID jobId = requestWebhook(inlineService());

            assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.FAILED);
            assertThat(jobs.get(jobId).getDeliveryError()).isEqualTo("HTTP 302 (redirect not followed)");
            assertThat(receiver.received())
                    .extracting(LocalWebhookServer.Received::path)
                    .containsOnly("/hooks/audit");
        }

        @Test
        @DisplayName("the destination is re-checked at delivery: a private answer now sends nothing and is not retried")
        void deliveryTimeAddressRecheck() throws Exception {
            webhookSender = receiver.senderResolvingTo(
                    WEBHOOK_DEFAULTS, Clock.fixed(NOW, ZoneOffset.UTC), java.net.InetAddress.getByName("10.0.0.7"));

            UUID jobId = requestWebhook(inlineService());

            AuditExportJob job = jobs.get(jobId);
            assertThat(job.getStatus()).isEqualTo(AuditExportStatus.FAILED);
            assertThat(job.getDeliveryStatus()).isEqualTo(AuditExportDeliveryStatus.FAILED);
            assertThat(job.getDeliveryAttempts()).isEqualTo(1);
            assertThat(job.getDeliveryError()).isEqualTo(AuditExportWebhookSender.DESTINATION_REFUSED);
            assertThat(job.getErrorMessage()).isEqualTo(AuditExportServiceImpl.DESTINATION_REFUSED_MESSAGE);
            assertThat(receiver.received()).isEmpty();
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("a destination re-registered to another URL after the request is never sent to")
        void destinationChangedIsNotDelivered() {
            AuditExportServiceImpl worker = queuingService(NOW);
            UUID jobId = requestWebhook(worker);
            registered = webhook(URI.create("https://attacker.example.net/collect"), "new-secret");

            queued.forEach(Runnable::run);

            AuditExportJob job = jobs.get(jobId);
            assertThat(job.getStatus()).isEqualTo(AuditExportStatus.FAILED);
            assertThat(job.getDeliveryAttempts()).isZero();
            assertThat(job.getDeliveryError()).isEqualTo(AuditExportServiceImpl.DESTINATION_CHANGED_ERROR);
            assertThat(job.getErrorMessage()).isEqualTo(AuditExportServiceImpl.DESTINATION_CHANGED_MESSAGE);
            assertThat(receiver.received()).isEmpty();
        }

        @Test
        @DisplayName("a removed and re-registered destination (a new id) is not used, even at the same URL")
        void reRegisteredDestinationIsNotDelivered() {
            AuditExportServiceImpl worker = queuingService(NOW);
            UUID jobId = requestWebhook(worker);
            AuditExportWebhook again = webhook(receiver.url("/hooks/audit"), SECRET);
            again.setWebhookId(UUID.fromString("01960000-0000-7000-8000-00000000b002"));
            registered = again;

            queued.forEach(Runnable::run);

            assertThat(jobs.get(jobId).getDeliveryError()).isEqualTo(AuditExportServiceImpl.DESTINATION_CHANGED_ERROR);
            assertThat(receiver.received()).isEmpty();
        }

        @Test
        @DisplayName("a secret rotated after the request (same registration, same URL) signs the delivery")
        void rotatedSecretSigns() throws Exception {
            AuditExportServiceImpl worker = queuingService(NOW);
            UUID jobId = requestWebhook(worker);
            registered.setSigningSecret("rotated-secret");

            queued.forEach(Runnable::run);

            assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
            LocalWebhookServer.Received delivered = receiver.received().getFirst();
            assertThat(delivered.header(AuditExportWebhookSender.SIGNATURE_HEADER))
                    .isEqualTo(signature(
                            "rotated-secret",
                            delivered.header(AuditExportWebhookSender.TIMESTAMP_HEADER),
                            delivered.body()));
        }

        @Test
        @DisplayName("a destination removed between attempts stops the retries")
        void removalStopsRetries() {
            receiver.replyWith(503, 200);
            AuditExportServiceImpl service =
                    service(Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), DEFAULTS, duration -> registered = null);

            UUID jobId = requestWebhook(service);

            AuditExportJob job = jobs.get(jobId);
            assertThat(job.getStatus()).isEqualTo(AuditExportStatus.FAILED);
            assertThat(job.getDeliveryAttempts()).isEqualTo(1);
            assertThat(job.getDeliveryError()).isEqualTo(AuditExportServiceImpl.DESTINATION_CHANGED_ERROR);
            assertThat(receiver.received()).hasSize(1);
        }

        @Test
        @DisplayName("an interrupted wait between attempts FAILS the job and keeps the interrupt")
        void interruptedBackoff() {
            receiver.replyWith(503);
            AuditExportServiceImpl service =
                    service(Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), DEFAULTS, duration -> {
                        throw new InterruptedException("shutdown");
                    });

            try {
                UUID jobId = requestWebhook(service);

                assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.FAILED);
                assertThat(jobs.get(jobId).getDeliveryError()).isEqualTo(AuditExportServiceImpl.INTERRUPTED_ERROR);
                assertThat(jobs.get(jobId).getErrorMessage())
                        .isEqualTo(AuditExportServiceImpl.DELIVERY_INTERRUPTED_MESSAGE);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
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
    @DisplayName("GET is read-only: a job past stale-after is reported as stored, not changed")
    void getNeverMutates() {
        UUID jobId = requestCsv(queuingService(NOW));
        AuditExportServiceImpl later = queuingService(NOW.plus(Duration.ofMinutes(31)));

        assertThat(later.getExportJob(jobId).getStatus()).isEqualTo(AuditExportStatus.PENDING);
        verify(jobRepository, never()).markFailed(any(), anyCollection(), anyString(), any());
        verify(jobRepository, never()).failRequestedBefore(anyCollection(), any(), anyString(), any());
    }

    @Test
    @DisplayName("the sweep fails a job left PENDING past stale-after, and the late task then skips it")
    void sweepInterruptsStaleJob() {
        UUID jobId = requestCsv(queuingService(NOW));
        AuditExportServiceImpl later = queuingService(NOW.plus(Duration.ofMinutes(31)));

        assertThat(later.sweepBoundTenant()).isEqualTo(new AuditExportService.SweepResult(1, 0));

        AuditExportJobResponse polled = later.getExportJob(jobId);
        assertThat(polled.getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(polled.getErrorMessage()).isEqualTo(AuditExportServiceImpl.INTERRUPTED_MESSAGE);
        queued.forEach(Runnable::run);
        assertThat(jobs.get(jobId).getStatus())
                .as("the late task leaves it FAILED")
                .isEqualTo(AuditExportStatus.FAILED);
        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("the sweep leaves a job still within stale-after alone")
    void sweepLeavesFreshJob() {
        UUID jobId = requestCsv(queuingService(NOW));
        AuditExportServiceImpl later = queuingService(NOW.plus(Duration.ofMinutes(29)));

        assertThat(later.sweepBoundTenant()).isEqualTo(new AuditExportService.SweepResult(0, 0));
        assertThat(later.getExportJob(jobId).getStatus()).isEqualTo(AuditExportStatus.PENDING);
        assertThatThrownBy(() -> later.getExportFile(jobId))
                .isInstanceOf(AuditExportNotReadyException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("a sweep that fails the job while it renders wins: completion writes no file")
    void staleSweepDuringRenderingBeatsCompletion() {
        auditEvents(3, "{}");
        AuditExportServiceImpl worker = queuingService(NOW);
        UUID jobId = requestCsv(worker);
        AuditExportServiceImpl sweeper = queuingService(NOW.plus(Duration.ofMinutes(31)));
        // The sweep runs between the worker's first page and its completion.
        afterEachPage = () -> {
            if (jobs.get(jobId).getStatus() == AuditExportStatus.IN_PROGRESS) {
                sweeper.sweepBoundTenant();
            }
        };

        queued.forEach(Runnable::run);

        assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.FAILED);
        assertThat(jobs.get(jobId).getErrorMessage()).isEqualTo(AuditExportServiceImpl.INTERRUPTED_MESSAGE);
        assertThat(files).as("no orphan file").isEmpty();
        verify(fileRepository, never()).save(any());
    }

    @Test
    @DisplayName("a completed job is not failed by a later sweep")
    void completionBeforeSweepStands() {
        auditEvents(1, "{}");
        UUID jobId = requestCsv(inlineService());

        AuditExportServiceImpl later = queuingService(NOW.plus(Duration.ofMinutes(31)));
        assertThat(later.sweepBoundTenant().interrupted()).isZero();
        assertThat(jobs.get(jobId).getStatus()).isEqualTo(AuditExportStatus.COMPLETED);
        assertThat(files).containsKey(jobId);
    }

    @Test
    @DisplayName("the sweep purges jobs and files past retention; a purged job is not found")
    void sweepPurgesPastRetention() {
        auditEvents(1, "{}");
        UUID old = requestCsv(inlineService());
        UUID recent =
                requestCsv(service(Runnable::run, Clock.fixed(NOW.plus(Duration.ofDays(6)), ZoneOffset.UTC), DEFAULTS));
        AuditExportServiceImpl later =
                queuingService(NOW.plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(later.sweepBoundTenant()).isEqualTo(new AuditExportService.SweepResult(0, 1));

        assertThatThrownBy(() -> later.getExportJob(old)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> later.getExportFile(old)).isInstanceOf(EntityNotFoundException.class);
        assertThat(files).doesNotContainKey(old).containsKey(recent);
        verify(fileRepository).deleteForExpiredJobs(eq(NOW.plusSeconds(1)));
        verify(jobRepository).deleteExpired(eq(NOW.plusSeconds(1)));
    }

    @Test
    @DisplayName("an executor that refuses the task FAILS the job instead of leaving it PENDING")
    void rejectedDispatchFails() {
        AuditExportServiceImpl service = service(
                task -> {
                    throw new RejectedExecutionException("queue full");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                DEFAULTS);

        UUID jobId = requestCsv(service);

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
        verify(auditLogEventRepository, never()).findBy(any(Specification.class), any(Function.class));
    }
}
