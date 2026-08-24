package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import com.positivity.marketing.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link CatalogEventsListener}, which feeds the {@code ext_catalog} replica a
 * campaign's {@code catalogFocusRef} is resolved against (#1306).
 *
 * <p>What is worth pinning is what the replica must get right for that resolution to mean anything:
 *
 * <ul>
 *   <li><b>Both catalog facts land.</b> Services are why this listener exists, but {@code sku:} and
 *       {@code category:} references resolve against product rows, so products are replicated too.
 *   <li><b>Kinds do not blur.</b> A service row carries no sku or category, so a {@code sku:}
 *       reference can never accidentally resolve to a service.
 *   <li><b>A deletion is not silence.</b> The tombstone keeps the row and marks it inactive, which
 *       is what lets the validator say "that service was removed" instead of nothing.
 *   <li><b>An out-of-order redelivery cannot undo a newer fact</b>, guarded on the owner's
 *       {@code aggregateVersion}.
 *   <li><b>Replays apply nothing twice</b>, and a fact this module does not consume is not recorded
 *       as processed.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogEventsListener — catalog replication for reference resolution")
class CatalogEventsListenerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("01960006-0000-7000-8000-0000000000d1");
    private static final UUID SERVICE_ID = UUID.fromString("01960006-0000-7000-8000-0000000000d2");
    private static final UUID CATEGORY_ID = UUID.fromString("01960006-0000-7000-8000-0000000000d3");
    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtCatalogReplicaRepository catalogReplicaRepository;

    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(clock, objectMapper, processedEventRepository, catalogReplicaRepository);
    }

    private static String envelope(String eventId, String eventType, long aggregateVersion, String payloadJson) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,"payload":%s}""".formatted(eventId, eventType, aggregateVersion, payloadJson);
    }

    private static String productPayload(boolean active) {
        return """
                {"productId":"%s","sku":"SKU-1","name":"Tire 205/55R16","categoryId":"%s","category":"Tires","active":%b}""".formatted(PRODUCT_ID, CATEGORY_ID, active);
    }

    private static String servicePayload(boolean active) {
        return """
                {"serviceId":"%s","name":"Wheel alignment","active":%b}""".formatted(SERVICE_ID, active);
    }

    private ExtCatalogReplica captureSaved() {
        ArgumentCaptor<ExtCatalogReplica> captor = ArgumentCaptor.forClass(ExtCatalogReplica.class);
        verify(catalogReplicaRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("product facts")
    class ProductFacts {

        @Test
        @DisplayName("replicates the identifiers a product reference can be written with")
        void replicatesProduct() {
            listener.onCatalogEvent(
                    envelope("evt-1", ProductUpdatedV1.EVENT_TYPE, 1_700_000_000_000L, productPayload(true)));

            ExtCatalogReplica saved = captureSaved();
            assertThat(saved.getCatalogItemId()).isEqualTo(PRODUCT_ID);
            assertThat(saved.getItemKind()).isEqualTo(CatalogItemKind.PRODUCT);
            assertThat(saved.getName()).isEqualTo("Tire 205/55R16");
            assertThat(saved.getSku()).isEqualTo("SKU-1");
            assertThat(saved.getCategoryId()).isEqualTo(CATEGORY_ID);
            assertThat(saved.getCategory()).isEqualTo("Tires");
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.getAggregateVersion()).isEqualTo(1_700_000_000_000L);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a deactivated product stays in the replica, marked inactive")
        void deactivatedProductIsKept() {
            listener.onCatalogEvent(envelope("evt-2", ProductUpdatedV1.EVENT_TYPE, 2L, productPayload(false)));

            assertThat(captureSaved().isActive()).isFalse();
        }

        @Test
        @DisplayName("a fact with no productId is skipped rather than written under a null key")
        void missingProductIdIsSkipped() {
            listener.onCatalogEvent(envelope("evt-3", ProductUpdatedV1.EVENT_TYPE, 1L, """
                    {"name":"Nameless"}"""));

            verify(catalogReplicaRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("service facts")
    class ServiceFacts {

        @Test
        @DisplayName("replicates the service, leaving product-only columns unset")
        void replicatesService() {
            listener.onCatalogEvent(
                    envelope("evt-4", CatalogServiceUpdatedV1.EVENT_TYPE, 1_700_000_000_001L, servicePayload(true)));

            ExtCatalogReplica saved = captureSaved();
            assertThat(saved.getCatalogItemId()).isEqualTo(SERVICE_ID);
            assertThat(saved.getItemKind()).isEqualTo(CatalogItemKind.SERVICE);
            assertThat(saved.getName()).isEqualTo("Wheel alignment");
            assertThat(saved.isActive()).isTrue();
            // A sku: reference must never resolve to a service.
            assertThat(saved.getSku()).isNull();
            assertThat(saved.getCategoryId()).isNull();
            assertThat(saved.getCategory()).isNull();
        }

        @Test
        @DisplayName("the delete tombstone keeps the row and marks it inactive")
        void tombstoneMarksInactive() {
            listener.onCatalogEvent(
                    envelope("evt-5", CatalogServiceUpdatedV1.EVENT_TYPE, 1_700_000_000_002L, servicePayload(false)));

            ExtCatalogReplica saved = captureSaved();
            assertThat(saved.isActive()).isFalse();
            assertThat(saved.getName()).isEqualTo("Wheel alignment");
        }

        @Test
        @DisplayName("a fact with no serviceId is skipped")
        void missingServiceIdIsSkipped() {
            listener.onCatalogEvent(envelope("evt-6", CatalogServiceUpdatedV1.EVENT_TYPE, 1L, """
                    {"name":"Nameless"}"""));

            verify(catalogReplicaRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("envelope handling")
    class Envelope {

        @Test
        @DisplayName("an older redelivery does not undo the newer fact already held")
        void staleFactIsIgnored() {
            when(catalogReplicaRepository.findById(SERVICE_ID))
                    .thenReturn(Optional.of(ExtCatalogReplica.builder()
                            .catalogItemId(SERVICE_ID)
                            .itemKind(CatalogItemKind.SERVICE)
                            .active(false)
                            .aggregateVersion(1_700_000_000_002L)
                            .updatedAt(NOW)
                            .build()));

            listener.onCatalogEvent(
                    envelope("evt-7", CatalogServiceUpdatedV1.EVENT_TYPE, 1_700_000_000_001L, servicePayload(true)));

            verify(catalogReplicaRepository, never()).save(any());
            // The envelope is still recorded: it was seen and deliberately not applied.
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips a replay of an event already in the processed log")
        void skipsReplay() {
            when(processedEventRepository.existsById("evt-8")).thenReturn(true);

            listener.onCatalogEvent(envelope("evt-8", ProductUpdatedV1.EVENT_TYPE, 1L, productPayload(true)));

            verifyNoInteractions(catalogReplicaRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips an event with no id, since it cannot be de-duplicated")
        void skipsUnidentifiedEvent() {
            listener.onCatalogEvent(envelope("", ProductUpdatedV1.EVENT_TYPE, 1L, productPayload(true)));

            verifyNoInteractions(catalogReplicaRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("a catalog fact this module does not consume is not recorded as processed")
        void unknownEventTypeIsNotRecorded() {
            listener.onCatalogEvent(
                    envelope("evt-9", "catalog.supplier-article-code.updated", 1L, """
                    {"productId":"%s"}""".formatted(PRODUCT_ID)));

            verifyNoInteractions(catalogReplicaRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("records what it applied, keyed on the producing domain")
        void recordsProcessedEvent() {
            listener.onCatalogEvent(envelope("evt-10", ProductUpdatedV1.EVENT_TYPE, 1L, productPayload(true)));

            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("evt-10");
            assertThat(captor.getValue().getOwner()).isEqualTo(CatalogEventsListener.OWNER);
            assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("an unparsable message is left to the container, so it reaches the dead-letter topic")
        void unparsableMessagePropagates() {
            // Swallowing it would acknowledge the record as handled and lose the catalog update;
            // KafkaErrorHandlingConfig retries and then routes it to {topic}.dlq instead.
            assertThatThrownBy(() -> listener.onCatalogEvent("not json at all")).isInstanceOf(JacksonException.class);

            verifyNoInteractions(catalogReplicaRepository);
            verify(processedEventRepository, never()).save(any());
        }
    }
}
