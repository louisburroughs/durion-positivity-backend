package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseIntegrationTest;
import com.positivity.securityservice.internal.client.CustomerRegistrationClient;
import com.positivity.securityservice.internal.client.PeopleRegistrationClient;
import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonResponse;
import com.positivity.securityservice.internal.client.dto.PeopleUserLinkResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.SelfRegistrationAttemptRepository;
import com.positivity.securityservice.internal.repository.SelfRegistrationReviewCaseRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("SelfRegistrationControllerIT")
class SelfRegistrationControllerIT extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SelfRegistrationAttemptRepository selfRegistrationAttemptRepository;

    @Autowired
    private SelfRegistrationReviewCaseRepository selfRegistrationReviewCaseRepository;

    @MockitoBean
    private PeopleRegistrationClient peopleRegistrationClient;

    @MockitoBean
    private CustomerRegistrationClient customerRegistrationClient;

    @BeforeEach
    void clearUsers() {
        selfRegistrationAttemptRepository.deleteAll();
        selfRegistrationReviewCaseRepository.deleteAll();
        userRepository.deleteAll();
        assertThat(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).isPresent();
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 201 and creates a linked user")
    void selfRegister_success_returnsCreated() throws Exception {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", "+15551234567"))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        personId, "Jane", "Smith", "Jane Smith", List.of(), true, true, 2, null, null)));
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(
                        personId,
                        true,
                        60,
                        30,
                        List.of("EMAIL"),
                        "Jane",
                        "Smith",
                        "jane@example.com",
                        List.of("+15551234567")));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of());
        when(peopleRegistrationClient.linkUserToPerson(any())).thenAnswer(invocation -> {
            var req = invocation.getArgument(
                    0, com.positivity.securityservice.internal.client.dto.PeopleLinkUserRequest.class);
            return new PeopleUserLinkResponse(
                    UUID.randomUUID(),
                    req.userId(),
                    req.personId(),
                    "PRIMARY",
                    null,
                    "system",
                    "Created by self-registration");
        });

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Sup3rS3cret!",
                                  "firstName": "Jane",
                                  "lastName": "Smith",
                                  "phone": "+1-555-123-4567",
                                  "idempotencyKey": "self-reg-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personId").value(personId.toString()))
                .andExpect(jsonPath("$.username").value("jane"))
                .andExpect(jsonPath("$.linkStatus").value("LINKED"))
                .andExpect(jsonPath("$.matchedExistingPerson").value(true))
                .andExpect(jsonPath("$.idempotencyKey").value("self-reg-001"))
                .andExpect(jsonPath("$.issuedTokens").value(false));

        User created = userRepository.findByUsername("jane").orElseThrow();
        assertThat(created.getPersonId()).isEqualTo(personId);
        assertThat(created.getRoles()).extracting("name").containsExactly("SELF_SERVICE_CUSTOMER");
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 409 for duplicate account")
    void selfRegister_duplicateActiveAccount_returnsConflict() throws Exception {
        User existing = new User();
        existing.setUsername("jane");
        existing.setPassword("encoded");
        existing.getRoles()
                .add(roleRepository.findByName("SELF_SERVICE_CUSTOMER").orElseThrow());
        userRepository.save(existing);

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Sup3rS3cret!",
                                  "firstName": "Jane",
                                  "lastName": "Smith"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(
                        jsonPath("$.nextAction")
                                .value(
                                        "Sign in with the existing account or use password recovery instead of registering again."))
                .andExpect(
                        jsonPath("$.supportAction")
                                .value(
                                        "Confirm that the submitted username or derived email username already maps to an active user in pos-security-service. Preserve the existing account."));
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 409 when link conflict occurs")
    void selfRegister_linkConflict_returnsConflict() throws Exception {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(
                        personId, false, 0, 30, List.of("CREATED"), "Jane", "Smith", "jane@example.com", List.of()));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of());
        when(peopleRegistrationClient.linkUserToPerson(any()))
                .thenThrow(new SelfRegistrationConflictException(
                        "USER_PERSON_LINK_CONFLICT",
                        "User was created but could not be linked to the resolved person"));

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Sup3rS3cret!",
                                  "firstName": "Jane",
                                  "lastName": "Smith"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_PERSON_LINK_CONFLICT"));

        assertThat(userRepository.findByUsername("jane")).isEmpty();
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 409 when CRM shows a conflicting existing identity")
    void selfRegister_crmConflict_returnsConflict() throws Exception {
        UUID createdPersonId = UUID.fromString("00000000-0000-0000-0000-000000000203");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        UUID.fromString("00000000-0000-0000-0000-000000000204"),
                        "Jane",
                        "Smith",
                        "Jane Smith",
                        List.of(new CustomerPersonSearchResponse.ContactPointDto(
                                UUID.fromString("00000000-0000-0000-0000-000000000205"),
                                "EMAIL",
                                "jane@example.com",
                                true)),
                        true,
                        true,
                        1,
                        null,
                        null)));
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(
                        createdPersonId,
                        false,
                        0,
                        30,
                        List.of("CREATED"),
                        "Jane",
                        "Smith",
                        "jane@example.com",
                        List.of()));

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Sup3rS3cret!",
                                  "firstName": "Jane",
                                  "lastName": "Smith"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRM_PERSON_CONFLICT"))
                .andExpect(jsonPath("$.referenceId").isNotEmpty())
                .andExpect(
                        jsonPath("$.nextAction")
                                .value(
                                        "Do not retry self-registration. Contact support to review the existing customer or contact identity."))
                .andExpect(
                        jsonPath("$.supportAction")
                                .value(
                                        "Review CRM person matches, people resolution output, and linked users before creating or linking any account."));

        assertThat(userRepository.findByUsername("jane")).isEmpty();
    }

    @Test
    @DisplayName("POST /v1/auth/self-register replays the same successful result for a reused idempotency key")
    void selfRegister_sameIdempotencyKey_replaysCreatedResponse() throws Exception {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000211");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(
                        personId, false, 0, 30, List.of("CREATED"), "Jane", "Smith", "jane@example.com", List.of()));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of());
        when(peopleRegistrationClient.linkUserToPerson(any())).thenAnswer(invocation -> {
            var req = invocation.getArgument(
                    0, com.positivity.securityservice.internal.client.dto.PeopleLinkUserRequest.class);
            return new PeopleUserLinkResponse(
                    UUID.randomUUID(),
                    req.userId(),
                    req.personId(),
                    "PRIMARY",
                    null,
                    "system",
                    "Created by self-registration");
        });

        String payload = """
                {
                  "email": "jane@example.com",
                  "password": "Sup3rS3cret!",
                  "firstName": "Jane",
                  "lastName": "Smith",
                  "idempotencyKey": "self-reg-replay-001"
                }
                """;

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value("self-reg-replay-001"));

        String firstUserId =
                userRepository.findByUsername("jane").orElseThrow().getId().toString();

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(firstUserId))
                .andExpect(jsonPath("$.idempotencyKey").value("self-reg-replay-001"));

        assertThat(userRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("GET /v1/self-registration/review-cases returns blocked CRM review cases")
    void listReviewCases_returnsBlockedIdentityReviewCases() throws Exception {
        UUID createdPersonId = UUID.fromString("00000000-0000-0000-0000-000000000212");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        UUID.fromString("00000000-0000-0000-0000-000000000213"),
                        "Jane",
                        "Smith",
                        "Jane Smith",
                        List.of(new CustomerPersonSearchResponse.ContactPointDto(
                                UUID.fromString("00000000-0000-0000-0000-000000000214"),
                                "EMAIL",
                                "jane@example.com",
                                true)),
                        true,
                        true,
                        1,
                        null,
                        null)));
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(
                        createdPersonId,
                        false,
                        0,
                        30,
                        List.of("CREATED"),
                        "Jane",
                        "Smith",
                        "jane@example.com",
                        List.of()));

        mockMvc.perform(post("/v1/auth/self-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "jane@example.com",
                                  "password": "Sup3rS3cret!",
                                  "firstName": "Jane",
                                  "lastName": "Smith"
                                }
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(withAuth(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/v1/self-registration/review-cases")
                                .param("status", "OPEN")
                                .param("caseType", "IDENTITY_REVIEW"),
                        "security:user_account_state:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reasonCode").value("CRM_PERSON_CONFLICT"))
                .andExpect(jsonPath("$[0].email").value("jane@example.com"));
    }
}
