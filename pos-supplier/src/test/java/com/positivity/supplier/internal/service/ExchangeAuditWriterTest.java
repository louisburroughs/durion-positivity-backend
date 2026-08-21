package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit write's transaction boundary — the property that was documented but not real.
 *
 * <p>{@code ExchangeAuditObserver} used to call {@code persist(context)} on {@code this}. Self-invocation
 * does not pass through the Spring proxy, so the {@code REQUIRES_NEW} on that method never took effect and
 * both halves of the guarantee were false while the annotation sat there looking correct.
 *
 * <p>Neither half is observable from inside a {@code @DataJpaTest}'s own rolled-back transaction, which is
 * why the defect survived a full test suite. This class runs with test-managed transactions disabled and
 * drives real commit boundaries through a {@link TransactionTemplate}.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_audit_writer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class, ExchangeAuditWriter.class, ExchangeAuditObserver.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExchangeAuditWriterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000009a1");

    /**
     * A binding id that intentionally references no binding row. The writer resolves the capture level by
     * looking the binding up and falls back to the configured default when it is gone -- which is also the
     * real case of a binding deleted between the exchange and the audit write.
     */
    private static final UUID BINDING_ID = UUID.fromString("018f0000-0000-7000-8000-0000000009b2");

    @Autowired
    private ExchangeAuditObserver observer;

    @Autowired
    private ExchangeAuditRepository auditRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    /** Spied so one test can make the audit insert fail the way a dead connection would. */
    @MockitoSpyBean
    private ExchangeAuditRepository spiedRepository;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM supplier_exchange_audit");
            statement.executeUpdate("DELETE FROM supplier_endpoint_binding_redaction");
            statement.executeUpdate("DELETE FROM supplier_endpoint_binding");
            statement.executeUpdate("DELETE FROM supplier_profile");
        }
    }

    private static ExchangeContext context(String correlationId) {
        return context(correlationId, "https://edi.michelin.example/a25/stock/inquiry", null);
    }

    private static ExchangeContext context(String correlationId, String uri, String failureDetail) {
        return new ExchangeContext(
                PROFILE_ID,
                "michelin-eu",
                SupplierCapability.STOCK_INQUIRY,
                ProtocolFamily.EDIWHEEL_A25,
                "A2_5",
                BINDING_ID,
                "POST",
                uri,
                1,
                correlationId,
                ExchangeOutcome.OK,
                200,
                Instant.parse("2026-08-11T09:14:02.117Z"),
                Duration.ofMillis(184),
                "<StockInquiry/>",
                "<StockInquiryResponse/>",
                failureDetail);
    }

    /**
     * The first half: an audit row must outlive a caller that rolls back.
     *
     * <p>An exchange that fails and rolls its caller back is precisely the exchange an operator goes looking
     * for. Before the split, the row went with the rollback — the audit trail lost exactly the events worth
     * auditing, and no test noticed because every test was already inside a rolled-back transaction.
     */
    @Test
    void auditRowSurvivesARolledBackCallerTransaction() {
        transactionTemplate.executeWithoutResult(status -> {
            observer.onExchange(context("rolled-back-caller"));
            status.setRollbackOnly();
        });

        assertThat(auditRepository.findAll())
                .as("REQUIRES_NEW means the audit row commits independently. A caller's rollback must not"
                        + " erase the record of what was sent -- the failed exchange is the one that matters")
                .hasSize(1);
    }

    /**
     * The second half, and the dangerous one: a broken audit sink must not be able to fail real work.
     *
     * <p>Before the split the audit insert joined the caller's transaction, so a failure marked the
     * <em>caller's</em> transaction rollback-only. The observer's catch swallowed the exception, the caller
     * carried on believing it had succeeded, and the caller's commit then threw
     * {@code UnexpectedRollbackException}. An unavailable audit sink could therefore destroy committed
     * business work — the exact inverse of the documented guarantee.
     */
    @Test
    void aFailingAuditWriteCannotPoisonTheCallersTransaction() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("audit sink down"))
                .when(spiedRepository)
                .flush();

        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
                    // Real work in the caller's transaction, committed afterwards.
                    SupplierProfileEntity profile = new SupplierProfileEntity();
                    profile.setSupplierRef("michelin-eu");
                    profile.setDisplayName("Michelin EU");
                    profile.setEnabled(true);
                    profile.setSourceOfTruth(ProfileSourceOfTruth.ADMIN);
                    profileRepository.saveAndFlush(profile);

                    observer.onExchange(context("audit-sink-down"));
                }))
                .as("the caller's commit must succeed. If the audit write shares its transaction, the failed"
                        + " insert marks it rollback-only and this throws UnexpectedRollbackException")
                .doesNotThrowAnyException();

        assertThat(profileRepository.findAll())
                .as("and the caller's own work must actually be committed, not silently discarded")
                .hasSize(1);
    }

    @Test
    void aCommittedCallerStillGetsItsAuditRow() {
        transactionTemplate.executeWithoutResult(status -> observer.onExchange(context("committed-caller")));

        assertThat(auditRepository.findAll()).hasSize(1);
    }

    @Test
    void anOversizedCorrelationIdIsTruncatedRatherThanCostingTheRow() {
        // The correlation id is reused from the inbound X-Correlation-Id header, so its length is influenced
        // by a remote party. The column is 100 chars; an oversized header must not be able to delete evidence.
        String oversized = "c".repeat(400);

        observer.onExchange(context(oversized));

        assertThat(auditRepository.findAll())
                .as("a client-controlled value must never be able to suppress an audit row")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getCorrelationId()).hasSize(100));
    }

    // ── Credential redaction of the metadata columns (ADR-0050 §4/§7) ────────────────

    /**
     * Asserted on the PERSISTED ROW, not on {@code PayloadRedactor} in isolation.
     *
     * <p>The redactor had unit coverage and the wiring did not: reverting the writer to
     * {@code truncate(context.uri(), 2048)} left the whole suite green, and the width-parity test still passed
     * because it only reads the numeric bound. Same unproven-seam shape as the {@code @EventListener} finding.
     * These tests fail if the call is removed.
     */
    @Test
    void aStoredUriHasItsCredentialQueryParametersRedacted() {
        observer.onExchange(
                context("redact-uri", "https://edi.example/stock?apikey=live-secret-value&article=205", null));

        assertThat(auditRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getEndpointUri())
                    .as("a credential in a query parameter must never reach a column that is"
                            + " unencrypted, survives the purge, and is returned by the listing")
                    .doesNotContain("live-secret-value");
            assertThat(row.getEndpointUri()).contains("article=205");
        });
    }

    @Test
    void aStoredUriHasItsUserinfoStrippedEvenAtMetadataOnly() {
        // No binding row exists for BINDING_ID, so the writer falls back to its configured default (REDACTED).
        observer.onExchange(context("redact-userinfo", "https://apiuser:hunter2@edi.example/a25/stock", null));

        assertThat(auditRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getEndpointUri())
                        .as("userinfo is a plaintext credential (ADR-0050 §4) and is not part of the query"
                                + " string, so dropping the query alone would leave it intact")
                        .doesNotContain("hunter2"));
    }

    @Test
    void aStoredFailureDetailHasEmbeddedUrlCredentialsRedacted() {
        observer.onExchange(context(
                "redact-failure",
                "https://edi.example/a25/stock",
                "Vendor redirected to https://cdn.example/doc?sig=AbC123SignatureValue&exp=99 (302)"));

        assertThat(auditRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getFailureDetail())
                    .as("a redirect Location routinely carries a signed URL whose token is a live"
                            + " bearer credential, and this column asserted it never held one")
                    .doesNotContain("AbC123SignatureValue");
            assertThat(row.getFailureDetail())
                    .as("the rest of the operator-facing message must survive, or the redaction has"
                            + " destroyed the diagnostic it exists to carry")
                    .contains("Vendor redirected to")
                    .contains("(302)");
        });
    }

    // ── Per-binding classification-driven redaction (issue #1259, ADR-0050 §7 minimization) ─

    /**
     * Asserted on the persisted row for the same reason as the URI tests above: {@code PayloadRedactor}
     * has classification unit coverage, and this is the seam that proves the writer actually reads the
     * binding's declared classifications and passes them through.
     */
    @Test
    void aBindingsDeclaredClassificationsNarrowItsRedactedCapture() {
        UUID bindingId = transactionTemplate.execute(status -> {
            SupplierProfileEntity profile = new SupplierProfileEntity();
            profile.setSupplierRef("michelin-eu");
            profile.setDisplayName("Michelin EU");
            profile.setEnabled(true);
            profile.setSourceOfTruth(ProfileSourceOfTruth.ADMIN);
            profileRepository.saveAndFlush(profile);

            SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
            binding.setVendorProfileId(profile.getVendorProfileId());
            binding.setCapability(SupplierCapability.WORKORDER_AUTHORIZATION);
            binding.setProtocolFamily(ProtocolFamily.EDIWHEEL_A25);
            binding.setProtocolVersion("A2_5");
            binding.setBaseUrl("https://edi.michelin.example");
            binding.setPath("/a25/workorder");
            binding.setAuthConfigName("ediwheel-basic");
            binding.setEnabled(true);
            binding.setCaptureLevel(com.positivity.supplier.internal.enums.PayloadCaptureLevel.REDACTED);
            binding.setRedactionClassifications(new java.util.HashSet<>(java.util.Set.of(
                    com.positivity.supplier.internal.enums.RedactionClassification.CUSTOMER_IDENTIFIER)));
            return bindingRepository.saveAndFlush(binding).getId();
        });

        String workorderBody = "<WorkorderAuthorization><CustomerNumber>FLEET-CUST-0042</CustomerNumber>"
                + "<Article>225/45R17</Article></WorkorderAuthorization>";
        observer.onExchange(workorderContext(bindingId, workorderBody));

        assertThat(auditRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getRequestPayload())
                    .as("the binding declared CUSTOMER_IDENTIFIER, so the §7 example -- a customer"
                            + " identifier in a fleet workorder authorization payload -- must not be stored")
                    .doesNotContain("FLEET-CUST-0042")
                    .contains("225/45R17");
            assertThat(row.getResponsePayload()).doesNotContain("FLEET-CUST-0042");
        });
    }

    @Test
    void withoutDeclaredClassificationsARedactedCaptureKeepsNonCredentialFields() {
        // The default path (no binding row -> configured default REDACTED, no classifications): exactly
        // the pre-#1259 behavior, now a deliberate configuration state rather than the only option.
        String workorderBody = "<WorkorderAuthorization><CustomerNumber>FLEET-CUST-0042</CustomerNumber>"
                + "</WorkorderAuthorization>";
        observer.onExchange(workorderContext(BINDING_ID, workorderBody));

        assertThat(auditRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getRequestPayload()).contains("FLEET-CUST-0042"));
    }

    private static ExchangeContext workorderContext(UUID bindingId, String body) {
        return new ExchangeContext(
                PROFILE_ID,
                "michelin-eu",
                SupplierCapability.WORKORDER_AUTHORIZATION,
                ProtocolFamily.EDIWHEEL_A25,
                "A2_5",
                bindingId,
                "POST",
                "https://edi.michelin.example/a25/workorder",
                1,
                "classification-test",
                ExchangeOutcome.OK,
                200,
                Instant.parse("2026-08-11T09:14:02.117Z"),
                Duration.ofMillis(184),
                body,
                body,
                null);
    }
}
