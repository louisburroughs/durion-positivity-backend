package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.client.CatalogClient;
import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.dto.CandidateLine;
import com.positivity.warranty.internal.enums.LineSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Candidate origin-line search (PRD §7 step 2): combines invoice and workorder sources, filters
 * by SKU / product, and degrades gracefully (partial results) when a callee is down.
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
    private InvoiceClient invoiceClient;

    @Mock
    private WorkorderClient workorderClient;

    @Mock
    private CatalogClient catalogClient;

    private CandidateLineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CandidateLineServiceImpl(invoiceClient, workorderClient, catalogClient);
    }

    private static InvoiceClient.InvoiceLine invoiceLine(String description) {
        return new InvoiceClient.InvoiceLine(
                INVOICE_ID,
                "INV-100",
                INVOICE_ITEM_ID,
                description,
                new BigDecimal("2"),
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                null,
                "PART",
                "PAID",
                INVOICE_AT);
    }

    private static WorkorderClient.WorkorderSummary workorderSummary() {
        return new WorkorderClient.WorkorderSummary(
                WORKORDER_ID, "WO-200", "COMPLETED", CUSTOMER_ID, VEHICLE_ID, "1FTFW1ET5DFC12345", WORKORDER_AT);
    }

    private static WorkorderClient.WorkorderDetail workorderDetail(UUID partProductId, String partDescription) {
        return new WorkorderClient.WorkorderDetail(
                WORKORDER_ID,
                "WO-200",
                CUSTOMER_ID,
                VEHICLE_ID,
                List.of(new WorkorderClient.ServiceLine(
                        SERVICE_LINE_ID,
                        "Mount and balance",
                        new BigDecimal("1.5"),
                        new BigDecimal("90.00"),
                        new BigDecimal("135.00"))),
                List.of(new WorkorderClient.PartLine(
                        PART_LINE_ID,
                        partProductId,
                        partDescription,
                        BigDecimal.ONE,
                        new BigDecimal("150.00"),
                        new BigDecimal("150.00"),
                        "https://photos/part.jpg")));
    }

    @Test
    void combinesInvoiceAndWorkorderSources_whenUnfiltered() {
        when(invoiceClient.searchInvoiceLines(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17")));
        when(workorderClient.searchWorkorders(CUSTOMER_ID, VEHICLE_ID)).thenReturn(List.of(workorderSummary()));
        when(workorderClient.getWorkorderDetail(WORKORDER_ID))
                .thenReturn(Optional.of(workorderDetail(PRODUCT_ID, "SuperTire 225/45R17")));

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
        when(catalogClient.getProduct(PRODUCT_ID))
                .thenReturn(Optional.of(new CatalogClient.ProductInfo(
                        PRODUCT_ID, "TIRE-1", "SuperTire", null, null, null, null, "Tires", null, null)));
        when(invoiceClient.searchInvoiceLines(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17"), invoiceLine("Oil change")));
        when(workorderClient.searchWorkorders(CUSTOMER_ID, null)).thenReturn(List.of(workorderSummary()));
        when(workorderClient.getWorkorderDetail(WORKORDER_ID))
                .thenReturn(Optional.of(workorderDetail(PRODUCT_ID, "Brake pad set")));

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
        when(invoiceClient.searchInvoiceLines(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(invoiceLine("Tire WX-100 all season"), invoiceLine("Wiper blades")));
        when(workorderClient.searchWorkorders(CUSTOMER_ID, null)).thenReturn(List.of());

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, null, "wx-100", null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).description()).contains("WX-100");
    }

    @Test
    void degradesToPartialResults_whenWorkorderServiceIsDown() {
        when(invoiceClient.searchInvoiceLines(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(invoiceLine("SuperTire 225/45R17")));
        when(workorderClient.searchWorkorders(CUSTOMER_ID, VEHICLE_ID))
                .thenThrow(new IllegalStateException("workorder service down"));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, VEHICLE_ID, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sourceType()).isEqualTo(LineSourceType.INVOICE_LINE);
    }

    @Test
    void degradesToPartialResults_whenInvoiceServiceIsDown() {
        when(invoiceClient.searchInvoiceLines(eq(CUSTOMER_ID), any()))
                .thenThrow(new IllegalStateException("invoice down"));
        when(workorderClient.searchWorkorders(CUSTOMER_ID, null)).thenReturn(List.of(workorderSummary()));
        when(workorderClient.getWorkorderDetail(any(UUID.class)))
                .thenReturn(Optional.of(workorderDetail(PRODUCT_ID, "SuperTire")));

        List<CandidateLine> results = service.findCandidateLines(CUSTOMER_ID, null, null, null);

        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(CandidateLine::sourceType)
                .containsExactlyInAnyOrder(LineSourceType.WORKORDER_PART, LineSourceType.WORKORDER_SERVICE);
    }
}
