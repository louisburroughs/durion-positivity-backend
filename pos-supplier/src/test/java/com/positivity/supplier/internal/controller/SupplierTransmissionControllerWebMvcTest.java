package com.positivity.supplier.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.order.service.SupplierOrderService;
import com.positivity.supplier.internal.order.service.model.OrderTransmissionStatus;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Web contract of the transmission-ledger search (#1638 decision 6), over the real production
 * {@link SecurityConfig}.
 *
 * <p>What this class pins: the queue list is readable under exactly
 * {@code supplier:transmission:read} — the same authority as the existing per-purchase-order reads,
 * because it answers the same question wider — and its filters arrive at the service parsed, not as
 * raw strings.
 */
@WebMvcTest(controllers = SupplierTransmissionController.class)
@Import({SecurityConfig.class, SupplierTransmissionControllerWebMvcTest.FixedClockConfig.class})
@DisplayName("Transmission queue web contract (#1638)")
class SupplierTransmissionControllerWebMvcTest {

    /** The exception advice needs a Clock, and the gateway filter must run inside the security chain. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);
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

    private static final String BASE = "/v1/supplier/transmissions";
    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierOrderService orderService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static OrderTransmissionStatus manualReviewRow() {
        return new OrderTransmissionStatus(
                INTENT_ID,
                UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001"),
                "PO-4471",
                "michelin-eu",
                "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D",
                OrderTransmissionStatus.State.MANUAL_REVIEW,
                null,
                null,
                null,
                1,
                null,
                null,
                Instant.parse("2026-08-13T09:00:00Z"),
                null,
                null,
                null,
                "vendor answered neither confirmed nor rejected");
    }

    @Nested
    @DisplayName("GET /v1/supplier/transmissions")
    class SearchTransmissions {

        @Test
        void servesTheManualReviewQueueToAReaderWithParsedFilters() throws Exception {
            when(orderService.searchTransmissions(
                            eq(OrderTransmissionStatus.State.MANUAL_REVIEW),
                            eq(PROFILE_ID),
                            eq("MICH"),
                            eq(Instant.parse("2026-08-01T00:00:00Z")),
                            eq(Instant.parse("2026-09-01T00:00:00Z")),
                            eq(0),
                            eq(50)))
                    .thenReturn(new PagedResponse<>(List.of(manualReviewRow()), 0, 50, 1, 1));

            mockMvc.perform(authed(
                            get(BASE + "?attemptState=MANUAL_REVIEW&vendorProfileId=" + PROFILE_ID
                                    + "&search=MICH&dateFrom=2026-08-01T00:00:00Z&dateTo=2026-09-01T00:00:00Z"),
                            SupplierPermissions.TRANSMISSION_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].state").value("MANUAL_REVIEW"))
                    .andExpect(jsonPath("$.items[0].purchaseOrderNumber").value("PO-4471"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void servesTheWholeLedgerWhenNoFilterIsGiven() throws Exception {
            when(orderService.searchTransmissions(null, null, null, null, null, 0, 50))
                    .thenReturn(new PagedResponse<>(List.of(), 0, 50, 0, 0));

            mockMvc.perform(authed(get(BASE), SupplierPermissions.TRANSMISSION_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }

        @Test
        void rejectsAnUnknownAttemptState() throws Exception {
            mockMvc.perform(authed(get(BASE + "?attemptState=SHOUTING"), SupplierPermissions.TRANSMISSION_READ))
                    .andExpect(status().isBadRequest());

            verify(orderService, never()).searchTransmissions(any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void rejectsAPageSizeBeyondTheBound() throws Exception {
            mockMvc.perform(authed(get(BASE + "?size=500"), SupplierPermissions.TRANSMISSION_READ))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void isDeniedToAResolverWhoCannotRead() throws Exception {
            // Resolve is deliberately not a superset of read (two permissions, not one).
            mockMvc.perform(authed(get(BASE), SupplierPermissions.TRANSMISSION_RESOLVE))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        }
    }
}
