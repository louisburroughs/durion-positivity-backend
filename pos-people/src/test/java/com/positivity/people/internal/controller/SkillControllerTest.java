package com.positivity.people.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.SkillDto;
import com.positivity.people.internal.service.SkillRegistryService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The registry read over HTTP: the gate and the shape (CAP-328). */
@WebMvcTest(SkillController.class)
@Import({TestSecurityConfig.class, SkillControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
class SkillControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SkillRegistryService skillRegistryService;

    @Test
    void listSkills_answersTheRegistry() throws Exception {
        when(skillRegistryService.listActive())
                .thenReturn(List.of(SkillDto.builder()
                        .skillId(UUID.fromString("01960003-0000-7000-8000-000000000001"))
                        .code("BRAKES-LIGHT")
                        .name("Brakes (light duty)")
                        .competenceCode("BRAKES")
                        .minGvwrClass(1)
                        .maxGvwrClass(3)
                        .sourceCodes(List.of("ASE:A5-BRAKES"))
                        .build()));

        mockMvc.perform(get("/v1/people/skills").header("X-Authorities", "people:skill:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BRAKES-LIGHT"))
                .andExpect(jsonPath("$[0].maxGvwrClass").value(3))
                .andExpect(jsonPath("$[0].sourceCodes[0]").value("ASE:A5-BRAKES"));
    }

    @Test
    void listSkills_withoutTheAuthority_isForbidden() throws Exception {
        // The slice's default test authorities carry the employee reads, not the registry read.
        mockMvc.perform(get("/v1/people/skills").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isForbidden());
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
