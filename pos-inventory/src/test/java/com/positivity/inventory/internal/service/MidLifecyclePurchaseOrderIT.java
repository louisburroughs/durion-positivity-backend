package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * An order caught mid-lifecycle must be right on both sides of the move (CAP-320 #1334).
 *
 * <h2>What this is checking</h2>
 *
 * The migration copies orders in whatever state they are in, and the interesting ones are not the
 * fresh drafts or the closed orders — they are the ones part-way through: approved, some lines
 * delivered in full, some part delivered, some untouched. That is where a move goes wrong quietly,
 * because every line has a different relationship between what was ordered and what is still owed,
 * and an implementation that confuses the two produces a number that looks plausible.
 *
 * <p>The check is on the figure that matters. Availability-to-promise sums outstanding quantity,
 * so the test asserts that figure — not that rows exist — because rows existing is not the claim.
 *
 * <p>The order is stated here exactly as pos-order's publisher states it, which is what makes this
 * a migration test rather than a fixture test: the same shape crosses the wire in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("A mid-lifecycle purchase order survives the move intact (#1334)")
class MidLifecyclePurchaseOrderIT {

    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
    private static final UUID SKU_SETTLED = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");
    private static final UUID SKU_PARTIAL = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");
    private static final UUID SKU_UNTOUCHED = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b04");
    private static final LocalDate HORIZON = LocalDate.of(2026, 12, 31);

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private ExtPurchaseOrderRepository orderRepository;

    @Autowired
    private ExtPurchaseOrderLineRepository lineRepository;

    @Autowired
    private ExpectedSupplyService expectedSupplyService;

    /** Approved, part delivered: one line settled, one part received, one untouched. */
    private UUID seedMidLifecycleOrder() {
        return projection.project(
                UUID.randomUUID(),
                "PARTIALLY_RECEIVED",
                SITE,
                LocalDate.of(2026, 9, 30),
                List.of(
                        // Ordered 10, all 10 arrived — settled, and must contribute nothing.
                        new PurchaseOrderProjectionTestSupport.Line(SKU_SETTLED, "10", "0"),
                        // Ordered 8, 3 arrived — 5 still owed.
                        new PurchaseOrderProjectionTestSupport.Line(SKU_PARTIAL, "8", "5"),
                        // Ordered 4, nothing yet — all 4 still owed.
                        new PurchaseOrderProjectionTestSupport.Line(SKU_UNTOUCHED, "4", "4")));
    }

    @Test
    @DisplayName("each line keeps what was ordered and what is still owed apart")
    void orderedAndOutstandingStayDistinct() {
        UUID orderId = seedMidLifecycleOrder();

        assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order -> {
            assertThat(order.getStatus()).isEqualTo("PARTIALLY_RECEIVED");
            assertThat(order.getShipToLocationId()).isEqualTo(SITE);
        });

        // The settled line is the one that catches a confusion between the two figures: it was
        // ordered for ten and owes nothing, so an implementation reading the ordered quantity
        // would promise ten units that are already on the shelf.
        assertThat(lineRepository.findByPurchaseOrderId(orderId))
                .filteredOn(line -> SKU_SETTLED.equals(line.getSkuId()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getOrderedQuantity()).isEqualByComparingTo("10");
                    assertThat(line.getOpenQuantity()).isEqualByComparingTo("0");
                });
    }

    @Test
    @DisplayName("expected supply counts only what is still owed")
    void expectedSupplyCountsOnlyTheOutstanding() {
        seedMidLifecycleOrder();

        assertThat(expectedSupplyService.expectedIncomingQuantity(SKU_SETTLED.toString(), SITE, null))
                .as("a fully delivered line promises nothing further")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(expectedSupplyService.expectedIncomingQuantity(SKU_PARTIAL.toString(), SITE, null))
                .as("a part delivered line promises only the remainder")
                .isEqualByComparingTo("5");
        assertThat(expectedSupplyService.expectedIncomingQuantity(SKU_UNTOUCHED.toString(), SITE, null))
                .as("an untouched line promises all of it")
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a part delivered order still counts as open supply")
    void partiallyReceivedIsStillOpenSupply() {
        seedMidLifecycleOrder();

        // PARTIALLY_RECEIVED is in the open-supply set precisely so the remainder of a part
        // delivered order keeps counting. Dropping it at the first receipt would lose the rest of
        // every order the moment any of it arrived.
        assertThat(PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES).contains("PARTIALLY_RECEIVED");
        assertThat(expectedSupplyService.expectedIncomingQuantity(SKU_PARTIAL.toString(), SITE, null))
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("the order's outstanding quantities are attributed to its ship-to site")
    void supplyIsAttributedToTheShipToSite() {
        seedMidLifecycleOrder();
        UUID elsewhere = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b09");

        // Site attribution survives the move because it travels on the order, not on the line.
        // An order whose site was lost would promise stock to every branch at once.
        assertThat(expectedSupplyService.expectedIncomingQuantity(SKU_PARTIAL.toString(), elsewhere, null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the order's delivery date still bounds the horizon it is promised within")
    void deliveryDateStillBoundsTheHorizon() {
        seedMidLifecycleOrder();

        assertThat(expectedSupplyService.expectedIncomingQuantity(
                        SKU_PARTIAL.toString(),
                        SITE,
                        HORIZON.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .as("due inside the horizon, so it counts")
                .isEqualByComparingTo("5");

        assertThat(expectedSupplyService.expectedIncomingQuantity(
                        SKU_PARTIAL.toString(),
                        SITE,
                        LocalDate.of(2026, 8, 1)
                                .atStartOfDay(java.time.ZoneOffset.UTC)
                                .toInstant()))
                .as("due after the horizon, so it does not")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
