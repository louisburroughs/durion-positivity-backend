package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Issue #1480 — receiving resolves its source document from the projected purchase order, so
 * sessions work without a stub and without a synchronous call to pos-order that ADR-0044 forbids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SourceDocumentResolver — receiving reads the projected purchase order (#1480)")
class SourceDocumentResolverTest {

    private static final UUID PO_ID = UUID.fromString("01a02fd3-b675-7000-8000-000000000001");
    private static final UUID LINE_ID = UUID.fromString("01a02fd3-b675-7000-8000-000000000002");
    private static final UUID SKU_ID = UUID.fromString("01a02fd3-b675-7000-8000-000000000003");

    @Mock
    private ExtPurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private ExtPurchaseOrderLineRepository purchaseOrderLineRepository;

    @InjectMocks
    private SourceDocumentResolver resolver;

    @Test
    @DisplayName("an approved order yields its open lines with SKUs and quantities")
    void approvedOrderYieldsItsOpenLines() {
        projectOrder("APPROVED", line(LINE_ID, SKU_ID, 1, "12", "12"));

        SourceDocumentResolver.SourceDocument document = resolve();

        assertThat(document.lines()).hasSize(1);
        assertThat(document.lines().getFirst().getProductId()).isEqualTo(SKU_ID.toString());
        assertThat(document.lines().getFirst().getSourceLineId()).isEqualTo(LINE_ID.toString());
        assertThat(document.lines().getFirst().getExpectedQuantity()).isEqualByComparingTo("12");
    }

    /** A partially received order must expect only what is still open, never the ordered figure. */
    @Test
    @DisplayName("a partially received order expects only its open quantity")
    void partiallyReceivedOrderExpectsOnlyWhatIsOpen() {
        projectOrder("PARTIALLY_RECEIVED", line(LINE_ID, SKU_ID, 1, "12", "4"));

        assertThat(resolve().lines().getFirst().getExpectedQuantity()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("lines are ordered by their line number, as the vendor sees them")
    void linesKeepTheirDocumentOrder() {
        UUID secondLine = UUID.fromString("01a02fd3-b675-7000-8000-000000000004");
        projectOrder("APPROVED", line(secondLine, SKU_ID, 2, "1", "1"), line(LINE_ID, SKU_ID, 1, "1", "1"));

        assertThat(resolve().lines())
                .extracting(SourceDocumentResolver.SourceDocumentLine::getSourceLineId)
                .containsExactly(LINE_ID.toString(), secondLine.toString());
    }

    @Test
    @DisplayName("an order whose lines are all closed reads as already received, not as an empty session")
    void fullyReceivedLinesReadAsAlreadyReceived() {
        projectOrder("PARTIALLY_RECEIVED", line(LINE_ID, SKU_ID, 1, "12", "0"));

        assertThatThrownBy(this::resolve).isInstanceOf(SourceDocumentAlreadyReceivedException.class);
    }

    @Test
    @DisplayName("a received order status reads as already received")
    void receivedStatusReadsAsAlreadyReceived() {
        projectOrder("FULLY_RECEIVED", line(LINE_ID, SKU_ID, 1, "12", "12"));

        assertThatThrownBy(this::resolve).isInstanceOf(SourceDocumentAlreadyReceivedException.class);
    }

    /** Nothing has been committed to on a draft, and a cancelled order never will be. */
    @Test
    @DisplayName("a draft or cancelled order is refused as an invalid PO reference")
    void unreceivableStatusesAreRefused() {
        projectOrder("DRAFT", line(LINE_ID, SKU_ID, 1, "12", "12"));
        assertThatThrownBy(this::resolve).isInstanceOf(InvalidPoReferenceException.class);

        projectOrder("CANCELLED", line(LINE_ID, SKU_ID, 1, "12", "12"));
        assertThatThrownBy(this::resolve).isInstanceOf(InvalidPoReferenceException.class);
    }

    @Test
    @DisplayName("an order the projection does not hold reports lines unavailable rather than NOT_FOUND (#1492)")
    void unprojectedOrderReportsLinesUnavailable() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::resolve)
                .isInstanceOf(SourceDocumentLinesUnavailableException.class)
                .hasMessageContaining(PO_ID.toString());
    }

    @Test
    @DisplayName("a source document id that is not a PO identifier is not found")
    void nonUuidSourceDocumentIsNotFound() {
        assertThatThrownBy(() -> resolver.resolve(SourceDocumentType.PO, "PO-123"))
                .isInstanceOf(SourceDocumentNotFoundException.class);
    }

    @Test
    @DisplayName("ASN is refused explicitly rather than resolved against a service that does not exist")
    void asnIsRefusedExplicitly() {
        assertThatThrownBy(() -> resolver.resolve(SourceDocumentType.ASN, "ASN-1"))
                .isInstanceOf(UnsupportedSourceDocumentTypeException.class)
                .hasMessageContaining("ASN");
    }

    /**
     * #2009 — receive-into-staging asks where its stock lands, and the order's ship-to answers it,
     * so the caller no longer has to send X-Site-Id.
     */
    @Test
    @DisplayName("the order's ship-to location is the site a session receives at (#2009)")
    void shipToLocationIsTheSessionsSite() {
        UUID shipTo = UUID.fromString("01a02fd3-b675-7000-8000-000000000005");
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(ExtPurchaseOrderReplica.builder()
                        .purchaseOrderId(PO_ID)
                        .poNumber("PO-2026-00042")
                        .vendorId(UUID.fromString("01a02fd3-b675-7000-8000-00000000000f"))
                        .status("APPROVED")
                        .shipToLocationId(shipTo)
                        .build()));

        assertThat(resolver.resolveShipToLocationId(SourceDocumentType.PO, PO_ID.toString()))
                .contains(shipTo);
    }

    /**
     * Unanswerable is empty, not an exception: the caller has a fallback, and a session whose site
     * cannot be established must still be able to receive.
     */
    @Test
    @DisplayName("an unprojected order, a non-PO type and a non-UUID id all resolve to no ship-to")
    void unanswerableShipToIsEmpty() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolveShipToLocationId(SourceDocumentType.PO, PO_ID.toString()))
                .isEmpty();
        assertThat(resolver.resolveShipToLocationId(SourceDocumentType.PO, "PO-123"))
                .isEmpty();
        assertThat(resolver.resolveShipToLocationId(SourceDocumentType.ASN, PO_ID.toString()))
                .isEmpty();
        assertThat(resolver.resolveShipToLocationId(null, PO_ID.toString())).isEmpty();
    }

    private SourceDocumentResolver.SourceDocument resolve() {
        return resolver.resolve(SourceDocumentType.PO, PO_ID.toString());
    }

    // ─── #2203: receipt document cost from the purchase order line ──────────────

    @Test
    @DisplayName("receipt cost: the linked order line's price per base unit, in the order's currency")
    void receiptUnitCost_linkedLine_pricesPerBaseUnit() {
        projectOrder("APPROVED");
        when(purchaseOrderLineRepository.findById(LINE_ID))
                .thenReturn(Optional.of(pricedLine(LINE_ID, 12_000L, new BigDecimal("12"))));

        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.PO, PO_ID.toString(), LINE_ID, SKU_ID.toString()))
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("10.0000"));
    }

    @Test
    @DisplayName("receipt cost: an unlinked receiving line falls back to the order's only line for the product")
    void receiptUnitCost_unlinked_usesSoleLineForProduct() {
        projectOrder("APPROVED", pricedLine(LINE_ID, 1_250L, BigDecimal.ONE));

        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.PO, PO_ID.toString(), null, SKU_ID.toString()))
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("12.5"));
    }

    @Test
    @DisplayName("receipt cost: unknown when the unlinked product matches several order lines")
    void receiptUnitCost_unlinkedAmbiguous_isEmpty() {
        UUID otherLine = UUID.fromString("01a02fd3-b675-7000-8000-000000000005");
        projectOrder(
                "APPROVED", pricedLine(LINE_ID, 1_000L, BigDecimal.ONE), pricedLine(otherLine, 2_000L, BigDecimal.ONE));

        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.PO, PO_ID.toString(), null, SKU_ID.toString()))
                .isEmpty();
    }

    @Test
    @DisplayName("receipt cost: unknown for a line projected before its conversion factor was published")
    void receiptUnitCost_noConversionFactor_isEmpty() {
        projectOrder("APPROVED");
        when(purchaseOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.of(pricedLine(LINE_ID, 12_000L, null)));

        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.PO, PO_ID.toString(), LINE_ID, SKU_ID.toString()))
                .isEmpty();
    }

    @Test
    @DisplayName("receipt cost: unknown for an ASN or a non-UUID document id")
    void receiptUnitCost_notAPurchaseOrder_isEmpty() {
        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.ASN, "ASN-1", LINE_ID, SKU_ID.toString()))
                .isEmpty();
        assertThat(resolver.resolveReceiptUnitCost(SourceDocumentType.PO, "PO-FREE-TEXT", LINE_ID, SKU_ID.toString()))
                .isEmpty();
    }

    private static ExtPurchaseOrderLineReplica pricedLine(UUID lineId, Long unitCostMinor, BigDecimal factor) {
        ExtPurchaseOrderLineReplica line = line(lineId, SKU_ID, 1, "12", "12");
        line.setUnitCostMinor(unitCostMinor);
        line.setConversionFactor(factor);
        return line;
    }

    private void projectOrder(String status, ExtPurchaseOrderLineReplica... lines) {
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(ExtPurchaseOrderReplica.builder()
                        .purchaseOrderId(PO_ID)
                        .poNumber("PO-2026-00042")
                        .vendorId(UUID.fromString("01a02fd3-b675-7000-8000-00000000000f"))
                        .status(status)
                        .currency("USD")
                        .build()));
        when(purchaseOrderLineRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(lines));
    }

    private static ExtPurchaseOrderLineReplica line(
            UUID lineId, UUID skuId, int lineNumber, String ordered, String open) {
        return ExtPurchaseOrderLineReplica.builder()
                .lineId(lineId)
                .purchaseOrderId(PO_ID)
                .lineNumber(lineNumber)
                .skuId(skuId)
                .orderedQuantity(new BigDecimal(ordered))
                .openQuantity(new BigDecimal(open))
                .build();
    }
}
