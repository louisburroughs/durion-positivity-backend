package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("supplier.events.v1 single dispatching consumer (#2177)")
class SupplierEventsListenerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SupplierPriceCatalogEventHandler priceCatalogHandler;

    @Mock
    private SupplierCatalogEnrichmentHandler enrichmentHandler;

    private SupplierEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierEventsListener(
                CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                priceCatalogHandler,
                enrichmentHandler,
                mock(PlatformTransactionManager.class));
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
    }

    private static String event(String eventId, String eventType) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"aggregateVersion\":0,\"payload\":{}}";
    }

    private static JsonNode envelopeOf(String json) {
        return new ObjectMapper().readTree(json);
    }

    @Test
    void routesAPriceCatalogChunkToThePricatHandlerOnly() {
        String json = event("e-1", "supplier.pricecatalog.updated");

        listener.onSupplierEvent(json);

        verify(priceCatalogHandler).handle(envelopeOf(json), "e-1");
        verifyNoInteractions(enrichmentHandler);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void routesAnImportCompletionToThePricatHandlerOnly() {
        String json = event("e-2", "supplier.pricecatalog.import.completed");

        listener.onSupplierEvent(json);

        verify(priceCatalogHandler).handle(envelopeOf(json), "e-2");
        verifyNoInteractions(enrichmentHandler);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void routesACatalogUpdateToTheEnrichmentHandlerWithoutMarkingItProcessedFirst() {
        String json = event("e-3", "supplier.catalog.updated");

        listener.onSupplierEvent(json);

        verify(enrichmentHandler).handle(envelopeOf(json), "e-3");
        verifyNoInteractions(priceCatalogHandler);
        // #2177: recording the id before enrichment runs is what suppressed the enrichment.
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void recordsAnUnrelatedTypeOnceAsSupplierOwnedAndHandsItToNoHandler() {
        listener.onSupplierEvent(event("e-4", "supplier.order.confirmed"));

        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo("e-4");
        assertThat(saved.getValue().getOwner()).isEqualTo("supplier");
        assertThat(saved.getValue().getProcessedAt()).isEqualTo(Instant.now(CLOCK));
        verifyNoInteractions(priceCatalogHandler, enrichmentHandler);
    }

    @Test
    void skipsAnAlreadyProcessedEventIdEntirely() {
        when(processedEventRepository.existsById("e-5")).thenReturn(true);

        listener.onSupplierEvent(event("e-5", "supplier.catalog.updated"));
        listener.onSupplierEvent(event("e-5", "supplier.pricecatalog.updated"));
        listener.onSupplierEvent(event("e-5", "supplier.order.confirmed"));

        verifyNoInteractions(priceCatalogHandler, enrichmentHandler);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void skipsAnEventWithoutAnEventId() {
        listener.onSupplierEvent("{\"eventType\":\"supplier.catalog.updated\",\"payload\":{}}");
        listener.onSupplierEvent("{\"eventId\":\" \",\"eventType\":\"supplier.catalog.updated\",\"payload\":{}}");

        verifyNoInteractions(priceCatalogHandler, enrichmentHandler);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void skipsAnUnparsableMessage() {
        listener.onSupplierEvent("not json");

        verifyNoInteractions(priceCatalogHandler, enrichmentHandler);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void rethrowsATransientDatabaseErrorOnTheIgnoredPathSoTheContainerRetries() {
        when(processedEventRepository.save(any(ProcessedEvent.class))).thenThrow(new QueryTimeoutException("db busy"));

        assertThatThrownBy(() -> listener.onSupplierEvent(event("e-6", "supplier.order.confirmed")))
                .isInstanceOf(QueryTimeoutException.class);

        verifyNoInteractions(priceCatalogHandler, enrichmentHandler);
    }
}
