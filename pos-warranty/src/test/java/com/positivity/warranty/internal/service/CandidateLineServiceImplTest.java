package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.dto.CandidateLine;
import com.positivity.warranty.internal.entity.ExtCatalogReplica;
import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import com.positivity.warranty.internal.entity.ExtWorkorderLineReplica;
import com.positivity.warranty.internal.entity.ExtWorkorderReplica;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.warranty.internal.repository.ExtInvoiceLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderReplicaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Candidate origin-line search (PRD §7 step 2): combines invoice and workorder sources read from
 * the event-fed {@code ext_*} replicas (ADR-0044 §6, #924), filters by SKU / product, and degrades
 * gracefully (partial results) when a replica read fails.
 */
@ExtendWith(MockitoExtension.class)
class CandidateLineServiceImplTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000101");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000102");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000103");
    private static final UUID INVOICE_ITEM_ID = UUID.fromString("018f0000-0000-7000-8000-000000000104");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000105");
    private static final UUID PART_LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000106");
    private static final UUID SERVICE_LINE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000107");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000108");
    private static final Instant INVOICE_AT = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant WORKORDER_AT = Instant.parse("2026-01-11T12:00:00Z");

    @Mock
    private ExtInvoiceReplicaRepository extInvoiceReplicaRepository;

    @Mock
    private ExtInvoiceLineReplicaRepository extInvoiceLineReplicaRepository;

    @Mock
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Mock
    private ExtWorkorderLineReplicaRepository extWorkorderLineReplicaRepository;

    @Mock
    private ExtCatalogReplicaRepository extCatalogReplicaRepository;

    private CandidateLineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CandidateLineServiceImpl(
                extInvoiceReplicaRepository,
                extInvoiceLineReplicaRepository,
                extWorkorderReplicaRepository,
                extWorkorderLineReplicaRepository,
                extCatalogReplicaRepository);
    }

    private static ExtInvoiceReplica invoiceHeader() {
        return ExtInvoiceReplica.builder()
                .invoiceId(INVOICE_ID)
                .invoiceNumber("INV-100")
                .partyId(CUSTOMER_ID.toString())
                .status("PAID")
                .invoiceCreatedAt(INVOICE_AT)
                .aggregateVersion(1L)
                .updatedAt(INVOICE_AT)
                .build();
    }

    private static ExtInvoiceLineReplica invoiceLine(String description) {
        return ExtInvoiceLineReplica.builder()
                .invoiceItemId(INVOICE_ITEM_ID)
                .invoiceId(INVOICE_ID)
                .description(description)
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("100.00"))
                .amount(new BigDecimal("200.00"))
                .itemType("PART")
                .build();
    }

    private static ExtWorkorderReplica workorderHeader() {
        return ExtWorkorderReplica.builder()
                .workorderId(WORKORDER_ID)
                .workorderNumber("WO-200")
                .status("COMPLETED")
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .workorderCreatedAt(WORKORDER_AT)
                .aggregateVersion(1L)
                .updatedAt(WORKORDER_AT)
                .build();
    }

    private static ExtWorkorderLineReplica partLine(UUID productId, String description) {
        return ExtWorkorderLineReplica.builder()
                .workorderLineId(PART_LINE_ID)
                .workorderId(WORKORDER_ID)
                .lineKind("PART")
                .productEntityId(productId)
                .description(description)
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("150.00"))
                .lineTotal(new BigDecimal("150.00"))
                .photoEvidenceUrl("https://photos/part.jpg")
                .build();
    }

    private static ExtWorkorderLineReplica serviceLine() {
        return ExtWorkorderLineReplica.builder()
                .workorderLineId(SERVICE_LINE_ID)
                .workorderId(WORKORDER_ID)
                .lineKind("SERVICE")
                .description("Mount and balance")
                .quantity(new BigDecimal("1.5"))
                .unitPrice(new BigDecimal("90.00"))
                .lineTotal(new BigDecimal("135.00"))
                .build();
    }

    @Test
    void combinesInvoiceAndWorkorderSources_whenUnfiltered() {
        when(extInvoiceReplicaRepository.findByPartyIdOrderByInvoiceIdDesc(
                        eq(CUSTOMER_ID.toString()), any(Pageable.class)))
                .thenReturn(List.of(invoiceHeader()));
        when(extInvoiceLineReplicaRepository.findByInvoiceIdIn(any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17")));
        when(extWorkorderReplicaRepository.findByCustomerIdAndVehicleId(CUSTOMER_ID, VEHICLE_ID))
                .thenReturn(List.of(workorderHeader()));
        when(extWorkorderLineReplicaRepository.findByWorkorderId(WORKORDER_ID))
                .thenReturn(List.of(partLine(PRODUCT_ID, "SuperTire 225/45R17"), serviceLine()));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, VEHICLE_ID, null, null);

        assertThat(results).hasSize(3);
        assertThat(results)
                .extracting(CandidateLine::sourceType)
                .containsExactlyInAnyOrder(
                        LineSourceType.INVOICE_LINE, LineSourceType.WORKORDER_PART, LineSourceType.WORKORDER_SERVICE);

        CandidateLine invoice = results.stream()
                .filter(c -> c.sourceType() == LineSourceType.INVOICE_LINE)
                .findFirst()
                .orElseThrow();
        assertThat(invoice.sourceId()).isEqualTo(INVOICE_ID);
        assertThat(invoice.sourceLineId()).isEqualTo(INVOICE_ITEM_ID);
        assertThat(invoice.sourceReference()).isEqualTo("INV-100");
        assertThat(invoice.unitPrice()).isEqualByComparingTo("100.00");
        assertThat(invoice.sourceCreatedAt()).isEqualTo(INVOICE_AT);

        CandidateLine part = results.stream()
                .filter(c -> c.sourceType() == LineSourceType.WORKORDER_PART)
                .findFirst()
                .orElseThrow();
        assertThat(part.sourceId()).isEqualTo(WORKORDER_ID);
        assertThat(part.sourceLineId()).isEqualTo(PART_LINE_ID);
        assertThat(part.sourceReference()).isEqualTo("WO-200");
        assertThat(part.productEntityId()).isEqualTo(PRODUCT_ID);
        assertThat(part.photoEvidenceUrl()).isEqualTo("https://photos/part.jpg");

        CandidateLine labor = results.stream()
                .filter(c -> c.sourceType() == LineSourceType.WORKORDER_SERVICE)
                .findFirst()
                .orElseThrow();
        assertThat(labor.sourceLineId()).isEqualTo(SERVICE_LINE_ID);
        assertThat(labor.quantity()).isEqualByComparingTo("1.5");
        assertThat(labor.unitPrice()).isEqualByComparingTo("90.00");
    }

    @Test
    void productFilter_matchesPartsByIdAndInvoiceLinesByCatalogTokens() {
        when(extCatalogReplicaRepository.findById(PRODUCT_ID))
                .thenReturn(java.util.Optional.of(ExtCatalogReplica.builder()
                        .productId(PRODUCT_ID)
                        .sku("TIRE-1")
                        .name("SuperTire")
                        .category("Tires")
                        .active(true)
                        .aggregateVersion(1L)
                        .updatedAt(INVOICE_AT)
                        .build()));
        when(extInvoiceReplicaRepository.findByPartyIdOrderByInvoiceIdDesc(
                        eq(CUSTOMER_ID.toString()), any(Pageable.class)))
                .thenReturn(List.of(invoiceHeader()));
        when(extInvoiceLineReplicaRepository.findByInvoiceIdIn(any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17"), invoiceLine("Oil change")));
        when(extWorkorderReplicaRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(List.of(workorderHeader()));
        when(extWorkorderLineReplicaRepository.findByWorkorderId(WORKORDER_ID))
                .thenReturn(List.of(partLine(PRODUCT_ID, "Brake pad set"), serviceLine()));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, null, null, PRODUCT_ID);

        // invoice line matched by product-name token; part matched by productEntityId even though
        // its description does not match; "Oil change" and "Mount and balance" excluded
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(CandidateLine::sourceType)
                .containsExactlyInAnyOrder(LineSourceType.INVOICE_LINE, LineSourceType.WORKORDER_PART);
        CandidateLine part = results.stream()
                .filter(c -> c.sourceType() == LineSourceType.WORKORDER_PART)
                .findFirst()
                .orElseThrow();
        assertThat(part.sku()).isEqualTo("TIRE-1"); // resolved from catalog
    }

    @Test
    void skuFilter_matchesDescriptionsCaseInsensitively() {
        when(extInvoiceReplicaRepository.findByPartyIdOrderByInvoiceIdDesc(
                        eq(CUSTOMER_ID.toString()), any(Pageable.class)))
                .thenReturn(List.of(invoiceHeader()));
        when(extInvoiceLineReplicaRepository.findByInvoiceIdIn(any()))
                .thenReturn(List.of(invoiceLine("Tire WX-100 all season"), invoiceLine("Wiper blades")));
        when(extWorkorderReplicaRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(List.of());

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, null, "wx-100", null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).description()).contains("WX-100");
    }

    @Test
    void degradesToPartialResults_whenWorkorderReplicaReadFails() {
        when(extInvoiceReplicaRepository.findByPartyIdOrderByInvoiceIdDesc(
                        eq(CUSTOMER_ID.toString()), any(Pageable.class)))
                .thenReturn(List.of(invoiceHeader()));
        when(extInvoiceLineReplicaRepository.findByInvoiceIdIn(any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17")));
        when(extWorkorderReplicaRepository.findByCustomerIdAndVehicleId(CUSTOMER_ID, VEHICLE_ID))
                .thenThrow(new IllegalStateException("replica read failed"));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, VEHICLE_ID, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sourceType()).isEqualTo(LineSourceType.INVOICE_LINE);
    }

    @Test
    void degradesToPartialResults_whenInvoiceReplicaReadFails() {
        when(extInvoiceReplicaRepository.findByPartyIdOrderByInvoiceIdDesc(
                        eq(CUSTOMER_ID.toString()), any(Pageable.class)))
                .thenThrow(new IllegalStateException("replica read failed"));
        when(extWorkorderReplicaRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(List.of(workorderHeader()));
        when(extWorkorderLineReplicaRepository.findByWorkorderId(WORKORDER_ID))
                .thenReturn(List.of(partLine(PRODUCT_ID, "SuperTire"), serviceLine()));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, null, null, null);

        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(CandidateLine::sourceType)
                .containsExactlyInAnyOrder(LineSourceType.WORKORDER_PART, LineSourceType.WORKORDER_SERVICE);
    }
}
