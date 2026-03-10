package com.positivity.order;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests for {@code SalesOrderController} covering Story #21
 * acceptance criteria.
 *
 * <p>
 * Phase: GREEN – Story #21 implemented; happy-path and validation tests verify
 * expected API behavior.
 *
 * <p>
 * ADR-0017 compliance: each test asserts the expected HTTP status code per the
 * API contract.
 * ADR-0018 compliance: actor information is expected to be derived from
 * security context via
 * gateway-injected {@code X-User} header, not from the request body.
 *
 * <p>
 * Issue: #21
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SalesOrder Controller HTTP Contract Tests – Story #21")
class SalesOrderControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SalesOrderService salesOrderService;

    @BeforeEach
    void mockServiceDefaults() {
        SalesOrderSummary fakeOrder = new SalesOrderSummary(
                UUID.randomUUID().toString(),
                null,
                null,
                "clerk-001",
                "terminal-001",
                "DRAFT",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                List.of());
        SalesOrderLineSummary fakeLine = new SalesOrderLineSummary(
                UUID.randomUUID().toString(),
                "ABC-123",
                "Test Product",
                1,
                BigDecimal.TEN,
                "AVAILABLE",
                "PRICING_SERVICE",
                null,
                null,
                null,
                null);

        when(salesOrderService.createCart(any(), any(), any(), any())).thenReturn(fakeOrder);
        when(salesOrderService.addItem(any(), any(), anyInt(), any(), any())).thenReturn(fakeLine);
        when(salesOrderService.getOrder(any())).thenReturn(fakeOrder);
        when(salesOrderService.updateItemQuantity(any(), any(), anyInt())).thenReturn(fakeLine);
        doNothing().when(salesOrderService).removeItem(any(), any());
        when(salesOrderService.linkSource(any(), any(), any())).thenReturn(fakeOrder);
    }

    // -----------------------------------------------------------------------
    // SC-001: Create cart — POST /v1/orders/carts → 201 Created
    // -----------------------------------------------------------------------

    /**
     * SC-001: Happy-path cart creation with valid clerkId and terminalId must
     * return 201 Created.
     */
    @Test
    @DisplayName("SC-001: POST /v1/orders/carts with valid body returns 201 Created")
    void createCart_whenValidRequest_thenReturns201Created() throws Exception {
        String body = """
                {
                  "clerkId": "clerk-001",
                  "terminalId": "terminal-001"
                }
                """;

        // Issue #21: Actor (clerkId) is provided via request but will ultimately be
        // sourced from
        // security context (ADR-0018). The test asserts the HTTP contract outcome.
        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isCreated());
    }

    /**
     * SC-001b: Anonymous cart creation (no customerId) must also return 201
     * Created.
     */
    @Test
    @DisplayName("SC-001b: POST /v1/orders/carts without customerId (anonymous cart) returns 201 Created")
    void createCart_whenAnonymousCart_thenReturns201Created() throws Exception {
        String body = """
                {
                  "clerkId": "clerk-002",
                  "terminalId": "terminal-001",
                  "customerId": null
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isCreated());
    }

    // -----------------------------------------------------------------------
    // SC-002: Create cart — validation failure → 400 Bad Request
    // -----------------------------------------------------------------------

    /**
     * SC-002: Missing required {@code clerkId} field must return 400 Bad Request.
     *
     * <p>
     * Bean Validation guard fires before the service is invoked, so this test may
     * pass even in RED phase — demonstrating the validation contract is already in
     * place.
     */
    @Test
    @DisplayName("SC-002: POST /v1/orders/carts missing clerkId returns 400 Bad Request")
    void createCart_whenClerkIdMissing_thenReturns400BadRequest() throws Exception {
        String body = """
                {
                  "terminalId": "terminal-001"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isBadRequest());
    }

    /**
     * SC-002b: Missing required {@code terminalId} field must return 400 Bad
     * Request.
     */
    @Test
    @DisplayName("SC-002b: POST /v1/orders/carts missing terminalId returns 400 Bad Request")
    void createCart_whenTerminalIdMissing_thenReturns400BadRequest() throws Exception {
        String body = """
                {
                  "clerkId": "clerk-001"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // SC-003: Add item — POST /v1/orders/carts/{orderId}/items → 201 Created
    // -----------------------------------------------------------------------

    /**
     * SC-003: Adding a valid item to an existing cart must return 201 Created.
     */
    @Test
    @DisplayName("SC-003: POST /v1/orders/carts/{orderId}/items with valid body returns 201 Created")
    void addItem_whenValidRequest_thenReturns201Created() throws Exception {
        UUID orderId = UUID.randomUUID();
        String body = """
                {
                  "itemSku": "ABC-123",
                  "quantity": 2
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts/{orderId}/items", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isCreated());
    }

    /**
     * SC-003b: Adding an item with a missing {@code itemSku} must return 400 Bad
     * Request.
     */
    @Test
    @DisplayName("SC-003b: POST /v1/orders/carts/{orderId}/items missing itemSku returns 400 Bad Request")
    void addItem_whenItemSkuMissing_thenReturns400BadRequest() throws Exception {
        UUID orderId = UUID.randomUUID();
        String body = """
                {
                  "quantity": 1
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts/{orderId}/items", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isBadRequest());
    }

    /**
     * SC-003c: Adding an item with quantity less than 1 must return 400 Bad
     * Request.
     */
    @Test
    @DisplayName("SC-003c: POST /v1/orders/carts/{orderId}/items with quantity 0 returns 400 Bad Request")
    void addItem_whenQuantityIsZero_thenReturns400BadRequest() throws Exception {
        UUID orderId = UUID.randomUUID();
        String body = """
                {
                  "itemSku": "ABC-123",
                  "quantity": 0
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts/{orderId}/items", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // SC-004: Get order — GET /v1/orders/carts/{orderId} → 200 OK
    // -----------------------------------------------------------------------

    /**
     * SC-004: Retrieving a sales order by ID must return 200 OK.
     */
    @Test
    @DisplayName("SC-004: GET /v1/orders/carts/{orderId} returns 200 OK")
    void getOrder_whenOrderExists_thenReturns200Ok() throws Exception {
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(withGatewayAuth(get("/v1/orders/carts/{orderId}", orderId)))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // SC-005: Update item quantity (PUT /v1/orders/carts/{orderId}/items/{lineId})
    // returns 200 OK
    // -----------------------------------------------------------------------

    /**
     * SC-005: Updating an item's quantity must return 200 OK.
     */
    @Test
    @DisplayName("SC-005: PUT /v1/orders/carts/{orderId}/items/{lineId} with valid quantity returns 200 OK")
    void updateItemQuantity_whenValidRequest_thenReturns200Ok() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String body = """
                {
                  "quantity": 3
                }
                """;

        mockMvc.perform(withGatewayAuth(put("/v1/orders/carts/{orderId}/items/{lineId}", orderId, lineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // SC-006: Remove item — DELETE /v1/orders/carts/{orderId}/items/{lineId} → 204
    // No Content
    // -----------------------------------------------------------------------

    /**
     * SC-006: Removing an item from the cart must return 204 No Content.
     */
    @Test
    @DisplayName("SC-006: DELETE /v1/orders/carts/{orderId}/items/{lineId} returns 204 No Content")
    void removeItem_whenItemExists_thenReturns204NoContent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        mockMvc.perform(withGatewayAuth(delete("/v1/orders/carts/{orderId}/items/{lineId}", orderId, lineId)))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------------
    // SC-007: Unauthenticated request — no auth headers
    // -----------------------------------------------------------------------

    /**
     * SC-007: An unauthenticated request to the order cart endpoint must return 401
     * Unauthorized.
     *
     * <p>
     * This test executes without gateway auth headers and asserts the gateway
     * security contract directly in test profile (ADR-0011, ADR-0014).
     */
    @Test
    @DisplayName("SC-007: Request without auth headers returns 401 Unauthorized (gateway contract)")
    void createCart_whenUnauthenticated_thenReturns401() throws Exception {
        String body = """
                {
                  "clerkId": "clerk-001",
                  "terminalId": "terminal-001"
                }
                """;

        // withGatewayAuth is intentionally not used here to simulate an
        // unauthenticated call.
        mockMvc.perform(post("/v1/orders/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnauthorized());
    }

    /**
     * SC-008: Linking a source to an existing cart must return 200 OK and order
     * response body.
     */
    @Test
    @DisplayName("SC-008: PATCH /v1/orders/carts/{orderId}/source with valid body returns 200 OK")
    void linkSource_whenValidRequest_thenReturns200WithOrderBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        SalesOrderSummary linkedOrder = new SalesOrderSummary(
                orderId.toString(),
                null,
                null,
                "clerk-001",
                "terminal-001",
                "DRAFT",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                List.of());

        when(salesOrderService.linkSource(any(UUID.class), anyString(), anyString())).thenReturn(linkedOrder);

        // SC-008
        mockMvc.perform(withGatewayAuth(patch("/v1/orders/carts/{orderId}/source", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" +
                        "\"sourceType\":\"WORKORDER\"," +
                        "\"sourceId\":\"WO-001\"" +
                        "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").exists());
    }

    /**
     * SC-009: Adding an item without required permission must return 403 Forbidden.
     */
    @Test
    @DisplayName("SC-009: POST /v1/orders/carts/{orderId}/items when access denied returns 403 Forbidden")
    void addItem_whenAccessDenied_thenReturns403() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(salesOrderService.addItem(any(), any(), anyInt(), any(), any()))
                .thenThrow(new AccessDeniedException("Insufficient permissions"));

        // SC-009
        mockMvc.perform(withGatewayAuth(post("/v1/orders/carts/{orderId}/items", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" +
                        "\"itemSku\":\"ABC-123\"," +
                        "\"quantity\":1" +
                        "}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }
}
