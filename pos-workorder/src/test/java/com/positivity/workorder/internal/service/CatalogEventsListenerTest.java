package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtProductUomReplica;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog-owned {@code ext_product_uom} replica (ADR-0044 §6, #1413) — the only source this
 * module has for how divisible a product's stock is.
 */
@DisplayName("pos-workorder catalog replica listener")
class CatalogEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-19T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtProductUomReplicaRepository productUoms = mock(ExtProductUomReplicaRepository.class);
    private final com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository
            catalogServiceReplicas =
                    mock(com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository.class);

    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEvents, productUoms, catalogServiceReplicas);
        when(processedEvents.existsById(any())).thenReturn(false);
        when(productUoms.findAggregateVersions(any())).thenReturn(List.of());
    }

    private String productEvent(String eventId, long version, String conversions) {
        return """
                {"eventId":"%s","eventType":"catalog.product.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"productId":"%s","sku":"VALV-ATF-ML-QT","baseUom":"QT",
                            "uomConversions":%s}}
                """.formatted(eventId, PRODUCT_ID, version, PRODUCT_ID, conversions);
    }

    @Test
    @DisplayName("replaces the product's unit-of-measure set wholesale")
    void replacesUomSetWholesale() {
        listener.onCatalogEvent(productEvent("e1", 5L, """
                [{"uomCode":"LB","uomType":"BASE","factorToBase":1,"precisionScale":2},
                 {"uomCode":"BAG","uomType":"PURCHASE","factorToBase":1.01,"precisionScale":0}]"""));

        // Wholesale, not merged: a UoM catalog retired must disappear here too, or a part would go
        // on being judged against a scale its owner no longer publishes.
        verify(productUoms).deleteByProductId(PRODUCT_ID);
        ArgumentCaptor<ExtProductUomReplica> saved = ArgumentCaptor.forClass(ExtProductUomReplica.class);
        verify(productUoms, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(ExtProductUomReplica::getUomCode, ExtProductUomReplica::getPrecisionScale)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LB", 2), org.assertj.core.groups.Tuple.tuple("BAG", 0));
        assertThat(saved.getAllValues().getFirst().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("discards a fact older than what the replica already holds")
    void discardsStaleFact() {
        when(productUoms.findAggregateVersions(PRODUCT_ID)).thenReturn(List.of(9L));

        listener.onCatalogEvent(productEvent("e2", 5L, """
                [{"uomCode":"LB","uomType":"BASE","factorToBase":1,"precisionScale":2}]"""));

        verify(productUoms, never()).deleteByProductId(any());
        verify(productUoms, never()).save(any());
        // Still recorded as processed: the fact was seen, it simply had nothing newer to say.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("ignores fact types this module does not replicate")
    void ignoresOtherFactTypes() {
        listener.onCatalogEvent("""
                {"eventId":"e3","eventType":"catalog.supplier-article-code.updated","payload":{}}""");

        verify(productUoms, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("rethrows a transient database error so the container can retry")
    void rethrowsTransientErrors() {
        when(productUoms.findAggregateVersions(PRODUCT_ID)).thenThrow(new QueryTimeoutException("db busy"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCatalogEvent(productEvent("e4", 1L, """
                        [{"uomCode":"LB","uomType":"BASE","factorToBase":1,"precisionScale":2}]""")));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("drops a malformed payload rather than poisoning the partition")
    void dropsMalformedPayload() {
        listener.onCatalogEvent("""
                {"eventId":"e5","eventType":"catalog.product.updated","payload":{"productId":"not-a-uuid"}}""");

        verify(productUoms, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("skips a product with no unit-of-measure rows, leaving it at the whole-unit default")
    void appliesEmptyConversionSet() {
        listener.onCatalogEvent(productEvent("e6", 3L, "[]"));

        verify(productUoms).deleteByProductId(PRODUCT_ID);
        verify(productUoms, never()).save(any());
        verify(processedEvents).save(any());
    }

    // ── catalog.service.updated → ext_catalog_service (#1569 Phase 1) ──────────────────

    private static final UUID SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static String serviceEvent(String eventId, long version, String extraPayloadFields) {
        return """
                {"eventId":"%s","eventType":"catalog.service.updated","aggregateVersion":%d,
                 "payload":{"serviceId":"%s","name":"Brake Pad Replacement - Front","active":true%s}}""".formatted(eventId, version, SERVICE_ID, extraPayloadFields);
    }

    @Test
    @DisplayName("upserts the service-taxonomy replica from a schema-v2 fact")
    void upsertsServiceReplica() {
        when(catalogServiceReplicas.findById(SERVICE_ID)).thenReturn(java.util.Optional.empty());

        listener.onCatalogEvent(serviceEvent(
                "s1",
                7L,
                ",\"operationCode\":\"BRAKE-PAD-FRONT\",\"operationCategory\":\"REPAIR\","
                        + "\"defaultLaborHours\":1.5"));

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.positivity.workorder.internal.entity.ExtCatalogServiceReplica.class);
        verify(catalogServiceReplicas).save(captor.capture());
        assertThat(captor.getValue().getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(captor.getValue().getOperationCode()).isEqualTo("BRAKE-PAD-FRONT");
        assertThat(captor.getValue().getDefaultLaborHours()).isEqualByComparingTo("1.5");
        assertThat(captor.getValue().getAggregateVersion()).isEqualTo(7L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("a v1 service fact lands with null taxonomy — absent means the catalog stated nothing")
    void v1ServiceFactLandsWithNullTaxonomy() {
        when(catalogServiceReplicas.findById(SERVICE_ID)).thenReturn(java.util.Optional.empty());

        listener.onCatalogEvent(serviceEvent("s2", 3L, ""));

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.positivity.workorder.internal.entity.ExtCatalogServiceReplica.class);
        verify(catalogServiceReplicas).save(captor.capture());
        assertThat(captor.getValue().getOperationCode()).isNull();
        assertThat(captor.getValue().getDefaultLaborHours()).isNull();
    }

    @Test
    @DisplayName("an older service fact does not roll the replica backwards")
    void staleServiceFactIgnored() {
        when(catalogServiceReplicas.findById(SERVICE_ID))
                .thenReturn(java.util.Optional.of(
                        com.positivity.workorder.internal.entity.ExtCatalogServiceReplica.builder()
                                .serviceId(SERVICE_ID)
                                .active(true)
                                .aggregateVersion(9L)
                                .updatedAt(Instant.parse("2026-09-01T00:00:00Z"))
                                .build()));

        listener.onCatalogEvent(serviceEvent("s3", 8L, ""));

        verify(catalogServiceReplicas, never()).save(any());
        // Seen and deliberately not applied — still recorded as processed.
        verify(processedEvents).save(any());
    }
}
