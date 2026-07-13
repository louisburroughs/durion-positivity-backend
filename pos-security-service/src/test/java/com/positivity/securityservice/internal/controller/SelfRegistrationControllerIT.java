package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
import com.positivity.securityservice.BaseIntegrationTest;
import com.positivity.securityservice.internal.client.CustomerRegistrationClient;
import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.SelfRegistrationAttemptRepository;
import com.positivity.securityservice.internal.repository.SelfRegistrationReviewCaseRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.PeopleContactCommandEmitter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Self-registration contract under the amended ADR-0043 (#876): person resolution runs against
 * the local {@code ext_people_contact_person} replica, the user is created unlinked
 * ({@code users.person_id} is a link-fact projection), and the link leaves as a command.
 */
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

    @Autowired
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @MockitoBean
    private PeopleContactCommandEmitter peopleContactCommandEmitter;

    @MockitoBean
    private CustomerRegistrationClient customerRegistrationClient;

    @BeforeEach
    void clearUsers() {
        selfRegistrationAttemptRepository.deleteAll();
        selfRegistrationReviewCaseRepository.deleteAll();
        userRepository.deleteAll();
        extPersonReplicaRepository.deleteAll();
        assertThat(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).isPresent();
    }

    private void seedReplicaPerson(UUID personId, String email, String phone) {
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(personId)
                .firstName("Jane")
                .lastName("Smith")
                .primaryEmail(email)
                .primaryPhone(phone)
                .aggregateVersion(0)
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 201 with a pending link and an unlinked user")
    void selfRegister_success_returnsCreated() throws Exception {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        seedReplicaPerson(personId, "jane@example.com", "+15551234567");
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", "+15551234567"))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        personId, "Jane", "Smith", "Jane Smith", List.of(), true, true, 2, null, null)));

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
                .andExpect(jsonPath("$.linkStatus").value("PENDING"))
                .andExpect(jsonPath("$.matchedExistingPerson").value(true))
                .andExpect(jsonPath("$.idempotencyKey").value("self-reg-001"))
                .andExpect(jsonPath("$.issuedTokens").value(false));

        // users.person_id is a projection written only from link facts — the fresh user is unlinked.
        User created = userRepository.findByUsername("jane").orElseThrow();
        assertThat(created.getPersonId()).isNull();
        assertThat(created.getRoles()).extracting("name").containsExactly("SELF_SERVICE_CUSTOMER");

        ArgumentCaptor<UserPersonLinkCreateRequestedV1> command =
                ArgumentCaptor.forClass(UserPersonLinkCreateRequestedV1.class);
        verify(peopleContactCommandEmitter).requestLinkCreate(command.capture());
        assertThat(command.getValue().personId()).isEqualTo(personId);
        assertThat(command.getValue().username()).isEqualTo("jane");
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
    @DisplayName("POST /v1/auth/self-register returns 409 when the person already has an active user")
    void selfRegister_personAlreadyLinked_returnsConflict() throws Exception {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        seedReplicaPerson(personId, "jane@example.com", null);
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());

        // The users.person_id projection is the local source for "who is linked to this person".
        User linked = new User();
        linked.setUsername("other.jane");
        linked.setPassword("encoded");
        linked.setPersonId(personId);
        linked.getRoles().add(roleRepository.findByName("SELF_SERVICE_CUSTOMER").orElseThrow());
        userRepository.save(linked);

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
                .andExpect(jsonPath("$.code").value("PERSON_ALREADY_HAS_ACTIVE_USER"));

        assertThat(userRepository.findByUsername("jane")).isEmpty();
    }

    @Test
    @DisplayName("POST /v1/auth/self-register returns 409 when CRM shows a conflicting existing identity")
    void selfRegister_crmConflict_returnsConflict() throws Exception {
        // No replica person seeded — the CRM conflict fires on the unmatched-person path,
        // before any person command or user row is created.
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
        verify(peopleContactCommandEmitter, org.mockito.Mockito.never()).requestPersonUpsert(any());
    }

    @Test
    @DisplayName("POST /v1/auth/self-register replays the same successful result for a reused idempotency key")
    void selfRegister_sameIdempotencyKey_replaysCreatedResponse() throws Exception {
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());

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
                .andExpect(jsonPath("$.matchedExistingPerson").value(false))
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
