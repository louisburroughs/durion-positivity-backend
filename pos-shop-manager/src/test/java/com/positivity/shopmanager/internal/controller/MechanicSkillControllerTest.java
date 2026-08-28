package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.service.MechanicSyncService;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(MechanicSkillController.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class MechanicSkillControllerTest {

    private static final String PERSON_ID = "01960011-0000-7000-8000-000000000005";
    private static final String PATH = "/v1/shop-manager/mechanics/by-person/" + PERSON_ID + "/skills";
    private static final String VALID_BODY = """
            {"skills":[{"skillCode":"T4-BRAKES","proficiencyLevel":4},
                       {"skillCode":"T6-ELECTRICAL","proficiencyLevel":3}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MechanicSyncService mechanicSyncService;

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void replaceSkills_withAuthority_returns204AndMapsSkills() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HrMechanicEvent.Payload.Skill>> captor = ArgumentCaptor.forClass(List.class);
        verify(mechanicSyncService).replaceSkills(eq(PERSON_ID), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get(0).getSkillCode())
                .isEqualTo("T4-BRAKES");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get(0).getProficiencyLevel())
                .isEqualTo(4);
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void replaceSkills_unknownMechanic_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Mechanic not found for person " + PERSON_ID))
                .when(mechanicSyncService)
                .replaceSkills(any(), anyList());

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void replaceSkills_invalidProficiency_returns400() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                                {"skills":[{"skillCode":"T4-BRAKES","proficiencyLevel":9}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void replaceSkills_emptySkills_returns400() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {"skills":[]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void replaceSkills_withoutEditAuthority_returns403() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    /** Mirrors ShopAuditControllerTest.SliceTestConfig: Clock for GlobalExceptionHandler,
     * method security, and 403 mapping for AccessDeniedException in the slice. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-08-26T12:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    @org.springframework.web.bind.annotation.ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @org.springframework.web.bind.annotation.ExceptionHandler(
                org.springframework.security.access.AccessDeniedException.class)
        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // status set by @ResponseStatus
        }
    }
}
