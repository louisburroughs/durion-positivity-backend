package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.vendorfeed.UnmappedFeedVendorResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingResponse;
import com.positivity.inventory.internal.exception.VendorFeedMappingException;
import com.positivity.inventory.internal.service.VendorFeedMappingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for VendorFeedMappingController (CAP:550 S36, #2525): permission gating (reads need
 * replenishment:manage, changes need vendor_feed_mapping:manage), request validation and the per-case error codes.
 */
@WebMvcTest(VendorFeedMappingController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class VendorFeedMappingControllerTest {

    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VENDOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String MANUFACTURER_ID = "00000000-0000-0000-0000-0000000000c3";
    private static final String READ = "inventory:replenishment:manage";
    private static final String MANAGE = "inventory:vendor_feed_mapping:manage";
    private static final String BASE = "/v1/inventory/vendor-feed-mappings";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    VendorFeedMappingService service;

    @BeforeEach
    void stubClock() {
        // The module exception advice timestamps every ApiError from the injected Clock.
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-17T00:00:00Z"));
    }

    private static VendorFeedMappingResponse sample() {
        return VendorFeedMappingResponse.builder()
                .mappingId(MAPPING_ID)
                .feedType("MANUFACTURER")
                .feedVendorRef(MANUFACTURER_ID)
                .vendorId(VENDOR_ID)
                .vendorNumber("V-000001")
                .vendorDisplayName("Acme Parts")
                .vendorStatus("ACTIVE")
                .version(0L)
                .build();
    }

    private static String createBody() {
        return """
                {"feedType":"MANUFACTURER","feedVendorRef":"%s","vendorId":"%s"}
                """.formatted(MANUFACTURER_ID, VENDOR_ID);
    }

    @Test
    void list_withReadAuthority_returns200() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new PageImpl<>(List.of(sample())));

        mockMvc.perform(get(BASE).header("X-Authorities", READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].mappingId").value(MAPPING_ID.toString()))
                .andExpect(jsonPath("$.content[0].vendorNumber").value("V-000001"));
    }

    @Test
    void list_withManageOnly_returns403() throws Exception {
        mockMvc.perform(get(BASE).header("X-Authorities", MANAGE)).andExpect(status().isForbidden());
    }

    @Test
    void list_withUnknownFeedType_returns400() throws Exception {
        mockMvc.perform(get(BASE).param("feedType", "CARRIER").header("X-Authorities", READ))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unmapped_withReadAuthority_returns200() throws Exception {
        when(service.listUnmapped(any()))
                .thenReturn(List.of(UnmappedFeedVendorResponse.builder()
                        .feedType("DISTRIBUTOR")
                        .feedVendorRef("ACME-DIST-01")
                        .reason("UNMAPPED")
                        .productCount(3)
                        .build()));

        mockMvc.perform(get(BASE + "/unmapped").header("X-Authorities", READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("UNMAPPED"))
                .andExpect(jsonPath("$[0].productCount").value(3));
    }

    @Test
    void create_withManageAuthority_returns201() throws Exception {
        when(service.create(any())).thenReturn(sample());

        mockMvc.perform(post(BASE)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendorId").value(VENDOR_ID.toString()));
    }

    @Test
    void create_withReadOnlyAuthority_returns403() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("X-Authorities", READ)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());
    }

    @Test
    void create_withoutVendorId_returns400() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedType\":\"MANUFACTURER\",\"feedVendorRef\":\"" + MANUFACTURER_ID + "\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }

    @Test
    void create_existingMapping_returns409WithCode() throws Exception {
        when(service.create(any())).thenThrow(VendorFeedMappingException.exists("MANUFACTURER", MANUFACTURER_ID));

        mockMvc.perform(post(BASE)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENDOR_FEED_MAPPING_EXISTS"));
    }

    @Test
    void create_unknownVendor_returns422WithCode() throws Exception {
        when(service.create(any())).thenThrow(VendorFeedMappingException.vendorNotFound(VENDOR_ID));

        mockMvc.perform(post(BASE)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
    }

    @Test
    void create_inactiveVendor_returns422WithCode() throws Exception {
        when(service.create(any())).thenThrow(VendorFeedMappingException.vendorInactive(VENDOR_ID));

        mockMvc.perform(post(BASE)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VENDOR_INACTIVE"));
    }

    @Test
    void update_withManageAuthority_returns200() throws Exception {
        when(service.update(any(), any())).thenReturn(sample());

        mockMvc.perform(put(BASE + "/" + MAPPING_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + VENDOR_ID + "\",\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void update_withoutVersion_returns400() throws Exception {
        mockMvc.perform(put(BASE + "/" + MAPPING_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + VENDOR_ID + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_staleVersion_returns409Conflict() throws Exception {
        when(service.update(any(), any())).thenThrow(VendorFeedMappingException.staleVersion(MAPPING_ID));

        mockMvc.perform(put(BASE + "/" + MAPPING_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + VENDOR_ID + "\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void update_missingMapping_returns404WithCode() throws Exception {
        when(service.update(any(), any())).thenThrow(VendorFeedMappingException.notFound(MAPPING_ID));

        mockMvc.perform(put(BASE + "/" + MAPPING_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + VENDOR_ID + "\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENDOR_FEED_MAPPING_NOT_FOUND"));
    }

    @Test
    void delete_withManageAuthority_returns204() throws Exception {
        mockMvc.perform(delete(BASE + "/" + MAPPING_ID).header("X-Authorities", MANAGE))
                .andExpect(status().isNoContent());
        verify(service).delete(MAPPING_ID);
    }

    @Test
    void delete_withReadOnlyAuthority_returns403() throws Exception {
        mockMvc.perform(delete(BASE + "/" + MAPPING_ID).header("X-Authorities", READ))
                .andExpect(status().isForbidden());
        verify(service, never()).delete(any());
    }
}
