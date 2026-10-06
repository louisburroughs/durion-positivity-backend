package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.OrderCustomerRequiredException;
import com.positivity.order.internal.exception.OrderNotEditableException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
import com.positivity.order.internal.exception.WalkInNotAllowedException;
import com.positivity.order.internal.exception.WalkInNotPaidInFullException;
import com.positivity.order.internal.exception.WalkInUnavailableException;
import com.positivity.order.internal.service.SalesOrderService;
import com.positivity.order.internal.service.model.CheckoutResult;
import com.positivity.order.internal.service.model.SalesOrderSummary;
import com.positivity.order.internal.service.model.SetCartCustomerCommand;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CAP:550 S8 (#2506): the wire contract of the cart-customer command and of every code the
 * customer and walk-in rules add — status, {@code ApiError.code} and {@code fieldErrors}
 * (ADR-0017) — on the endpoints that answer them.
 */
@DisplayName("Sales-order endpoints: cart customer, walk-in codes (CAP:550 S8)")
@WebMvcTest(SalesOrderController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class SalesOrderWalkInControllerTest extends BaseControllerSliceTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID HOUSE_ACCOUNT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c03");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c04");

    private static final String EDIT = "order:order:edit";
    private static final String CHECKOUT = "order:order:checkout";

    @MockitoBean
    private SalesOrderService salesOrderService;

    private static SalesOrderSummary cart(UUID customerId, boolean walkIn, String displayName, String status) {
        return new SalesOrderSummary(
                ORDER_ID.toString(),
                "SO-TEST-2610-000001",
                null,
                null,
                customerId == null ? null : customerId.toString(),
                null,
                customerId == null ? null : "VALIDATED",
                "clerk-001",
                "terminal-001",
                status,
                new BigDecimal("78.1200"),
                BigDecimal.ZERO,
                new BigDecimal("6.2500"),
                new BigDecimal("84.3700"),
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                walkIn,
                displayName,
                List.of());
    }

    private MockHttpServletRequestBuilder putCustomer(String body) {
        return put("/v1/orders/carts/{orderId}/customer", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder checkout(String body) {
        return post("/v1/orders/{orderId}/checkout", ORDER_ID)
                .header("Idempotency-Key", "chk-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // ── PUT /v1/orders/carts/{orderId}/customer ──────────────────────────────

    @Test
    @DisplayName("PUT customer {walkIn:true} → 200 with walkIn and customerDisplayName")
    void setsWalkIn() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenReturn(cart(HOUSE_ACCOUNT_ID, true, "Walk-in customer", "DRAFT"));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.customerId").value(HOUSE_ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.walkIn").value(true))
                .andExpect(jsonPath("$.customerDisplayName").value("Walk-in customer"));

        verify(salesOrderService).setCartCustomer(ORDER_ID, new SetCartCustomerCommand(null, true, null));
    }

    @Test
    @DisplayName("PUT customer {customerId, vehicleId} → 200; the command carries both, walkIn false")
    void setsNamedCustomer() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenReturn(cart(CUSTOMER_ID, false, "Fleet Co", "DRAFT"));

        mockMvc.perform(withGatewayAuth(
                        putCustomer("{\"customerId\":\"%s\",\"vehicleId\":\"%s\"}".formatted(CUSTOMER_ID, VEHICLE_ID)),
                        EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walkIn").value(false))
                .andExpect(jsonPath("$.customerDisplayName").value("Fleet Co"));

        ArgumentCaptor<SetCartCustomerCommand> command = ArgumentCaptor.forClass(SetCartCustomerCommand.class);
        verify(salesOrderService).setCartCustomer(eq(ORDER_ID), command.capture());
        assertThat(command.getValue()).isEqualTo(new SetCartCustomerCommand(CUSTOMER_ID, false, VEHICLE_ID));
    }

    @Test
    @DisplayName("PUT customer needs order:order:edit")
    void setCustomerRequiresEditPermission() throws Exception {
        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), "order:order:view"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(salesOrderService);
    }

    @Test
    @DisplayName("PUT customer with both or neither choice → 400 ORDER_INVALID_ARGUMENT")
    void setCustomerInvalidArgument() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenThrow(
                        new SalesOrderRequestValidationException("Provide exactly one of customerId or walkIn: true"));

        mockMvc.perform(withGatewayAuth(putCustomer("{}"), EDIT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("PUT customer on an unknown order → 404 ORDER_NOT_FOUND")
    void setCustomerNotFound() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenThrow(new SalesOrderNotFoundException(ORDER_ID));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), EDIT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("PUT customer on an order that is not DRAFT → 409 ORDER_NOT_EDITABLE")
    void setCustomerNotEditable() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenThrow(new OrderNotEditableException(ORDER_ID, SalesOrderStatus.PENDING_PAYMENT));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), EDIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("PUT customer with an unknown customer → 422 ORDER_INVALID_CUSTOMER")
    void setCustomerInvalidCustomer() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenThrow(new InvalidCustomerException("Customer not found in CRM: " + CUSTOMER_ID));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"customerId\":\"%s\"}".formatted(CUSTOMER_ID)), EDIT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_CUSTOMER"));
    }

    @Test
    @DisplayName("PUT customer {walkIn:true} with no house account → 422 ORDER_WALK_IN_UNAVAILABLE")
    void setCustomerWalkInUnavailable() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any())).thenThrow(new WalkInUnavailableException());

        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), EDIT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("PUT customer on a workorder-linked cart → 422 ORDER_UNPROCESSABLE")
    void setCustomerWorkorderLocked() throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any()))
                .thenThrow(new SalesOrderUnprocessableException(
                        "Cannot change the customer: this cart carries a linked WORKORDER source"));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"customerId\":\"%s\"}".formatted(CUSTOMER_ID)), EDIT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_UNPROCESSABLE"));
    }

    @ParameterizedTest(name = "PUT customer walkIn refused for {0} → 422 ORDER_WALK_IN_NOT_ALLOWED")
    @EnumSource(
            value = WalkInNotAllowedException.Reason.class,
            names = {"DEPOSIT", "WORKORDER_LINK"})
    void setCustomerWalkInNotAllowed(WalkInNotAllowedException.Reason reason) throws Exception {
        when(salesOrderService.setCartCustomer(eq(ORDER_ID), any())).thenThrow(new WalkInNotAllowedException(reason));

        mockMvc.perform(withGatewayAuth(putCustomer("{\"walkIn\":true}"), EDIT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("walkIn"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(reason.name()));
    }

    // ── POST /v1/orders/{orderId}/checkout ───────────────────────────────────

    @Test
    @DisplayName("checkout passes tenderedAmount to the service; 201 PENDING_PAYMENT")
    void checkoutPassesTenderedAmount() throws Exception {
        when(salesOrderService.checkout(eq(ORDER_ID), eq("chk-1"), any(), any()))
                .thenReturn(
                        new CheckoutResult(cart(HOUSE_ACCOUNT_ID, true, "Walk-in customer", "PENDING_PAYMENT"), false));

        mockMvc.perform(withGatewayAuth(checkout("{\"tenderedAmount\":84.37}"), CHECKOUT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.walkIn").value(true));

        ArgumentCaptor<BigDecimal> tendered = ArgumentCaptor.forClass(BigDecimal.class);
        verify(salesOrderService).checkout(eq(ORDER_ID), eq("chk-1"), isNull(), tendered.capture());
        assertThat(tendered.getValue()).isEqualByComparingTo("84.37");
    }

    @Test
    @DisplayName("AC8: a replayed checkout answers 200 with the stored result")
    void checkoutReplayAnswers200() throws Exception {
        when(salesOrderService.checkout(eq(ORDER_ID), eq("chk-1"), any(), any()))
                .thenReturn(new CheckoutResult(cart(CUSTOMER_ID, false, "Fleet Co", "PENDING_PAYMENT"), true));

        mockMvc.perform(withGatewayAuth(checkout("{}"), CHECKOUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("AC1: checkout without a customer → 422 ORDER_CUSTOMER_REQUIRED")
    void checkoutCustomerRequired() throws Exception {
        when(salesOrderService.checkout(eq(ORDER_ID), any(), any(), any()))
                .thenThrow(new OrderCustomerRequiredException());

        mockMvc.perform(withGatewayAuth(checkout("{}"), CHECKOUT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_CUSTOMER_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Choose a customer before taking payment"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @ParameterizedTest(name = "AC4: walk-in checkout refused for {0} → 422 ORDER_WALK_IN_NOT_ALLOWED")
    @EnumSource(WalkInNotAllowedException.Reason.class)
    void checkoutWalkInNotAllowed(WalkInNotAllowedException.Reason reason) throws Exception {
        when(salesOrderService.checkout(eq(ORDER_ID), any(), any(), any()))
                .thenThrow(new WalkInNotAllowedException(reason));

        mockMvc.perform(withGatewayAuth(checkout("{\"tenderType\":\"ON_ACCOUNT\"}"), CHECKOUT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("walkIn"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(reason.name()));
    }

    @Test
    @DisplayName("AC3: walk-in partial tender → 422 ORDER_WALK_IN_NOT_PAID_IN_FULL naming the grand total")
    void checkoutWalkInNotPaidInFull() throws Exception {
        when(salesOrderService.checkout(eq(ORDER_ID), any(), any(), any()))
                .thenThrow(new WalkInNotPaidInFullException(new BigDecimal("84.3700"), new BigDecimal("80.00")));

        mockMvc.perform(withGatewayAuth(checkout("{\"tenderedAmount\":80.00}"), CHECKOUT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_NOT_PAID_IN_FULL"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("tenderedAmount"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must cover the grand total 84.37"));
    }

    // ── PATCH /v1/orders/carts/{orderId}/source ──────────────────────────────

    @Test
    @DisplayName("AC4: linking a WORKORDER to a walk-in cart → 422 ORDER_WALK_IN_NOT_ALLOWED (WORKORDER_LINK)")
    void linkSourceWalkInNotAllowed() throws Exception {
        when(salesOrderService.linkSource(eq(ORDER_ID), any(), any()))
                .thenThrow(new WalkInNotAllowedException(WalkInNotAllowedException.Reason.WORKORDER_LINK));

        mockMvc.perform(withGatewayAuth(
                        patch("/v1/orders/carts/{orderId}/source", ORDER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sourceType\":\"WORKORDER\",\"sourceId\":\"%s\"}".formatted(VEHICLE_ID)),
                        EDIT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_WALK_IN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("WORKORDER_LINK"));
    }
}
