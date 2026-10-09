package com.positivity.customer.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.PersonService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /v1/crm/persons must accept the contact points the published contract declares. The
 * service-level contract ITs build {@link CreatePersonRequest} directly, so only a request that
 * goes through JSON binding catches a nested input the mapper cannot read.
 */
@WebMvcTest(CrmPersonController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S100"})
class CrmPersonControllerWebMvcTest {

    /** The body the generated TypeScript SDK sends: OpenAPI names the flag {@code primary}. */
    private static final String BODY_WITH_CONTACT_POINTS = """
            {"firstName":"Dana",
             "lastName":"Ortiz",
             "preferredContactMethod":"EMAIL",
             "emails":[{"value":"dana.ortiz@example.com","primary":true}],
             "phones":[{"value":"(512) 555-0142","primary":true}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PersonService personService;

    @Test
    void contactPointInputs_deserializeWithTheApplicationMapper() {
        CreatePersonRequest request = objectMapper.readValue(BODY_WITH_CONTACT_POINTS, CreatePersonRequest.class);

        assertThat(request.getPreferredContactMethod()).isEqualTo(PreferredContactMethod.EMAIL);
        assertThat(request.getEmails()).singleElement().satisfies(email -> {
            assertThat(email.getValue()).isEqualTo("dana.ortiz@example.com");
            assertThat(email.isPrimary()).isTrue();
        });
        assertThat(request.getPhones()).singleElement().satisfies(phone -> {
            assertThat(phone.getValue()).isEqualTo("(512) 555-0142");
            assertThat(phone.isPrimary()).isTrue();
        });
    }

    @Test
    void createCrmPerson_withEmailsAndPhones_returnsCreated() throws Exception {
        when(personService.createPerson(any(), any()))
                .thenReturn(CreatePersonResponse.builder()
                        .personId(UUID.randomUUID())
                        .build());

        mockMvc.perform(post("/v1/crm/persons")
                        .header("X-Authorities", CrmPermissionRegistry.PERSON_CREATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_WITH_CONTACT_POINTS))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreatePersonRequest> captor = ArgumentCaptor.forClass(CreatePersonRequest.class);
        verify(personService).createPerson(captor.capture(), any());
        assertThat(captor.getValue().getEmails()).hasSize(1);
        assertThat(captor.getValue().getPhones()).hasSize(1);
    }
}
