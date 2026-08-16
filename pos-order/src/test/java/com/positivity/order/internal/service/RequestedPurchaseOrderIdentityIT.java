package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity a requester chooses is the identity the order gets, and only one order can have it
 * (CAP-320 #1334).
 *
 * <h2>Why this is checked against a real database</h2>
 *
 * Two things have to be true for a replenishment conversion to produce exactly one order, and
 * neither is visible in a unit test with a mocked repository.
 *
 * <p>The first is that a pre-supplied id survives. {@code purchaseOrderId} carries
 * {@code @GeneratedValue}, which ordinarily means Hibernate mints the value — if it did so here,
 * the requester would stamp its suggestions with an id no order actually has, and the two would
 * never be reconcilable. It does not, because the UUIDv7 generator returns the assigned value when
 * one is present. That is a property of a generator two modules away, so it is pinned here rather
 * than assumed.
 *
 * <p>The second is that a repeat insert fails rather than overwrites. That is the whole guarantee:
 * a check-then-act races, and the only thing that does not is the primary key.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("A requested purchase order keeps the identity it was asked for (#1334)")
class RequestedPurchaseOrderIdentityIT {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    private static PurchaseOrderEntity order(UUID purchaseOrderId, String poNumber) {
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(purchaseOrderId)
                .poNumber(poNumber)
                .vendorId(VENDOR_ID)
                .status(PurchaseOrderStatus.DRAFT)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(1_000L)
                .taxMinor(0L)
                .grandTotalMinor(1_000L)
                .openBalanceMinor(1_000L)
                .createdBy("pos-inventory")
                .lines(new ArrayList<>())
                .build();
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineNumber(1)
                .skuId(SKU_ID)
                .description("requested line")
                .quantityDecimal(BigDecimal.TEN)
                .openQuantityDecimal(BigDecimal.TEN)
                .unitCostMinor(100L)
                .lineTotalMinor(1_000L)
                .purchaseOrder(po)
                .build();
        po.setLines(List.of(line));
        return po;
    }

    @Test
    @Transactional
    @DisplayName("the requester's id is the order's id, not one Hibernate minted")
    void suppliedIdSurvivesPersist() {
        UUID chosen = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c03");

        entityManager.persist(order(chosen, "REQ-0001"));
        entityManager.flush();
        entityManager.clear();

        // Had @GeneratedValue won, the order would exist under an id nobody asked for and the
        // suggestion that requested it would point at nothing.
        assertThat(purchaseOrderRepository.findById(chosen))
                .as("the order must be findable at exactly the id the requester chose")
                .isPresent();
    }

    @Test
    @Transactional
    @DisplayName("a second order at the same id is refused, not merged into the first")
    void duplicateIdIsRefused() {
        UUID chosen = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c04");

        entityManager.persist(order(chosen, "REQ-0002"));
        entityManager.flush();
        entityManager.clear();

        // This is the guarantee. save() would have merged — quietly replacing the first order's
        // contents with the second's, so a duplicated conversion would look like one order that
        // had simply changed. persist() refuses.
        assertThatThrownBy(() -> {
                    entityManager.persist(order(chosen, "REQ-0003"));
                    entityManager.flush();
                })
                .isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    @DisplayName("an order created without an id still gets one")
    void generatedIdStillWorksForOrdinaryCreation() {
        PurchaseOrderEntity po = order(null, "REQ-0004");

        entityManager.persist(po);
        entityManager.flush();

        // The assigned-id path must not have cost the ordinary one: a purchase order keyed by hand
        // through the API supplies no id and still needs a UUIDv7.
        assertThat(po.getPurchaseOrderId()).isNotNull();
    }
}
