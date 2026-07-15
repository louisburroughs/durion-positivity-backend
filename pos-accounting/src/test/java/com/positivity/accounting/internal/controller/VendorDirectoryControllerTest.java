package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.service.VendorDirectoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests for VendorDirectoryController (Issue #816): vendor name typeahead
 * search and single-vendor lookup, including permission enforcement.
 */
@DisplayName("VendorDirectoryController Tests")
class VendorDirectoryControllerTest extends BaseIntegrationTest {

    private static final UUID VENDOR_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");

    @MockitoBean
    private VendorDirectoryService vendorDirectoryService;

    private static VendorResponse acme() {
        return VendorResponse.builder()
                .vendorId(VENDOR_ID)
                .name("Acme Auto Parts")
                .status("ACTIVE")
                .build();
    }

    @Nested
    @DisplayName("GET /v1/accounting/vendors")
    class SearchVendors {

        @Test
        @DisplayName("Should return name-matched vendors")
        void shouldReturnMatchedVendors() throws Exception {
            when(vendorDirectoryService.searchVendors(eq("acme"), anyInt())).thenReturn(List.of(acme()));

            mockMvc.perform(withAuth(get("/v1/accounting/vendors").param("name", "acme")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].vendorId").value(VENDOR_ID.toString()))
                    .andExpect(jsonPath("$[0].name").value("Acme Auto Parts"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Should list vendors when no name term is given")
        void shouldListWithoutTerm() throws Exception {
            when(vendorDirectoryService.searchVendors(any(), anyInt())).thenReturn(List.of(acme()));

            mockMvc.perform(withAuth(get("/v1/accounting/vendors")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].vendorId").value(VENDOR_ID.toString()));
        }

        @Test
        @DisplayName("Should reject search without accounting:ap:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/vendors").param("name", "acme"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/vendors/{vendorId}")
    class GetVendorById {

        @Test
        @DisplayName("Should resolve a single vendor by id")
        void shouldResolveVendorById() throws Exception {
            when(vendorDirectoryService.getVendorById(VENDOR_ID)).thenReturn(Optional.of(acme()));

            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vendorId").value(VENDOR_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Acme Auto Parts"));
        }

        @Test
        @DisplayName("Should return 404 for unknown vendor")
        void shouldReturn404ForUnknownVendor() throws Exception {
            when(vendorDirectoryService.getVendorById(VENDOR_ID)).thenReturn(Optional.empty());

            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should reject lookup without accounting:ap:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }
}
