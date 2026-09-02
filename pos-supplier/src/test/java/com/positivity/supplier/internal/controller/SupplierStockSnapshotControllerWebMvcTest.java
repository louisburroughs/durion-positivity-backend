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
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.stockreport.service.SupplierStockSnapshotService;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotLineView;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Web contract of the stock-snapshot reads (#1638 decision 5), over the real production
 * {@link SecurityConfig}.
 *
 * <p>What this class pins: snapshots are readable under exactly
 * {@code supplier:stocksnapshot:read} — neither the live-inquiry authority nor profile read grants
 * it — and a snapshot that does not exist under the addressed profile is a 404 in the standard
 * {@code ApiError} envelope.
 */
@WebMvcTest(controllers = SupplierStockSnapshotController.class)
@Import({SecurityConfig.class, SupplierStockSnapshotControllerWebMvcTest.FixedClockConfig.class})
@DisplayName("Stock snapshot web contract (#1638)")
class SupplierStockSnapshotControllerWebMvcTest {

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

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID SNAPSHOT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final String BASE = "/v1/supplier/vendor-profiles/" + PROFILE_ID + "/stock-snapshots";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierStockSnapshotService stockSnapshotService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static StockSnapshotSummary summary() {
        return new StockSnapshotSummary(
                SNAPSHOT_ID,
                PROFILE_ID,
                "michelin-eu",
                "COMPLETED",
                "STOCK-4046266",
                LocalDate.of(2026, 8, 12),
                Instant.parse("2026-08-12T04:00:00Z"),
                Instant.parse("2026-08-13T06:00:00Z"),
                Instant.parse("2026-08-13T06:00:41Z"),
                12500,
                3,
                "EDIWHEEL_B-2.1");
    }

    private static StockSnapshotLineView lineWithoutQuantity() {
        return new StockSnapshotLineView(
                UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d"),
                "417",
                "3528709999083",
                "999908",
                null,
                "MICHELIN PILOT SPORT 5",
                null);
    }

    @Nested
    @DisplayName("GET /latest")
    class LatestSnapshot {

        @Test
        void servesTheLatestSnapshotMetadataToAReader() throws Exception {
            when(stockSnapshotService.getLatestSnapshot(PROFILE_ID)).thenReturn(summary());

            mockMvc.perform(authed(get(BASE + "/latest"), SupplierPermissions.STOCK_SNAPSHOT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.snapshotId").value(SNAPSHOT_ID.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    // Vendor time and platform time both travel, distinctly.
                    .andExpect(jsonPath("$.snapshotAsOf").value("2026-08-12T04:00:00Z"))
                    .andExpect(jsonPath("$.fetchedAt").value("2026-08-13T06:00:00Z"))
                    .andExpect(jsonPath("$.linesReported").value(12500));
        }

        @Test
        void reportsAProfileWithoutSnapshotsAsNotFoundInTheStandardEnvelope() throws Exception {
            when(stockSnapshotService.getLatestSnapshot(PROFILE_ID))
                    .thenThrow(new SupplierNotFoundException(
                            SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND, "no snapshot"));

            mockMvc.perform(authed(get(BASE + "/latest"), SupplierPermissions.STOCK_SNAPSHOT_READ))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SUPPLIER_STOCK_SNAPSHOT_NOT_FOUND"));
        }

        @Test
        void isDeniedToALiveStockInquirer() throws Exception {
            // Reading a stored report and calling a vendor live are different acts under
            // different permissions; neither implies the other.
            mockMvc.perform(authed(get(BASE + "/latest"), SupplierPermissions.STOCK_INQUIRE))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isDeniedToAProfileReader() throws Exception {
            mockMvc.perform(authed(get(BASE + "/latest"), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(get(BASE + "/latest")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /{snapshotId}/lines")
    class SnapshotLines {

        @Test
        void servesOnePageOfLinesWithTheSearchPassedThrough() throws Exception {
            when(stockSnapshotService.listSnapshotLines(PROFILE_ID, SNAPSHOT_ID, "PILOT", 0, 50))
                    .thenReturn(new PagedResponse<>(List.of(lineWithoutQuantity()), 0, 50, 1, 1));

            mockMvc.perform(authed(
                            get(BASE + "/" + SNAPSHOT_ID + "/lines?search=PILOT"),
                            SupplierPermissions.STOCK_SNAPSHOT_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].articleEan").value("3528709999083"))
                    // "The vendor stated no quantity" serialises as null, never as zero.
                    .andExpect(jsonPath("$.items[0].availableQuantity").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void reportsAForeignOrMissingSnapshotAsNotFound() throws Exception {
            when(stockSnapshotService.listSnapshotLines(eq(PROFILE_ID), eq(SNAPSHOT_ID), any(), anyInt(), anyInt()))
                    .thenThrow(new SupplierNotFoundException(
                            SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND, "not yours"));

            mockMvc.perform(authed(get(BASE + "/" + SNAPSHOT_ID + "/lines"), SupplierPermissions.STOCK_SNAPSHOT_READ))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SUPPLIER_STOCK_SNAPSHOT_NOT_FOUND"));
        }

        @Test
        void rejectsAPageSizeBeyondTheBound() throws Exception {
            mockMvc.perform(authed(
                            get(BASE + "/" + SNAPSHOT_ID + "/lines?size=500"), SupplierPermissions.STOCK_SNAPSHOT_READ))
                    .andExpect(status().isBadRequest());

            verify(stockSnapshotService, never()).listSnapshotLines(any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void isDeniedToALiveStockInquirer() throws Exception {
            mockMvc.perform(authed(get(BASE + "/" + SNAPSHOT_ID + "/lines"), SupplierPermissions.STOCK_INQUIRE))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(get(BASE + "/" + SNAPSHOT_ID + "/lines")).andExpect(status().isUnauthorized());
        }
    }
}
