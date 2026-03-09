package com.positivity.workorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.dto.CreateSubstituteLinkRequest;
import com.positivity.workorder.dto.SubstituteLinkResponse;
import com.positivity.workorder.dto.UpdateSubstituteLinkRequest;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.controller.SubstituteLinkController;
import com.positivity.workorder.internal.enums.SubstituteType;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.service.SubstituteLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for {@link SubstituteLinkController}.
 *
 * <p>
 * Covers HTTP routing, response codes per ADR-0017, and conflict detection:
 * <ul>
 * <li>POST /v1/products/substitutes/{productId} → 201</li>
 * <li>POST (duplicate) → 409</li>
 * <li>PUT /v1/products/substitutes/{productId} (valid version) → 200</li>
 * <li>GET /v1/products/substitutes/{productId} → 200 list</li>
 * <li>PUT /v1/products/substitutes/{productId} (stale version) → 409</li>
 * <li>DELETE /v1/products/substitutes/{productId}?substitute={id} → 200</li>
 * <li>DELETE /v1/products/substitutes/{productId} (not found) → 404</li>
 * <li>PUT /v1/products/substitutes/{productId} (not found) → 404</li>
 * <li>POST /v1/workorders/{workorderId}/suggestSubstitutes → 200</li>
 * </ul>
 * </p>
 *
 * Issue: CAP-171 Story #45
 */
