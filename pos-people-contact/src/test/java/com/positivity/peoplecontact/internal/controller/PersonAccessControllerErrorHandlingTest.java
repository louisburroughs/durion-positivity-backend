package com.positivity.peoplecontact.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.peoplecontact.config.TestSecurityConfig;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.SecurityServiceContractException;
import com.positivity.peoplecontact.internal.service.PeopleAccessControlService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/**
 * End-to-end proof for issue #1694, exercised through {@link PersonAccessController} to avoid a
 * request body (no JSON/bean-validation concerns to muddy the assertion — {@code
 * PersonRoleAssignmentRequest.roleCode} carries its own {@code @NotBlank}, which would answer
 * first and never reach the exception this test targets): {@link PeopleContactValidationException}
 * keeps the genuine-client-error contract (400 {@code VALIDATION_ERROR}, message echoed, own
 * correlation id in body and header), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by this module's {@link PeopleExceptionHandler}: it falls
 * through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which
 * answers a generic, correlated 500 that never echoes the exception's own text. {@link
 * SecurityServiceContractException} is proven the same way: a downstream 400 that no caller
 * input could have caused (a {@code SecurityServiceClient} call whose every value is fixed or
 * module-internal) must reach the client as that same safe 500, not a misattributed 400.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist — an unrelated {@code
 * @AutoConfiguration} from another artifact is simply not imported by the slice), so {@link
 * WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real fallback
 * chain rather than asserting a weaker substitute.
 */
@WebMvcTest(PersonAccessController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
class PersonAccessControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final String AUTHORITIES = "X-Authorities";
    private static final String ROLE_VIEW = "people-contact:role:view";
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final String ASSIGNMENTS_PATH = "/v1/people/" + PERSON_ID + "/access/assignments";
    private static final String ROLES_PATH = "/v1/people/" + PERSON_ID + "/access/roles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PeopleAccessControlService peopleAccessControlService;

    @Test
    void aPeopleContactValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(peopleAccessControlService.getPersonRoleAssignments(any(), anyBoolean(), any()))
                .thenThrow(new PeopleContactValidationException("endDate must be greater than or equal to startDate"));

        mockMvc.perform(get(ASSIGNMENTS_PATH).header(AUTHORITIES, ROLE_VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("endDate must be greater than or equal to startDate"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /**
     * The regression this test guards against: a bare {@code IllegalArgumentException} must NOT
     * come back as a 400 carrying its own message. It is an unexpected server-side failure, so
     * it must land on the generic, correlated 500 fallback.
     */
    @Test
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'personId' of "
                + "'com.positivity.peoplecontact.internal.entity.Person'";
        when(peopleAccessControlService.getPersonRoleAssignments(any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get(ASSIGNMENTS_PATH).header(AUTHORITIES, ROLE_VIEW))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("personId");
    }

    /**
     * Guards the fix for the code-review finding on issue #1694: {@code SecurityServiceClient}'s
     * {@code getUserByUsername}/{@code getAvailableRoles} 400-mapping used to blame the caller
     * even when the downstream 400 could not have been caused by anything the caller sent (no
     * query parameter, or a fixed module constant). That is now a {@link
     * SecurityServiceContractException}, which — like a bare {@code IllegalArgumentException} —
     * must reach the client as a generic, correlated 500, never as a 400 carrying the downstream
     * detail (a version-drift message naming pos-security-service internals).
     */
    @Test
    void aSecurityServiceContractViolationAnswers500WithoutLeakingTheDownstreamDetail() throws Exception {
        String leakCanary = "pos-security-service rejected GET /v1/roles?scopeType=GLOBAL as malformed, but scope "
                + "is a module-internal constant, never caller input";
        when(peopleAccessControlService.getAvailableRolesForPerson(any()))
                .thenThrow(new SecurityServiceContractException(leakCanary));

        String body = mockMvc.perform(get(ROLES_PATH).header(AUTHORITIES, ROLE_VIEW))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("pos-security-service")
                .doesNotContain("scopeType");
    }

    /** Clock for {@link PeopleExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
