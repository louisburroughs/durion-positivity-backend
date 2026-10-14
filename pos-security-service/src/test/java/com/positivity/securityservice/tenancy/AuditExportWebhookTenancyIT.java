package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.entity.AuditExportWebhook;
import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportDeliveryStatus;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.exception.AuditExportNotReadyException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException;
import com.positivity.securityservice.internal.repository.AuditExportWebhookRepository;
import com.positivity.securityservice.internal.service.AuditExportService;
import com.positivity.securityservice.internal.service.AuditExportWebhookService;
import com.positivity.tenancy.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #2497 on Postgres: the audit export webhook and the delivery columns of a job are tenant rows
 * (V11, ADR-0062). A tenant sees and removes only its own destination, has at most one, and a WEBHOOK
 * job pins its destination and records how delivery went, all under row-level security.
 *
 * <p>Registrations are written through the repository rather than the registration endpoint, whose
 * destination check resolves the host in DNS; the destinations use the {@code .invalid} TLD, which
 * never resolves, so a delivery here fails without touching the network.
 */
@DisplayName("pos-security-service on Postgres: audit export webhook tenant isolation (#2497)")
class AuditExportWebhookTenancyIT extends PostgresTenancyTestBase {

    private static final String A_URL = "https://tenant-a.hooks.invalid/collect";
    private static final String B_URL = "https://tenant-b.hooks.invalid/collect";

    @Autowired
    private AuditExportWebhookRepository webhooks;

    @Autowired
    private AuditExportWebhookService webhookService;

    @Autowired
    private AuditExportService auditExportService;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void noDestinations() {
        asTenant(TENANT_A, webhookService::delete);
        asTenant(TENANT_B, webhookService::delete);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static AuditExportWebhook webhook(String url) {
        AuditExportWebhook webhook = new AuditExportWebhook();
        webhook.setUrl(url);
        webhook.setSigningSecret("it-only-secret-" + UUID.randomUUID());
        webhook.setRegisteredBy("webhook-it");
        webhook.setRegisteredAt(Instant.now());
        return webhook;
    }

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    @Test
    void aDestinationIsTheTenantsOwnAndThereIsOnePerTenant() {
        asTenant(TENANT_A, () -> webhooks.saveAndFlush(webhook(A_URL)));

        asTenant(TENANT_B, () -> {
            assertThatThrownBy(webhookService::get).isInstanceOf(AuditExportWebhookNotRegisteredException.class);
            assertThat(count(new JdbcTemplate(dataSource), "SELECT count(*) FROM audit_export_webhook"))
                    .as("RLS hides tenant A's destination from raw SQL")
                    .isZero();
            webhooks.saveAndFlush(webhook(B_URL));
        });

        asTenant(TENANT_A, () -> {
            assertThat(webhookService.get().getUrl()).isEqualTo(A_URL);
            assertThatThrownBy(() -> webhooks.saveAndFlush(webhook("https://second.hooks.invalid/x")))
                    .as("UNIQUE (tenant_id): one destination per tenant")
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
        asTenant(TENANT_B, () -> assertThat(webhookService.get().getUrl()).isEqualTo(B_URL));

        // Tenant B's DELETE removes its own row only.
        asTenant(TENANT_B, webhookService::delete);
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(count(owner, "SELECT count(*) FROM audit_export_webhook WHERE tenant_id = ?", TENANT_A))
                .isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM audit_export_webhook WHERE tenant_id = ?", TENANT_B))
                .isZero();
    }

    @Test
    void aWebhookExportWithoutADestinationCreatesNoJob() {
        AuditExportRequest request = AuditExportRequest.builder()
                .format(AuditExportFormat.CSV)
                .deliveryMode(AuditDeliveryMode.WEBHOOK)
                .build();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        int before = count(owner, "SELECT count(*) FROM audit_export_jobs WHERE tenant_id = ?", TENANT_B);

        asTenant(
                TENANT_B,
                () -> assertThatThrownBy(() -> auditExportService.requestExport(request))
                        .isInstanceOf(AuditExportWebhookNotRegisteredException.class));

        assertThat(count(owner, "SELECT count(*) FROM audit_export_jobs WHERE tenant_id = ?", TENANT_B))
                .isEqualTo(before);
    }

    @Test
    void aWebhookJobPinsItsDestinationAndRecordsTheFailedDeliveryUnderRls() {
        asTenant(TENANT_A, () -> webhooks.saveAndFlush(webhook(A_URL)));
        AuditExportRequest request = AuditExportRequest.builder()
                .format(AuditExportFormat.JSON)
                .deliveryMode(AuditDeliveryMode.WEBHOOK)
                .build();

        AuditExportJobResponse created = asTenant(TENANT_A, () -> auditExportService.requestExport(request));
        UUID jobId = created.getJobId();
        assertThat(created.getWebhookUrl()).isEqualTo(A_URL);

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> asTenant(
                                TENANT_A,
                                () -> auditExportService.getExportJob(jobId).getStatus())
                        == AuditExportStatus.FAILED);

        asTenant(TENANT_A, () -> {
            AuditExportJobResponse job = auditExportService.getExportJob(jobId);
            assertThat(job.getDeliveryMode()).isEqualTo(AuditDeliveryMode.WEBHOOK);
            assertThat(job.getWebhookUrl()).isEqualTo(A_URL);
            assertThat(job.getDeliveryStatus()).isEqualTo(AuditExportDeliveryStatus.FAILED);
            assertThat(job.getDeliveryAttempts()).isEqualTo(2);
            assertThat(job.getDeliveryError()).isEqualTo("UnknownHostException");
            assertThat(job.getDeliveredAt()).isNull();
            assertThat(job.getErrorMessage()).contains("could not be delivered", "2 attempt(s)");
            assertThat(job.getDownloadUrl()).isNull();
            assertThatThrownBy(() -> auditExportService.getExportFile(jobId))
                    .isInstanceOf(AuditExportNotReadyException.class);
        });

        asTenant(TENANT_B, () -> {
            assertThatThrownBy(() -> auditExportService.getExportJob(jobId))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThat(count(
                            new JdbcTemplate(dataSource),
                            "SELECT count(*) FROM audit_export_jobs WHERE job_id = ?",
                            jobId))
                    .isZero();
        });

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(count(
                        owner,
                        "SELECT count(*) FROM audit_export_jobs WHERE job_id = ? AND tenant_id = ? AND webhook_url = ?"
                                + " AND delivery_status = 'FAILED' AND delivery_attempts = 2",
                        jobId,
                        TENANT_A,
                        A_URL))
                .as("the job row is tenant A's and carries the pinned destination and the delivery outcome")
                .isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM audit_export_files WHERE job_id = ?", jobId))
                .as("an undelivered export keeps no file")
                .isZero();
    }
}
