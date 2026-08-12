package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.AuditAccessEntity;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import com.positivity.supplier.internal.enums.AuditPayloadOutcome;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.AuditAccessRepository;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.service.model.ExchangeAuditPayloadView;
import com.positivity.supplier.service.model.ExchangeAuditSummary;
import com.positivity.supplier.service.model.PagedResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit read path against the real V3/V4 schema, with <strong>real transactions</strong>.
 *
 * <h2>Why this test is not {@code @DataJpaTest}-default transactional</h2>
 *
 * Everything worth proving here is a commit boundary: that an access record commits together with the
 * payload it authorises, that a failure to write it takes the payload down with it, and that an
 * <em>unreadable</em> payload does the opposite and leaves the record behind. A test wrapped in a
 * rolled-back transaction cannot observe any of that — it would pass with the semantics inverted. The
 * class therefore declares {@code Propagation.NOT_SUPPORTED} so each call manages its own transaction,
 * and cleans up over a plain JDBC connection.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_audit_read;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaConfig.class,
    AuditAccessRecorder.class,
    SupplierExchangeAuditServiceImpl.class,
    SupplierExchangeAuditPersistenceTest.FixedClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupplierExchangeAuditPersistenceTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-11T14:22:07.412Z"), ZoneOffset.UTC);
        }
    }

    private static final String READER = "a.operator@example.com";
    private static final UUID PROFILE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000004a1");
    private static final Instant STARTED_AT = Instant.parse("2026-08-11T09:14:02.117Z");
    private static final String REQUEST_DOC = "<StockInquiry><Article>225/45R17</Article></StockInquiry>";
    private static final String RESPONSE_DOC = "<StockInquiryResponse><Available>4</Available></StockInquiryResponse>";

    @Autowired
    private SupplierExchangeAuditServiceImpl auditService;

    @Autowired
    private AuditAccessRecorder accessRecorder;

    @Autowired
    private ExchangeAuditRepository exchangeAuditRepository;

    /**
     * Spied, not mocked: every test but the fail-closed one needs the real repository. The spy exists so
     * one test can make the access write fail the way a constraint violation or a dead connection would.
     */
    @MockitoSpyBean
    private AuditAccessRepository accessRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void authenticate() {
        // The recorder takes its subject from the security context, so the test must supply one or it
        // would assert against the "unknown" fallback and prove nothing about attribution.
        //
        // The details map is not optional decoration: SecurityContextHelper reads the audit identity from
        // the gateway-injected details (ADR-0018) and only then falls back to the principal name. Setting
        // the principal alone silently yields "unknown" -- which is how the first run of this test passed
        // its record assertions while proving nothing about WHO was recorded.
        var authentication = new UsernamePasswordAuthenticationToken(READER, "N/A", java.util.List.of());
        authentication.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USERNAME, READER));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void cleanUp() throws Exception {
        SecurityContextHolder.clearContext();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM supplier_audit_access");
            statement.executeUpdate("DELETE FROM supplier_exchange_audit");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private UUID seedExchange(PayloadCaptureLevel level, String requestDoc, String responseDoc) {
        return transactionTemplate.execute(status -> {
            ExchangeAuditEntity row = ExchangeAuditEntity.builder()
                    .vendorProfileId(PROFILE_ID)
                    .supplierRef("michelin-eu")
                    .bindingId(UUID.fromString("018f0000-0000-7000-8000-0000000004b2"))
                    .capability(SupplierCapability.STOCK_INQUIRY)
                    .protocolFamily(ProtocolFamily.EDIWHEEL_A25)
                    .protocolVersion("A2_5")
                    .httpMethod("POST")
                    .endpointUri("https://edi.michelin.example/a25/stock/inquiry")
                    .attempt(1)
                    .correlationId("018f0a1b2c3d7e4f8a9b0c1d2e3f4a71")
                    .outcome("OK")
                    .httpStatus(200)
                    .startedAt(STARTED_AT)
                    .durationMs(184L)
                    .captureLevel(level)
                    .requestPayload(requestDoc)
                    .responsePayload(responseDoc)
                    .build();
            return exchangeAuditRepository.saveAndFlush(row).getExchangeAuditId();
        });
    }

    /**
     * Overwrites a row's stored request payload with an envelope naming a key id this deployment does not
     * hold — the shape of a key rotation that dropped a retired key, and the case ADR-0050 §7 most wants
     * on the record. Written over raw JDBC precisely because the mapped entity cannot express it.
     */
    private void corruptRequestPayload(UUID exchangeAuditId) throws Exception {
        byte[] keyId = "retired-key".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = new byte[2 + keyId.length + 12 + 32];
        envelope[0] = 0x01;
        envelope[1] = (byte) keyId.length;
        System.arraycopy(keyId, 0, envelope, 2, keyId.length);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE supplier_exchange_audit SET request_payload = ? WHERE exchange_audit_id = ?")) {
            statement.setBytes(1, envelope);
            statement.setObject(2, exchangeAuditId);
            assertThat(statement.executeUpdate())
                    .as("the corrupting UPDATE must actually hit the seeded row, or this test proves nothing")
                    .isEqualTo(1);
        }
    }

    private long countAccessRows() {
        return transactionTemplate.execute(status -> accessRepository.count());
    }

    // ── Metadata reads never decrypt ────────────────────────────────────────────────

    @Nested
    @DisplayName("metadata reads never touch the encrypted columns")
    class MetadataReads {

        @Test
        void listsAnExchangeWhoseStoredPayloadCannotBeDecrypted() throws Exception {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);
            corruptRequestPayload(id);

            PagedResponse<ExchangeAuditSummary> page = auditService.listExchanges(
                    PROFILE_ID,
                    STARTED_AT.minus(1, ChronoUnit.HOURS),
                    STARTED_AT.plus(1, ChronoUnit.HOURS),
                    null,
                    0,
                    50);

            assertThat(page.items())
                    .as("a row sealed with a key this deployment no longer holds must still LIST -- it is"
                            + " precisely the row an investigation is looking for, and a listing that failed on"
                            + " it would hide the evidence")
                    .hasSize(1);
            assertThat(page.items().getFirst().exchangeAuditId()).isEqualTo(id);
            assertThat(page.items().getFirst().requestPayloadPresent())
                    .as("presence is computed by a SQL null test, so it is knowable without decrypting")
                    .isTrue();
        }

        @Test
        void readsMetadataOfAnUndecryptableRowById() throws Exception {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);
            corruptRequestPayload(id);

            ExchangeAuditSummary summary = auditService.getExchange(id);

            assertThat(summary.exchangeAuditId()).isEqualTo(id);
            assertThat(summary.captureLevel().name()).isEqualTo("FULL");
        }

        @Test
        void metadataReadsAreNotRecordedAsAccesses() {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);

            auditService.listExchanges(
                    PROFILE_ID,
                    STARTED_AT.minus(1, ChronoUnit.HOURS),
                    STARTED_AT.plus(1, ChronoUnit.HOURS),
                    null,
                    0,
                    50);
            auditService.getExchange(id);
            auditService.traceCorrelation("018f0a1b2c3d7e4f8a9b0c1d2e3f4a71", 0, 50);
            auditService.listAccesses(id, 0, 50);

            assertThat(countAccessRows())
                    .as("browsing metadata discloses no commercial content, so recording it would bury the"
                            + " reads that matter in noise (ADR-0050 §7)")
                    .isZero();
        }

        @Test
        void reportsPresenceFalseForAMetadataOnlyRow() {
            UUID id = seedExchange(PayloadCaptureLevel.METADATA_ONLY, null, null);

            ExchangeAuditSummary summary = auditService.getExchange(id);

            assertThat(summary.requestPayloadPresent()).isFalse();
            assertThat(summary.responsePayloadPresent()).isFalse();
            assertThat(summary.payloadsPurgedAt())
                    .as("never captured is not the same fact as purged")
                    .isNull();
        }

        @Test
        void rejectsAWindowThatCanNeverMatch() {
            assertThatThrownBy(() -> auditService.listExchanges(PROFILE_ID, STARTED_AT, STARTED_AT, null, 0, 50))
                    .isInstanceOf(SupplierValidationException.class)
                    .hasMessageContaining("half-open");
        }

        @Test
        void rejectsAnUnknownCapabilityFilterRatherThanReturningAnEmptyPage() {
            assertThatThrownBy(() -> auditService.listExchanges(
                            PROFILE_ID,
                            STARTED_AT.minus(1, ChronoUnit.HOURS),
                            STARTED_AT.plus(1, ChronoUnit.HOURS),
                            "STOCK_INQUIRIES",
                            0,
                            50))
                    .as("a filter that silently matches nothing turns a typo into 'this integration was never used'")
                    .isInstanceOf(SupplierValidationException.class);
        }

        @Test
        void excludesTheExclusiveWindowEnd() {
            seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);

            PagedResponse<ExchangeAuditSummary> endsAtStart =
                    auditService.listExchanges(PROFILE_ID, STARTED_AT.minusSeconds(60), STARTED_AT, null, 0, 50);
            PagedResponse<ExchangeAuditSummary> startsAtStart =
                    auditService.listExchanges(PROFILE_ID, STARTED_AT, STARTED_AT.plusSeconds(60), null, 0, 50);

            assertThat(endsAtStart.items())
                    .as("'to' is exclusive, so the attempt landing exactly on it belongs to the NEXT window")
                    .isEmpty();
            assertThat(startsAtStart.items())
                    .as("'from' is inclusive, so adjacent windows tile without dropping or duplicating a row")
                    .hasSize(1);
        }

        @Test
        void unknownIdIsNotFound() {
            assertThatThrownBy(() -> auditService.getExchange(UUID.randomUUID()))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .extracting(ex -> ((SupplierNotFoundException) ex).getCode())
                    .isEqualTo(SupplierNotFoundException.EXCHANGE_AUDIT_NOT_FOUND);
        }
    }

    // ── The audited content read ────────────────────────────────────────────────────

    @Nested
    @DisplayName("payload reads are recorded, and recorded honestly")
    class PayloadReads {

        @Test
        void servesContentAndCommitsAnAccessRecordNamingTheReader() {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);

            ExchangeAuditPayloadView view = auditService.readPayload(id);

            assertThat(view.requestPayload()).isEqualTo(REQUEST_DOC);
            assertThat(view.responsePayload()).isEqualTo(RESPONSE_DOC);
            assertThat(view.redacted())
                    .as("FULL is the only capture level whose content is the wire document")
                    .isFalse();

            // Read back in a SEPARATE transaction: this proves the row COMMITTED, not merely flushed.
            AuditAccessEntity recorded = transactionTemplate.execute(
                    status -> accessRepository.findAll().getFirst());
            assertThat(recorded.getExchangeAuditId()).isEqualTo(id);
            assertThat(recorded.getVendorProfileId()).isEqualTo(PROFILE_ID);
            assertThat(recorded.getCapability()).isEqualTo(SupplierCapability.STOCK_INQUIRY);
            assertThat(recorded.getAccessedBy())
                    .as("the subject comes from the security context; an actor a caller could supply would be a"
                            + " claim, not an audit record")
                    .isEqualTo(READER);
            assertThat(recorded.getPayloadOutcome()).isEqualTo(AuditPayloadOutcome.DECRYPTED);
            assertThat(recorded.getAccessedAt()).isEqualTo(Instant.parse("2026-08-11T14:22:07.412Z"));
        }

        @Test
        void marksARedactedRowAsAlteredContent() {
            UUID id = seedExchange(PayloadCaptureLevel.REDACTED, REQUEST_DOC, RESPONSE_DOC);

            assertThat(auditService.readPayload(id).redacted())
                    .as("a REDACTED row is NOT the wire document, and a caller comparing it against a vendor's"
                            + " records must be told so explicitly rather than left to infer it")
                    .isTrue();
        }

        @Test
        void recordsNotCapturedRatherThanClaimingADecryption() {
            UUID id = seedExchange(PayloadCaptureLevel.METADATA_ONLY, null, null);

            ExchangeAuditPayloadView view = auditService.readPayload(id);

            assertThat(view.requestPayload())
                    .as("no captured content is a normal state, not a 404 and not an error")
                    .isNull();
            AuditPayloadOutcome outcome = transactionTemplate.execute(
                    status -> accessRepository.findAll().getFirst().getPayloadOutcome());
            assertThat(outcome)
                    .as("an evidentiary row must not claim a disclosure that never happened")
                    .isEqualTo(AuditPayloadOutcome.NOT_CAPTURED);
        }

        @Test
        void keepsTheAccessRecordWhenTheStoredContentCannotBeDecrypted() throws Exception {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);
            corruptRequestPayload(id);

            assertThatThrownBy(() -> auditService.readPayload(id))
                    .isInstanceOf(PayloadUnreadableException.class)
                    .extracting(ex -> ((PayloadUnreadableException) ex).getCode())
                    .isEqualTo(PayloadUnreadableException.UNKNOWN_KEY_ID);

            // Asserted on the LIST, not on getFirst(). If the record is missing, getFirst() throws
            // NoSuchElementException before this test's own assertion can run -- so the failure would be an
            // exception with no explanation instead of a message naming the guarantee that broke. The
            // difference matters because this is the assertion the mutation check points at.
            var recorded = transactionTemplate.execute(status -> accessRepository.findAll());
            assertThat(recorded)
                    .as("the read HAPPENED and produced nothing usable, so noRollbackFor must keep this record"
                            + " alive through the error response -- rolling it back would delete the evidence"
                            + " of the one event most worth investigating")
                    .hasSize(1);
            AuditAccessEntity row = recorded.getFirst();
            assertThat(row.getPayloadOutcome()).isEqualTo(AuditPayloadOutcome.UNREADABLE);
            assertThat(row.getAccessedBy()).isEqualTo(READER);
        }

        @Test
        void servesNoContentAtAllWhenTheAccessRecordCannotBeWritten() {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "simulated access-audit write failure"))
                    .when(accessRepository)
                    .saveAndFlush(org.mockito.ArgumentMatchers.any());

            assertThatThrownBy(() -> auditService.readPayload(id))
                    .as("ADR-0050 §7 makes the access record a PRECONDITION of access: if it cannot be written,"
                            + " the caller gets nothing. Serving the payload anyway is the one outcome §7 exists"
                            + " to prevent")
                    .isInstanceOf(DataAccessException.class);

            assertThat(countAccessRows())
                    .as("and the failed record must not have half-committed either")
                    .isZero();
        }

        @Test
        void unknownIdIsNotFoundAndRecordsNothing() {
            assertThatThrownBy(() -> auditService.readPayload(UUID.randomUUID()))
                    .isInstanceOf(SupplierNotFoundException.class);

            assertThat(countAccessRows())
                    .as("there was no exchange to read, so there is nothing to record")
                    .isZero();
        }

        @Test
        void listsTheAccessTrailNewestFirstWithoutRecordingTheListing() {
            UUID id = seedExchange(PayloadCaptureLevel.FULL, REQUEST_DOC, RESPONSE_DOC);
            auditService.readPayload(id);
            auditService.readPayload(id);

            var trail = auditService.listAccesses(id, 0, 50);

            assertThat(trail.items()).hasSize(2);
            assertThat(trail.totalElements()).isEqualTo(2);
            assertThat(trail.items())
                    .allSatisfy(access -> assertThat(access.accessedBy()).isEqualTo(READER));
            assertThat(countAccessRows())
                    .as("inspecting the trail must not extend it, or every audit review would generate the noise"
                            + " the next review has to sift through")
                    .isEqualTo(2);
        }
    }

    // ── The MANDATORY guard ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the recorder cannot be called outside a transaction")
    class MandatoryPropagation {

        @Test
        void refusesToRecordWithNoEnclosingTransaction() {
            assertThatThrownBy(() -> accessRecorder.recordPayloadRead(
                            UUID.randomUUID(),
                            PROFILE_ID,
                            SupplierCapability.STOCK_INQUIRY,
                            AuditPayloadOutcome.DECRYPTED))
                    .as("MANDATORY rather than REQUIRED: without it this could commit on its own while the read"
                            + " it authorises fails, and the atomic 'recorded if and only if served' property"
                            + " would be lost silently")
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(countAccessRows()).isZero();
        }

        @Test
        void recordsHappilyInsideOne() {
            UUID exchangeId = UUID.randomUUID();

            assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> accessRecorder.recordPayloadRead(
                            exchangeId, PROFILE_ID, SupplierCapability.STOCK_INQUIRY, AuditPayloadOutcome.DECRYPTED)))
                    .doesNotThrowAnyException();

            assertThat(countAccessRows()).isEqualTo(1);
        }
    }
}
