package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.exception.PurchaseOrderStateConflictException;
import com.positivity.order.internal.exception.PurchaseOrderVendorException;
import com.positivity.order.internal.exception.PurchaseOrderVersionConflictException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.PurchaseOrderLineRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
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
import org.junit.jupiter.api.Nested;
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
 * The purchase-order aggregate now that pos-order owns it (CAP-320 #1334).
 *
 * <p>Two properties matter beyond the ordinary lifecycle rules. Every transition must publish the
 * fact, because that is the only way anything outside this module learns the order changed — a
 * transition that forgets is invisible rather than merely late. And a new line must start fully
 * outstanding, because the open quantity is what downstream counts as incoming supply.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderServiceImpl — the aggregate in pos-order (#1334)")
class PurchaseOrderServiceImplTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final String ACTOR = "buyer-1";

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Mock
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @Mock
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Mock
    private DocumentQuantityConverter documentQuantityConverter;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    @Mock
    private ExtSupplierVendorRepository vendorRepository;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                purchaseOrderLineRepository,
                transmissionEventRepository,
                entityManager,
                purchaseOrderFactPublisher,
                documentQuantityConverter,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SupplierVendorGuard(vendorRepository));
        when(vendorRepository.findById(VENDOR_ID))
                .thenReturn(Optional.of(vendor(VENDOR_ID, ExtSupplierVendor.Status.ACTIVE)));
        when(documentQuantityConverter.convertIfPresent(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.getNextPurchaseOrderSequence()).thenReturn(42L);
        // Revise reads the order under a row lock (#2632); here the locked read finds what findById is stubbed with.
        when(purchaseOrderRepository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> purchaseOrderRepository.findById(invocation.getArgument(0)));
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> {
            PurchaseOrderEntity saved = invocation.getArgument(0);
            if (saved.getPurchaseOrderId() == null) {
                saved.setPurchaseOrderId(PO_ID);
            }
            return saved;
        });
    }

    private static ExtSupplierVendor vendor(UUID vendorId, ExtSupplierVendor.Status status) {
        return ExtSupplierVendor.builder()
                .vendorId(vendorId)
                .vendorNumber("V-000123")
                .displayName("Acme Parts")
                .status(status)
                .aggregateVersion(1L)
                .updatedAt(NOW)
                .build();
    }

    private static PurchaseOrderLineRequest lineRequest(String quantity, long unitCostMinor) {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(1);
        line.setSkuId(SKU_ID);
        line.setDescription("Front brake rotor");
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitCostMinor(unitCostMinor);
        return line;
    }

    private static CreatePurchaseOrderRequest createRequest() {
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setVendorId(VENDOR_ID);
        request.setCurrency("USD");
        request.setShipToLocationId(SITE_ID);
        request.setExpectedDeliveryDate(LocalDate.of(2026, 9, 1));
        request.setLines(List.of(lineRequest("10", 5_000L)));
        return request;
    }

    private static PurchaseOrderEntity existingOrder(PurchaseOrderStatus status) {
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05"))
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
                .vendorId(VENDOR_ID)
                .status(status)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(50_000L)
                .taxMinor(0L)
                .grandTotalMinor(50_000L)
                .openBalanceMinor(50_000L)
                .shipToLocationId(SITE_ID)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);
        return po;
    }

    @Test
    @DisplayName("a created order starts as a draft with every line fully outstanding")
    void createStartsDraftWithFullyOpenLines() {
        PurchaseOrderResponse response = service.createPurchaseOrder(createRequest(), ACTOR);

        assertThat(response.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(response.getGrandTotalMinor()).isEqualTo(50_000L);
        // Open quantity is what downstream counts as incoming supply. A new line that started at
        // zero would be an order nobody could see coming.
        assertThat(response.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getQuantityDecimal()).isEqualByComparingTo("10");
            assertThat(line.getOpenQuantityDecimal()).isEqualByComparingTo("10");
        });
    }

    @Test
    @DisplayName("the PO number renders the sequence as eight base-36 characters")
    void poNumberIsEightBase36Characters() {
        PurchaseOrderResponse response = service.createPurchaseOrder(createRequest(), ACTOR);

        assertThat(response.getPoNumber()).hasSize(8).isEqualTo("00000016");
    }

    @Test
    @DisplayName("a sequence value too large for eight characters is refused rather than truncated")
    void sequenceOverflowIsRefused() {
        when(purchaseOrderRepository.getNextPurchaseOrderSequence()).thenReturn(Long.MAX_VALUE);

        // Truncating would mint a number that collides with one already printed on an order.
        assertThatThrownBy(() -> service.createPurchaseOrder(createRequest(), ACTOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("approval stamps who approved and when, and only from draft")
    void approvalStampsApproverAndRejectsNonDraft() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        ApprovePurchaseOrderRequest request = new ApprovePurchaseOrderRequest();
        request.setApprovalNotes("within budget");

        PurchaseOrderResponse approved = service.approvePurchaseOrder(PO_ID, request, ACTOR);

        assertThat(approved.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(approved.getApproverId()).isEqualTo(ACTOR);
        assertThat(approved.getApprovalTimestamp()).isEqualTo(NOW);

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));
        assertThatThrownBy(() -> service.approvePurchaseOrder(PO_ID, request, ACTOR))
                .isInstanceOf(PurchaseOrderStateConflictException.class);
    }

    @Test
    @DisplayName("a revision bumps the version and replaces the lines outright")
    void revisionBumpsVersionAndReplacesLines() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
        request.setExpectedVersionNumber(1);
        request.setRevisionReason("vendor changed the price");
        request.setLines(List.of(lineRequest("4", 6_000L)));

        PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, request, ACTOR);

        assertThat(revised.getVersionNumber()).isEqualTo(2);
        // Replaced, not merged: a line the buyer removed must be gone, or it keeps counting as
        // supply nobody ordered.
        assertThat(revised.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getQuantityDecimal()).isEqualByComparingTo("4");
            assertThat(line.getUnitCostMinor()).isEqualTo(6_000L);
        });
        assertThat(revised.getGrandTotalMinor()).isEqualTo(24_000L);
    }

    @Test
    @DisplayName("a received or closed order cannot be cancelled")
    void cancelIsBlockedOnTerminalStatuses() {
        for (PurchaseOrderStatus terminal : List.of(PurchaseOrderStatus.FULLY_RECEIVED, PurchaseOrderStatus.CLOSED)) {
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(terminal)));

            // The goods are already here. Cancelling would deny an obligation the vendor has met.
            assertThatThrownBy(() -> service.cancelPurchaseOrder(PO_ID, ACTOR))
                    .isInstanceOf(PurchaseOrderStateConflictException.class);
        }
        verify(purchaseOrderFactPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("cancelling an approved order publishes the fact that removes it from supply")
    void cancelPublishesTheFact() {
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));

        service.cancelPurchaseOrder(PO_ID, ACTOR);

        ArgumentCaptor<PurchaseOrderEntity> captor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(purchaseOrderFactPublisher).publish(captor.capture(), any());
        // CANCELLED is not an open-supply status, so a consumer applying this fact drops the
        // order's quantities. No separate "supply dropped" message is needed from here.
        assertThat(captor.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("every state-changing transition publishes exactly one fact")
    void everyTransitionPublishes() {
        service.createPurchaseOrder(createRequest(), ACTOR);
        verify(purchaseOrderFactPublisher).publish(any(), any());

        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR);

        RevisePurchaseOrderRequest revise = new RevisePurchaseOrderRequest();

        revise.setExpectedVersionNumber(1);
        revise.setLines(List.of(lineRequest("1", 100L)));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        service.revisePurchaseOrder(PO_ID, revise, ACTOR);

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));
        service.cancelPurchaseOrder(PO_ID, ACTOR);

        // Four transitions, four facts. A transition that publishes nothing is invisible to every
        // reader rather than merely late, and the projection would never correct itself.
        verify(purchaseOrderFactPublisher, org.mockito.Mockito.times(4)).publish(any(), any());
    }

    @Test
    @DisplayName("an unknown order id is a not-found, not a null")
    void unknownOrderThrows() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPurchaseOrder(PO_ID)).isInstanceOf(PurchaseOrderNotFoundException.class);
    }

    // ─── Currency (ADR-0067 DF-6, #2314) ─────────────────────────────────────

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"XYZ", "US Dollars", "usdollar", "EU"})
    @DisplayName("a hand-keyed order whose currency is not ISO 4217 is refused with a 400 (#2314)")
    void nonIsoCurrencyRefused(String currency) {
        CreatePurchaseOrderRequest request = createRequest();
        request.setCurrency(currency);

        assertThatThrownBy(() -> service.createPurchaseOrder(request, "buyer-1"))
                .isInstanceOf(PurchaseOrderRequestValidationException.class)
                .hasMessageContaining("ISO 4217");
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a valid currency is stored in its canonical upper-case form (#2314)")
    void currencyStoredCanonical() {
        CreatePurchaseOrderRequest request = createRequest();
        request.setCurrency(" eur ");

        service.createPurchaseOrder(request, "buyer-1");

        ArgumentCaptor<PurchaseOrderEntity> captor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(purchaseOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("an order requested by pos-inventory goes through the same currency validation (#2314)")
    void requestedOrderNonIsoCurrencyRefused() {
        CreatePurchaseOrderRequest request = createRequest();
        request.setCurrency("XYZ");

        assertThatThrownBy(() -> service.createRequested(PO_ID, request, "pos-inventory"))
                .isInstanceOf(PurchaseOrderRequestValidationException.class);
        verify(entityManager, never()).persist(any());
    }

    @Test
    @DisplayName("a requested order is inserted at the id it was asked for, never merged into one")
    void requestedOrderIsInsertedNotMerged() {
        CreatePurchaseOrderRequest request = createRequest();

        service.createRequested(PO_ID, request, "pos-inventory");

        // save() would read the pre-set id as "not new" and merge, which silently updates an
        // existing row — the duplicate would overwrite the order it was meant not to create twice.
        // persist() inserts or fails, so the primary key is what enforces one-order-per-request.
        ArgumentCaptor<PurchaseOrderEntity> captor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(entityManager).persist(captor.capture());
        assertThat(captor.getValue().getPurchaseOrderId()).isEqualTo(PO_ID);
        verify(purchaseOrderRepository, never()).save(any());

        // The fact still goes out, or nothing downstream learns the order exists.
        verify(purchaseOrderFactPublisher).publish(any(), any());
    }

    @Nested
    @DisplayName("the revise gate: DRAFT, or APPROVED with nothing received (#2632)")
    class RevisionGate {

        private RevisePurchaseOrderRequest revision() {
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(1);
            request.setRevisionReason("vendor changed the price");
            request.setLines(List.of(lineRequest("4", 6_000L)));
            return request;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = PurchaseOrderStatus.class,
                names = {"PARTIALLY_RECEIVED", "FULLY_RECEIVED", "CLOSED", "CANCELLED"})
        @DisplayName("past APPROVED: 409, nothing changes and no fact is published")
        void pastApprovedIsRefused(PurchaseOrderStatus status) {
            PurchaseOrderEntity order = existingOrder(status);
            UUID lineId = order.getLines().getFirst().getLineId();
            doReturn(Optional.of(order)).when(purchaseOrderRepository).findByIdForUpdate(PO_ID);

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revision(), ACTOR))
                    .isInstanceOf(PurchaseOrderStateConflictException.class)
                    .hasMessageContaining(status.name());

            assertUnchanged(order, lineId);
        }

        @Test
        @DisplayName("APPROVED with goods received on a line: 409, nothing changes and no fact is published")
        void approvedWithReceivedLineIsRefused() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.APPROVED);
            PurchaseOrderLineEntity line = order.getLines().getFirst();
            line.setOpenQuantityDecimal(new BigDecimal("6"));
            doReturn(Optional.of(order)).when(purchaseOrderRepository).findByIdForUpdate(PO_ID);

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revision(), ACTOR))
                    .isInstanceOf(PurchaseOrderStateConflictException.class)
                    .hasMessageContaining("received goods on line 1");

            assertUnchanged(order, line.getLineId());
            assertThat(line.getOpenQuantityDecimal()).isEqualByComparingTo("6");
        }

        @Test
        @DisplayName("APPROVED with nothing received is revised, through the locked read")
        void approvedWithNothingReceivedIsRevised() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.APPROVED);
            doReturn(Optional.of(order)).when(purchaseOrderRepository).findByIdForUpdate(PO_ID);

            PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, revision(), ACTOR);

            assertThat(revised.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(revised.getVersionNumber()).isEqualTo(2);
            assertThat(revised.getLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.getQuantityDecimal()).isEqualByComparingTo("4"));
            verify(purchaseOrderRepository).findByIdForUpdate(PO_ID);
            verify(purchaseOrderRepository, never()).findById(PO_ID);
            verify(purchaseOrderFactPublisher).publish(any(), any());
        }

        @Test
        @DisplayName("a line with no open quantity recorded yet counts as nothing received")
        void nullOpenQuantityIsNothingReceived() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.APPROVED);
            order.getLines().getFirst().setOpenQuantityDecimal(null);
            doReturn(Optional.of(order)).when(purchaseOrderRepository).findByIdForUpdate(PO_ID);

            assertThat(service.revisePurchaseOrder(PO_ID, revision(), ACTOR).getVersionNumber())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an unknown order is still a not-found")
        void unknownOrderIsNotFound() {
            doReturn(Optional.empty()).when(purchaseOrderRepository).findByIdForUpdate(PO_ID);

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revision(), ACTOR))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);
        }

        private void assertUnchanged(PurchaseOrderEntity order, UUID lineId) {
            assertThat(order.getVersionNumber()).isEqualTo(1);
            assertThat(order.getOpenBalanceMinor()).isEqualTo(50_000L);
            assertThat(order.getLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.getLineId()).isEqualTo(lineId));
            verify(purchaseOrderRepository, never()).save(any());
            verify(purchaseOrderFactPublisher, never()).publish(any(), any());
        }
    }

    @Nested
    @DisplayName("the vendor guard (CAP:550 S24, #2517)")
    class VendorGuard {

        private final UUID otherVendor = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a09");

        @Test
        @DisplayName(
                "create answers 503 VENDOR_REPLICATION_PENDING for a vendor not in the copy yet, and saves nothing")
        void createRefusesUnknownVendor() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(createRequest(), ACTOR))
                    .isInstanceOfSatisfying(
                            com.positivity.web.common.ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));
            verify(purchaseOrderRepository, never()).save(any());
            verify(purchaseOrderFactPublisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("create refuses an inactive vendor with VENDOR_INACTIVE, naming its number")
        void createRefusesInactiveVendor() {
            when(vendorRepository.findById(VENDOR_ID))
                    .thenReturn(Optional.of(vendor(VENDOR_ID, ExtSupplierVendor.Status.INACTIVE)));

            assertThatThrownBy(() -> service.createPurchaseOrder(createRequest(), ACTOR))
                    .isInstanceOfSatisfying(PurchaseOrderVendorException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(PurchaseOrderVendorException.Code.VENDOR_INACTIVE);
                        assertThat(e.getMessage()).contains("V-000123");
                    });
            verify(purchaseOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName(
                "approve of a draft whose vendor is not in the copy yet answers 503 VENDOR_REPLICATION_PENDING; the order stays a draft")
        void approveRefusesUnknownVendor() {
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR))
                    .isInstanceOfSatisfying(
                            com.positivity.web.common.ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));
            assertThat(draft.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
            verify(purchaseOrderFactPublisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("approve refuses a draft whose vendor is inactive")
        void approveRefusesInactiveVendor() {
            when(purchaseOrderRepository.findById(PO_ID))
                    .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
            when(vendorRepository.findById(VENDOR_ID))
                    .thenReturn(Optional.of(vendor(VENDOR_ID, ExtSupplierVendor.Status.INACTIVE)));

            assertThatThrownBy(() -> service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR))
                    .isInstanceOfSatisfying(
                            PurchaseOrderVendorException.class,
                            e -> assertThat(e.getCode()).isEqualTo(PurchaseOrderVendorException.Code.VENDOR_INACTIVE));
        }

        @Test
        @DisplayName("a draft revised to an active vendor takes that vendor, and can then be approved")
        void revisionChangesVendorOfDraft() {
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));
            when(vendorRepository.findById(otherVendor))
                    .thenReturn(Optional.of(vendor(otherVendor, ExtSupplierVendor.Status.ACTIVE)));
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(1);
            request.setRevisionReason("vendor set up in the master");
            request.setVendorId(otherVendor);
            request.setLines(List.of(lineRequest("4", 6_000L)));

            PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, request, ACTOR);

            assertThat(revised.getVendorId()).isEqualTo(otherVendor);
            assertThat(service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR)
                            .getStatus())
                    .isEqualTo(PurchaseOrderStatus.APPROVED);
        }

        @Test
        @DisplayName("a revision to a vendor missing from the copy or inactive is refused and keeps the vendor")
        void revisionRefusesInvalidVendor() {
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(1);
            request.setVendorId(otherVendor);
            request.setLines(List.of(lineRequest("4", 6_000L)));

            when(vendorRepository.findById(otherVendor)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, request, ACTOR))
                    .isInstanceOfSatisfying(
                            com.positivity.web.common.ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));

            when(vendorRepository.findById(otherVendor))
                    .thenReturn(Optional.of(vendor(otherVendor, ExtSupplierVendor.Status.INACTIVE)));
            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, request, ACTOR))
                    .isInstanceOfSatisfying(
                            PurchaseOrderVendorException.class,
                            e -> assertThat(e.getCode()).isEqualTo(PurchaseOrderVendorException.Code.VENDOR_INACTIVE));
            assertThat(draft.getVendorId()).isEqualTo(VENDOR_ID);
            verify(purchaseOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("an approved order cannot change its vendor; naming its own vendor is not a change")
        void revisionCannotChangeVendorPastDraft() {
            when(purchaseOrderRepository.findById(PO_ID))
                    .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(1);
            request.setVendorId(otherVendor);
            request.setLines(List.of(lineRequest("4", 6_000L)));

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, request, ACTOR))
                    .isInstanceOf(PurchaseOrderStateConflictException.class);

            request.setVendorId(VENDOR_ID);
            assertThat(service.revisePurchaseOrder(PO_ID, request, ACTOR).getVendorId())
                    .isEqualTo(VENDOR_ID);
        }

        @Test
        @DisplayName("an order requested on order.commands.v1 lands in DRAFT whatever its vendor; approval stops it")
        void requestedOrderLandsDraftThenApprovalRefuses() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());
            UUID requestedId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a0a");
            ArgumentCaptor<PurchaseOrderEntity> persisted = ArgumentCaptor.forClass(PurchaseOrderEntity.class);

            service.createRequested(requestedId, createRequest(), "pos-inventory");

            verify(entityManager).persist(persisted.capture());
            assertThat(persisted.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
            when(purchaseOrderRepository.findById(requestedId)).thenReturn(Optional.of(persisted.getValue()));
            assertThatThrownBy(
                            () -> service.approvePurchaseOrder(requestedId, new ApprovePurchaseOrderRequest(), ACTOR))
                    .isInstanceOfSatisfying(
                            com.positivity.web.common.ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));
            assertThat(persisted.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        }
    }

    @Nested
    @DisplayName("expected version on revise and approve, and lines that round-trip (#2681)")
    class VersionGuard {

        private RevisePurchaseOrderRequest revisionBuiltOn(int version) {
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(version);
            request.setRevisionReason("vendor changed the price");
            request.setLines(List.of(lineRequest("4", 6_000L)));
            return request;
        }

        @Test
        @DisplayName("a revise built on a stale version is refused: 409 code, no save, no fact")
        void staleReviseIsRefused() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.DRAFT);
            order.setVersionNumber(3);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revisionBuiltOn(2), ACTOR))
                    .isInstanceOfSatisfying(PurchaseOrderVersionConflictException.class, e -> {
                        assertThat(e.getExpectedVersionNumber()).isEqualTo(2);
                        assertThat(e.getCurrentVersionNumber()).isEqualTo(3);
                        assertThat(e.getMessage())
                                .contains("0000001A")
                                .contains("version 3")
                                .contains("version 2");
                    });
            assertThat(order.getVersionNumber()).isEqualTo(3);
            assertThat(order.getLines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.getQuantityDecimal()).isEqualByComparingTo(BigDecimal.TEN));
            verify(purchaseOrderRepository, never()).save(any());
            verify(purchaseOrderFactPublisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("the version is checked under the row lock, before the state gate")
        void versionIsCheckedUnderTheLock() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.CLOSED);
            order.setVersionNumber(2);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revisionBuiltOn(1), ACTOR))
                    .isInstanceOf(PurchaseOrderVersionConflictException.class);
            verify(purchaseOrderRepository).findByIdForUpdate(PO_ID);
        }

        @Test
        @DisplayName("a revise without expectedVersionNumber is a 400, not a silent overwrite")
        void missingExpectedVersionIsRefused() {
            when(purchaseOrderRepository.findById(PO_ID))
                    .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
            RevisePurchaseOrderRequest request = revisionBuiltOn(1);
            request.setExpectedVersionNumber(null);

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, request, ACTOR))
                    .isInstanceOf(PurchaseOrderRequestValidationException.class)
                    .hasMessageContaining("expectedVersionNumber");
            verify(purchaseOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("an approve built on a stale version is refused the same way; without a version it approves")
        void approveHonoursExpectedVersionWhenGiven() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.DRAFT);
            order.setVersionNumber(2);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
            ApprovePurchaseOrderRequest stale = new ApprovePurchaseOrderRequest();
            stale.setExpectedVersionNumber(1);

            assertThatThrownBy(() -> service.approvePurchaseOrder(PO_ID, stale, ACTOR))
                    .isInstanceOf(PurchaseOrderVersionConflictException.class);
            assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
            verify(purchaseOrderFactPublisher, never()).publish(any(), any());

            ApprovePurchaseOrderRequest current = new ApprovePurchaseOrderRequest();
            current.setExpectedVersionNumber(2);
            assertThat(service.approvePurchaseOrder(PO_ID, current, ACTOR).getStatus())
                    .isEqualTo(PurchaseOrderStatus.APPROVED);

            PurchaseOrderEntity another = existingOrder(PurchaseOrderStatus.DRAFT);
            another.setVersionNumber(7);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(another));
            assertThat(service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR)
                            .getStatus())
                    .isEqualTo(PurchaseOrderStatus.APPROVED);
            verify(purchaseOrderRepository, times(3)).findByIdForUpdate(PO_ID);
        }

        @Test
        @DisplayName("the line read carries taxCodeId and glAccountId, and read -> revise keeps every line field")
        void linesRoundTrip() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.DRAFT);
            PurchaseOrderLineEntity stored = order.getLines().getFirst();
            stored.setTaxCodeId("TAX-STD");
            stored.setGlAccountId("5000");
            stored.setTaxMinor(5_000L); // the service's default rate on 50_000, so a re-save recomputes the same
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));

            PurchaseOrderResponse read = service.getPurchaseOrder(PO_ID);
            PurchaseOrderLineResponse line = read.getLines().getFirst();
            assertThat(line.getTaxCodeId()).isEqualTo("TAX-STD");
            assertThat(line.getGlAccountId()).isEqualTo("5000");

            PurchaseOrderLineRequest echoed = new PurchaseOrderLineRequest();
            echoed.setLineNumber(line.getLineNumber());
            echoed.setSkuId(line.getSkuId());
            echoed.setDescription(line.getDescription());
            echoed.setQuantity(line.getQuantityDecimal());
            echoed.setUnitCostMinor(line.getUnitCostMinor());
            echoed.setTaxCodeId(line.getTaxCodeId());
            echoed.setGlAccountId(line.getGlAccountId());
            echoed.setDocumentUom(line.getDocumentUom());
            echoed.setDocumentQuantity(line.getDocumentQuantity());
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(read.getVersionNumber());
            request.setRevisionReason("re-saved from the screen, nothing changed");
            request.setLines(List.of(echoed));

            PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, request, ACTOR);

            assertThat(revised.getVersionNumber()).isEqualTo(2);
            assertThat(revised.getLines()).singleElement().satisfies(after -> {
                assertThat(after.getSkuId()).isEqualTo(line.getSkuId());
                assertThat(after.getTaxCodeId()).isEqualTo("TAX-STD");
                assertThat(after.getGlAccountId()).isEqualTo("5000");
                assertThat(after.getQuantityDecimal()).isEqualByComparingTo(line.getQuantityDecimal());
                assertThat(after.getUnitCostMinor()).isEqualTo(line.getUnitCostMinor());
                assertThat(after.getLineTotalMinor()).isEqualTo(line.getLineTotalMinor());
                assertThat(after.getTaxMinor()).isEqualTo(line.getTaxMinor());
                assertThat(after.getDocumentUom()).isEqualTo(line.getDocumentUom());
                assertThat(after.getDocumentQuantity()).isEqualTo(line.getDocumentQuantity());
            });
        }

        @Test
        @DisplayName("an expectedVersionNumber below 1 is a 400, not a 409 against 'version 0'")
        void nonPositiveExpectedVersionIsRefused() {
            when(purchaseOrderRepository.findById(PO_ID))
                    .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));

            assertThatThrownBy(() -> service.revisePurchaseOrder(PO_ID, revisionBuiltOn(0), ACTOR))
                    .isInstanceOf(PurchaseOrderRequestValidationException.class);
        }

        @Test
        @DisplayName("cancel reads the order under the row lock, like every other header writer")
        void cancelLocksTheRow() {
            when(purchaseOrderRepository.findById(PO_ID))
                    .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));

            service.cancelPurchaseOrder(PO_ID, ACTOR);

            verify(purchaseOrderRepository).findByIdForUpdate(PO_ID);
        }

        @Test
        @DisplayName("a blank requestedBy clears the requester")
        void blankRequesterClears() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.DRAFT);
            order.setRequestedBy("tech-maria");
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
            RevisePurchaseOrderRequest cleared = revisionBuiltOn(1);
            cleared.setRequestedBy("   ");

            assertThat(service.revisePurchaseOrder(PO_ID, cleared, ACTOR).getRequestedBy())
                    .isNull();
        }

        @Test
        @DisplayName("a revision keeps the order's requester unless it names a new one")
        void requesterIsKeptUnlessCorrected() {
            PurchaseOrderEntity order = existingOrder(PurchaseOrderStatus.DRAFT);
            order.setRequestedBy("tech-maria");
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));

            assertThat(service.revisePurchaseOrder(PO_ID, revisionBuiltOn(1), ACTOR)
                            .getRequestedBy())
                    .isEqualTo("tech-maria");

            RevisePurchaseOrderRequest corrected = revisionBuiltOn(2);
            corrected.setRequestedBy("tech-jonas");
            assertThat(service.revisePurchaseOrder(PO_ID, corrected, ACTOR).getRequestedBy())
                    .isEqualTo("tech-jonas");
        }
    }

    @Nested
    @DisplayName("#2713: the vendor's purchasing channel is stamped on the order")
    class PurchasingChannelStamp {

        private ExtSupplierVendor vendorWithChannel(String channel) {
            ExtSupplierVendor v = vendor(VENDOR_ID, ExtSupplierVendor.Status.ACTIVE);
            v.setPurchaseOrderSupplierRef(channel);
            return v;
        }

        @Test
        @DisplayName("create stamps the copy's channel and the read says transmittable")
        void createStamps() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(vendorWithChannel("edi-main")));

            PurchaseOrderResponse created = service.createPurchaseOrder(createRequest(), ACTOR);

            assertThat(created.isTransmittable()).isTrue();
            ArgumentCaptor<PurchaseOrderEntity> saved = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
            verify(purchaseOrderRepository).save(saved.capture());
            assertThat(saved.getValue().getSupplierRef()).isEqualTo("edi-main");
        }

        @Test
        @DisplayName("a vendor without a channel leaves the stamp empty and the read not transmittable")
        void createWithoutChannel() {
            PurchaseOrderResponse created = service.createPurchaseOrder(createRequest(), ACTOR);
            assertThat(created.isTransmittable()).isFalse();
        }

        @Test
        @DisplayName("approve re-stamps a channel the vendor gained since the order was raised")
        void approveRestamps() {
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(vendorWithChannel("edi-main")));

            PurchaseOrderResponse approved = service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR);

            assertThat(approved.isTransmittable()).isTrue();
            assertThat(draft.getSupplierRef()).isEqualTo("edi-main");
        }

        @Test
        @DisplayName("approve keeps an existing stamp when the vendor now has no channel, and never clears it")
        void approveKeepsStampWithoutChannel() {
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            draft.setSupplierRef("edi-old");
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));

            service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR);

            assertThat(draft.getSupplierRef()).isEqualTo("edi-old");
        }

        @Test
        @DisplayName("a vendor-changing revise stamps the new vendor's channel")
        void reviseVendorStamps() {
            UUID other = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a77");
            PurchaseOrderEntity draft = existingOrder(PurchaseOrderStatus.DRAFT);
            draft.setSupplierRef("edi-old");
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(draft));
            ExtSupplierVendor otherVendor = vendor(other, ExtSupplierVendor.Status.ACTIVE);
            otherVendor.setPurchaseOrderSupplierRef("edi-other");
            when(vendorRepository.findById(other)).thenReturn(Optional.of(otherVendor));
            RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
            request.setExpectedVersionNumber(1);
            request.setRevisionReason("vendor set up in the master");
            request.setVendorId(other);
            request.setLines(List.of(lineRequest("4", 6_000L)));

            service.revisePurchaseOrder(PO_ID, request, ACTOR);

            assertThat(draft.getSupplierRef()).isEqualTo("edi-other");
        }

        @Test
        @DisplayName("once transmitted, the stamp is frozen")
        void frozenAfterSend() {
            PurchaseOrderEntity sent = existingOrder(PurchaseOrderStatus.APPROVED);
            sent.setSupplierRef("edi-old");
            sent.setTransmissionState(TransmissionState.TRANSMITTED);
            PurchaseOrderServiceImpl.stampChannel(sent, vendorWithChannel("edi-new"));
            assertThat(sent.getSupplierRef()).isEqualTo("edi-old");
        }
    }
}
