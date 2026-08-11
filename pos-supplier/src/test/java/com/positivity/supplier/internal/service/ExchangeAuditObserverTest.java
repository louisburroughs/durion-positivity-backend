package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.AuditPayloadCipher;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * The audit writer against the real V3 schema (ADR-0050 §7).
 *
 * <p>The assertions that matter run against the <strong>raw column bytes</strong>, not the mapped
 * entity: reading back through the converter would decrypt and redact-check nothing, so a test that
 * only round-trips the entity would pass even if payloads were stored in plaintext. Every payload
 * assertion here therefore inspects what is actually on disk.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class ExchangeAuditObserverTest {

    private static final String SECRET = "hunter2-actual-password";
    private static final String REQUEST_DOC =
            "<StockInquiry><Password>" + SECRET + "</Password><Article>225/45R17</Article></StockInquiry>";

    @Autowired
    private ExchangeAuditRepository auditRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AuditPayloadCipher cipher;

    private ExchangeAuditWriter observer;
    private UUID profileId;

    @BeforeEach
    void setUp() {
        // The writer is exercised directly here. This class's assertions are about what reaches the
        // COLUMNS -- redaction, capture levels, raw ciphertext -- which is the writer's job; the observer's
        // only job is failure isolation and its transaction boundary, both covered by
        // ExchangeAuditWriterTest, which is the only place a rolled-back caller is observable.
        observer = new ExchangeAuditWriter(auditRepository, bindingRepository, "REDACTED");
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setSupplierRef("michelin-eu");
        profile.setDisplayName("Michelin EU");
        profile.setEnabled(true);
        profile.setSourceOfTruth(ProfileSourceOfTruth.ADMIN);
        profileId = profileRepository.saveAndFlush(profile).getVendorProfileId();
    }

    private UUID bindingWithCaptureLevel(PayloadCaptureLevel level) {
        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setVendorProfileId(profileId);
        binding.setCapability(SupplierCapability.STOCK_INQUIRY);
        binding.setProtocolFamily(ProtocolFamily.EDIWHEEL_A25);
        binding.setProtocolVersion("A2_5");
        binding.setBaseUrl("https://vendor.test");
        binding.setPath("/stock");
        binding.setAuthConfigName("ediwheel-basic");
        binding.setEnabled(true);
        binding.setCaptureLevel(level);
        return bindingRepository.saveAndFlush(binding).getId();
    }

    private ExchangeContext context(UUID bindingId, ExchangeOutcome outcome, String requestBody) {
        return new ExchangeContext(
                profileId,
                "michelin-eu",
                SupplierCapability.STOCK_INQUIRY,
                ProtocolFamily.EDIWHEEL_A25,
                "A2_5",
                bindingId,
                "POST",
                "https://vendor.test/stock",
                1,
                "corr-audit-1",
                outcome,
                outcome == ExchangeOutcome.OK ? 200 : null,
                Instant.parse("2026-08-11T12:00:00Z"),
                Duration.ofMillis(120),
                requestBody,
                outcome == ExchangeOutcome.OK ? "<StockResponse><Qty>7</Qty></StockResponse>" : null,
                outcome == ExchangeOutcome.OK ? null : "transport failed");
    }

    /** Raw bytes as stored, bypassing the converter entirely. */
    private byte[] rawRequestPayload(UUID auditId) {
        entityManager.clear();
        Object value = entityManager
                .createNativeQuery("SELECT request_payload FROM supplier_exchange_audit WHERE exchange_audit_id = ?1")
                .setParameter(1, auditId)
                .getSingleResult();
        return (byte[]) value;
    }

    @Test
    void writesOneRowPerAttemptWithIdentityAndSnapshot() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);

        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));

        List<ExchangeAuditEntity> rows = auditRepository.findAll();
        assertThat(rows).hasSize(1);
        ExchangeAuditEntity row = rows.getFirst();
        assertThat(row.getVendorProfileId()).isEqualTo(profileId);
        assertThat(row.getSupplierRef()).isEqualTo("michelin-eu");
        assertThat(row.getOutcome()).isEqualTo("OK");
        assertThat(row.getAttempt()).isEqualTo(1);
        assertThat(row.getDurationMs()).isEqualTo(120);
        assertThat(row.getCreatedBy())
                .as("a scheduler exchange has no security context, so the system actor is recorded")
                .isEqualTo(ExchangeAuditObserver.SYSTEM_ACTOR);
    }

    /** The load-bearing test: what is ON DISK must be neither plaintext nor unredacted. */
    @Test
    void storedPayloadIsEncryptedAndRedacted() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);

        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));
        UUID auditId = auditRepository.findAll().getFirst().getExchangeAuditId();
        byte[] stored = rawRequestPayload(auditId);

        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("stored bytes must not be readable plaintext")
                .doesNotContain("StockInquiry")
                .doesNotContain(SECRET);
        // And once decrypted, the credential is still gone -- encryption alone is not enough,
        // because anyone holding the key would otherwise read the password.
        String decrypted = cipher.decrypt(stored);
        assertThat(decrypted).doesNotContain(SECRET).contains("225/45R17").contains(PayloadRedactor.REDACTED);
    }

    @Test
    void metadataOnlyStoresNoPayloadAtAll() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.METADATA_ONLY);

        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));

        ExchangeAuditEntity row = auditRepository.findAll().getFirst();
        assertThat(row.getRequestPayload()).isNull();
        assertThat(row.getResponsePayload()).isNull();
        assertThat(row.getCaptureLevel()).isEqualTo(PayloadCaptureLevel.METADATA_ONLY);
    }

    @Test
    void fullCaptureStoresTheDocumentAsSentButStillEncrypted() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.FULL);

        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));
        UUID auditId = auditRepository.findAll().getFirst().getExchangeAuditId();

        assertThat(new String(rawRequestPayload(auditId), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("FULL preserves body content but never bypasses encryption at rest")
                .doesNotContain(SECRET);
        assertThat(cipher.decrypt(rawRequestPayload(auditId))).isEqualTo(REQUEST_DOC);
    }

    /** ADR-0050 §7: failures must be recorded, or the trail is a success log. */
    @Test
    void failedExchangesAreRecordedToo() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);

        observer.write(context(bindingId, ExchangeOutcome.PRE_SEND_FAILURE, REQUEST_DOC));

        ExchangeAuditEntity row = auditRepository.findAll().getFirst();
        assertThat(row.getOutcome()).isEqualTo("PRE_SEND_FAILURE");
        assertThat(row.getHttpStatus()).isNull();
        assertThat(row.getFailureDetail()).isEqualTo("transport failed");
    }

    /**
     * An unreadable binding must not widen capture. A deleted binding, or one with no level, falls
     * back to the configured default rather than FULL.
     */
    @Test
    void anUnknownBindingFallsBackToTheSafeDefaultNotFull() {
        observer.write(context(UUID.randomUUID(), ExchangeOutcome.OK, REQUEST_DOC));

        ExchangeAuditEntity row = auditRepository.findAll().getFirst();
        assertThat(row.getCaptureLevel()).isEqualTo(PayloadCaptureLevel.REDACTED);
        assertThat(cipher.decrypt(rawRequestPayload(row.getExchangeAuditId()))).doesNotContain(SECRET);
    }

    /**
     * Every attempt the base client observes has a resolved binding — an unbound capability fails at
     * {@code SupplierProfileResolver} before any exchange exists, so there is nothing to audit. This
     * pins that invariant rather than pretending the writer handles a binding-less attempt. The column
     * stays nullable so a future binding-less flow needs no migration, but nothing produces one today.
     */
    @Test
    void everyObservedAttemptCarriesItsBinding() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);

        observer.write(context(bindingId, ExchangeOutcome.CONFIGURATION_ERROR, null));

        ExchangeAuditEntity row = auditRepository.findAll().getFirst();
        assertThat(row.getBindingId()).isEqualTo(bindingId);
        assertThat(row.getRequestPayload())
                .as("a configuration failure has no request body to capture")
                .isNull();
    }

    @Test
    void everyAttemptOfARetrySequenceIsItsOwnRow() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);
        for (int attempt = 1; attempt <= 3; attempt++) {
            ExchangeContext base = context(bindingId, ExchangeOutcome.PRE_SEND_FAILURE, REQUEST_DOC);
            observer.write(new ExchangeContext(
                    base.vendorProfileId(),
                    base.supplierRef(),
                    base.capability(),
                    base.protocolFamily(),
                    base.protocolVersion(),
                    base.bindingId(),
                    base.method(),
                    base.uri(),
                    attempt,
                    base.correlationId(),
                    base.outcome(),
                    base.httpStatus(),
                    base.startedAt(),
                    base.duration(),
                    base.requestBody(),
                    base.responseBody(),
                    base.failureDetail()));
        }

        assertThat(auditRepository.findAll())
                .as("retries must be individually visible, not collapsed into one row")
                .hasSize(3)
                .extracting(ExchangeAuditEntity::getAttempt)
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void theRetentionPurgeNullsPayloadsButKeepsTheMetadataRow() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);
        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));
        Instant purgeAt = Instant.parse("2027-01-01T00:00:00Z");

        int purged = auditRepository.purgePayloadsOlderThan(purgeAt, purgeAt);
        entityManager.clear();

        assertThat(purged).isEqualTo(1);
        ExchangeAuditEntity row = auditRepository.findAll().getFirst();
        assertThat(row.getRequestPayload()).isNull();
        assertThat(row.getResponsePayload()).isNull();
        assertThat(row.getPayloadsPurgedAt())
                .as("stamped so 'purged' stays distinguishable from 'never captured'")
                .isNotNull();
        assertThat(row.getOutcome())
                .as("the metadata trail is retained permanently")
                .isEqualTo("OK");
        assertThat(row.getCorrelationId()).isEqualTo("corr-audit-1");
    }

    @Test
    void thePurgeLeavesRowsInsideTheRetentionWindowAlone() {
        UUID bindingId = bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED);
        observer.write(context(bindingId, ExchangeOutcome.OK, REQUEST_DOC));

        int purged = auditRepository.purgePayloadsOlderThan(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(purged).isZero();
        assertThat(auditRepository.countPurgeableOlderThan(Instant.parse("2027-01-01T00:00:00Z")))
                .isEqualTo(1);
    }

    /**
     * Auditing must never turn a good exchange into a bad one (ADR-0050 §7).
     *
     * <p>Now expressed against the real collaboration rather than an anonymous subclass overriding the write.
     * That override could only ever prove the try/catch compiles: it replaced the very method whose
     * transactional boundary was the actual defect, so the test passed for years of self-invocation silently
     * disabling {@code REQUIRES_NEW}. A stub of the writer keeps the failure-isolation assertion; the boundary
     * itself is proven in {@code ExchangeAuditWriterTest} against real commits.
     */
    @Test
    void anAuditWriteFailureDoesNotPropagateToTheCaller() {
        ExchangeAuditObserver isolating =
                new ExchangeAuditObserver(new ExchangeAuditWriter(auditRepository, bindingRepository, "REDACTED") {
                    @Override
                    public void write(ExchangeContext context) {
                        throw new IllegalStateException("audit store unavailable");
                    }
                });

        isolating.onExchange(
                context(bindingWithCaptureLevel(PayloadCaptureLevel.REDACTED), ExchangeOutcome.OK, REQUEST_DOC));

        assertThat(auditRepository.findAll()).isEmpty();
    }
}
