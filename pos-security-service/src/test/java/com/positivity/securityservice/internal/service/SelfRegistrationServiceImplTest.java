package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
import com.positivity.securityservice.internal.client.CustomerRegistrationClient;
import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.SelfRegistrationAttempt;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.SelfRegistrationAttemptStatus;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.SelfRegistrationReviewService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Self-registration under the amended ADR-0043 (#876): person resolution is local (replica
 * match / person upsert command), the user is created unlinked, and the link travels as a
 * {@code people-contact.user-person-link.create-requested} command.
 */
@ExtendWith(MockitoExtension.class)
class SelfRegistrationServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PersonResolutionService personResolutionService;

    @Mock
    private PeopleContactCommandEmitter peopleContactCommandEmitter;

    @Mock
    private CustomerRegistrationClient customerRegistrationClient;

    @Mock
    private SelfRegistrationAttemptService selfRegistrationAttemptService;

    @Mock
    private SelfRegistrationReviewService selfRegistrationReviewService;

    @InjectMocks
    private SelfRegistrationServiceImpl service;

    @Test
    void selfRegister_matchedPerson_createsUnlinkedUserAndQueuesLinkCommand() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        Role customerRole = new Role();
        customerRole.setName("SELF_SERVICE_CUSTOMER");

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", "+15551234567"))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        personId, "Jane", "Smith", "Jane Smith", List.of(), true, true, 2, null, null)));
        when(personResolutionService.match("jane@example.com", "+15551234567", "Jane", "Smith"))
                .thenReturn(Optional.of(personId));
        when(userRepository.findByPersonId(personId)).thenReturn(Optional.empty());
        when(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });

        SelfRegistrationResponse response = service.selfRegister(SelfRegistrationRequest.builder()
                .email("Jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .phone("+1-555-123-4567")
                .build());

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.personId()).isEqualTo(personId);
        assertThat(response.username()).isEqualTo("jane");
        assertThat(response.linkStatus()).isEqualTo("PENDING");
        assertThat(response.matchedExistingPerson()).isTrue();
        assertThat(response.issuedTokens()).isFalse();

        // users.person_id is a projection: the created user row must NOT carry the person id.
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPersonId()).isNull();

        ArgumentCaptor<UserPersonLinkCreateRequestedV1> linkCommand =
                ArgumentCaptor.forClass(UserPersonLinkCreateRequestedV1.class);
        verify(peopleContactCommandEmitter).requestLinkCreate(linkCommand.capture());
        assertThat(linkCommand.getValue().personId()).isEqualTo(personId);
        assertThat(linkCommand.getValue().username()).isEqualTo("jane");
    }

    @Test
    void selfRegister_noMatch_createsPersonViaCommand() {
        UUID createdPersonId = UUID.fromString("00000000-0000-0000-0000-000000000110");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        Role customerRole = new Role();
        customerRole.setName("SELF_SERVICE_CUSTOMER");

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());
        when(personResolutionService.match("jane@example.com", null, "Jane", "Smith"))
                .thenReturn(Optional.empty());
        when(personResolutionService.createPerson("jane@example.com", null, "Jane", "Smith"))
                .thenReturn(createdPersonId);
        when(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });

        SelfRegistrationResponse response = service.selfRegister(SelfRegistrationRequest.builder()
                .email("jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .build());

        assertThat(response.personId()).isEqualTo(createdPersonId);
        assertThat(response.matchedExistingPerson()).isFalse();
        assertThat(response.linkStatus()).isEqualTo("PENDING");
        verify(peopleContactCommandEmitter).requestLinkCreate(any(UserPersonLinkCreateRequestedV1.class));
    }

    @Test
    void selfRegister_replaysSuccessfulAttemptForSameIdempotencyKey() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        SelfRegistrationAttempt attempt = new SelfRegistrationAttempt();
        attempt.setIdempotencyKey("retry-001");
        attempt.setRequestFingerprint("90347d3f97f7eba7e0c299d832330bc658c3de70865560154f2f585b8b00f42c");
        attempt.setStatus(SelfRegistrationAttemptStatus.SUCCEEDED);
        attempt.setUserId(userId);
        attempt.setPersonId(personId);
        attempt.setUsername("jane");
        attempt.setLinkStatus("PENDING");
        attempt.setMatchedExistingPerson(true);
        attempt.setIssuedTokens(false);
        attempt.setCrmCandidateCount(1);
        attempt.setCrmAnyMatches(true);
        attempt.setCrmSharedIdentityCandidateCount(1);
        attempt.setCrmExactEmailMatch(true);
        attempt.setCrmExactPhoneMatch(false);
        attempt.setCrmExactNameMatch(true);
        attempt.setCrmReviewRequired(true);

        when(selfRegistrationAttemptService.findByIdempotencyKey("retry-001")).thenReturn(Optional.of(attempt));

        SelfRegistrationResponse response = service.selfRegister(SelfRegistrationRequest.builder()
                .email("jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .idempotencyKey("retry-001")
                .build());

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.personId()).isEqualTo(personId);
        assertThat(response.username()).isEqualTo("jane");
        assertThat(response.idempotencyKey()).isEqualTo("retry-001");
        verifyNoInteractions(
                userRepository,
                roleRepository,
                customerRegistrationClient,
                personResolutionService,
                peopleContactCommandEmitter);
    }

    @Test
    void selfRegister_existingActiveUser_blocksRegistration() {
        User existing = new User();
        existing.setUsername("jane");
        existing.setEnabled(true);
        existing.setAccountNonLocked(true);
        existing.setAccountNonExpired(true);
        existing.setCredentialsNonExpired(true);
        when(userRepository.findByUsername("jane")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                        .email("jane@example.com")
                        .password("secret")
                        .firstName("Jane")
                        .lastName("Smith")
                        .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void selfRegister_existingInactiveLinkedUser_requiresRecovery() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID linkedUserId = UUID.fromString("00000000-0000-0000-0000-000000000104");
        UUID reviewCaseId = UUID.fromString("00000000-0000-0000-0000-000000000113");
        User linkedUser = new User();
        linkedUser.setId(linkedUserId);
        linkedUser.setEnabled(false);
        linkedUser.setAccountNonLocked(true);
        linkedUser.setAccountNonExpired(true);
        linkedUser.setCredentialsNonExpired(true);

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());
        when(personResolutionService.match("jane@example.com", null, "Jane", "Smith"))
                .thenReturn(Optional.of(personId));
        // The projection answers "who is linked to this person" locally now.
        when(userRepository.findByPersonId(personId)).thenReturn(Optional.of(linkedUser));
        when(selfRegistrationReviewService.openCase(any())).thenReturn(reviewCaseId);

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                        .email("jane@example.com")
                        .password("secret")
                        .firstName("Jane")
                        .lastName("Smith")
                        .idempotencyKey("retry-recovery")
                        .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("inactive linked user")
                .extracting("referenceId")
                .isEqualTo(reviewCaseId);

        verify(selfRegistrationAttemptService)
                .recordConflict(
                        eq("retry-recovery"),
                        anyString(),
                        eq("jane@example.com"),
                        eq("jane"),
                        eq("ACCOUNT_RECOVERY_REQUIRED"),
                        eq("Resolved person already has an inactive linked user and must go through recovery"),
                        eq(reviewCaseId));
    }

    @Test
    void selfRegister_activeLinkedUser_blocksRegistration() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        User linkedUser = new User();
        linkedUser.setId(UUID.fromString("00000000-0000-0000-0000-000000000104"));
        linkedUser.setEnabled(true);
        linkedUser.setAccountNonLocked(true);
        linkedUser.setAccountNonExpired(true);
        linkedUser.setCredentialsNonExpired(true);

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of());
        when(personResolutionService.match("jane@example.com", null, "Jane", "Smith"))
                .thenReturn(Optional.of(personId));
        when(userRepository.findByPersonId(personId)).thenReturn(Optional.of(linkedUser));

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                        .email("jane@example.com")
                        .password("secret")
                        .firstName("Jane")
                        .lastName("Smith")
                        .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    void selfRegister_crmConflictOnUnmatchedPerson_blocksBeforeAnythingIsCreated() {
        UUID reviewCaseId = UUID.fromString("00000000-0000-0000-0000-000000000114");
        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        UUID.fromString("00000000-0000-0000-0000-000000000108"),
                        "Jane",
                        "Smith",
                        "Jane Smith",
                        List.of(new CustomerPersonSearchResponse.ContactPointDto(
                                UUID.fromString("00000000-0000-0000-0000-000000000109"),
                                "EMAIL",
                                "jane@example.com",
                                true)),
                        true,
                        true,
                        1,
                        null,
                        null)));
        when(personResolutionService.match("jane@example.com", null, "Jane", "Smith"))
                .thenReturn(Optional.empty());
        when(selfRegistrationReviewService.openCase(any())).thenReturn(reviewCaseId);

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                        .email("jane@example.com")
                        .password("secret")
                        .firstName("Jane")
                        .lastName("Smith")
                        .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("contact support");

        // Conflict detected before creation: no person command, no user, no compensation.
        verify(personResolutionService, never()).createPerson(any(), any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(peopleContactCommandEmitter);
    }
}
