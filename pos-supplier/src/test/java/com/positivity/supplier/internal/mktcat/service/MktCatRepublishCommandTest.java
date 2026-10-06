package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.command.service.SupplierCommandListener;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.order.service.OrderNotDispatchedPublisher;
import com.positivity.supplier.internal.order.service.TransmissionIntentWriter;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogRepublisher;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierOutboxReplayService;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recovery #2356 needs, end to end on this side of the wall: an unchanged variant that an
 * ordinary import will not publish is published again, under a new event id, by a
 * {@code supplier.catalog.republish.requested} command.
 *
 * <h2>Why it runs against the real outbox</h2>
 *
 * The defect being recovered from is one of identity: the consumer recorded the original event ids
 * as ignored, so only an event with a <em>different</em> id and the <em>same</em> content gets
 * through. Asserting that on a mocked writer would prove the republisher called something; reading
 * the committed outbox rows proves what a consumer will actually receive — and that the command and
 * its {@code processed_events} mark commit together, so a redelivery re-emits nothing.
 *
 * <p>{@code Propagation.NOT_SUPPORTED} for the reason {@link MktCatVariantStagerTransactionTest}
 * gives: the stager, the listener and the republisher each own their transaction, and a test wrapped
 * in its own would hide whether any of them committed.
 *
 * <p>The page size is 1 throughout, so every run that re-emits more than one variant crosses a page
 * boundary — and with it the flush and clear of the persistence context that boundary performs.
 */
@Import({
    JpaConfig.class,
    MktCatVariantStager.class,
    MktCatRepublisher.class,
    SupplierOutboxEventWriter.class,
    MktCatRepublishCommandTest.SupportConfig.class
})
@TestPropertySource(properties = "pos.supplier.mktcat.republish-page-size=1")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("MKCAT re-publication command — an unchanged variant is published again under a new id (#2356)")
class MktCatRepublishCommandTest extends PostgresSliceTestBase {

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

    /**
     * Finer than the {@code timestamp(6)} the staged row keeps, as a production clock is. With a
     * whole-second instant the round trip through the database is lossless by accident, and the
     * comparison of the original payload with the re-emitted one below would prove nothing.
     */
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123456789Z");

    private static final SupplierRef SUPPLIER = new SupplierRef("ediwheel-net");

    @Autowired
    private MktCatVariantStager stager;

    @Autowired
    private MktCatRepublisher republisher;

    @Autowired
    private SupplierMktCatVariantRepository variantRepository;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

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

    private final List<String> commandIds = new ArrayList<>();
    private SupplierCommandListener listener;

