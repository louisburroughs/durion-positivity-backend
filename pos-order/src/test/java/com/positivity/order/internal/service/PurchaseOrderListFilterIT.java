package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins that every filter on {@code GET /v1/orders/purchase-orders} reaches the database (#1804).
 *
 * <p>{@code currency} and {@code locationId} used to be applied to the page after it was fetched,
 * with the envelope built from the unfiltered total. A caller asking for {@code ?currency=USD&size=N}
 * got however many of that page's rows happened to be USD, matching rows on later pages were
 * unreachable by any amount of paging, and {@code totalElements} claimed the missing rows existed.
 * These tests seed more matching rows than one page holds, interleaved with non-matching rows, so
 * that in-memory filtering would produce a short page and a wrong total.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("listPurchaseOrders applies every filter in the query, not to the fetched page (#1804)")
class PurchaseOrderListFilterIT {

    private static final UUID VENDOR_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");
    private static final UUID VENDOR_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e02");
    private static final UUID LOCATION_1 = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e03");
    private static final UUID LOCATION_2 = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e04");
    private static final int PAGE_SIZE = 5;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    private int sequence;

    /**
     * Twenty orders, alternating USD/CAD and location 1/2 in lock-step with the poNumber order, so
     * that any page of the unfiltered set holds only half of any single currency or location. Ten
     * are USD (five per vendor); of the USD orders, five ship to location 1.
     */
    @BeforeEach
    void seed() {
        for (int i = 0; i < 20; i++) {
            UUID vendor = i < 10 ? VENDOR_A : VENDOR_B;
            String currency = i % 2 == 0 ? "USD" : "CAD";
            UUID location = i % 2 == 0 ? LOCATION_1 : LOCATION_2;
            PurchaseOrderStatus status = i % 4 == 0 ? PurchaseOrderStatus.APPROVED : PurchaseOrderStatus.DRAFT;
            persist(vendor, status, currency, location);
        }
        entityManager.flush();
        entityManager.clear();
    }

    private void persist(UUID vendorId, PurchaseOrderStatus status, String currency, UUID shipToLocationId) {
        sequence++;
        entityManager.persist(PurchaseOrderEntity.builder()
                .vendorId(vendorId)
                .poNumber(String.format("LIST-%04d", sequence))
                .status(status)
                .versionNumber(1)
                .currency(currency)
                .subtotalMinor(1_000L)
                .taxMinor(0L)
                .grandTotalMinor(1_000L)
                .openBalanceMinor(1_000L)
                .shipToLocationId(shipToLocationId)
                .createdBy("itest")
                .build());
    }

    private Page<PurchaseOrderResponse> list(ListPurchaseOrdersRequest filter, int pageNumber) {
        return purchaseOrderService.listPurchaseOrders(
                filter, PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("poNumber")));
    }

    @Test
    @DisplayName("a currency-filtered page is full and its total counts only matching rows")
    void currencyFilterPagesOverMatchingRows() {
        ListPurchaseOrdersRequest filter = new ListPurchaseOrdersRequest();
        filter.setCurrency("USD");

        Page<PurchaseOrderResponse> first = list(filter, 0);

        // In-memory filtering of the first unfiltered page (LIST-0001..0005) would yield three rows
        // and claim a total of twenty.
        assertThat(first.getContent()).hasSize(PAGE_SIZE);
        assertThat(first.getTotalElements()).isEqualTo(10);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent())
                .allSatisfy(po -> assertThat(po.getCurrency()).isEqualTo("USD"));
        assertThat(first.getContent())
                .extracting(PurchaseOrderResponse::getPoNumber)
                .as("rows beyond the first unfiltered page must be reachable")
                .contains("LIST-0007", "LIST-0009");

        Page<PurchaseOrderResponse> second = list(filter, 1);
        assertThat(second.getContent()).hasSize(PAGE_SIZE);
        assertThat(second.getContent())
                .extracting(PurchaseOrderResponse::getPoNumber)
                .containsExactly("LIST-0011", "LIST-0013", "LIST-0015", "LIST-0017", "LIST-0019");
    }

    @Test
    @DisplayName("a location-filtered page is full and its total counts only matching rows")
    void locationFilterPagesOverMatchingRows() {
        ListPurchaseOrdersRequest filter = new ListPurchaseOrdersRequest();
        filter.setLocationId(LOCATION_2);

        Page<PurchaseOrderResponse> first = list(filter, 0);

        assertThat(first.getContent()).hasSize(PAGE_SIZE);
        assertThat(first.getTotalElements()).isEqualTo(10);
        assertThat(first.getContent())
                .allSatisfy(po -> assertThat(po.getShipToLocationId()).isEqualTo(LOCATION_2));
        assertThat(first.getContent())
                .extracting(PurchaseOrderResponse::getPoNumber)
                .containsExactly("LIST-0002", "LIST-0004", "LIST-0006", "LIST-0008", "LIST-0010");
    }

    @Test
    @DisplayName("currency and locationId compose with vendorId and status instead of being dropped")
    void allFourFiltersCompose() {
        ListPurchaseOrdersRequest filter = new ListPurchaseOrdersRequest();
        filter.setVendorId(VENDOR_A);
        filter.setStatus(PurchaseOrderStatus.DRAFT);
        filter.setCurrency("USD");
        filter.setLocationId(LOCATION_1);

        Page<PurchaseOrderResponse> page = list(filter, 0);

        // Vendor A has ten orders; five are USD/location 1 (LIST-0001/3/5/7/9), and of those
        // LIST-0001, LIST-0005 and LIST-0009 are APPROVED. A total of five (vendor and status only)
        // would mean currency/location were dropped when combined.
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(PurchaseOrderResponse::getPoNumber)
                .containsExactly("LIST-0003", "LIST-0007");
        assertThat(page.getContent()).allSatisfy(po -> {
            assertThat(po.getVendorId()).isEqualTo(VENDOR_A);
            assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(po.getCurrency()).isEqualTo("USD");
            assertThat(po.getShipToLocationId()).isEqualTo(LOCATION_1);
        });
    }

    @Test
    @DisplayName("currency matches case-insensitively, as the in-memory filter it replaces did")
    void currencyMatchIsCaseInsensitive() {
        ListPurchaseOrdersRequest filter = new ListPurchaseOrdersRequest();
        filter.setCurrency("usd");

        Page<PurchaseOrderResponse> page = list(filter, 0);

        assertThat(page.getTotalElements()).isEqualTo(10);
        assertThat(page.getContent()).hasSize(PAGE_SIZE);
        assertThat(page.getContent())
                .allSatisfy(po -> assertThat(po.getCurrency()).isEqualTo("USD"));
    }

    @Test
    @DisplayName("no filters returns the whole population, paged")
    void noFiltersPagesEverything() {
        Page<PurchaseOrderResponse> page = list(new ListPurchaseOrdersRequest(), 0);

        assertThat(page.getTotalElements()).isEqualTo(20);
        assertThat(page.getContent()).hasSize(PAGE_SIZE);
    }
}
