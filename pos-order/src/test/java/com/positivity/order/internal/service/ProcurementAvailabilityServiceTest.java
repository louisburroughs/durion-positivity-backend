package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.SupplierStockClient;
import com.positivity.order.internal.dto.purchaseorder.ProcurementAvailabilityResponse;
import com.positivity.order.internal.entity.ExtProductCode;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Live vendor availability for a buyer deciding what to order (CAP-319 #1329).
 *
 * <p>The property that matters most here is one the type system cannot express: "we do not know"
 * and "the vendor has none" must stay distinct from the vendor's answer all the way to the
 * response. A buyer shown zero when the truth is silence will either not order something they could
 * have, or distrust the column entirely — and a column nobody trusts is worse than no column.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcurementAvailabilityService — what the vendor can supply (#1329)")
class ProcurementAvailabilityServiceTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID VENDOR_PROFILE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID DELIVERY_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a03");
    private static final UUID SKU_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a04");
    private static final UUID SKU_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a05");
    private static final String EAN_A = "5012345678900";
    private static final String EAN_B = "5012345678917";
    private static final Instant AS_OF = Instant.parse("2026-08-16T10:00:00Z");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private ExtProductCodeRepository extProductCodeRepository;

    @Mock
    private SupplierStockClient supplierStockClient;

    private ProcurementAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new ProcurementAvailabilityService(
                purchaseOrderRepository, extProductCodeRepository, supplierStockClient);
        code(SKU_A, EAN_A);
        code(SKU_B, EAN_B);
    }

    private void code(UUID skuId, String ean) {
        when(extProductCodeRepository.findById(skuId))
                .thenReturn(Optional.of(ExtProductCode.builder()
                        .productId(skuId)
                        .codeType("EAN")
                        .code(ean)
                        .aggregateVersion(1L)
                        .build()));
    }

    private static PurchaseOrderLineEntity line(int number, UUID skuId, String openQuantity) {
        return PurchaseOrderLineEntity.builder()
                .lineId(UUID.randomUUID())
                .lineNumber(number)
                .skuId(skuId)
                .description("line " + number)
                .quantityDecimal(new BigDecimal(openQuantity))
                .openQuantityDecimal(new BigDecimal(openQuantity))
                .unitCostMinor(100L)
                .lineTotalMinor(100L)
                .build();
    }

    private void order(PurchaseOrderLineEntity... lines) {
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .lines(new ArrayList<>(List.of(lines)))
                .build();
        for (PurchaseOrderLineEntity line : lines) {
            line.setPurchaseOrder(po);
        }
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));
    }

    private void answers(SupplierStockClient.Status status, SupplierStockClient.AnswerLine... lines) {
        when(supplierStockClient.inquire(any(), any(), any()))
                .thenReturn(new SupplierStockClient.StockAnswer(status, List.of(lines), AS_OF));
    }

    @Test
    @DisplayName("a vendor stating none is zero; a vendor stating nothing is null")
    void unknownIsNotZero() {
        order(line(1, SKU_A, "5"), line(2, SKU_B, "3"));
        answers(
                SupplierStockClient.Status.OK,
                new SupplierStockClient.AnswerLine(EAN_A, null, SupplierStockClient.LineStatus.UNAVAILABLE, 0, null),
                new SupplierStockClient.AnswerLine(
                        EAN_B, null, SupplierStockClient.LineStatus.NOT_ANSWERED, null, null));

        ProcurementAvailabilityResponse response = service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        // The vendor said it has none of A. That is a fact a buyer can act on.
        assertThat(response.lines().get(0).availableQuantity()).isZero();
        assertThat(response.lines().get(0).status()).isEqualTo("UNAVAILABLE");
        // The vendor said nothing about B. Showing zero here would be inventing a statement it
        // never made, and the buyer would skip an article the vendor may well have.
        assertThat(response.lines().get(1).availableQuantity()).isNull();
        assertThat(response.lines().get(1).status()).isEqualTo("NOT_ANSWERED");
    }

    @ParameterizedTest
    @EnumSource(
            value = SupplierStockClient.Status.class,
            names = {"SUPPLIER_UNAVAILABLE", "NOT_LISTED", "CAPABILITY_NOT_CONFIGURED", "CONFIGURATION_ERROR"})
    @DisplayName("every degraded outcome answers without quantities rather than failing")
    void degradedOutcomesDoNotFailTheRequest(SupplierStockClient.Status status) {
        order(line(1, SKU_A, "5"));
        when(supplierStockClient.inquire(any(), any(), any()))
                .thenReturn(new SupplierStockClient.StockAnswer(status, List.of(), null));

        ProcurementAvailabilityResponse response = service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        // The screen renders. The buyer is told we could not ask, which is the truth, rather than
        // being shown an error page or a column of confident zeroes.
        assertThat(response.status()).isEqualTo(status.name());
        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.availableQuantity()).isNull();
            assertThat(line.status()).isEqualTo("NOT_ANSWERED");
        });
        assertThat(response.asOf()).isNull();
    }

    @Test
    @DisplayName("a ten-line order makes one vendor round trip, not ten")
    void linesAreBatchedIntoOneInquiry() {
        PurchaseOrderLineEntity[] lines = new PurchaseOrderLineEntity[10];
        for (int i = 0; i < 10; i++) {
            UUID sku = UUID.randomUUID();
            code(sku, "50123456789" + (10 + i));
            lines[i] = line(i + 1, sku, "2");
        }
        order(lines);
        answers(SupplierStockClient.Status.OK);

        service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        ArgumentCaptor<List<SupplierStockClient.InquiryLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(supplierStockClient, times(1)).inquire(eq(VENDOR_PROFILE), eq(DELIVERY_SITE), captor.capture());
        // One call carrying ten articles. Ten calls would multiply the vendor's latency by ten and
        // invite a rate limit that degrades the whole screen instead of one row.
        assertThat(captor.getValue()).hasSize(10);
    }

    @Test
    @DisplayName("two lines for the same article are asked about once, for the combined quantity")
    void repeatedArticlesAreCombined() {
        order(line(1, SKU_A, "4"), line(2, SKU_A, "6"));
        answers(
                SupplierStockClient.Status.OK,
                new SupplierStockClient.AnswerLine(EAN_A, null, SupplierStockClient.LineStatus.AVAILABLE, 7, null));

        ProcurementAvailabilityResponse response = service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        ArgumentCaptor<List<SupplierStockClient.InquiryLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(supplierStockClient).inquire(any(), any(), captor.capture());
        // The vendor is asked whether it can cover all ten, not twice about the same article.
        assertThat(captor.getValue()).singleElement().satisfies(line -> {
            assertThat(line.articleEan()).isEqualTo(EAN_A);
            assertThat(line.requestedQuantity()).isEqualTo(10);
        });
        // Both lines carry the answer, because both are about that article.
        assertThat(response.lines())
                .allSatisfy(line -> assertThat(line.availableQuantity()).isEqualTo(7));
    }

    @Test
    @DisplayName("a line with no article code is reported as such, not as unanswered")
    void unidentifiableLineIsDistinctFromUnanswered() {
        order(line(1, SKU_A, "5"), line(2, SKU_B, "3"));
        when(extProductCodeRepository.findById(SKU_B)).thenReturn(Optional.empty());
        answers(
                SupplierStockClient.Status.OK,
                new SupplierStockClient.AnswerLine(EAN_A, null, SupplierStockClient.LineStatus.AVAILABLE, 5, null));

        ProcurementAvailabilityResponse response = service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        // NOT_ANSWERED would say the vendor declined to comment. It never had the chance — we had
        // no name for the article to ask about, which is our data gap and ours to fix.
        assertThat(response.lines().get(1).status()).isEqualTo("ARTICLE_NOT_IDENTIFIABLE");
        assertThat(response.lines().get(1).articleEan()).isNull();

        ArgumentCaptor<List<SupplierStockClient.InquiryLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(supplierStockClient).inquire(any(), any(), captor.capture());
        assertThat(captor.getValue()).singleElement();
    }

    @Test
    @DisplayName("the quantity asked about is what is still outstanding")
    void asksAboutTheOutstandingQuantity() {
        PurchaseOrderLineEntity partlyDelivered = line(1, SKU_A, "10");
        partlyDelivered.setQuantityDecimal(new BigDecimal("10"));
        partlyDelivered.setOpenQuantityDecimal(new BigDecimal("4"));
        order(partlyDelivered);
        answers(SupplierStockClient.Status.OK);

        service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        ArgumentCaptor<List<SupplierStockClient.InquiryLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(supplierStockClient).inquire(any(), any(), captor.capture());
        // Six already arrived. Asking about ten would have the buyer chasing stock they have.
        assertThat(captor.getValue().getFirst().requestedQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("an order with nothing identifiable asks the vendor nothing at all")
    void nothingIdentifiableMakesNoCall() {
        order(line(1, SKU_A, "5"));
        when(extProductCodeRepository.findById(SKU_A)).thenReturn(Optional.empty());
        when(supplierStockClient.inquire(any(), any(), any()))
                .thenReturn(new SupplierStockClient.StockAnswer(SupplierStockClient.Status.OK, List.of(), AS_OF));

        ProcurementAvailabilityResponse response = service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        assertThat(response.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.status()).isEqualTo("ARTICLE_NOT_IDENTIFIABLE"));
    }

    @Test
    @DisplayName("the delivery location asked about is the one supplied, never the order's own")
    void deliveryLocationIsNotDefaultedFromTheOrder() {
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .shipToLocationId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a09"))
                .lines(new ArrayList<>(List.of(line(1, SKU_A, "5"))))
                .build();
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));
        answers(SupplierStockClient.Status.OK);

        service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE);

        // Availability is consignee-specific. Substituting the order's ship-to would answer
        // confidently about a different warehouse than the buyer asked about.
        verify(supplierStockClient).inquire(eq(VENDOR_PROFILE), eq(DELIVERY_SITE), any());
    }

    @Test
    @DisplayName("an unknown order is a not-found, and the vendor is never asked")
    void unknownOrderIsNotFound() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.availabilityFor(PO_ID, VENDOR_PROFILE, DELIVERY_SITE))
                .isInstanceOf(PurchaseOrderNotFoundException.class);
        verify(supplierStockClient, never()).inquire(any(), any(), any());
    }
}
