package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.internal.service.AuditExportService;
import com.positivity.tenancy.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Audit export jobs and their files are tenant rows (#2408, ADR-0062): the export runs on the
 * executor as the requesting tenant, covers only that tenant's audit events, and another tenant can
 * neither read the job nor download its file, through the service or through raw SQL.
 */
@DisplayName("pos-security-service on Postgres: audit export tenant isolation (#2408)")
class AuditExportTenancyIT extends PostgresTenancyTestBase {

    @Autowired
    private AuditExportService auditExportService;

    @Autowired
    private AuditLogEventRepository auditLogEvents;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static AuditLogEvent event(String eventType, String entityId) {
        AuditLogEvent event = new AuditLogEvent();
        event.setEventType(eventType);
        event.setActorId("export-it");
        event.setEntityId(entityId);
        event.setEntityType("USER");
        event.setOldValue("{}");
        event.setNewValue("{}");
        return event;
    }

    @Test
    void anExportIsTheRequestingTenantsAndInvisibleToAnother() {
        String eventType = "EXPORT_IT_" + UUID.randomUUID().toString().substring(0, 8);
        asTenant(TENANT_A, () -> auditLogEvents.saveAndFlush(event(eventType, "tenant-a-entity")));
        asTenant(TENANT_B, () -> auditLogEvents.saveAndFlush(event(eventType, "tenant-b-entity")));

        AuditExportRequest request = AuditExportRequest.builder()
                .format(AuditExportFormat.CSV)
                .deliveryMode(AuditDeliveryMode.DOWNLOAD)
                .filters(AuditEventSearchFilter.builder().eventType(eventType).build())
                .build();
        UUID jobId = asTenant(
                TENANT_A, () -> auditExportService.requestExport(request).getJobId());

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> asTenant(
                                TENANT_A,
                                () -> auditExportService.getExportJob(jobId).getStatus())
                        == AuditExportStatus.COMPLETED);

        asTenant(TENANT_A, () -> {
            assertThat(auditExportService.getExportJob(jobId).getRowCount()).isEqualTo(1L);
            String csv = new String(auditExportService.getExportFile(jobId).content(), StandardCharsets.UTF_8);
            assertThat(csv).contains("tenant-a-entity").doesNotContain("tenant-b-entity");
        });

        asTenant(TENANT_B, () -> {
            assertThatThrownBy(() -> auditExportService.getExportJob(jobId))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> auditExportService.getExportFile(jobId))
                    .isInstanceOf(EntityNotFoundException.class);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(count(jdbc, "SELECT count(*) FROM audit_export_jobs WHERE job_id = ?", jobId))
                    .isZero();
            assertThat(count(jdbc, "SELECT count(*) FROM audit_export_files WHERE job_id = ?", jobId))
                    .isZero();
        });

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(count(
                        owner,
                        "SELECT count(*) FROM audit_export_files WHERE job_id = ? AND tenant_id = '" + TENANT_A + "'",
                        jobId))
                .as("the file row is tenant A's")
                .isEqualTo(1);
    }

    private static int count(JdbcTemplate jdbc, String sql, UUID jobId) {
        Integer count = jdbc.queryForObject(sql, Integer.class, jobId);
        return count == null ? 0 : count;
    }
}