    @BeforeEach
    void setUp() {
        profileId = UUID.randomUUID();
        listener = new SupplierCommandListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper,
                processedEventRepository,
                mock(TransmissionIntentWriter.class),
                mock(PriceCatalogRepublisher.class),
                republisher,
                mock(OrderNotDispatchedPublisher.class),
                mock(SupplierOutboxReplayService.class),
                Duration.ofDays(30),
                transactionManager);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            delete(connection, "DELETE FROM supplier_mktcat_variant WHERE vendor_profile_id = ?", profileId);
            delete(connection, "DELETE FROM supplier_event_outbox WHERE payload LIKE ?", "%" + profileId + "%");
            for (String commandId : commandIds) {
                delete(connection, "DELETE FROM processed_events WHERE event_id = ?", commandId);
            }
        }
    }

    private static void delete(Connection connection, String sql, Object parameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("an ordinary import publishes nothing for an unchanged variant; the command publishes it again")
    void unchangedVariantIsNotPublishedByAnImportButIsByTheCommand() {
        assertThat(stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1"))
                .isTrue();
        JsonNode original = single(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE));

        // The limitation the issue names: the vendor sends the same content again and nothing goes
        // out, however many times the import runs.
        assertThat(stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1"))
                .isFalse();
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE)).hasSize(1);

        listener.onSupplierCommand(republishCommand(newCommandId()));

        List<JsonNode> updates = eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE);
        assertThat(updates).hasSize(2);
        JsonNode reEmitted = updates.stream()
                .filter(event -> !event.path("eventId").equals(original.path("eventId")))
                .findFirst()
                .orElseThrow();
        // A new id — the one thing a replay of the original event cannot have — on the same
        // partition key, carrying the same content.
        assertThat(reEmitted.path("eventId").stringValue()).isNotBlank();
        assertThat(reEmitted.path("aggregateId")).isEqualTo(original.path("aggregateId"));
        assertThat(reEmitted.path("tenantId").stringValue()).isEqualTo(TENANT.toString());
        assertThat(reEmitted.path("payload").path("contentHash").stringValue()).isEqualTo("hash-1");
        assertThat(reEmitted.path("payload")).isEqualTo(original.path("payload"));

        JsonNode completion = single(eventsOfType(SupplierCatalogRepublishCompletedV1.EVENT_TYPE));
        assertThat(completion.path("payload").path("variantCount").intValue()).isEqualTo(1);
        assertThat(completion.path("payload").path("vendorProfileId").stringValue())
                .isEqualTo(profileId.toString());
    }

    @Test
    @DisplayName("the re-publication leaves the stored hash alone, so the next import is still silent")
    void rePublicationDoesNotChangeWhatAnOrdinaryImportPublishes() {
        stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1");
        SupplierMktCatVariantEntity before = stagedRow("V1");

        listener.onSupplierCommand(republishCommand(newCommandId()));

        SupplierMktCatVariantEntity after = stagedRow("V1");
        assertThat(after.getContentHash()).isEqualTo("hash-1");
        assertThat(after.getLastPublishedAt()).isEqualTo(before.getLastPublishedAt());
        assertThat(stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1"))
                .isFalse();
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE)).hasSize(2);
    }

    @Test
    @DisplayName("every variant of the profile is re-emitted, and only that profile's")
    void reEmitsEveryVariantOfTheNamedProfileOnly() {
        UUID otherProfile = UUID.randomUUID();
        try {
            stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1");
            stager.stageAndPublish(profileId, SUPPLIER, variant("V2"), texts(), List.of(), "hash-2");
            stager.stageAndPublish(otherProfile, SUPPLIER, variant("V9"), texts(), List.of(), "hash-9");

            listener.onSupplierCommand(republishCommand(newCommandId()));

            // Two original publications plus their two re-emits; the other profile's variant was
            // published once and not again.
            assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE))
                    .extracting(event ->
                            event.path("payload").path("vendorVariantId").stringValue())
                    .containsExactlyInAnyOrder("V1", "V2", "V1", "V2");
            assertThat(single(eventsOfType(SupplierCatalogRepublishCompletedV1.EVENT_TYPE))
                            .path("payload")
                            .path("variantCount")
                            .intValue())
                    .isEqualTo(2);
            assertThat(outboxRepository.findAll().stream()
                            .filter(row -> row.getPayload().contains(otherProfile.toString()))
                            .count())
                    .isEqualTo(1);
        } finally {
            cleanUpProfile(otherProfile);
        }
    }

    @Test
    @DisplayName("a redelivered command re-emits nothing; a second command re-emits everything again")
    void redeliveryIsANoOpAndASecondCommandIsNot() {
        stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1");
        String commandId = newCommandId();

        listener.onSupplierCommand(republishCommand(commandId));
        listener.onSupplierCommand(republishCommand(commandId));

        // The command and its processed mark committed together, so the redelivery stops at the
        // event-id guard.
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE)).hasSize(2);

        listener.onSupplierCommand(republishCommand(newCommandId()));

        // Deliberately unbounded: a new command is a new request. Safe only because the consumer
        // treats an unchanged contentHash as a no-op.
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE)).hasSize(3);
        assertThat(eventsOfType(SupplierCatalogRepublishCompletedV1.EVENT_TYPE)).hasSize(2);
    }

    @Test
    @DisplayName("a multi-page run keeps one page in the persistence context, not the whole catalogue")
    void aMultiPageRunDoesNotAccumulateEveryPageInThePersistenceContext() {
        stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1");
        stager.stageAndPublish(profileId, SUPPLIER, variant("V2"), texts(), images(), "hash-2");
        stager.stageAndPublish(profileId, SUPPLIER, variant("V3"), texts(), images(), "hash-3");

        // The republisher joins this transaction the way it joins the listener's, so the persistence
        // context it worked in can still be inspected once it has returned.
        Integer managedAfterTheRun = new TransactionTemplate(transactionManager).execute(_ -> {
            republisher.republish(new SupplierCatalogRepublishRequestedV1(profileId, "operator", "#2356"));
            return entityManager.unwrap(Session.class).getStatistics().getEntityCount();
        });

        // Three pages of one variant each. Left to accumulate, the context would hold all three
        // staged rows, their three outbox rows and the completion: seven entities, growing with the
        // catalogue. Cleared per page it holds only what was queued after the last clear — the
        // completion event.
        assertThat(managedAfterTheRun).isEqualTo(1);
        // And clearing lost nothing and skipped nothing: every variant was re-emitted exactly once.
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE))
                .extracting(
                        event -> event.path("payload").path("vendorVariantId").stringValue())
                .containsExactlyInAnyOrder("V1", "V2", "V3", "V1", "V2", "V3");
        assertThat(single(eventsOfType(SupplierCatalogRepublishCompletedV1.EVENT_TYPE))
                        .path("payload")
                        .path("variantCount")
                        .intValue())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an unreadable staged row on a later page rolls back the pages already flushed, and the mark")
    void anUnreadableLaterRowRollsBackEverythingTheRunFlushed() throws Exception {
        stager.stageAndPublish(profileId, SUPPLIER, variant("V1"), texts(), images(), "hash-1");
        stager.stageAndPublish(profileId, SUPPLIER, variant("V2"), texts(), images(), "hash-2");
        // The second row in the republisher's own order. With a page size of 1 the first row's
        // outbox insert has been flushed to the database by the time this one is read.
        UUID secondRow = variantRepository
                .findByVendorProfileIdOrderBySupplierMktCatVariantIdAsc(profileId, PageRequest.of(1, 1))
                .getFirst()
                .getSupplierMktCatVariantId();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement corrupt = connection.prepareStatement(
                        "UPDATE supplier_mktcat_variant SET texts_json = ? WHERE supplier_mktcat_variant_id = ?")) {
            corrupt.setString(1, "{not json");
            corrupt.setObject(2, secondRow);
            assertThat(corrupt.executeUpdate()).isEqualTo(1);
        }
        String commandId = newCommandId();

        // Rethrown to the container, not recorded: the command is valid and this module's staged data
        // is what is broken, so the record is retried and dead-lettered rather than marked handled.
        assertThatThrownBy(() -> listener.onSupplierCommand(republishCommand(commandId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("texts_json");

        // All or nothing. Only the two original publications remain: the first variant's re-emit was
        // flushed and then rolled back, no completion claims a count, and the command is unmarked so
        // its redelivery is not skipped as a repeat.
        assertThat(eventsOfType(SupplierCatalogUpdatedV1.EVENT_TYPE)).hasSize(2);
        assertThat(eventsOfType(SupplierCatalogRepublishCompletedV1.EVENT_TYPE)).isEmpty();
        assertThat(processedEventRepository.existsById(commandId)).isFalse();
    }

    private void cleanUpProfile(UUID otherProfile) {
        try (Connection connection = dataSource.getConnection()) {
            delete(connection, "DELETE FROM supplier_mktcat_variant WHERE vendor_profile_id = ?", otherProfile);
            delete(connection, "DELETE FROM supplier_event_outbox WHERE payload LIKE ?", "%" + otherProfile + "%");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String newCommandId() {
        String commandId = UUID.randomUUID().toString();
        commandIds.add(commandId);
        return commandId;
    }

    private String republishCommand(String eventId) {
        return """
                {"eventId":"%s","eventType":"supplier.catalog.republish.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-10-02T09:59:00Z",
                 "sourceService":"pos-catalog","tenantId":"%s",
                 "payload":{"vendorProfileId":"%s","requestedBy":"operator","reason":"#2356 recovery"}}
                """.formatted(eventId, profileId, TENANT, profileId);
    }

    /** This test's committed outbox envelopes of one type, in publication order. */
    private List<JsonNode> eventsOfType(String eventType) {
        return outboxRepository.findAll().stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .filter(row -> row.getPayload().contains(profileId.toString()))
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .map(SupplierOutboxEventEntity::getPayload)
                .map(objectMapper::readTree)
                .toList();
    }

    private static JsonNode single(List<JsonNode> events) {
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private SupplierMktCatVariantEntity stagedRow(String vendorVariantId) {
        return variantRepository
                .findByVendorProfileIdAndVendorVariantId(profileId, vendorVariantId)
                .orElseThrow();
    }

    private static MarketingVariant variant(String vendorVariantId) {
        return new MarketingVariant(
                vendorVariantId,
                "Michelin",
                "Primacy 4",
                null,
                "Michelin Primacy 4",
                "passenger",
                "summer",
                List.of(),
                List.of());
    }

    private static List<SupplierCatalogEnrichmentText> texts() {
        return List.of(new SupplierCatalogEnrichmentText("de", "Primacy 4", "Sommerreifen", null));
    }

    private static List<SupplierCatalogEnrichmentImage> images() {
        return List.of(
                new SupplierCatalogEnrichmentImage("HERO", 42L, "img-hash", "https://cdn.example.com/a.jpg", false),
                SupplierCatalogEnrichmentImage.unresolved("TREAD", "https://cdn.example.com/b.jpg"));
    }
}
