package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.entity.ExtCatalogReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the catalog.events.v1 listener (issue #924): manual envelope parse,
 * processed_events dedupe, strictly-below stale guard on aggregateVersion, transient DB errors
 * rethrown for container retry/DLQ.
 */
class CatalogEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000b01");
    private static final UUID MANUFACTURER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000b02");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000b03");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtCatalogReplicaRepository extCatalog = mock(ExtCatalogReplicaRepository.class);

    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, extCatalog, mock(ObjectProvider.class));
    }

    private static String updatedEvent(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"catalog.product.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"productId":"%s","sku":"TIRE-1","name":"SuperTire","manufacturerId":"%s",
                            "manufacturerName":"Michelin","manufacturerBrand":"Michelin","categoryId":"%s",
                            "category":"Tires","warranty":"2yr","manufacturerWarranty":"5yr","active":true,
                            "createdAt":"2026-07-01T09:00:00Z","updatedAt":"2026-07-10T23:30:00Z"}}
                """.formatted(eventId, PRODUCT_ID, aggregateVersion, PRODUCT_ID, MANUFACTURER_ID, CATEGORY_ID);
    }

    @Test
    @DisplayName("Upserts the ext_catalog replica from a catalog.product.updated fact")
    void upsertsReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(extCatalog.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        listener.onCatalogEvent(updatedEvent("e-1", 100));

        ArgumentCaptor<ExtCatalogReplica> captor = ArgumentCaptor.captor();
        verify(extCatalog).save(captor.capture());
        ExtCatalogReplica saved = captor.getValue();
        assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getSku()).isEqualTo("TIRE-1");
        assertThat(saved.getManufacturerId()).isEqualTo(MANUFACTURER_ID);
        assertThat(saved.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(saved.getManufacturerWarranty()).isEqualTo("5yr");
        assertThat(saved.getAggregateVersion()).isEqualTo(100L);

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getOwner()).isEqualTo(CatalogEventsListener.OWNER);
    }

    @Test
    @DisplayName("Drops a stale fact (older aggregateVersion) but still records it processed")
    void dropsStaleFact() {
        when(processedEvents.existsById("e-stale")).thenReturn(false);
        when(extCatalog.findById(PRODUCT_ID))
                .thenReturn(Optional.of(ExtCatalogReplica.builder()
                        .productId(PRODUCT_ID)
                        .aggregateVersion(200L)
                        .updatedAt(Instant.parse("2026-07-14T00:00:00Z"))
                        .build()));

        listener.onCatalogEvent(updatedEvent("e-stale", 100));

        verify(extCatalog, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onCatalogEvent(updatedEvent("e-dup", 100));

        verify(extCatalog, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(extCatalog.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        when(extCatalog.save(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCatalogEvent(updatedEvent("e-4", 100)));

        verify(processedEvents, never()).save(any());
    }
}
