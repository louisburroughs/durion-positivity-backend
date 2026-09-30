package com.positivity.people.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.service.PeopleAvailabilityService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import com.positivity.people.internal.service.UserPersonTranslationService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1994: a {@link com.positivity.web.common.ReplicationPendingException} raised for a caller whose
 * user link has not replicated reaches the wire as {@code 503} with {@code Retry-After} and its
 * code. This module's {@link PeopleExceptionHandler} maps neither {@code RuntimeException} nor
 * {@code Exception}, so {@code pos-web-common}'s advice renders it; see {@link
 * TimeEntryExceptionControllerErrorHandlingTest} for why {@link WebCommonErrorAutoConfiguration}
 * is imported explicitly.
 */
@WebMvcTest(PeopleAvailabilityController.class)
@Import({
    TestSecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    PeopleAvailabilityControllerTest.FixedClockConfig.class
})
@ActiveProfiles("test")
class ReplicationPendingResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PeopleAvailabilityService peopleAvailabilityService;

    @MockitoBean
    private StaffingAssignmentService staffingAssignmentService;

    @MockitoBean
    private UserPersonTranslationService userPersonTranslationService;

    @Test
    void aCallerWhoseUserLinkHasNotReplicatedIsA503WithRetryAfterAndItsCode() throws Exception {
        when(userPersonTranslationService.getPersonUuidForCurrentUser())
                .thenThrow(UserPersonTranslationService.userLinkReplicationPending());

        mockMvc.perform(get("/v1/people/me/locations").header("X-Authorities", "people:self:view"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("USER_LINK_REPLICATION_PENDING"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
