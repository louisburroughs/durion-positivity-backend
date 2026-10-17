package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.repository.ExtSupplierVendorReplicaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

/** The vendor copy writer's refusal handling (CAP:550 S36, #2525; ADR-0072): no row data in what propagates. */
class SupplierVendorReplicaWriterTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-0000000005b2");
    private static final String LEGAL_NAME = "Secret Legal Name Ltd";

    private final ExtSupplierVendorReplicaRepository vendors = mock(ExtSupplierVendorReplicaRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SupplierVendorReplicaWriter writer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        writer = new SupplierVendorReplicaWriter(
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC), objectMapper, vendors, meters);
        when(vendors.findById(VENDOR_ID)).thenReturn(Optional.empty());
    }

    private tools.jackson.databind.JsonNode envelope(String payloadFields) {
        return objectMapper.readTree("""
                {"eventId":"evt-1","eventType":"supplier.vendor.updated","schemaVersion":2,"aggregateVersion":1,
                 "payload":%s}
                """.formatted(payloadFields));
    }

    private tools.jackson.databind.JsonNode validEnvelope() {
        return envelope("""
                {"vendorId":"%s","vendorNumber":"V-000123","legalName":"%s","displayName":"Acme Parts",
                 "taxRegistrations":[],"remitTo":null,"remitToVersion":0,"defaultPaymentTerms":null,
                 "defaultCurrency":null,"status":"ACTIVE","statusChangedAt":null,"statusReason":null,
                 "remitToChangedAt":null,"remitToRequestedBy":null,"remitToApprovedBy":null,"createdBy":"alice",
                 "createdAt":"2026-10-01T00:00:00Z","occurredAt":"2026-10-07T09:00:00Z"}
                """.formatted(VENDOR_ID, LEGAL_NAME));
    }

    @Test
    @DisplayName("a refused row surfaces the constraint name only, never the row's columns")
    void refusal_namesConstraintOnly() {
        when(vendors.saveAndFlush(any(ExtSupplierVendorReplica.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Failing row contains (" + LEGAL_NAME + ")",
                        new org.hibernate.exception.ConstraintViolationException(
                                "refused", new java.sql.SQLException("x"), "ext_supplier_vendor_status_check")));

        assertThatThrownBy(() -> writer.apply(validEnvelope(), "evt-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ext_supplier_vendor_status_check")
                .hasMessageNotContaining(LEGAL_NAME)
                .hasNoCause();
    }

    @Test
    @DisplayName("a malformed payload fails with the failure class only, never the payload")
    void malformedPayload_doesNotQuotePayload() {
        assertThatThrownBy(() -> writer.apply(envelope("{\"vendorId\":\"" + LEGAL_NAME + "\"}"), "evt-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(LEGAL_NAME)
                .hasNoCause();
    }
}
