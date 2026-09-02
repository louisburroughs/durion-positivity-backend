package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("catalog.events.v1 → ext_product_code replica (#1224, ADR-0044 R3)")
class CatalogProductEventsListenerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtProductCodeReplicaRepository replicaRepository;

    private CatalogProductEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogProductEventsListener(
                CLOCK, new ObjectMapper(), processedEventRepository, replicaRepository);
        when(replicaRepository.save(any(ExtProductCodeReplica.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static String productEvent(String eventId, long aggregateVersion, String code, String codeType) {
        return """
                {"eventId":"%s","eventType":"catalog.product.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-08-14T08:59:00Z",
                 "sourceService":"pos-catalog",
                 "payload":{"productId":"%s","sku":"SKU-1","name":"Tire","active":true,
                   "productCode":%s,"productCodeType":%s}}
                """.formatted(
                        eventId,
                        PRODUCT_ID,
                        aggregateVersion,
                        PRODUCT_ID,
                        code == null ? "null" : "\"" + code + "\"",
                        codeType == null ? "null" : "\"" + codeType + "\"");
    }

    @Test
    void replicatesTheProductCode() {
        listener.onCatalogEvent(productEvent("e-1", 100L, "3528709999083", "EAN"));

        ArgumentCaptor<ExtProductCodeReplica> captor = ArgumentCaptor.forClass(ExtProductCodeReplica.class);
        verify(replicaRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().getCode()).isEqualTo("3528709999083");
        assertThat(captor.getValue().getCodeType()).isEqualTo("EAN");
        assertThat(captor.getValue().getAggregateVersion()).isEqualTo(100L);
        // The SKU rides the same fact (#1637 decision 1): the availability read resolves a
        // caller's SKU from this replica rather than asking pos-catalog synchronously.
        assertThat(captor.getValue().getSku()).isEqualTo("SKU-1");
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void canonicalisesAMixedCaseSkuToUppercaseOnWrite() {
        // pos-catalog keeps SKUs mixed-case but unique case-insensitively; the replica stores the
        // canonical uppercase form so the availability lookup can match case-insensitively over a
        // plain index (#1637 review fix).
        listener.onCatalogEvent("""
                {"eventId":"e-sku-case","eventType":"catalog.product.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":100,"occurredAtUtc":"2026-08-14T08:59:00Z",
                 "sourceService":"pos-catalog",
                 "payload":{"productId":"%s","sku":"  AbC-123 ","name":"Tire","active":true,
                   "productCode":"3528709999083","productCodeType":"EAN"}}
                """.formatted(PRODUCT_ID, PRODUCT_ID));

        ArgumentCaptor<ExtProductCodeReplica> captor = ArgumentCaptor.forClass(ExtProductCodeReplica.class);
        verify(replicaRepository).save(captor.capture());
        assertThat(captor.getValue().getSku()).isEqualTo("ABC-123");
    }

    @Test
    void keepsTheRowWithANullCodeWhenAProductsCodeIsCleared() {
        listener.onCatalogEvent(productEvent("e-2", 101L, null, null));

        ArgumentCaptor<ExtProductCodeReplica> captor = ArgumentCaptor.forClass(ExtProductCodeReplica.class);
        verify(replicaRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isNull();
        assertThat(captor.getValue().getCodeType()).isNull();
    }

    @Test
    void skipsAnEventItHasAlreadyProcessed() {
        when(processedEventRepository.existsById("e-3")).thenReturn(true);

        listener.onCatalogEvent(productEvent("e-3", 102L, "3528709999083", "EAN"));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void skipsAFactOlderThanTheReplicaItAlreadyHolds() {
        when(replicaRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(ExtProductCodeReplica.builder()
                        .productId(PRODUCT_ID)
                        .code("3528709999083")
                        .codeType("EAN")
                        .aggregateVersion(200L)
                        .build()));

        listener.onCatalogEvent(productEvent("e-4", 100L, "0000000000000", "EAN"));

        verify(replicaRepository, never()).save(any());
        // Still recorded as processed: the fact was delivered and deliberately not applied.
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void recordsUnsupportedEventTypesSoTheOwnersManifestReconciles() {
        listener.onCatalogEvent("""
                {"eventId":"e-5","eventType":"catalog.category.updated","aggregateVersion":1,"payload":{}}
                """);

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void ignoresAnEventWithoutAnEventId() {
        listener.onCatalogEvent("""
                {"eventType":"catalog.product.updated","payload":{}}
                """);

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void ignoresAnUnparsableMessage() {
        listener.onCatalogEvent("not json");

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void rethrowsTransientDatabaseErrorsSoTheContainerRetries() {
        when(replicaRepository.findById(PRODUCT_ID)).thenThrow(new QueryTimeoutException("db busy"));

        assertThatThrownBy(() -> listener.onCatalogEvent(productEvent("e-6", 100L, "3528709999083", "EAN")))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }
}
