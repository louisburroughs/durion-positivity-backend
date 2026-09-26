package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.SITE_IN_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.SITE_OUT_OF_REACH;
import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static com.positivity.location.config.LocationScopeTestSupport.scopedOn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
 * Controller-boundary proof for the mobile unit refusals a client maps to messages (#2252, #2248)
 * and the list's filters (#2253): each answers the ApiError envelope with the status, code and
 * field it documents, through the same three advices that meet in production.
 */
@WebMvcTest(MobileUnitController.class)
@Import({
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class MobileUnitControllerTest {

    private static final String UNITS_URL = "/v1/mobile-units";
    private static final UUID UNIT_ID = UUID.fromString("019200aa-0000-7000-8000-000000000001");
    private static final UUID BASE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID AREA_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000d1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MobileUnitService mobileUnitService;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private static MobileUnitResponse van() {
        return MobileUnitResponse.builder()
                .id(UNIT_ID)
                .name("Van 7")
                .baseLocationId(BASE_ID)
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("#2253 - baseLocationId, status and include=coverageRules reach the service; rules are embedded")
    void listFiltersByBaseLocation() throws Exception {
        MobileUnitResponse withRules = van();
        withRules.setCoverageRules(List.of(CoverageRuleResponse.builder()
                .mobileUnitId(UNIT_ID)
                .serviceAreaId(AREA_ID)
                .ruleType("SERVICE_AREA")
                .priority(1)
                .build()));
        when(mobileUnitService.list(0, 20, BASE_ID, "ACTIVE", true)).thenReturn(new PageImpl<>(List.of(withRules)));
        as(preRollout(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(UNITS_URL)
                        .param("baseLocationId", BASE_ID.toString())
                        .param("status", "ACTIVE")
                        .param("include", "coverageRules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].baseLocationId").value(BASE_ID.toString()))
                .andExpect(
                        jsonPath("$.content[0].coverageRules[0].serviceAreaId").value(AREA_ID.toString()));
    }

    @Test
    @DisplayName("#2253 - a scoped caller naming a base location outside their reach is 403; the service is not called")
    void listDeniesOutOfReachBaseLocation() throws Exception {
        as(scopedOn(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(UNITS_URL).param("baseLocationId", SITE_OUT_OF_REACH.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));

        verify(mobileUnitService, never()).list(anyInt(), anyInt(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("#2253 - a scoped caller naming a base location in reach gets that shop's units")
    void listAllowsInReachBaseLocation() throws Exception {
        when(mobileUnitService.list(0, 20, SITE_IN_REACH, null, false)).thenReturn(new PageImpl<>(List.of(van())));
        as(scopedOn(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(UNITS_URL).param("baseLocationId", SITE_IN_REACH.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(UNIT_ID.toString()));
    }

    @Test
    @DisplayName("#2253 - with no filter the list is unchanged, and carries no coverageRules key")
    void listWithoutFilterIsUnchanged() throws Exception {
        when(mobileUnitService.list(0, 20, null, null, false)).thenReturn(new PageImpl<>(List.of(van())));
        as(preRollout(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(UNITS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(UNIT_ID.toString()))
                .andExpect(jsonPath("$.content[0].coverageRules").doesNotExist());
    }

    @Test
    @DisplayName("#2253 - an include value other than coverageRules is 400 on include")
    void listRejectsUnknownInclude() throws Exception {
        as(preRollout(LocationPermissions.MOBILE_UNIT_READ));

        mockMvc.perform(get(UNITS_URL).param("include", "coverageRules,technicians"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("include"));

        verify(mobileUnitService, never()).list(anyInt(), anyInt(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("#2252 row 5 - a blank name is 400 VALIDATION_ERROR naming name; the service is not called")
    void createRejectsBlankName() throws Exception {
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(post(UNITS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"baseLocationId\":\"" + BASE_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));

        verify(mobileUnitService, never()).createMobileUnit(any(MobileUnitRequest.class));
    }

    @Test
    @DisplayName("#2252 row 4 - a missing baseLocationId is 400 VALIDATION_ERROR naming baseLocationId")
    void createRequiresBaseLocation() throws Exception {
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(post(UNITS_URL).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Van 7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("baseLocationId"));

        verify(mobileUnitService, never()).createMobileUnit(any(MobileUnitRequest.class));
    }

    @Test
    @DisplayName("#2252 row 2 - an unknown reference is 422 with its code and the field")
    void createUnknownPolicyIs422() throws Exception {
        when(mobileUnitService.createMobileUnit(any(MobileUnitRequest.class)))
                .thenThrow(InvalidFieldException.unknownReference(
                        "TRAVEL_BUFFER_POLICY_NOT_FOUND",
                        "travelBufferPolicyId",
                        "travelBufferPolicyId does not reference an existing travel buffer policy"));
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(post(UNITS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Van 7\",\"baseLocationId\":\"" + BASE_ID + "\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("TRAVEL_BUFFER_POLICY_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("travelBufferPolicyId"));
    }

    @Test
    @DisplayName("#2252 row 6 - a duplicate name is 409 with code MOBILE_UNIT_NAME_TAKEN, not a bare CONFLICT")
    void duplicateNameKeepsItsCode() throws Exception {
        when(mobileUnitService.createMobileUnit(any(MobileUnitRequest.class)))
                .thenThrow(new DuplicateResourceException("MOBILE_UNIT_NAME_TAKEN"));
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(post(UNITS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Van 7\",\"baseLocationId\":\"" + BASE_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_UNIT_NAME_TAKEN"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("#2252 row 1 - patching an unknown unit is 404 NOT_FOUND")
    void patchUnknownUnitIs404() throws Exception {
        when(mobileUnitService.patch(eq(UNIT_ID), any()))
                .thenThrow(new ResourceNotFoundException("Mobile unit not found"));
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(patch(UNITS_URL + "/{id}", UNIT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"edited elsewhere\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("#2252 row 7 - a patch status the service refuses is 400 naming status")
    void patchInvalidStatusIs400() throws Exception {
        when(mobileUnitService.patch(eq(UNIT_ID), any()))
                .thenThrow(InvalidFieldException.invalid("status", "status must be ACTIVE or INACTIVE"));
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(patch(UNITS_URL + "/{id}", UNIT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("status must be ACTIVE or INACTIVE"));
    }

    @Test
    @DisplayName("#2248 - a rules value that is not an array is 400 naming rules; nothing is replaced")
    void replaceRejectsNonArrayRules() throws Exception {
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(put(UNITS_URL + "/{id}/coverage-rules", UNIT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":{\"ruleType\":\"SERVICE_AREA\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rules"));

        verify(mobileUnitService, never()).replaceCoverageRules(anyString(), anyList());
    }

    @Test
    @DisplayName("#2248 - a well-formed rules array reaches the service as sent")
    void replacePassesRulesThrough() throws Exception {
        when(mobileUnitService.replaceCoverageRules(eq(UNIT_ID.toString()), anyList()))
                .thenReturn(List.of());
        as(preRollout(LocationPermissions.MOBILE_UNIT_MANAGE));

        mockMvc.perform(put(UNITS_URL + "/{id}/coverage-rules", UNIT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":[{\"serviceAreaId\":\"" + AREA_ID + "\",\"ruleType\":\"SERVICE_AREA\"}]}"))
                .andExpect(status().isOk());

        verify(mobileUnitService)
                .replaceCoverageRules(
                        UNIT_ID.toString(),
                        List.of(Map.of("serviceAreaId", AREA_ID.toString(), "ruleType", "SERVICE_AREA")));
    }
}
