package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtProductCodeReplica;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductSubstitutionReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductSubstitutionReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
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
 * Consumer contract of the catalog.events.v1 listener (odoo-parity B1, #1033): manual envelope
 * parse, processed_events dedupe, strictly-lower-only stale guard (equal versions APPLY —
 * catalog fans out same-millisecond facts), wholesale child replacement, v2 null tolerance,
 * transient DB errors rethrown for container retry/DLQ.
 */
class CatalogEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000c01");
    private static final UUID GROUP_ID = UUID.fromString("018f0000-0000-7000-8000-000000000c02");
    private static final UUID MEMBER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000c03");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtProductReplicaRepository extProduct = mock(ExtProductReplicaRepository.class);
    private final ExtProductCodeReplicaRepository extProductCode = mock(ExtProductCodeReplicaRepository.class);
    private final ExtProductUomReplicaRepository extProductUom = mock(ExtProductUomReplicaRepository.class);
    private final ExtProductSubstitutionReplicaRepository extProductSubstitution =
            mock(ExtProductSubstitutionReplicaRepository.class);

    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                extProduct,
                extProductCode,
                extProductUom,
                extProductSubstitution,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    /** Full schema-v2 fact: base UoM, tracking level, conversion set, substitution group. */
    private static String v2Event(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"catalog.product.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"productId":"%s","sku":"OIL-5W30","name":"Engine Oil 5W30","active":true,
                            "updatedAt":"2026-07-20T10:00:00Z",
                            "baseUom":"EA","trackingLevel":"LOT",
                            "uomConversions":[
                              {"uomCode":"EA","uomType":"BASE","factorToBase":1,"precisionScale":0},
                              {"uomCode":"CASE","uomType":"PURCHASE","factorToBase":12,"precisionScale":2}],
                            "substitutionGroupId":"%s","substitutionProductIds":["%s","%s"],
                            "productCode":"4012345678901","productCodeType":"EAN"}}
                """.formatted(eventId, PRODUCT_ID, aggregateVersion, PRODUCT_ID, GROUP_ID, PRODUCT_ID, MEMBER_ID);
    }

    /** Pre-v2 fact: none of the v2 fields present. */
    private static String v1Event(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"catalog.product.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"productId":"%s","sku":"OIL-5W30","name":"Engine Oil 5W30","active":true,
                            "updatedAt":"2026-07-20T10:00:00Z"}}
                """.formatted(eventId, PRODUCT_ID, aggregateVersion, PRODUCT_ID);
    }

    private ExtProductReplica existingReplica(long aggregateVersion) {
        return ExtProductReplica.builder()
                .productId(PRODUCT_ID)
                .baseUom("EA")
                .trackingLevel("NONE")
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.parse("2026-07-19T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("Creates the ext_product replica with UoM and substitution children from a v2 fact")
    void createsReplicaWithChildren() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        listener.onCatalogEvent(v2Event("e-1", 100));

        ArgumentCaptor<ExtProductReplica> parent = ArgumentCaptor.captor();
        verify(extProduct).save(parent.capture());
        assertThat(parent.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(parent.getValue().getBaseUom()).isEqualTo("EA");
        assertThat(parent.getValue().getTrackingLevel()).isEqualTo("LOT");
        assertThat(parent.getValue().getSubstitutionGroupId()).isEqualTo(GROUP_ID);
        assertThat(parent.getValue().getAggregateVersion()).isEqualTo(100L);

        verify(extProductUom).deleteByProductId(PRODUCT_ID);
        ArgumentCaptor<ExtProductUomReplica> uomRows = ArgumentCaptor.captor();
        verify(extProductUom, times(2)).save(uomRows.capture());
        assertThat(uomRows.getAllValues())
                .extracting(
                        ExtProductUomReplica::getUomCode,
                        ExtProductUomReplica::getUomType,
                        r -> r.getFactorToBase().stripTrailingZeros(),
                        ExtProductUomReplica::getPrecisionScale)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("EA", "BASE", BigDecimal.ONE, 0),
                        org.assertj.core.groups.Tuple.tuple("CASE", "PURCHASE", new BigDecimal("12"), 2));

        verify(extProductSubstitution).deleteByProductId(PRODUCT_ID);
        ArgumentCaptor<ExtProductSubstitutionReplica> members = ArgumentCaptor.captor();
        verify(extProductSubstitution, times(2)).save(members.capture());
        assertThat(members.getAllValues())
                .extracting(ExtProductSubstitutionReplica::getMemberProductId)
                .containsExactly(PRODUCT_ID, MEMBER_ID);

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getOwner()).isEqualTo(CatalogEventsListener.OWNER);
    }

    @Test
    @DisplayName("Newer fact replaces the replica and its child sets wholesale")
    void updateReplacesChildrenWholesale() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.of(existingReplica(50)));

        listener.onCatalogEvent(v2Event("e-2", 100));

        verify(extProduct).save(any(ExtProductReplica.class));
        verify(extProductUom).deleteByProductId(PRODUCT_ID);
        verify(extProductUom, times(2)).save(any(ExtProductUomReplica.class));
        verify(extProductSubstitution).deleteByProductId(PRODUCT_ID);
        verify(extProductSubstitution, times(2)).save(any(ExtProductSubstitutionReplica.class));
    }

    @Test
    @DisplayName("Drops a strictly-lower fact but still records it processed")
    void dropsStaleFact() {
        when(processedEvents.existsById("e-stale")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.of(existingReplica(200)));

        listener.onCatalogEvent(v2Event("e-stale", 100));

        verify(extProduct, never()).save(any());
        verify(extProductUom, never()).deleteByProductId(any());
        verify(extProductSubstitution, never()).deleteByProductId(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Applies an equal-version fact — catalog fans out same-millisecond facts on membership changes")
    void equalVersionApplies() {
        when(processedEvents.existsById("e-equal")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.of(existingReplica(100)));

        listener.onCatalogEvent(v2Event("e-equal", 100));

        verify(extProduct).save(any(ExtProductReplica.class));
        verify(extProductUom).deleteByProductId(PRODUCT_ID);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Pre-v2 fact: trackingLevel null ⇒ NONE, null child sets ⇒ children cleared")
    void nullToleranceForPreV2Facts() {
        when(processedEvents.existsById("e-v1")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        listener.onCatalogEvent(v1Event("e-v1", 100));

        ArgumentCaptor<ExtProductReplica> parent = ArgumentCaptor.captor();
        verify(extProduct).save(parent.capture());
        assertThat(parent.getValue().getBaseUom()).isNull();
        assertThat(parent.getValue().getTrackingLevel()).isEqualTo("NONE");
        assertThat(parent.getValue().getSubstitutionGroupId()).isNull();

        verify(extProductUom).deleteByProductId(PRODUCT_ID);
        verify(extProductUom, never()).save(any());
        verify(extProductSubstitution).deleteByProductId(PRODUCT_ID);
        verify(extProductSubstitution, never()).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId (idempotent replay)")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onCatalogEvent(v2Event("e-dup", 100));

        verify(extProduct, never()).save(any());
        verify(extProductUom, never()).deleteByProductId(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Ignores other event types but still records them processed for the owner's manifest")
    void ignoresOtherEventTypesButRecordsThem() {
        when(processedEvents.existsById("e-other")).thenReturn(false);

        listener.onCatalogEvent("""
                {"eventId":"e-other","eventType":"catalog.category.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":5,"payload":{}}
                """.formatted(PRODUCT_ID));

        verify(extProduct, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-transient")).thenReturn(false);
        when(extProduct.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        when(extProduct.save(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCatalogEvent(v2Event("e-transient", 100)));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("product identity codes land in the ext_product_code replica")
    void product_codes_are_replicated() {
        listener.onCatalogEvent(v2Event("e-code", 100));

        ArgumentCaptor<ExtProductCodeReplica> saved = ArgumentCaptor.forClass(ExtProductCodeReplica.class);
        verify(extProductCode).save(saved.capture());
        assertThat(saved.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getValue().getCodeType()).isEqualTo("EAN");
        assertThat(saved.getValue().getCode()).isEqualTo("4012345678901");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(100L);
    }

    @Test
    @DisplayName("a product carrying no code keeps its row with a null code")
    void cleared_code_is_kept_as_null() {
        listener.onCatalogEvent(v1Event("e-nocode", 100));

        ArgumentCaptor<ExtProductCodeReplica> saved = ArgumentCaptor.forClass(ExtProductCodeReplica.class);
        verify(extProductCode).save(saved.capture());
        // "This product has no code" and "never replicated here" are different answers to the
        // resolver: it gives up on the first and defers on the second.
        assertThat(saved.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getValue().getCode()).isNull();
        assertThat(saved.getValue().getCodeType()).isNull();
    }
}
