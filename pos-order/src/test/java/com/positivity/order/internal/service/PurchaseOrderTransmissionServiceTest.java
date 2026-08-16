package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.order.internal.config.OutboxEventWriter;
import com.positivity.order.internal.entity.ExtProductCode;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.exception.PurchaseOrderNotTransmittableException;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Sending a purchase order to its vendor (CAP-320 #1330, ADR-0052).
 *
 * <p>Almost every test here is about a refusal, which is the shape of the problem: pos-supplier
 * will never re-send an order and offers no operation to make it, so the judgement about whether
 * to send at all lives here. The cost of getting it wrong is not an error message — it is a second
 * lorry, or a delivery of the wrong goods, discovered weeks later at a loading bay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderTransmissionService — sending an order to its vendor (#1330)")
class PurchaseOrderTransmissionServiceTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d01");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d02");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d03");
    private static final Instant NOW = Instant.parse("2026-08-16T09:00:00Z");
    private static final String ACTOR = "buyer-1";

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private ExtProductCodeRepository extProductCodeRepository;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<OutboxEventWriter> outboxProvider;

    private PurchaseOrderTransmissionService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderTransmissionService(
                purchaseOrderRepository, extProductCodeRepository, outboxProvider, Clock.fixed(NOW, ZoneOffset.UTC));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(extProductCodeRepository.findById(SKU_ID))
                .thenReturn(Optional.of(ExtProductCode.builder()
                        .productId(SKU_ID)
                        .codeType("EAN")
                        .code("5012345678900")
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    private PurchaseOrderEntity order(PurchaseOrderStatus status, TransmissionState transmissionState) {
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(UUID.randomUUID())
                .lineNumber(1)
                .skuId(SKU_ID)
                .description("Front brake rotor")
                .quantityDecimal(BigDecimal.TEN)
                .openQuantityDecimal(BigDecimal.TEN)
                .unitCostMinor(5_000L)
                .lineTotalMinor(50_000L)
                .build();
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .vendorId(UUID.randomUUID())
                .supplierRef("acme-parts")
                .status(status)
                .transmissionState(transmissionState)
                .transmissionCount(0)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(50_000L)
                .taxMinor(0L)
                .grandTotalMinor(50_000L)
                .openBalanceMinor(50_000L)
                .shipToLocationId(SITE_ID)
                .expectedDeliveryDate(LocalDate.of(2026, 9, 1))
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));
        return po;
    }

    private SupplierOrderRequestedV1 published() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("supplier.commands.v1"), captor.capture());
        return (SupplierOrderRequestedV1) captor.getValue().payload();
    }

    @Test
    @DisplayName("a first send is INITIAL and carries what the vendor needs")
    void firstSendIsInitial() {
        order(PurchaseOrderStatus.APPROVED, TransmissionState.NOT_TRANSMITTED);

        service.requestTransmission(PO_ID, ACTOR);

        SupplierOrderRequestedV1 command = published();
        assertThat(command.intentType()).isEqualTo(SupplierOrderRequestedV1.IntentType.INITIAL);
        assertThat(command.supplierRef()).isEqualTo("acme-parts");
        assertThat(command.deliveryLocationId()).isEqualTo(SITE_ID);
        assertThat(command.revision()).isEqualTo(1);
        // The article is named by a code the vendor shares, never by our own product id.
        assertThat(command.lines()).singleElement().satisfies(line -> {
            assertThat(line.articleEan()).isEqualTo("5012345678900");
            assertThat(line.quantity()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("an order changed since it was sent is a REVISION")
    void changedSinceLastSendIsRevision() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.APPROVED, TransmissionState.CONFIRMED);
        po.setTransmissionCount(1);
        po.setTransmittedVersionNumber(1);
        po.setVersionNumber(2);

        service.requestTransmission(PO_ID, ACTOR);

        // The vendor holds an older version of an order it has acknowledged; this corrects it.
        assertThat(published().intentType()).isEqualTo(SupplierOrderRequestedV1.IntentType.REVISION);
    }

    @Test
    @DisplayName("an unchanged order sent again after a refusal is a REPLACEMENT")
    void unchangedAfterRejectionIsReplacement() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.APPROVED, TransmissionState.REJECTED);
        po.setTransmissionCount(1);
        po.setTransmittedVersionNumber(1);
        po.setVersionNumber(1);

        service.requestTransmission(PO_ID, ACTOR);

        // Nothing changed about the order — the previous send simply never became an order the
        // vendor holds, so this is a fresh one rather than a correction to one.
        assertThat(published().intentType()).isEqualTo(SupplierOrderRequestedV1.IntentType.REPLACEMENT);
    }

    @Test
    @DisplayName("an order already in flight is refused here, not left to the far side")
    void inFlightIsRefused() {
        order(PurchaseOrderStatus.APPROVED, TransmissionState.REQUESTED);

        // pos-supplier's active-intent guard is a backstop. The domain that knows why the order
        // exists is the one that must decline, because a blind re-send is a second delivery.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("TRANSMISSION_IN_FLIGHT");
        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("an order awaiting manual review is refused until a person settles it")
    void awaitingReviewIsRefused() {
        order(PurchaseOrderStatus.APPROVED, TransmissionState.MANUAL_REVIEW);

        // Nobody knows whether the vendor has the previous send. Sending again on that ignorance
        // is exactly the guess ADR-0052 exists to prevent.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("TRANSMISSION_AWAITING_REVIEW");
        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a draft is refused: nobody has committed to it yet")
    void draftIsRefused() {
        order(PurchaseOrderStatus.DRAFT, TransmissionState.NOT_TRANSMITTED);

        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("PURCHASE_ORDER_NOT_APPROVED");
        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("an order naming no supplier profile has nobody to send to")
    void missingSupplierRefIsRefused() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.APPROVED, TransmissionState.NOT_TRANSMITTED);
        po.setSupplierRef(null);

        // vendorId cannot stand in: it holds a manufacturer or distributor id that bears no
        // relationship to a supplier profile.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("SUPPLIER_REF_MISSING");
    }

    @Test
    @DisplayName("a line the vendor could not identify refuses the whole order, not just the line")
    void unidentifiableArticleRefusesTheOrder() {
        order(PurchaseOrderStatus.APPROVED, TransmissionState.NOT_TRANSMITTED);
        when(extProductCodeRepository.findById(SKU_ID)).thenReturn(Optional.empty());

        // Withholding the line would send an order that silently differs from the one on screen,
        // and the difference would surface as a short delivery rather than as a question now.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("ARTICLE_NOT_IDENTIFIABLE")
                .hasMessageContaining("[1]");
        verify(outboxEventWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a product with a code of the wrong type is not identified by it")
    void nonEanCodeIsNotAnArticleIdentifier() {
        order(PurchaseOrderStatus.APPROVED, TransmissionState.NOT_TRANSMITTED);
        when(extProductCodeRepository.findById(SKU_ID))
                .thenReturn(Optional.of(ExtProductCode.builder()
                        .productId(SKU_ID)
                        .codeType("INTERNAL")
                        .code("PART-9911")
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));

        // An internal code means nothing to the vendor. Sending it would name an article they
        // cannot look up, which is a guess wearing the costume of an identifier.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("ARTICLE_NOT_IDENTIFIABLE");
    }

    @Test
    @DisplayName("a fractional quantity is refused rather than rounded")
    void fractionalQuantityIsRefused() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.APPROVED, TransmissionState.NOT_TRANSMITTED);
        po.getLines().getFirst().setOpenQuantityDecimal(new BigDecimal("2.5"));

        // The wire carries an integer. Rounding would order an amount nobody asked for and it
        // would be discovered as a delivery of the wrong size.
        assertThatThrownBy(() -> service.requestTransmission(PO_ID, ACTOR))
                .isInstanceOf(PurchaseOrderNotTransmittableException.class)
                .hasMessageContaining("FRACTIONAL_QUANTITY");
    }

    @Test
    @DisplayName("a revision after a part delivery asks for the remainder, not the whole order")
    void revisionAsksForWhatIsStillOwed() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.PARTIALLY_RECEIVED, TransmissionState.CONFIRMED);
        po.setTransmissionCount(1);
        po.setTransmittedVersionNumber(1);
        po.setVersionNumber(2);
        po.getLines().getFirst().setOpenQuantityDecimal(new BigDecimal("4"));

        service.requestTransmission(PO_ID, ACTOR);

        // Asking for the ordered ten again would have the vendor ship the six already delivered a
        // second time.
        assertThat(published().lines().getFirst().quantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("the request is recorded on the order in the same transaction that publishes it")
    void requestIsRecordedOnTheOrder() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.APPROVED, TransmissionState.REJECTED);
        po.setTransmissionCount(1);
        po.setTransmissionRejectionReason("VENDOR_REJECTED");
        po.setTransmissionVendorReason("out of stock");

        service.requestTransmission(PO_ID, ACTOR);

        assertThat(po.getTransmissionState()).isEqualTo(TransmissionState.REQUESTED);
        assertThat(po.getTransmissionCount()).isEqualTo(2);
        assertThat(po.getTransmissionRequestedAt()).isEqualTo(NOW);
        // The previous refusal explained the order's last state, not this one. Left in place beside
        // a fresh request it would read as though the vendor had refused again.
        assertThat(po.getTransmissionRejectionReason()).isNull();
        assertThat(po.getTransmissionVendorReason()).isNull();
        // The intent id stays unset: pos-supplier mints it, which is what makes a redelivered
        // command a no-op rather than a second physical order.
        assertThat(po.getTransmissionIntentId()).isNull();
        verify(purchaseOrderRepository).save(po);
    }
}
