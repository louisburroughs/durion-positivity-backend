package com.positivity.location.contract;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.service.SiteDefaultsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract behavior tests for Site Defaults endpoints.
 *
 * <p>
 * Verifies HTTP contract for:
 * <ul>
 * <li>PUT /v1/locations/{locationId}/defaults — configure staging +
 * quarantine</li>
 * <li>GET /v1/locations/{locationId}/defaults — read current defaults</li>
 * </ul>
 *
 * Issue: CAP-214 #38
 */
@DisplayName("SiteDefaultsContractBehaviorIT")
class SiteDefaultsContractBehaviorIT extends BaseContractIntegrationTest {

        private static final UUID SITE_ID = UUID.nameUUIDFromBytes("site-defaults-38".getBytes());
        private static final UUID STAGING_ID = UUID.nameUUIDFromBytes("staging-38".getBytes());
        private static final UUID QUARANTINE_ID = UUID.nameUUIDFromBytes("quarantine-38".getBytes());

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private SiteDefaultsService siteDefaultsService;

        // -------------------------------------------------------------------------
        // PUT /v1/locations/{locationId}/defaults
        // -------------------------------------------------------------------------

        /**
         * Valid configure request returns 200 OK with the updated defaults.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - PUT /defaults with valid payload returns 200")
        void putDefaults_success_returns200() throws Exception {
                SiteDefaultsResponse expected = SiteDefaultsResponse.builder()
                                .siteId(SITE_ID)
                                .defaultStagingLocationId(STAGING_ID)
                                .defaultQuarantineLocationId(QUARANTINE_ID)
                                .build();

                when(siteDefaultsService.configureDefaults(eq(SITE_ID), any())).thenReturn(expected);

                mockMvc.perform(withGatewayAuth(put("/v1/locations/{locationId}/defaults", SITE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPayload(STAGING_ID, QUARANTINE_ID))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.siteId").value(SITE_ID.toString()))
                                .andExpect(jsonPath("$.defaultStagingLocationId").value(STAGING_ID.toString()))
                                .andExpect(jsonPath("$.defaultQuarantineLocationId").value(QUARANTINE_ID.toString()));
        }

        /**
         * Same UUID provided for both staging and quarantine — service rejects with
         * 400 DEFAULT_LOCATION_ROLE_CONFLICT.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - PUT /defaults with same staging and quarantine ID returns 400")
        void putDefaults_sameLocation_returns400() throws Exception {
                when(siteDefaultsService.configureDefaults(eq(SITE_ID), any()))
                                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "DEFAULT_LOCATION_ROLE_CONFLICT"));

                mockMvc.perform(withGatewayAuth(put("/v1/locations/{locationId}/defaults", SITE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPayload(STAGING_ID, STAGING_ID))))
                                .andExpect(status().isBadRequest());
        }

        /**
         * Non-existent locationId — service returns 404 Not Found.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - PUT /defaults with non-existent site returns 404")
        void putDefaults_siteNotFound_returns404() throws Exception {
                UUID unknownSiteId = UUID.randomUUID();

                when(siteDefaultsService.configureDefaults(eq(unknownSiteId), any()))
                                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

                mockMvc.perform(withGatewayAuth(put("/v1/locations/{locationId}/defaults", unknownSiteId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPayload(STAGING_ID, QUARANTINE_ID))))
                                .andExpect(status().isNotFound());
        }

        /**
         * Storage location ID does not belong to the site — service returns 422.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - PUT /defaults with storage location not belonging to site returns 422")
        void putDefaults_invalidStorageLocation_returns422() throws Exception {
                UUID foreignStorageId = UUID.randomUUID();

                when(siteDefaultsService.configureDefaults(eq(SITE_ID), any()))
                                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT));

                mockMvc.perform(withGatewayAuth(put("/v1/locations/{locationId}/defaults", SITE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPayload(foreignStorageId, QUARANTINE_ID))))
                                .andExpect(status().is(422));
        }

        // -------------------------------------------------------------------------
        // GET /v1/locations/{locationId}/defaults
        // -------------------------------------------------------------------------

        /**
         * Valid GET for a site with defaults configured — returns 200 with populated
         * response body.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - GET /defaults returns 200 with defaults")
        void getDefaults_success_returns200() throws Exception {
                SiteDefaultsResponse expected = SiteDefaultsResponse.builder()
                                .siteId(SITE_ID)
                                .defaultStagingLocationId(STAGING_ID)
                                .defaultQuarantineLocationId(QUARANTINE_ID)
                                .build();

                when(siteDefaultsService.getDefaults(SITE_ID)).thenReturn(expected);

                mockMvc.perform(withGatewayAuth(get("/v1/locations/{locationId}/defaults", SITE_ID)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.siteId").value(SITE_ID.toString()));
        }

        /**
         * Non-existent locationId for GET — service returns 404 Not Found.
         *
         * @throws Exception on MockMvc failure
         */
        @Test
        @DisplayName("#38 - GET /defaults with non-existent site returns 404")
        void getDefaults_siteNotFound_returns404() throws Exception {
                UUID unknownSiteId = UUID.randomUUID();

                when(siteDefaultsService.getDefaults(unknownSiteId))
                                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

                mockMvc.perform(withGatewayAuth(get("/v1/locations/{locationId}/defaults", unknownSiteId)))
                                .andExpect(status().isNotFound());
        }

        // -------------------------------------------------------------------------
        // helpers
        // -------------------------------------------------------------------------

        private String validPayload(UUID stagingId, UUID quarantineId) {
                return """
                                {
                                  "defaultStagingLocationId": "%s",
                                  "defaultQuarantineLocationId": "%s"
                                }
                                """.formatted(stagingId, quarantineId);
        }
}
