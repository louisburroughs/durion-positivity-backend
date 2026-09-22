package com.positivity.people.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.JobRoleDto;
import com.positivity.people.internal.service.JobRoleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The tenant's job-role list over HTTP: the gate and the shape (durion#2157). */
@WebMvcTest(JobRoleController.class)
@Import({TestSecurityConfig.class, JobRoleControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
class JobRoleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JobRoleService jobRoleService;

    private static JobRoleDto jobRoleDto() {
        return JobRoleDto.builder()
                .id(UUID.fromString("01960003-0000-7000-8000-000000000001"))
                .code("LEAD_TECH")
                .name("Lead Technician")
                .description("Senior technician who leads a repair bay")
                .active(true)
                .createdAt(Instant.parse("2026-03-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-03-01T00:00:00Z"))
                .build();
    }

    @Test
    void listJobRoles_answersTheTenantsList() throws Exception {
        when(jobRoleService.listActive()).thenReturn(List.of(jobRoleDto()));

        mockMvc.perform(get("/v1/people/job-roles").header("X-Authorities", "people:jobRole:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("LEAD_TECH"))
                .andExpect(jsonPath("$[0].name").value("Lead Technician"));
    }

    @Test
    void listJobRoles_withoutTheAuthority_isForbidden() throws Exception {
        mockMvc.perform(get("/v1/people/job-roles").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createJobRole_answersTheCreatedRole() throws Exception {
        when(jobRoleService.create(org.mockito.ArgumentMatchers.any())).thenReturn(jobRoleDto());

        mockMvc.perform(post("/v1/people/job-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"LEAD_TECH\",\"name\":\"Lead Technician\"}")
                        .header("X-Authorities", "people:jobRole:manage"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("LEAD_TECH"));
    }

    @Test
    void createJobRole_withoutTheAuthority_isForbidden() throws Exception {
        mockMvc.perform(post("/v1/people/job-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"LEAD_TECH\",\"name\":\"Lead Technician\"}")
                        .header("X-Authorities", "people:jobRole:view"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createJobRole_blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/v1/people/job-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"LEAD_TECH\",\"name\":\"\"}")
                        .header("X-Authorities", "people:jobRole:manage"))
                .andExpect(status().isBadRequest());
    }

    /** {@link PeopleExceptionHandler} needs a clock; the slice supplies a fixed one. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
