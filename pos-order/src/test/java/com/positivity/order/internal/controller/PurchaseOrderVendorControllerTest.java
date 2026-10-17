package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.service.PurchaseOrderVendorService;
import com.positivity.order.internal.service.SupplierVendorGuard;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.ReplicationPendingException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The purchase-order vendor read's HTTP contract (CAP:550, #2680): {@code order:purchase_order:view} gates both
 * endpoints, the item carries exactly four fields, a missing copy row is 503 with {@code Retry-After} and never 404,
 * an INACTIVE vendor is 200, a malformed id is 400, and a bad sort is 400 {@code PURCHASE_ORDER_BAD_REQUEST} through
 * {@link PurchaseOrderExceptionHandler}.
 */
@DisplayName("Purchase-order vendor read endpoints (#2680)")
@WebMvcTest(PurchaseOrderVendorController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class PurchaseOrderVendorControllerTest extends BaseControllerSliceTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b06");
    private static final String VIEW = "order:purchase_order:view";

    @MockitoBean
    private PurchaseOrderVendorService vendorService;

    private static PurchaseOrderVendorResponse vendor(PurchaseOrderVendorStatus status) {
        return new PurchaseOrderVendorResponse(VENDOR_ID, "V-000123", "Acme Tire Supply", status, false);
    }

    @Test
    @DisplayName("without order:purchase_order:view both reads answer 403 PURCHASE_ORDER_FORBIDDEN")
    void withoutViewAuthorityBothReadsAnswer403() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors"), "order:purchase_order:create"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_FORBIDDEN"));
        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-order-vendors/{vendorId}", VENDOR_ID), "supplier:vendor:read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_FORBIDDEN"));
        verifyNoInteractions(vendorService);
    }

    @Test
    @DisplayName("the list answers 200 with exactly vendorId, vendorNumber, displayName and status per item, and"
            + " passes status, q and a displayName default sort to the service")
    void listAnswersFourFieldsAndPassesFilters() throws Exception {
        when(vendorService.listVendors(any(), any()))
                .thenReturn(
                        new PageImpl<>(List.of(vendor(PurchaseOrderVendorStatus.ACTIVE)), PageRequest.of(0, 20), 1));

        String body = mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-order-vendors")
                                .param("status", "ACTIVE")
                                .param("q", "acme"),
                        VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].vendorId").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.content[0].vendorNumber").value("V-000123"))
                .andExpect(jsonPath("$.content[0].displayName").value("Acme Tire Supply"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode item = new ObjectMapper().readTree(body).get("content").get(0);
        assertThat(item.properties().stream().map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder("vendorId", "vendorNumber", "displayName", "status", "transmittable");

        ArgumentCaptor<ListPurchaseOrderVendorsRequest> filter =
                ArgumentCaptor.forClass(ListPurchaseOrderVendorsRequest.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vendorService).listVendors(filter.capture(), pageable.capture());
        assertThat(filter.getValue().getStatus()).isEqualTo(PurchaseOrderVendorStatus.ACTIVE);
        assertThat(filter.getValue().getQ()).isEqualTo("acme");
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("displayName"));
    }

    @Test
    @DisplayName("an explicit sort reaches the service as sent")
    void explicitSortReachesTheService() throws Exception {
        when(vendorService.listVendors(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/purchase-order-vendors")
                                .param("sort", "vendorNumber,desc")
                                .param("page", "2")
                                .param("size", "5"),
                        VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vendorService).listVendors(any(), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("vendorNumber")));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("a sort the service refuses answers 400 PURCHASE_ORDER_BAD_REQUEST with the reason")
    void refusedSortAnswers400() throws Exception {
        when(vendorService.listVendors(any(), any()))
                .thenThrow(new PurchaseOrderRequestValidationException(
                        "Unsupported sort property 'remitTo'; sort by vendorNumber, displayName, status or vendorId"));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors").param("sort", "remitTo"), VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_BAD_REQUEST"))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Unsupported sort property 'remitTo'; sort by vendorNumber, displayName, status or vendorId"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a status outside the enum or an over-long q is the platform's 400 VALIDATION_ERROR with fieldErrors")
    void unboundFilterAnswersValidationError() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors").param("status", "active"), VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors").param("q", "x".repeat(256)), VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("q"));
        verifyNoInteractions(vendorService);
    }

    @Test
    @DisplayName("an INACTIVE vendor is returned with status INACTIVE (200, not 422)")
    void inactiveVendorIs200() throws Exception {
        when(vendorService.getVendor(VENDOR_ID)).thenReturn(vendor(PurchaseOrderVendorStatus.INACTIVE));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors/{vendorId}", VENDOR_ID), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorId").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("an id the copy does not hold answers 503 VENDOR_REPLICATION_PENDING with Retry-After, never 404")
    void missingVendorAnswers503WithRetryAfter() throws Exception {
        when(vendorService.getVendor(eq(VENDOR_ID)))
                .thenThrow(new ReplicationPendingException(
                        SupplierVendorGuard.VENDOR_REPLICATION_PENDING,
                        "The vendor has not reached this shop's copy of the vendor master yet; retry shortly.",
                        VENDOR_ID));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors/{vendorId}", VENDOR_ID), VIEW))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("VENDOR_REPLICATION_PENDING"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a malformed vendorId answers 400 before the service is asked")
    void malformedVendorIdAnswers400() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/orders/purchase-order-vendors/{vendorId}", "not-a-uuid"), VIEW))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(vendorService);
    }
}
