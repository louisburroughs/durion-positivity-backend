package com.positivity.order;

import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_APPLY;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_APPROVE;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_REJECT;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.client.InvoiceRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.TaxPort;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Price Override and Checkout Contract Behavior Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderLineRepository salesOrderLineRepository;

    @Autowired
    private ExtCustomerRepository extCustomerRepository;

    @Autowired
    private ExtLocationRepository extLocationRepository;

    /** pos-tax and pos-invoice are other services; checkout reaches them through these ports. */
    @MockitoBean
    private TaxPort taxPort;

    @MockitoBean
    private InvoicingPort invoicingPort;

    private static final String CHECKOUT_AUTHORITIES = "order:order:checkout,order:order:edit,order:order:view";

    /** The cart's single line is 78.12; this much tax makes the final grand total 84.37. */
    private static final BigDecimal LINE_TAX = new BigDecimal("6.2500");

    @BeforeEach
    void resetCustomerReplicaAndStubDownstreams() {
        extCustomerRepository.deleteAll();
        when(taxPort.calculate(any())).thenAnswer(invocation -> {
            TaxCalculationRequest request = invocation.getArgument(0);
            return Optional.of(TaxCalculationResponse.builder()
                    .lineItemTaxes(request.getLineItems().stream()
                            .map(item -> TaxCalculationResponse.LineItemTax.builder()
                                    .lineItemId(item.getLineItemId())
                                    .taxAmount(LINE_TAX)
                                    .build())
                            .toList())
                    .build());
        });
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenReturn(
                        new InvoiceRef(UUID.randomUUID(), "INV-CONTRACT-1", "DRAFT", new BigDecimal("84.37"), false));
    }

    @Override
    protected String defaultAuthorities() {
        return String.join(
                ",",
                PRICE_OVERRIDE_VIEW,
                PRICE_OVERRIDE_APPLY,
                PRICE_OVERRIDE_APPROVE,
                PRICE_OVERRIDE_REJECT,
                "ROLE_MANAGER");
    }

    @Test
    @DisplayName("CP-001: Apply override with small discount returns 201 APPROVED")
    void testApplyPriceOverride_AutoApproved() throws Exception {
        ApplyFixture fixture = createApplyFixture("100.00");
        String payload =
                createApplyPayload(fixture.orderId(), fixture.orderLineId(), fixture.productId(), "100.00", "95.00");

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-apply-auto-approved")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overrideId").exists())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.requiresApproval").value(false));
    }

    @Test
    @DisplayName("CP-002: Apply override with large discount returns 201 PENDING_APPROVAL")
    void testApplyPriceOverride_RequiresApproval() throws Exception {
        ApplyFixture fixture = createApplyFixture("1000.00");
        String payload =
                createApplyPayload(fixture.orderId(), fixture.orderLineId(), fixture.productId(), "1000.00", "800.00");

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-apply-pending")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overrideId").exists())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.requiresApproval").value(true));
    }

    @Test
    @DisplayName("CP-003: Get override by ID returns 200")
    void testGetOverride_HappyPath() throws Exception {
        ApplyFixture fixture = createApplyFixture("1000.00");
        String orderId = fixture.orderId();
        String payload = createApplyPayload(orderId, fixture.orderLineId(), fixture.productId(), "1000.00", "800.00");

        MvcResult create = mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-get-create")))
                .andExpect(status().isCreated())
                .andReturn();

        String overrideId = readField(create, "overrideId");

        mockMvc.perform(withGatewayAuth(get("/v1/orders/price-overrides/{overrideId}", overrideId)
                        .header("X-Correlation-Id", "test-get-fetch")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideId").value(overrideId))
                .andExpect(jsonPath("$.orderId").value(orderId));
    }

    @Test
    @DisplayName("CP-004: Get overrides by orderId filter returns matching records")
    void testGetOverridesByOrderId_HappyPath() throws Exception {
        ApplyFixture fixture = createApplyFixture("500.00");
        String orderId = fixture.orderId();
        String payload = createApplyPayload(orderId, fixture.orderLineId(), fixture.productId(), "500.00", "450.00");

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-list-create")))
                .andExpect(status().isCreated());

        MvcResult listResult = mockMvc.perform(withGatewayAuth(get("/v1/orders/price-overrides")
                        .queryParam("orderId", orderId)
                        .header("X-Correlation-Id", "test-list-fetch")))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = listResult.getResponse().getContentAsString();
        assertThat(responseBody).contains(orderId);
    }

    @Test
    @DisplayName("CP-005: Pending approvals endpoint returns pending overrides")
    void testGetPendingApprovals_HappyPath() throws Exception {
        ApplyFixture fixture = createApplyFixture("1200.00");
        String pendingPayload =
                createApplyPayload(fixture.orderId(), fixture.orderLineId(), fixture.productId(), "1200.00", "900.00");

        MvcResult create = mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pendingPayload)
                        .header("X-Correlation-Id", "test-pending-create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();

        String pendingOverrideId = readField(create, "overrideId");

        MvcResult pendingList = mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/price-overrides/pending").header("X-Correlation-Id", "test-pending-fetch")))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = pendingList.getResponse().getContentAsString();
        assertThat(responseBody).contains(pendingOverrideId);
    }

    @Test
    @DisplayName("CP-006: Approve pending override returns 200 APPROVED")
    void testApproveOverride_HappyPath() throws Exception {
        ApplyFixture fixture = createApplyFixture("1000.00");
        String payload =
                createApplyPayload(fixture.orderId(), fixture.orderLineId(), fixture.productId(), "1000.00", "800.00");

        MvcResult create = mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-approve-create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();

        String overrideId = readField(create, "overrideId");

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides/{overrideId}/approve", overrideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comments\":\"approved in test\"}")
                        .header("X-Correlation-Id", "test-approve-call")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByUserId").value("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    @DisplayName("CP-007: Reject pending override returns 200 REJECTED")
    void testRejectOverride_HappyPath() throws Exception {
        ApplyFixture fixture = createApplyFixture("1000.00");
        String payload =
                createApplyPayload(fixture.orderId(), fixture.orderLineId(), fixture.productId(), "1000.00", "800.00");

        MvcResult create = mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "test-reject-create")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();

        String overrideId = readField(create, "overrideId");

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides/{overrideId}/reject", overrideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"exceeds policy\",\"comments\":\"reject in test\"}")
                        .header("X-Correlation-Id", "test-reject-call")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("exceeds policy"));
    }

    @Test
    @DisplayName("VE-001: Missing required fields in apply request returns 400")
    void testApplyPriceOverride_MissingRequiredField() throws Exception {
        String invalidPayload = """
                                {
                                  "orderLineId": "%s",
                                  "productId": "%s",
                                  "originalPrice": 100.00,
                                  "overridePrice": 90.00,
                                  "reasonCode": "CUSTOMER_LOYALTY"
                                }
                                """.formatted(newUuid(), newUuid());

        mockMvc.perform(withGatewayAuth(post("/v1/orders/price-overrides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .header("X-Correlation-Id", "test-apply-invalid")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CO-001 (CAP:550 S8 AC1): checkout without a customer returns 422 ORDER_CUSTOMER_REQUIRED")
    void testCheckout_WithoutCustomer_Refused() throws Exception {
        UUID orderId = createCheckoutCart(null);

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/checkout", orderId)
                                .header("Idempotency-Key", "co-001-" + orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tenderedAmount\":500.00}")
                                .header("X-Correlation-Id", "test-checkout-no-customer"),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_CUSTOMER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Choose a customer before taking payment"))
                .andExpect(jsonPath("$.correlationId").value("test-checkout-no-customer"));

        SalesOrder after = salesOrderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
        assertThat(after.getCustomerId())
                .as("the house account is never assigned")
                .isNull();
        assertThat(after.getInvoiceId()).isNull();
        assertThat(after.getCheckoutIdempotencyKey()).isNull();
        verify(invoicingPort, never()).createInvoiceForOrder(any());
    }

    @Test
    @DisplayName(
            "CO-002 (CAP:550 S8 AC2/AC3): walk-in partial tender is refused naming the total; paid in full checks out")
    void testCheckout_WalkInMustBePaidInFull() throws Exception {
        UUID houseAccountId = provisionHouseAccount();
        UUID orderId = createCheckoutCart(null);

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/carts/{orderId}/customer", orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"walkIn\":true}"),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(houseAccountId.toString()))
                .andExpect(jsonPath("$.walkIn").value(true))
                .andExpect(jsonPath("$.customerDisplayName").value("Walk-in customer"));

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/checkout", orderId)
                                .header("Idempotency-Key", "co-002a-" + orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tenderedAmount\":80.00}"),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_NOT_PAID_IN_FULL"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("tenderedAmount"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must cover the grand total 84.37"));

        SalesOrder refused = salesOrderRepository.findById(orderId).orElseThrow();
        assertThat(refused.getStatus()).as("the cart stays DRAFT").isEqualTo(SalesOrderStatus.DRAFT);
        assertThat(refused.getCheckoutIdempotencyKey()).isNull();
        verify(invoicingPort, never()).createInvoiceForOrder(any());

        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/checkout", orderId)
                                .header("Idempotency-Key", "co-002b-" + orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tenderedAmount\":84.37}"),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.walkIn").value(true))
                .andExpect(jsonPath("$.grandTotal").value(84.37));

        ArgumentCaptor<OrderInvoiceCreationRequest> invoice =
                ArgumentCaptor.forClass(OrderInvoiceCreationRequest.class);
        verify(invoicingPort).createInvoiceForOrder(invoice.capture());
        assertThat(invoice.getValue().getCustomerId()).isEqualTo(houseAccountId);

        // AC8: the same key replays the stored result with 200, re-evaluating nothing — even
        // though this replay declares no tender at all.
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/checkout", orderId).header("Idempotency-Key", "co-002b-" + orderId),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName(
            "CO-003 (CAP:550 S8 AC9): Walk-in with no house account in the replica returns 422 ORDER_WALK_IN_UNAVAILABLE")
    void testSetCartCustomer_WalkInUnavailable() throws Exception {
        // A party named and numbered like the walk-in customer, but without the flag, is not one.
        extCustomerRepository.save(ExtCustomer.builder()
                .partyId(UUID.randomUUID())
                .status("ACTIVE")
                .displayName("Walk-in customer")
                .partyType("COMMERCIAL")
                .requirementsMet(true)
                .aggregateVersion(1)
                .syncedAt(Instant.now())
                .build());
        UUID orderId = createCheckoutCart(null);

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/carts/{orderId}/customer", orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"walkIn\":true}"),
                        CHECKOUT_AUTHORITIES))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_UNAVAILABLE"));

        assertThat(salesOrderRepository.findById(orderId).orElseThrow().getCustomerId())
                .isNull();
    }

    private UUID provisionHouseAccount() {
        UUID houseAccountId = UUID.randomUUID();
        extCustomerRepository.save(ExtCustomer.builder()
                .partyId(houseAccountId)
                .status("ACTIVE")
                .displayName("Walk-in customer")
                .partyType("COMMERCIAL")
                .requirementsMet(true)
                .houseAccount(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE)
                .aggregateVersion(1)
                .syncedAt(Instant.now())
                .build());
        return houseAccountId;
    }

    /** A DRAFT cart at a taxable location with one manually priced line of 78.12. */
    private UUID createCheckoutCart(UUID customerId) {
        UUID locationId = UUID.randomUUID();
        ExtLocation location = new ExtLocation();
        location.setLocationId(locationId);
        location.setActive(true);
        location.setPostalCode("78701");
        location.setCountry("US");
        location.setAggregateVersion(1);
        location.setSyncedAt(Instant.now());
        extLocationRepository.save(location);

        UUID orderId = UUID.randomUUID();
        SalesOrder order = SalesOrder.builder()
                .orderId(orderId)
                .orderNumber("SO-CO-" + orderId)
                .locationId(locationId)
                .customerId(customerId)
                .clerkId("clerk-test")
                .terminalId("terminal-test")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("78.12"))
                .createdBy("contract-test")
                .updatedBy("contract-test")
                .build();
        salesOrderRepository.save(order);
        salesOrderLineRepository.save(SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(order)
                .itemSku("SKU-CO-" + orderId)
                .itemDescription("Contract Test Item")
                .quantity(1)
                .unitPrice(new BigDecimal("78.12"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.MANUAL)
                .build());
        return orderId;
    }

    private String createApplyPayload(
            String orderId, String orderLineId, String productId, String originalPrice, String overridePrice) {
        return """
                                {
                                  "orderId": "%s",
                                  "orderLineId": "%s",
                                  "productId": "%s",
                                  "originalPrice": %s,
                                  "overridePrice": %s,
                                  "reasonCode": "CUSTOMER_LOYALTY",
                                  "justification": "contract test"
                                }
                                """.formatted(orderId, orderLineId, productId, originalPrice, overridePrice);
    }

    private String newUuid() {
        return UUID.randomUUID().toString();
    }

    private String readField(MvcResult result, String field) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get(field)
                .asString();
    }

    private ApplyFixture createApplyFixture(String unitPriceAmount) {
        UUID orderId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        SalesOrder order = SalesOrder.builder()
                .orderId(orderId)
                .customerId(UUID.randomUUID())
                .clerkId("clerk-test")
                .terminalId("terminal-test")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal(unitPriceAmount))
                .createdBy("contract-test")
                .updatedBy("contract-test")
                .build();
        salesOrderRepository.save(order);

        SalesOrderLine line = SalesOrderLine.builder()
                .orderLineId(orderLineId)
                .order(order)
                .itemSku("SKU-" + orderLineId)
                .itemDescription("Contract Test Item")
                .quantity(1)
                .unitPrice(new BigDecimal(unitPriceAmount))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.MANUAL)
                .build();
        salesOrderLineRepository.save(line);

        return new ApplyFixture(orderId.toString(), orderLineId.toString(), productId.toString());
    }

    private record ApplyFixture(String orderId, String orderLineId, String productId) {}
}
