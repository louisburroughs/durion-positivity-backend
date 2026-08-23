package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.service.PurchaseOrderService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Pins that creating a purchase order through the service - the path the REST endpoint takes -
 * fills the audited {@code createdBy} column.
 *
 * <p>Nothing else covered it. {@code createdBy} is {@code @CreatedBy} with
 * {@code @Column(nullable = false)}, and the only other test that persists an order
 * ({@code RequestedPurchaseOrderIdentityIT}) sets the value on the builder by hand, so the
 * auditing path it depends on was never exercised. With {@code @EnableJpaAuditing} declared but no
 * {@code AuditorAware} bean, Spring Data leaves the field null and the insert dies on the
 * constraint - which is what every POST /v1/orders/purchase-orders returned on alpha the day this
 * service was first deployed.
 */
@SpringBootTest
@ActiveProfiles("test")
// The test schema comes from ddl-auto, which creates sequences only for @GeneratedValue
// mappings. PO numbers are drawn from a standalone sequence that V14 creates and Hibernate
// therefore does not - so it is declared here rather than the create path being untestable.
@Sql(
        statements = "CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("A purchase order created through the service records who created it")
class PurchaseOrderAuditingIT {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d01");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d02");
    private static final UUID SHIP_TO_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d03");

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Test
    @DisplayName("createPurchaseOrder persists, and createdBy is populated by auditing")
    void createPopulatesCreatedBy() {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(1);
        line.setSkuId(SKU_ID);
        line.setDescription("audited line");
        line.setQuantity(new BigDecimal("10"));
        line.setUnitCostMinor(100L);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setVendorId(VENDOR_ID);
        request.setCurrency("USD");
        request.setPoDate(LocalDate.of(2026, 1, 5));
        request.setExpectedDeliveryDate(LocalDate.of(2026, 1, 12));
        request.setShipToLocationId(SHIP_TO_LOCATION_ID);
        request.setRequestedBy("itest");
        request.setLines(List.of(line));

        UUID created =
                purchaseOrderService.createPurchaseOrder(request, "actor-1").getPurchaseOrderId();

        // The exact value, not merely non-null: this test has no authenticated principal, so
        // auditing is the only thing that can produce "system". A non-null assertion would still
        // pass if someone set createdBy on the builder or gave the column a default, which is
        // precisely the shape of the bug it exists to catch.
        assertThat(purchaseOrderRepository.findById(created))
                .as("the order must persist rather than fail the created_by NOT NULL constraint")
                .get()
                .extracting(po -> po.getCreatedBy())
                .as("AuditorAware must supply createdBy, falling back to \"system\" with no principal")
                .isEqualTo("system");
    }
}