@WebMvcTest(SubstituteLinkController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class SubstituteLinkControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000045");
    private static final UUID SUBSTITUTE_PART_ID = UUID.fromString("00000000-0000-0000-0000-000000000046");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000047");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000048");

    private static final String CREATE_URL = "/v1/products/substitutes/{productId}";
    private static final String LIST_URL = "/v1/products/substitutes/{productId}";
    private static final String UPDATE_URL = "/v1/products/substitutes/{productId}";
    private static final String DELETE_URL = "/v1/products/substitutes/{productId}";
    private static final String SUGGEST_URL = "/v1/workorders/{workorderId}/suggestSubstitutes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SubstituteLinkService substituteLinkService;

    // =========================================================================
    // POST → 201
    // =========================================================================

    // Issue CAP-171 Story #45: AC1 — create returns 201 per ADR-0017
    @Test
    @DisplayName("AC1: POST /v1/products/substitutes/{productId} returns 201 with created link")
    void whenCreateSubstituteLink_thenReturns201() throws Exception {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(100)
                .isAutoSuggest(false)
                .build();

        var response = SubstituteLinkResponse.builder()
                .id(LINK_ID)
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(100)
                .isActive(true)
                .version(0)
                .build();

        when(substituteLinkService.createLink(any(CreateSubstituteLinkRequest.class)))
                .thenReturn(response);

        // Act & Assert
        mockMvc.perform(post(CREATE_URL, PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.substituteType").value("EQUIVALENT"));
    }

    // =========================================================================
    // POST → 409 duplicate
    // =========================================================================

    // Issue CAP-171 Story #45: AC2 — duplicate create returns 409
    @Test
    @DisplayName("AC2: POST with duplicate productId+substitutePartId returns 409")
    void whenCreateDuplicateSubstituteLink_thenReturns409() throws Exception {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .build();

        when(substituteLinkService.createLink(any(CreateSubstituteLinkRequest.class)))
                .thenThrow(new DuplicateSubstituteLinkException(PRODUCT_ID, SUBSTITUTE_PART_ID));

        // Act & Assert
        mockMvc.perform(post(CREATE_URL, PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // GET → 200 list
    // =========================================================================

    // Issue CAP-171 Story #45: AC6 — list returns 200 with array
    @Test
    @DisplayName("AC6: GET /v1/products/substitutes/{productId} returns 200 with list body")
    void whenListSubstituteLinks_thenReturns200WithList() throws Exception {
        // Arrange
        var link = SubstituteLinkResponse.builder()
                .id(LINK_ID)
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(100)
                .isActive(true)
                .version(0)
                .build();

        when(substituteLinkService.listLinks(eq(PRODUCT_ID))).thenReturn(List.of(link));

        // Act & Assert
        mockMvc.perform(get(LIST_URL, PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(LINK_ID.toString()));
    }

    // =========================================================================
    // PUT → 200 happy path
    // =========================================================================

    @Test
    @DisplayName("AC3: PUT /v1/products/substitutes/{productId} with correct version returns 200")
    void whenUpdateSubstituteLink_withValidVersion_thenReturns200() throws Exception {
        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(50)
                .version(0)
                .build();

        var response = SubstituteLinkResponse.builder()
                .id(LINK_ID)
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(50)
                .version(1)
                .isActive(true)
                .build();

        when(substituteLinkService.updateLink(eq(LINK_ID), any(UpdateSubstituteLinkRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put(UPDATE_URL, PRODUCT_ID)
                .param("linkId", LINK_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    // =========================================================================
    // PUT → 409 stale version
    // =========================================================================

    // Issue CAP-171 Story #45: AC4 — stale version on update returns 409
    @Test
    @DisplayName("AC4: PUT with stale version returns 409")
    void whenUpdateWithStaleVersion_thenReturns409() throws Exception {
        // Arrange
        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .version(1) // stale
                .build();

        when(substituteLinkService.updateLink(eq(LINK_ID), any(UpdateSubstituteLinkRequest.class)))
                .thenThrow(new StaleSubstituteLinkVersionException(LINK_ID, 1, 5));

        // Act & Assert
        mockMvc.perform(put(UPDATE_URL, PRODUCT_ID)
                .param("linkId", LINK_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // DELETE → 200
    // =========================================================================

    // Issue CAP-171 Story #45: AC5 — soft-delete returns 200
    @Test
    @DisplayName("AC5: DELETE /v1/products/substitutes/{productId}?substitute={id} returns 200")
    void whenSoftDeleteSubstituteLink_thenReturns200() throws Exception {
        // Arrange
        var response = SubstituteLinkResponse.builder()
                .id(LINK_ID)
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .isActive(false)
                .version(1)
                .build();

        when(substituteLinkService.deleteLink(eq(PRODUCT_ID), eq(SUBSTITUTE_PART_ID)))
                .thenReturn(response);

        // Act & Assert
        mockMvc.perform(delete(DELETE_URL, PRODUCT_ID)
                .param("substitute", SUBSTITUTE_PART_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNotEmpty())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @DisplayName("DELETE substitute link not found returns 404")
    void whenDeleteSubstituteLinkNotFound_thenReturns404() throws Exception {
        doThrow(new SubstituteLinkNotFoundException(UUID.randomUUID()))
                .when(substituteLinkService)
                .deleteLink(any(), any());

        mockMvc.perform(delete(DELETE_URL, PRODUCT_ID)
                .param("substitute", SUBSTITUTE_PART_ID.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT substitute link not found returns 404")
    void whenUpdateSubstituteLinkNotFound_thenReturns404() throws Exception {
        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(50)
                .version(0)
                .build();

        when(substituteLinkService.updateLink(any(), any()))
                .thenThrow(new SubstituteLinkNotFoundException(UUID.randomUUID()));

        mockMvc.perform(put(UPDATE_URL, PRODUCT_ID)
                .param("linkId", LINK_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // suggestSubstitutes → 200
    // =========================================================================

    // Issue CAP-171 Story #45: AC8 — suggestSubstitutes returns 200
    @Test
    @DisplayName("AC8: POST /v1/workorders/{workorderId}/suggestSubstitutes returns 200")
    void whenSuggestSubstitutes_thenReturns200() throws Exception {
        // Arrange
        when(substituteLinkService.suggestSubstitutes(eq(WORKORDER_ID)))
                .thenReturn(List.of());

        // Act & Assert
        mockMvc.perform(post(SUGGEST_URL, WORKORDER_ID)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
