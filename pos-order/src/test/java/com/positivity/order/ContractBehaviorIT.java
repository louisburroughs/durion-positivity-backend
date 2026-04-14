package com.positivity.order;

import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_APPLY;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_APPROVE;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_REJECT;
import static com.positivity.order.internal.security.PriceOverridePermissions.PRICE_OVERRIDE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Price Override Contract Behavior Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderLineRepository salesOrderLineRepository;

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
                .customerId("customer-test")
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
