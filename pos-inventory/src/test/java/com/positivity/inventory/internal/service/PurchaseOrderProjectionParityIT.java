package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Availability-to-promise must be unchanged by the purchase-order split (#1333).
 *
 * <h2>Why this test exists and why it is written this way</h2>
 *
 * The projection replaces the local aggregate as the supply-side input to ATP. Its query was ported
 * rather than rewritten, and "ported" is a claim, not a fact — so the claim is checked by running the
 * old query and the new one over the same data and comparing the answers. Reading the two queries and
 * agreeing they look alike is exactly the check that misses a changed NULL handling or an off-by-one
 * on a horizon boundary.
 *
 * <p>The comparison must happen while both implementations still exist. Once the aggregate moves
 * there is nothing left to compare against, which is why this story is sequenced before the move
 * rather than after it.
 *
 * <p>Wrong ATP is not a cosmetic defect. It errs optimistically — a supply figure that is too high
 * promises a customer stock that was never ordered.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ATP parity between the purchase-order aggregate and its projection (#1333)")
class PurchaseOrderProjectionParityIT {

    private static final UUID SKU = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");
    private static final UUID SITE_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final UUID SITE_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a09");
    private static final LocalDate HORIZON = LocalDate.of(2026, 9, 30);

    private static final List<PurchaseOrderStatus> OPEN_ENUM =
            List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);
    private static final List<String> OPEN_STRING = List.of("APPROVED", "PARTIALLY_RECEIVED");

    @Autowired
    private PurchaseOrderRepository orderRepository;

    @Autowired
    private PurchaseOrderLineRepository lineRepository;

    @Autowired
    private ExtPurchaseOrderRepository extOrderRepository;

    @Autowired
    private ExtPurchaseOrderLineRepository extLineRepository;

    @BeforeEach
    void seedBothSides() {
        // Every case that distinguishes the two implementations, written once into both shapes.
        seed(PurchaseOrderStatus.APPROVED, SITE_A, LocalDate.of(2026, 9, 1), "10", "10");
        seed(PurchaseOrderStatus.PARTIALLY_RECEIVED, SITE_A, LocalDate.of(2026, 9, 15), "10", "4");
        // Draft: committed to by nobody, must not count.
        seed(PurchaseOrderStatus.DRAFT, SITE_A, LocalDate.of(2026, 9, 2), "50", "50");
        // Cancelled: never arriving.
        seed(PurchaseOrderStatus.CANCELLED, SITE_A, LocalDate.of(2026, 9, 3), "25", "25");
        // Another site: excluded when a site filter is applied.
        seed(PurchaseOrderStatus.APPROVED, SITE_B, LocalDate.of(2026, 9, 4), "7", "7");
        // Undated: excluded by the bounded query, included by the unbounded one. The single most
        // likely place for the two implementations to disagree.
        seed(PurchaseOrderStatus.APPROVED, SITE_A, null, "3", "3");
        // Exactly on the horizon: inclusive bound.
        seed(PurchaseOrderStatus.APPROVED, SITE_A, HORIZON, "5", "5");
        // Beyond the horizon.
        seed(PurchaseOrderStatus.APPROVED, SITE_A, HORIZON.plusDays(1), "9", "9");
        // Fully received: nothing outstanding, so it contributes zero rather than being filtered.
        seed(PurchaseOrderStatus.PARTIALLY_RECEIVED, SITE_A, LocalDate.of(2026, 9, 5), "8", "0");
    }

    private void seed(PurchaseOrderStatus status, UUID site, LocalDate expected, String ordered, String open) {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        PurchaseOrderEntity po = new PurchaseOrderEntity();
        po.setPurchaseOrderId(orderId);
        po.setPoNumber("PO-" + orderId.toString().substring(0, 8));
        po.setVendorId(UUID.randomUUID());
        po.setStatus(status);
        po.setShipToLocationId(site);
        po.setExpectedDeliveryDate(expected);
        po.setCurrency("USD");
        po.setSubtotalMinor(0L);
        po.setTaxMinor(0L);
        po.setGrandTotalMinor(0L);
        po.setVersionNumber(1);
        po.setCreatedBy("test");
        po.setCreatedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity();
        line.setLineId(lineId);
        line.setPurchaseOrder(po);
        line.setLineNumber(1);
        line.setSkuId(SKU);
        line.setDescription("parity fixture");
        line.setQuantityDecimal(new BigDecimal(ordered));
        line.setOpenQuantityDecimal(new BigDecimal(open));
        line.setUnitCostMinor(0L);
        line.setLineTotalMinor(0L);
        po.setLines(List.of(line));
        orderRepository.save(po);

        extOrderRepository.save(ExtPurchaseOrderReplica.builder()
                .purchaseOrderId(orderId)
                .poNumber(po.getPoNumber())
                .vendorId(po.getVendorId())
                .status(status.name())
                .shipToLocationId(site)
                .expectedDeliveryDate(expected)
                .currency("USD")
                .grandTotalMinor(0L)
                .aggregateVersion(1L)
                .occurredAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        extLineRepository.save(ExtPurchaseOrderLineReplica.builder()
                .lineId(lineId)
                .purchaseOrderId(orderId)
                .lineNumber(1)
                .skuId(SKU)
                .orderedQuantity(new BigDecimal(ordered))
                .openQuantity(new BigDecimal(open))
                .unitCostMinor(0L)
                .description("parity fixture")
                .build());
    }

    @ParameterizedTest(name = "site={0} bounded={1}")
    @CsvSource({
        "A, true",
        "A, false",
        "B, true",
        "B, false",
        "NONE, true",
        "NONE, false",
    })
    @DisplayName("open-supply sums are identical across every site and horizon combination")
    void openSupplySumsMatch(String site, boolean bounded) {
        UUID siteId = "A".equals(site) ? SITE_A : "B".equals(site) ? SITE_B : null;

        BigDecimal fromAggregate = lineRepository.sumOpenQuantityForSku(SKU, OPEN_ENUM, siteId, bounded, HORIZON);
        BigDecimal fromProjection = extLineRepository.sumOpenQuantityForSku(SKU, OPEN_STRING, siteId, bounded, HORIZON);

        assertThat(fromProjection)
                .as("projection must promise exactly what the aggregate promises")
                .isEqualByComparingTo(fromAggregate);
    }

    @Test
    @DisplayName("the undated order is the case that separates bounded from unbounded, in both")
    void undatedSupplyIsExcludedByTheBoundedQueryInBothImplementations() {
        BigDecimal aggBounded = lineRepository.sumOpenQuantityForSku(SKU, OPEN_ENUM, SITE_A, true, HORIZON);
        BigDecimal aggUnbounded = lineRepository.sumOpenQuantityForSku(SKU, OPEN_ENUM, SITE_A, false, HORIZON);
        BigDecimal projBounded = extLineRepository.sumOpenQuantityForSku(SKU, OPEN_STRING, SITE_A, true, HORIZON);
        BigDecimal projUnbounded = extLineRepository.sumOpenQuantityForSku(SKU, OPEN_STRING, SITE_A, false, HORIZON);

        // Both must differ between bounded and unbounded, and differ by the same amount — the undated
        // three plus the beyond-horizon nine. Agreeing on a wrong figure would pass a naive equality
        // check, so the shape of the difference is asserted too.
        assertThat(aggUnbounded.subtract(aggBounded)).isEqualByComparingTo(projUnbounded.subtract(projBounded));
        assertThat(projUnbounded).isGreaterThan(projBounded);
    }

    @Test
    @DisplayName("candidate cutoff dates are identical")
    void distinctDeliveryDatesMatch() {
        List<LocalDate> fromAggregate =
                lineRepository.findDistinctExpectedDeliveryDatesForSku(SKU, OPEN_ENUM, SITE_A, HORIZON);
        List<LocalDate> fromProjection =
                extLineRepository.findDistinctExpectedDeliveryDatesForSku(SKU, OPEN_STRING, SITE_A, HORIZON);

        assertThat(fromProjection).containsExactlyInAnyOrderElementsOf(fromAggregate);
    }

    @Test
    @DisplayName("a SKU nobody ordered returns zero, not null, from both")
    void unknownSkuReturnsZeroFromBoth() {
        UUID unknown = UUID.randomUUID();

        BigDecimal fromAggregate = lineRepository.sumOpenQuantityForSku(unknown, OPEN_ENUM, null, false, HORIZON);
        BigDecimal fromProjection = extLineRepository.sumOpenQuantityForSku(unknown, OPEN_STRING, null, false, HORIZON);

        // COALESCE on both sides: a null here would become a NullPointerException in the caller, or
        // worse, be treated as zero by one implementation and not the other.
        assertThat(fromAggregate).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fromProjection).isNotNull().isEqualByComparingTo(fromAggregate);
    }
}
