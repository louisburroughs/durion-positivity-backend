package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persistence-context bound of a PRICAT re-publication (#2420), against the real outbox.
 *
 * <p>Reading one chunk at a time bounds each query, not the heap: one transaction is one
 * persistence context, and without a clear every staged line read and every outbox row queued stays
 * managed until commit. The MKCAT re-publication had the same shape and was fixed in #2356; this is
 * the PRICAT counterpart of its {@code aMultiPageRunDoesNotAccumulateEveryPageInThePersistenceContext}.
 *
 * <p>{@code Propagation.NOT_SUPPORTED} so the seeded rows commit before the run and the run's
 * transaction is the one the test opens around it.
 */
@Import({
    JpaConfig.class,
    PriceCatalogRepublisher.class,
    SupplierOutboxEventWriter.class,
    PriceCatalogRepublishPersistenceContextTest.SupportConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PRICAT re-publication — one chunk at a time in the persistence context too (#2420)")
class PriceCatalogRepublishPersistenceContextTest extends PostgresSliceTestBase {

    @TestConfiguration
    static class SupportConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant FETCHED_AT = Instant.parse("2026-10-01T06:00:00Z");
    private static final int CHUNKS = 3;

    @Autowired
    private PriceCatalogRepublisher republisher;

    @Autowired
    private PriceCatalogImportRepository importRepository;

    @Autowired
    private PriceCatalogEntryRepository entryRepository;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    /** Fresh per test, so rows other slices left in the shared container cannot be mistaken for ours. */
    private UUID profileId;

    private UUID importId;

    @BeforeEach
    void setUp() {
        profileId = UUID.randomUUID();
        PriceCatalogImportEntity manifest = importRepository.saveAndFlush(PriceCatalogImportEntity.builder()
                .vendorProfileId(profileId)
                .supplierRef("michelin-eu")
                .protocolFamily(ProtocolFamily.EDIWHEEL_B)
                .protocolVersion("B4_0")
                .status(PriceCatalogImportStatus.COMPLETED)
                .sourceDocumentId("PRICAT-1")
                .sourceDocumentDate(LocalDate.of(2026, 9, 30))
                .buyerAccountNumber("30012456")
                .countryCode("SE")
                .currency("SEK")
                .fetchedAt(FETCHED_AT)
                .completedAt(FETCHED_AT.plusSeconds(60))
                .linesFetched(CHUNKS)
                .linesMatched(CHUNKS)
                .chunkCount(CHUNKS)
                .chunkSize(1)
                .contentChecksum("abc123")
                .correlationId("corr-2420")
                .build());
        importId = manifest.getImportManifestId();
        for (int sequence = 1; sequence <= CHUNKS; sequence++) {
            entryRepository.saveAndFlush(PriceCatalogEntryEntity.builder()
                    .importManifestId(importId)
                    .vendorProfileId(profileId)
                    .positionNumber(sequence)
                    .chunkSequence(sequence)
                    .articleEan("352870999908" + sequence)
                    .supplierArticleCode("9999" + sequence)
                    .matchedProductId(UUID.randomUUID())
                    .matchMethod(PriceCatalogMatchMethod.EAN)
                    .netPrice(new BigDecimal("90.00"))
                    .effectiveFrom(LocalDate.of(2026, 10, 1))
                    .buyerAccountNumber("30012456")
                    .countryCode("SE")
                    .currency("SEK")
                    .fetchedAt(FETCHED_AT)
                    .build());
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            delete(connection, "DELETE FROM supplier_event_outbox WHERE payload LIKE ?", "%" + importId + "%");
            delete(connection, "DELETE FROM supplier_pricat_entry WHERE import_manifest_id = ?", importId);
            delete(connection, "DELETE FROM supplier_pricat_import WHERE import_manifest_id = ?", importId);
        }
    }

    private static void delete(Connection connection, String sql, Object parameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("a multi-chunk run does not keep every chunk's lines and events in the persistence context")
    void aMultiChunkRunDoesNotAccumulateEveryChunkInThePersistenceContext() {
        // The republisher joins this transaction the way it joins the listener's, so the persistence
        // context it worked in can still be inspected once it has returned.
        Integer managedAfterTheRun = new TransactionTemplate(transactionManager).execute(_ -> {
            assertThat(republisher.republish(new SupplierPriceCatalogRepublishRequestedV1(
                            importId, profileId, 1, CHUNKS, "pos-catalog", "#2420")))
                    .isEqualTo(CHUNKS);
            return entityManager.unwrap(Session.class).getStatistics().getEntityCount();
        });

        // Three chunks of one line each. Left to accumulate, the context would hold the manifest, the
        // three staged lines, their three outbox rows and the completion: eight entities, growing with
        // the catalogue. Cleared per chunk it holds only what was queued after the last clear — the
        // completion event.
        assertThat(managedAfterTheRun).isEqualTo(1);

        // Clearing lost nothing and skipped nothing: every chunk was re-emitted once, in order, then
        // the completion.
        assertThat(eventsOfType(SupplierPriceCatalogUpdatedV1.EVENT_TYPE))
                .extracting(event -> event.path("payload").path("chunkSequence").intValue())
                .containsExactly(1, 2, 3);
        assertThat(eventsOfType(SupplierPriceCatalogImportCompletedV1.EVENT_TYPE))
                .hasSize(1);
        // And the attempt counter, written to the manifest the clear detaches, still reached the row.
        PriceCatalogImportEntity reloaded = importRepository.findById(importId).orElseThrow();
        assertThat(reloaded.getRepublishCount()).isEqualTo(1);
        assertThat(reloaded.getLastRepublishedAt()).isEqualTo(NOW);
    }

    /** This test's committed outbox envelopes of one type, in publication order. */
    private List<JsonNode> eventsOfType(String eventType) {
        return outboxRepository.findAll().stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .filter(row -> row.getPayload().contains(importId.toString()))
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .map(SupplierOutboxEventEntity::getPayload)
                .map(objectMapper::readTree)
                .toList();
    }
}
