package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.billintake.config.BillIntakeProperties;
import com.positivity.accounting.internal.billintake.dto.BillIntakeItemResponse;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.billintake.service.BillIntakeAuditRecorder;
import com.positivity.accounting.internal.billintake.service.BillIntakeChecks;
import com.positivity.accounting.internal.billintake.service.BillIntakeEntry;
import com.positivity.accounting.internal.billintake.service.BillIntakeMetrics;
import com.positivity.accounting.internal.billintake.service.BillIntakeProcessor;
import com.positivity.accounting.internal.billintake.service.ExtractionPort;
import com.positivity.accounting.internal.billintake.service.FileStore;
import com.positivity.accounting.internal.billintake.service.MalwareScanner;
import com.positivity.accounting.internal.service.TenantLocaleResolver;
import com.positivity.tenancy.TenantIterator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The bill-intake pipeline on the real schema (#2518 Part B): the file store and the draft under row-level security, the
 * upload with its replay, and the processor's claim query ({@code FOR UPDATE SKIP LOCKED}) moving drafts with a scanner
 * and a reader that are doubles. Requires Docker.
 */
@DisplayName("Bill intake pipeline (#2518 Part B, real Postgres)")
class BillIntakePipelinePostgresIT extends PostgresTenancyTestBase {

    private static final byte[] PDF = "%PDF-1.7 a bill".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private BillIntakeEntry entry;

    @Autowired
    private FileStore fileStore;

    @Autowired
    private BillIntakeItemRepository items;

    @Autowired
    private BillIntakeChecks checks;

    @Autowired
    private BillIntakeAuditRecorder audit;

    @Autowired
    private BillIntakeMetrics metrics;

    @Autowired
    private BillIntakeProperties properties;

    @Autowired
    private TenantIterator tenantIterator;

    @Autowired
    private TenantLocaleResolver localeResolver;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID tenant : tenants) {
            owner.update("DELETE FROM accounting_audit_log WHERE tenant_id = ?", tenant);
            owner.update("DELETE FROM bill_intake_item WHERE tenant_id = ?", tenant);
            owner.update("DELETE FROM bill_source_file_content WHERE tenant_id = ?", tenant);
            owner.update("UPDATE bill_source_file SET derived_from = NULL WHERE tenant_id = ?", tenant);
            owner.update("DELETE FROM bill_source_file WHERE tenant_id = ?", tenant);
            owner.update("DELETE FROM accounting_sequence WHERE tenant_id = ?", tenant);
            owner.update("DELETE FROM accounting_configuration WHERE tenant_id = ?", tenant);
        }
        tenants.clear();
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        return tenant;
    }

    private BillIntakeProcessor processor(MalwareScanner scanner, ExtractionPort extraction) {
        return new BillIntakeProcessor(
                items,
                fileStore,
                scanner,
                extraction,
                checks,
                audit,
                metrics,
                properties,
                tenantIterator,
                localeResolver,
                transactionManager,
                clock);
    }

    private static ExtractionPort extraction(
            ExtractionPort.InspectionResult inspection, ExtractionPort.ExtractionResult extracted) {
        return new ExtractionPort() {
            @Override
            public InspectionResult inspect(byte[] file, DeclaredType declaredType) {
                return inspection;
            }

            @Override
            public RenderResult render(byte[] file, DeclaredType declaredType, int page) {
                return RenderResult.unavailable();
            }

            @Override
            public TabulationResult tabulate(byte[] file, DeclaredType declaredType) {
                return TabulationResult.unavailable();
            }

            @Override
            public ExtractionResult extract(
                    byte[] file, DeclaredType declaredType, DocumentKind documentKind, String localeHint) {
                return extracted;
            }
        };
    }

    private BillIntakeItemResponse upload(UUID tenant, UUID requestId, byte[]... files) {
        List<BillIntakeEntry.IncomingFile> incoming = new ArrayList<>();
        for (byte[] file : files) {
            incoming.add(new BillIntakeEntry.IncomingFile(file, "application/pdf", "bill.pdf"));
        }
        return asTenant(
                        tenant,
                        () -> entry.receive(incoming, BillIntakeChannel.UPLOAD, requestId, "clerk.carol")
                                .items()
                                .get(0));
    }

    private String statusOf(UUID intakeId) {
        return owner.queryForObject("SELECT status FROM bill_intake_item WHERE intake_id = ?", String.class, intakeId);
    }

    @Test
    @DisplayName("an upload is QUARANTINED with its file apart; a replay answers the same draft; other content is refused")
    void uploadAndReplay() {
        UUID tenant = tenant();
        UUID requestId = UUID.randomUUID();

        BillIntakeItemResponse first = upload(tenant, requestId, PDF);
        BillIntakeItemResponse replay = upload(tenant, requestId, PDF);

        assertThat(first.status().name()).isEqualTo("QUARANTINED");
        assertThat(first.intakeReference()).matches("BI-\\d{6}-\\d{7}");
        assertThat(replay.intakeId()).isEqualTo(first.intakeId());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM bill_intake_item WHERE tenant_id = ?", Integer.class, tenant))
                .isEqualTo(1);
        assertThat(owner.queryForMap(
                        "SELECT f.state, f.sha256, f.size_bytes, length(c.content) AS bytes"
                                + " FROM bill_source_file f JOIN bill_source_file_content c ON c.file_id = f.file_id"
                                + " WHERE f.tenant_id = ?",
                        tenant))
                .containsEntry("state", "QUARANTINED")
                .containsEntry("sha256", first.sha256())
                .containsEntry("bytes", PDF.length);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_audit_log WHERE entity_type = 'BILL_INTAKE'"
                                + " AND operation = 'UPLOAD' AND tenant_id = ?",
                        Integer.class,
                        tenant))
                .isEqualTo(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(
                        tenant, requestId, "%PDF-1.4 another".getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("other files");
    }

    @Test
    @DisplayName("a file of another tenant is invisible: its bytes cannot be opened, its draft cannot be read")
    void tenantsAreIsolated() {
        UUID one = tenant();
        UUID two = tenant();
        BillIntakeItemResponse draft = upload(one, UUID.randomUUID(), PDF);
        UUID fileId = owner.queryForObject(
                "SELECT source_file_id FROM bill_intake_item WHERE intake_id = ?", UUID.class, draft.intakeId());

        assertThat(asTenant(one, () -> fileStore.open(fileId))).isPresent();
        assertThat(asTenant(two, () -> fileStore.open(fileId))).isEmpty();
        assertThat(asTenant(two, () -> items.findById(draft.intakeId()))).isEmpty();
    }

    @Test
    @DisplayName("the processor moves a clean, accepted upload to READY_FOR_REVIEW as a blank MANUAL draft, file STORED")
    void processorReachesReadyForReview() {
        UUID tenant = tenant();
        BillIntakeItemResponse draft = upload(tenant, UUID.randomUUID(), PDF);
        BillIntakeProcessor processor = processor(
                content -> MalwareScanner.ScanVerdict.CLEAN,
                extraction(
                        new ExtractionPort.InspectionResult(
                                ExtractionPort.InspectionVerdict.ACCEPTED,
                                null,
                                ExtractionPort.DeclaredType.PDF,
                                2,
                                null),
                        new ExtractionPort.ExtractionResult(
                                ExtractionPort.ExtractionOutcome.FAILED, "PROVIDER_COULD_NOT_READ", "none", 0, List.of())));

        asTenant(tenant, processor::processTenant);

        assertThat(statusOf(draft.intakeId())).isEqualTo("READY_FOR_REVIEW");
        assertThat(owner.queryForMap(
                        "SELECT extraction_outcome, last_extraction_failed, vendor_resolution, extraction_reason,"
                                + " read_back::text AS read_back FROM bill_intake_item WHERE intake_id = ?",
                        draft.intakeId()))
                .containsEntry("extraction_outcome", "MANUAL")
                .containsEntry("last_extraction_failed", true)
                .containsEntry("vendor_resolution", "NOT_SET_UP")
                .containsEntry("extraction_reason", "PROVIDER_COULD_NOT_READ");
        assertThat(owner.queryForObject(
                        "SELECT read_back::text FROM bill_intake_item WHERE intake_id = ?",
                        String.class,
                        draft.intakeId()))
                .contains("CHECK_THIS");
        assertThat(owner.queryForMap(
                        "SELECT state, page_count FROM bill_source_file WHERE tenant_id = ?", tenant))
                .containsEntry("state", "STORED")
                .containsEntry("page_count", 2);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_audit_log WHERE operation = 'SCAN' AND tenant_id = ?",
                        Integer.class,
                        tenant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an infected upload is FILE_REJECTED / MALWARE_FOUND with retention; a scanner outage leaves it QUARANTINED")
    void infectedAndOutage() {
        UUID tenant = tenant();
        BillIntakeItemResponse infected = upload(tenant, UUID.randomUUID(), PDF);
        AtomicReference<MalwareScanner.ScanVerdict> verdict =
                new AtomicReference<>(MalwareScanner.ScanVerdict.UNAVAILABLE);
        BillIntakeProcessor processor = processor(
                content -> verdict.get(), extraction(ExtractionPort.InspectionResult.unavailable(), ExtractionPort.ExtractionResult.unavailable()));

        asTenant(tenant, processor::processTenant);
        assertThat(statusOf(infected.intakeId())).isEqualTo("QUARANTINED");
        assertThat(owner.queryForObject(
                        "SELECT attempts FROM bill_intake_item WHERE intake_id = ?", Integer.class, infected.intakeId()))
                .isEqualTo(1);

        owner.update("UPDATE bill_intake_item SET next_attempt_at = NULL WHERE intake_id = ?", infected.intakeId());
        verdict.set(MalwareScanner.ScanVerdict.INFECTED);
        asTenant(tenant, processor::processTenant);

        assertThat(owner.queryForMap(
                        "SELECT status, rejection_reason FROM bill_intake_item WHERE intake_id = ?",
                        infected.intakeId()))
                .containsEntry("status", "FILE_REJECTED")
                .containsEntry("rejection_reason", "MALWARE_FOUND");
        assertThat(owner.queryForMap(
                        "SELECT state, retention_until IS NOT NULL AS has_retention FROM bill_source_file WHERE tenant_id = ?",
                        tenant))
                .containsEntry("state", "REJECTED")
                .containsEntry("has_retention", true);
    }

    @Test
    @DisplayName("purge drops the bytes and derived pages and keeps the metadata with purged_at")
    void purgeKeepsMetadata() {
        UUID tenant = tenant();
        BillIntakeItemResponse draft = upload(tenant, UUID.randomUUID(), PDF);
        UUID fileId = owner.queryForObject(
                "SELECT source_file_id FROM bill_intake_item WHERE intake_id = ?", UUID.class, draft.intakeId());
        asTenant(tenant, () -> fileStore.storeDerived(fileId, 1, "image/png", new byte[] {1, 2, 3}, "SYSTEM"));

        asTenant(tenant, () -> {
            fileStore.purge(fileId);
            return null;
        });

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM bill_source_file_content WHERE tenant_id = ?", Integer.class, tenant))
                .isZero();
        assertThat(owner.queryForList(
                        "SELECT state FROM bill_source_file WHERE tenant_id = ? AND purged_at IS NOT NULL",
                        String.class,
                        tenant))
                .containsExactly("PURGED", "PURGED");
        assertThat(asTenant(tenant, () -> fileStore.open(fileId))).isEmpty();
    }
}
