package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.AsnLineEntity;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.AsnRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for forecast quantity computation from open documents (odoo-parity A2,
 * issue #1028): incoming (PO + ASN), outgoing (reservations + released pick tasks), horizon
 * cutoffs including the null-date rule, and the projected-available formula.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ForecastQuantityServiceImpl incoming/outgoing/projected computation")
class ForecastQuantityServiceImplTest {

    private static final AtomicInteger PO_SEQ = new AtomicInteger();

    @Autowired
    private ForecastQuantityService forecastQuantityService;

    @Autowired
    private ExpectedSupplyService expectedSupplyService;

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private AsnRepository asnRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PickListRepository pickListRepository;

    @Autowired
    private PickTaskRepository pickTaskRepository;

    @Autowired
    private ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    // ─── incoming: purchase orders ───────────────────────────────────────────

    @Test
    @DisplayName("incoming counts open line quantity of APPROVED and PARTIALLY_RECEIVED POs only")
    void incomingCountsOpenApprovedPoQuantity() {
        UUID sku = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        savePo("APPROVED", site, null, line(sku, "7"));
        savePo("PARTIALLY_RECEIVED", site, null, line(sku, "3"));
        savePo("DRAFT", site, null, line(sku, "100"));
        savePo("CANCELLED", site, null, line(sku, "100"));
        savePo("CLOSED", site, null, line(sku, "100"));

        BigDecimal incoming = expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, null);

        assertThat(incoming).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("incoming is scoped by the PO ship-to site")
    void incomingScopedByShipToSite() {
        UUID sku = UUID.randomUUID();
        UUID siteA = UUID.randomUUID();
        UUID siteB = UUID.randomUUID();
        savePo("APPROVED", siteA, null, line(sku, "7"));
        savePo("APPROVED", siteB, null, line(sku, "5"));

        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), siteA, null))
                .isEqualByComparingTo("7");
        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), null, null))
                .isEqualByComparingTo("12");
    }

    // ─── incoming: ASN remainders ────────────────────────────────────────────

    @Test
    @DisplayName("incoming adds un-received ASN remainders for open ASN statuses, clamped at zero")
    void incomingAddsAsnRemainders() {
        UUID sku = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID po = savePo("APPROVED", site, null, line(sku, "0"));
        // 6 shipped, 2 received -> remainder 4.
        saveAsn(po, AsnStatus.PARTIALLY_RECEIVED, null, asnLine(po, sku, "6", "2"));
        // Over-received line must not subtract.
        saveAsn(po, AsnStatus.LOADED, null, asnLine(po, sku, "3", "5"));
        // FULLY_RECEIVED / CANCELLED ASNs never count.
        saveAsn(po, AsnStatus.FULLY_RECEIVED, null, asnLine(po, sku, "9", "0"));
        saveAsn(po, AsnStatus.CANCELLED, null, asnLine(po, sku, "9", "0"));

        BigDecimal incoming = expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, null);

        assertThat(incoming).isEqualByComparingTo("4");
    }

    // ─── horizon cutoffs & null-date rule ────────────────────────────────────

    @Test
    @DisplayName("horizon bounds PO supply by expectedDeliveryDate; null dates: include unbounded, exclude bounded")
    void horizonBoundsPoSupplyWithNullDateRule() {
        UUID sku = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        savePo("APPROVED", site, LocalDate.parse("2026-07-20"), line(sku, "5"));
        savePo("APPROVED", site, LocalDate.parse("2026-09-01"), line(sku, "11"));
        savePo("APPROVED", site, null, line(sku, "2"));

        Instant horizon = Instant.parse("2026-08-01T12:00:00Z");

        // Unbounded: all three (5 + 11 + 2).
        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, null))
                .isEqualByComparingTo("18");
        // Bounded: only the 2026-07-20 PO; the 2026-09-01 PO is after the horizon and the
        // undated PO is excluded (null-date rule).
        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, horizon))
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("horizon bounds ASN supply by expectedArrivalDate with the same null-date rule")
    void horizonBoundsAsnSupplyWithNullDateRule() {
        UUID sku = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID po = savePo("APPROVED", site, null, line(sku, "0"));
        saveAsn(po, AsnStatus.LOADED, LocalDate.parse("2026-07-25"), asnLine(po, sku, "4", null));
        saveAsn(po, AsnStatus.LOADED, LocalDate.parse("2026-10-01"), asnLine(po, sku, "6", null));
        saveAsn(po, AsnStatus.LOADED, null, asnLine(po, sku, "1", null));

        Instant horizon = Instant.parse("2026-08-01T12:00:00Z");

        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, null))
                .isEqualByComparingTo("11");
        assertThat(expectedSupplyService.expectedIncomingQuantity(sku.toString(), site, horizon))
                .isEqualByComparingTo("4");
    }

    // ─── outgoing: reservations ──────────────────────────────────────────────

    @Test
    @DisplayName("outgoing counts unallocated remainders of PENDING/PARTIALLY_FULFILLED reservations")
    void outgoingCountsReservationRemainders() {
        UUID sku = UUID.randomUUID();
        saveReservation(sku, ReservationStatus.PENDING, 10, 4, null); // remainder 6
        saveReservation(sku, ReservationStatus.PARTIALLY_FULFILLED, 8, 5, null); // remainder 3
        saveReservation(sku, ReservationStatus.FULFILLED, 9, 9, null); // closed states never count
        saveReservation(sku, ReservationStatus.CANCELLED, 9, 0, null);
        saveReservation(sku, ReservationStatus.BACKORDERED, 9, 0, null);
        // Fully soft-allocated PENDING reservation (creation default): remainder 0.
        saveReservation(sku, ReservationStatus.PENDING, 5, 5, null);

        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast(sku.toString(), null, null, new BigDecimal("0"));

        assertThat(forecast.outgoingQty()).isEqualByComparingTo("9");
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("-9");
    }

    @Test
    @DisplayName("horizon bounds reservation demand by dueDateTime; null due dates: include unbounded, exclude bounded")
    void horizonBoundsReservationDemandWithNullDateRule() {
        UUID sku = UUID.randomUUID();
        saveReservation(sku, ReservationStatus.PENDING, 10, 0, Instant.parse("2026-07-25T00:00:00Z"));
        saveReservation(sku, ReservationStatus.PENDING, 20, 0, Instant.parse("2026-09-15T00:00:00Z"));
        saveReservation(sku, ReservationStatus.PENDING, 3, 0, null);

        Instant horizon = Instant.parse("2026-08-01T12:00:00Z");

        assertThat(forecastQuantityService
                        .forecast(sku.toString(), null, null, new BigDecimal("0"))
                        .outgoingQty())
                .isEqualByComparingTo("33");
        assertThat(forecastQuantityService
                        .forecast(sku.toString(), null, horizon, new BigDecimal("0"))
                        .outgoingQty())
                .isEqualByComparingTo("10");
    }

    // ─── outgoing: released pick tasks ───────────────────────────────────────

    @Test
    @DisplayName("outgoing counts PENDING task remainders of released pick lists only")
    void outgoingCountsReleasedUnpickedPickTasks() {
        UUID sku = UUID.randomUUID();
        savePickTask(sku, PickListStatus.READY_TO_PICK, PickTaskStatus.PENDING, 5, 0, null); // counts 5
        savePickTask(sku, PickListStatus.IN_PROGRESS, PickTaskStatus.PENDING, 4, 1, null); // counts 3
        savePickTask(sku, PickListStatus.DRAFT, PickTaskStatus.PENDING, 9, 0, null); // not released
        savePickTask(sku, PickListStatus.COMPLETED, PickTaskStatus.PENDING, 9, 0, null); // closed list
        savePickTask(sku, PickListStatus.READY_TO_PICK, PickTaskStatus.PICKED, 9, 9, null); // picked task
        savePickTask(sku, PickListStatus.READY_TO_PICK, PickTaskStatus.CANCELLED, 9, 0, null);

        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast(sku.toString(), null, null, new BigDecimal("10"));

        assertThat(forecast.outgoingQty()).isEqualByComparingTo("8");
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName(
            "pick-task demand is site-scoped via the storage-location replica; reservation demand is site-agnostic")
    void pickTaskDemandSiteScopedReservationDemandGlobal() {
        UUID sku = UUID.randomUUID();
        UUID siteA = UUID.randomUUID();
        UUID siteB = UUID.randomUUID();
        UUID binAtSiteA = UUID.randomUUID();
        extStorageLocationReplicaRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(binAtSiteA)
                .siteId(siteA)
                .aggregateVersion(1L)
                .build());

        savePickTask(sku, PickListStatus.READY_TO_PICK, PickTaskStatus.PENDING, 5, 0, binAtSiteA);
        savePickTask(sku, PickListStatus.READY_TO_PICK, PickTaskStatus.PENDING, 2, 0, siteB);
        saveReservation(sku, ReservationStatus.PARTIALLY_FULFILLED, 4, 1, null); // remainder 3, site-agnostic

        assertThat(forecastQuantityService
                        .forecast(sku.toString(), siteA, null, new BigDecimal("0"))
                        .outgoingQty())
                .isEqualByComparingTo("8"); // 5 (bin under site A) + 3 (global reservation demand)
        assertThat(forecastQuantityService
                        .forecast(sku.toString(), siteB, null, new BigDecimal("0"))
                        .outgoingQty())
                .isEqualByComparingTo("5"); // 2 (task suggested at site B itself) + 3
        assertThat(forecastQuantityService
                        .forecast(sku.toString(), null, null, new BigDecimal("0"))
                        .outgoingQty())
                .isEqualByComparingTo("10"); // everything
    }

    // ─── projection ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("projectedAvailable = onHand + incoming - outgoing, at the precision the documents state")
    void projectedAvailableFormula() {
        UUID sku = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        savePo("APPROVED", site, null, line(sku, "7.900000"));
        saveReservation(sku, ReservationStatus.PENDING, 3, 0, null);

        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast(sku.toString(), site, null, new BigDecimal("100"));

        // ADR-0055 stage 2 (#1414): the forecast fields are decimal, so expected supply is no
        // longer floored DOWN to fit an integer. The floor existed to squeeze a fractional
        // expected quantity into a `long` without over-promising; with a decimal field there is
        // nothing to squeeze it into, and losing 0.9 of a divisible product's inbound supply
        // would understate the projection for exactly the stock this stage exists to serve.
        assertThat(forecast.incomingQty()).isEqualByComparingTo("7.900000");
        assertThat(forecast.outgoingQty()).isEqualByComparingTo("3");
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("104.900000");
    }

    @Test
    @DisplayName("non-UUID stock item ids skip PO/reservation matching without failing")
    void nonUuidStockItemIdIsSafe() {
        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast("SKU-FREE-TEXT", null, null, new BigDecimal("5"));

        assertThat(forecast.incomingQty()).isZero();
        assertThat(forecast.outgoingQty()).isZero();
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("5");
    }

    // ─── seeding helpers ─────────────────────────────────────────────────────

    private PurchaseOrderProjectionTestSupport.Line line(UUID skuId, String openQuantity) {
        BigDecimal open = new BigDecimal(openQuantity);
        return new PurchaseOrderProjectionTestSupport.Line(
                skuId, open.max(BigDecimal.ONE).toPlainString(), openQuantity);
    }

    /**
     * Puts an order into play by stating what it says.
     *
     * <p>pos-inventory has no purchase-order table any more (CAP-320 #1334); supply is read from
     * the projection, so this is the only shape the running system has.
     */
    private UUID savePo(
            String status,
            UUID shipToLocationId,
            LocalDate expectedDeliveryDate,
            PurchaseOrderProjectionTestSupport.Line... lines) {
        PO_SEQ.incrementAndGet();
        return projection.project(UUID.randomUUID(), status, shipToLocationId, expectedDeliveryDate, List.of(lines));
    }

    private AsnLineEntity asnLine(UUID poId, UUID skuId, String shipped, String received) {
        return AsnLineEntity.builder()
                .purchaseOrderId(poId)
                .sku(skuId.toString())
                .quantityShipped(new BigDecimal(shipped))
                .quantityReceived(received == null ? null : new BigDecimal(received))
                .build();
    }

    private void saveAsn(UUID poId, AsnStatus status, LocalDate expectedArrivalDate, AsnLineEntity... lines) {
        AdvanceShippingNoticeEntity asn = AdvanceShippingNoticeEntity.builder()
                .asnReferenceNumber("ASN-" + UUID.randomUUID())
                .vendorId(UUID.randomUUID())
                .status(status)
                .purchaseOrderId(poId)
                .expectedArrivalDate(expectedArrivalDate)
                .build();
        for (AsnLineEntity line : lines) {
            line.setAsn(asn);
            asn.getLines().add(line);
        }
        asnRepository.save(asn);
    }

    private void saveReservation(
            UUID stockItemId, ReservationStatus status, int required, int allocated, Instant dueDateTime) {
        reservationRepository.save(ReservationEntity.builder()
                .workorderLineId(UUID.randomUUID())
                .stockItemId(stockItemId)
                .requiredQuantity(BigDecimal.valueOf(required))
                .allocatedQuantity(BigDecimal.valueOf(allocated))
                .status(status)
                .dueDateTime(dueDateTime)
                .build());
    }

    private void savePickTask(
            UUID productId,
            PickListStatus listStatus,
            PickTaskStatus taskStatus,
            int required,
            int picked,
            UUID suggestedLocationId) {
        PickListEntity pickList = pickListRepository.save(PickListEntity.builder()
                .workorderId(UUID.randomUUID())
                .status(listStatus)
                .build());
        pickTaskRepository.save(PickTaskEntity.builder()
                .pickList(pickList)
                .productId(productId)
                .sku(productId.toString())
                .quantityRequired(required)
                .quantityPicked(picked)
                .suggestedLocationId(suggestedLocationId)
                .status(taskStatus)
                .build());
    }
}
