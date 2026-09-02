package com.positivity.supplier.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.stockinquiry.service.SupplierStockAvailabilityService;
import com.positivity.supplier.internal.stockinquiry.service.model.StockAvailabilityView;
import com.positivity.supplier.service.model.StockInquiryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web contract of the product-keyed availability fan-out (#1637 decision 1), over the real
 * production {@link SecurityConfig}.
 *
 * <p>What this class pins: the read is gated by exactly {@code supplier:stockavailability:read}
 * (the per-vendor inquiry authority does not grant it), exactly-one-of {@code productId}/{@code
 * sku} is a 400 naming both fields, an unresolvable product identity is a 404 in the standard
 * envelope, and a mixed per-vendor outcome — including a vendor that never answered — is a 200.
 */
@WebMvcTest(controllers = SupplierStockAvailabilityController.class)
@Import({SecurityConfig.class, SupplierStockAvailabilityControllerWebMvcTest.FixedClockConfig.class})
@DisplayName("Product-keyed availability web contract (#1637)")
class SupplierStockAvailabilityControllerWebMvcTest {

    /** The exception advice needs a Clock, and the gateway filter must run inside the security chain. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        com.positivity.security.common.GatewayAuthoritiesFilter>
                gatewayAuthoritiesFilterRegistration(
                        com.positivity.security.common.GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID LOCATION = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa1");
    private static final UUID VENDOR_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aa2");
    private static final String BASE = "/v1/supplier/stock/availability";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierStockAvailabilityService availabilityService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static StockAvailabilityView mixedOutcomes() {
        return new StockAvailabilityView(
                PRODUCT_ID,
                LOCATION,
                4,
                "PT15M",
                List.of(
                        new StockAvailabilityView.VendorAvailability(
                                VENDOR_A,
                                "Michelin Europe",
                                StockInquiryResponse.Status.OK,
                                Instant.parse("2026-08-14T11:59:30Z"),
                                Instant.parse("2026-08-14T11:40:00Z"),
                                true,
                                List.of(new StockAvailabilityView.Line(
                                        StockInquiryResponse.LineStatus.AVAILABLE,
                                        8,
                                        LocalDate.of(2026, 8, 20),
                                        null,
                                        null))),
                        new StockAvailabilityView.VendorAvailability(
                                VENDOR_B,
                                "Conti DACH",
                                StockInquiryResponse.Status.SUPPLIER_UNAVAILABLE,
                                null,
                                null,
                                null,
                                List.of())));
    }

    @Test
    void servesMixedPerVendorOutcomesAsOnePartial200() throws Exception {
        when(availabilityService.checkAvailability(PRODUCT_ID, null, LOCATION, 4))
                .thenReturn(mixedOutcomes());

        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION + "&quantity=4"),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.deliveryLocationId").value(LOCATION.toString()))
                .andExpect(jsonPath("$.requestedQuantity").value(4))
                // The backend-owned threshold is echoed so every client judges by the same rule.
                .andExpect(jsonPath("$.stalenessThreshold").value("PT15M"))
                .andExpect(jsonPath("$.vendors[0].vendorDisplayName").value("Michelin Europe"))
                .andExpect(jsonPath("$.vendors[0].status").value("OK"))
                // fetchedAt and asOf are two facts, and staleness is judged from asOf.
                .andExpect(jsonPath("$.vendors[0].fetchedAt").value("2026-08-14T11:59:30Z"))
                .andExpect(jsonPath("$.vendors[0].asOf").value("2026-08-14T11:40:00Z"))
                .andExpect(jsonPath("$.vendors[0].stale").value(true))
                .andExpect(jsonPath("$.vendors[0].lines[0].availableQuantity").value(8))
                // No article identity leaks: which codes were asked about stays internal.
                .andExpect(jsonPath("$.vendors[0].lines[0].articleEan").doesNotExist())
                .andExpect(jsonPath("$.vendors[0].lines[0].supplierArticleCode").doesNotExist())
                .andExpect(jsonPath("$.vendors[1].status").value("SUPPLIER_UNAVAILABLE"))
                .andExpect(jsonPath("$.vendors[1].fetchedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.vendors[1].stale").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.vendors[1].lines").isEmpty());
    }

    @Test
    void defaultsTheQuantityToOne() throws Exception {
        when(availabilityService.checkAvailability(PRODUCT_ID, null, LOCATION, 1))
                .thenReturn(new StockAvailabilityView(PRODUCT_ID, LOCATION, 1, "PT15M", List.of()));

        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isOk())
                // No configured vendor is a valid answer, not an error.
                .andExpect(jsonPath("$.vendors").isEmpty());

        verify(availabilityService).checkAvailability(PRODUCT_ID, null, LOCATION, 1);
    }

    @Test
    void reportsBothIdentitiesAsA400NamingBothFields() throws Exception {
        when(availabilityService.checkAvailability(any(), any(), any(), anyInt()))
                .thenThrow(new SupplierValidationException(
                        SupplierValidationException.AVAILABILITY_IDENTITY_INVALID,
                        "Exactly one of productId or sku must be provided",
                        List.of(
                                new ApiError.FieldError("productId", "exactly one of productId or sku"),
                                new ApiError.FieldError("sku", "exactly one of productId or sku"))));

        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&sku=MICH-1&deliveryLocationId=" + LOCATION),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_AVAILABILITY_IDENTITY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("productId"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("sku"));
    }

    @Test
    void rejectsAMissingDeliveryLocation() throws Exception {
        mockMvc.perform(authed(get(BASE + "?productId=" + PRODUCT_ID), SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));

        verify(availabilityService, never()).checkAvailability(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsAQuantityBelowOne() throws Exception {
        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION + "&quantity=0"),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isBadRequest());

        verify(availabilityService, never()).checkAvailability(any(), any(), any(), anyInt());
    }

    @Test
    void reportsAnUnresolvableProductIdentityAsNotFoundInTheStandardEnvelope() throws Exception {
        when(availabilityService.checkAvailability(PRODUCT_ID, null, LOCATION, 1))
                .thenThrow(new SupplierNotFoundException(
                        SupplierNotFoundException.PRODUCT_CODES_NOT_FOUND, "no codes for product"));

        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_CODES_NOT_FOUND"));
    }

    @Test
    void reportsAnAmbiguousSkuAsA409InTheStandardEnvelope() throws Exception {
        // Two replica rows carrying the same SKU is a data conflict the service refuses to guess
        // about; the web contract surfaces it as a 409 in the ApiError envelope (#1637 review fix).
        when(availabilityService.checkAvailability(null, "MICH-1", LOCATION, 1))
                .thenThrow(new SupplierConflictException(
                        SupplierConflictException.PRODUCT_SKU_AMBIGUOUS, "SKU 'MICH-1' matches more than one product"));

        mockMvc.perform(authed(
                        get(BASE + "?sku=MICH-1&deliveryLocationId=" + LOCATION),
                        SupplierPermissions.STOCK_AVAILABILITY_READ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_SKU_AMBIGUOUS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("SKU 'MICH-1' matches more than one product"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void isDeniedToAPerVendorStockInquirer() throws Exception {
        // The composing-service authority does not grant the frontend-facing panel; the two acts
        // expose different shapes of the vendor relationship and are gated apart.
        mockMvc.perform(authed(
                        get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION),
                        SupplierPermissions.STOCK_INQUIRE))
                .andExpect(status().isForbidden());
    }

    @Test
    void isRejectedWithoutAuthentication() throws Exception {
        mockMvc.perform(get(BASE + "?productId=" + PRODUCT_ID + "&deliveryLocationId=" + LOCATION))
                .andExpect(status().isUnauthorized());
    }
}
