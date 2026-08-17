package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.ExtSupplierArticleCode;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtSupplierArticleCodeRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
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

/**
 * This listener shares {@link ReplicaListenerContractTest}'s consumer contract in behavior, but is
 * tested standalone because its existing-row lookup and stale guard key on a
 * {@code (supplierRef, productId)} pair rather than a single id field, which does not fit that
 * shared harness's single-id-field model.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("catalog.events.v1 → ext_supplier_article_code replica (CAP-320 #1347)")
class SupplierArticleCodeEventsListenerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtSupplierArticleCodeRepository extSupplierArticleCodeRepository;

    private SupplierArticleCodeEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierArticleCodeEventsListener(
                CLOCK, new ObjectMapper(), processedEventRepository, extSupplierArticleCodeRepository);
        when(extSupplierArticleCodeRepository.save(any(ExtSupplierArticleCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static String factEvent(String eventId, long aggregateVersion, String supplierRef, String code) {
        return """
                {"eventId":"%s","eventType":"catalog.supplier-article-code.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-08-17T08:59:00Z",
                 "sourceService":"pos-catalog",
                 "payload":{"vendorProfileId":"%s","supplierRef":"%s","productId":"%s","supplierArticleCode":"%s"}}
                """.formatted(eventId, PRODUCT_ID, aggregateVersion, VENDOR_PROFILE_ID, supplierRef, PRODUCT_ID, code);
    }

    @Test
    void replicatesTheVendorArticleCode() {
        listener.onCatalogEvent(factEvent("e-1", 100L, "michelin-eu", "999908"));

        ArgumentCaptor<ExtSupplierArticleCode> captor = ArgumentCaptor.forClass(ExtSupplierArticleCode.class);
        verify(extSupplierArticleCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getSupplierRef()).isEqualTo("michelin-eu");
        assertThat(captor.getValue().getVendorProfileId()).isEqualTo(VENDOR_PROFILE_ID);
        assertThat(captor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().getSupplierArticleCode()).isEqualTo("999908");
        assertThat(captor.getValue().getAggregateVersion()).isEqualTo(100L);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void skipsAnEventItHasAlreadyProcessed() {
        when(processedEventRepository.existsById("e-2")).thenReturn(true);

        listener.onCatalogEvent(factEvent("e-2", 101L, "michelin-eu", "999908"));

        verify(extSupplierArticleCodeRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void skipsAFactOlderThanTheReplicaItAlreadyHolds() {
        when(extSupplierArticleCodeRepository.findBySupplierRefAndProductId("michelin-eu", PRODUCT_ID))
                .thenReturn(Optional.of(ExtSupplierArticleCode.builder()
                        .supplierRef("michelin-eu")
                        .vendorProfileId(VENDOR_PROFILE_ID)
                        .productId(PRODUCT_ID)
                        .supplierArticleCode("999908")
                        .aggregateVersion(200L)
                        .build()));

        listener.onCatalogEvent(factEvent("e-3", 100L, "michelin-eu", "000000"));

        verify(extSupplierArticleCodeRepository, never()).save(any());
        // Still recorded as processed: the fact was delivered and deliberately not applied.
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void skipsAnEventTypeThisModuleDoesNotReplicateWithoutRecordingIt() {
        listener.onCatalogEvent("""
                {"eventId":"e-4","eventType":"catalog.product.updated","aggregateVersion":1,"payload":{}}
                """);

        verify(extSupplierArticleCodeRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void ignoresAnEventWithoutAnEventId() {
        listener.onCatalogEvent("""
                {"eventType":"catalog.supplier-article-code.updated","payload":{}}
                """);

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void ignoresAnUnparsableMessage() {
        listener.onCatalogEvent("not json");

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void swallowsAMalformedPayloadButLeavesItUnrecorded() {
        listener.onCatalogEvent(factEvent("e-5", 100L, "", "999908"));

        verify(extSupplierArticleCodeRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void treatsABlankCodeAsMalformedRatherThanPersistingIt() {
        listener.onCatalogEvent(factEvent("e-7", 100L, "michelin-eu", " "));

        verify(extSupplierArticleCodeRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void rethrowsTransientDatabaseErrorsSoTheContainerRetries() {
        when(extSupplierArticleCodeRepository.findBySupplierRefAndProductId("michelin-eu", PRODUCT_ID))
                .thenThrow(new QueryTimeoutException("db busy"));

        assertThatThrownBy(() -> listener.onCatalogEvent(factEvent("e-6", 100L, "michelin-eu", "999908")))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }
}
