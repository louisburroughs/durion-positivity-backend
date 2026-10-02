package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.command.service.SupplierCommandListener;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.order.service.TransmissionIntentWriter;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogRepublisher;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
 */
@Import({
    JpaConfig.class,
    MktCatVariantStager.class,
    MktCatRepublisher.class,
    SupplierOutboxEventWriter.class,
    MktCatRepublishCommandTest.SupportConfig.class
})
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

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
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
