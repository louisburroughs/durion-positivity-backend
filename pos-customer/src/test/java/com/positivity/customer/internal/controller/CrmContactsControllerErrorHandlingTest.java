package com.positivity.customer.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.service.ContactRoleService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wire-contract proof for issue #1714 on {@link CrmContactsController#updateContactRoles}: an
 * unrecognised {@code roleCode} on an existing party and contact answers the documented
 * {@code 400} with the {@code ApiError} envelope and a correlation id (ADR-0017 §1, §3, §4) —
 * not the bodyless {@code 404} the removed local {@code catch (IllegalArgumentException)} used
 * to produce — and a genuinely missing party or contact still answers {@code 404}, now also with
 * the envelope.
 *
 * <p>{@code @WebMvcTest} does not auto-register pos-web-common's {@code @AutoConfiguration}, so
 * {@link WebCommonErrorAutoConfiguration} is imported explicitly to exercise the real fallback
 * chain that maps the service's {@link ResponseStatusException} to the {@code 404} envelope.
 */
@WebMvcTest(CrmContactsController.class)
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
class CrmContactsControllerErrorHandlingTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final UUID PARTY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID CONTACT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final String ROLES_PATH = "/v1/crm/parties/" + PARTY_ID + "/contacts/" + CONTACT_ID + "/roles";
    private static final String UNKNOWN_ROLE_BODY = """
            {"roles":[{"roleCode":"NOT_A_ROLE"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContactRoleService contactRoleService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(NOW);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("an unrecognised roleCode answers 400 VALIDATION_ERROR with the envelope and a correlation id")
    void anUnrecognisedRoleCodeAnswers400WithTheEnvelope() throws Exception {
        when(contactRoleService.updateContactRoles(eq(PARTY_ID), eq(CONTACT_ID), any()))
                .thenThrow(new CrmValidationException(
                        "Unrecognised roleCode: NOT_A_ROLE", new IllegalArgumentException("No enum constant")));

        mockMvc.perform(put(ROLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNKNOWN_ROLE_BODY)
                        .header("X-Authorities", "crm:contact_role:assign")
                        .header("X-Correlation-Id", "corr-1714"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "corr-1714"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Unrecognised roleCode: NOT_A_ROLE"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.correlationId").value("corr-1714"));
    }

    /**
     * The request body is bean-validated at the boundary ({@code @Valid}), so a role entry with
     * no {@code roleCode} never reaches the service: it answers 400 {@code VALIDATION_FAILED}
     * with a field error naming the offending element, not a 500 from {@code valueOf(null)}.
     */
    @Test
    @DisplayName("a role entry without a roleCode answers 400 VALIDATION_FAILED with a field error, before the service")
    void aRoleEntryWithoutARoleCodeAnswers400BeforeTheService() throws Exception {
        mockMvc.perform(put(ROLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles":[{"isPrimary":true}]}
                                """)
                        .header("X-Authorities", "crm:contact_role:assign"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("roles[0].roleCode"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verify(contactRoleService, never()).updateContactRoles(any(), any(), any());
    }

    @Test
    @DisplayName("a missing party or contact still answers 404, now with the envelope and a correlation id")
    void aMissingPartyOrContactStillAnswers404WithTheEnvelope() throws Exception {
        when(contactRoleService.updateContactRoles(eq(PARTY_ID), eq(CONTACT_ID), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Person not found for personId: " + CONTACT_ID));

        mockMvc.perform(put(ROLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles":[{"roleCode":"BILLING","isPrimary":true}]}
                                """)
                        .header("X-Authorities", "crm:contact_role:assign"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
