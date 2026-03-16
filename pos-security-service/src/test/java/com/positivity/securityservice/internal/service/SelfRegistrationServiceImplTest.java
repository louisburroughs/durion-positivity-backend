package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.client.CustomerRegistrationClient;
import com.positivity.securityservice.internal.client.PeopleRegistrationClient;
import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonResponse;
import com.positivity.securityservice.internal.client.dto.PeopleUserLinkResponse;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SelfRegistrationServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PeopleRegistrationClient peopleRegistrationClient;
    @Mock
    private CustomerRegistrationClient customerRegistrationClient;

    @InjectMocks
    private SelfRegistrationServiceImpl service;

    @Test
    void selfRegister_createsUserAfterPersonResolutionAndLinksPerson() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        Role customerRole = new Role();
        customerRole.setName("SELF_SERVICE_CUSTOMER");

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", "+15551234567"))
                .thenReturn(List.of(new CustomerPersonSearchResponse(
                        personId,
                        "Jane",
                        "Smith",
                        "Jane Smith",
                        List.of(),
                        true,
                        true,
                        2,
                        null,
                        null)));
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(personId, true, 60, 30, List.of("EMAIL"), "Jane", "Smith", "jane@example.com", List.of("+15551234567")));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of());
        when(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });
        when(peopleRegistrationClient.linkUserToPerson(any()))
                .thenReturn(new PeopleUserLinkResponse(UUID.randomUUID(), userId, personId, "PRIMARY", null, "system", null));

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
        assertThat(response.matchedExistingPerson()).isTrue();
        assertThat(response.issuedTokens()).isFalse();
        assertThat(response.crmMatchSummary()).isNotNull();
        assertThat(response.crmMatchSummary().getCandidateCount()).isEqualTo(1);
        assertThat(response.crmMatchSummary().getSharedIdentityCandidateCount()).isEqualTo(1);
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
        User linkedUser = new User();
        linkedUser.setId(linkedUserId);
        linkedUser.setEnabled(false);
        linkedUser.setAccountNonLocked(true);
        linkedUser.setAccountNonExpired(true);
        linkedUser.setCredentialsNonExpired(true);

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null)).thenReturn(List.of());
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(personId, true, 60, 30, List.of("EMAIL"), "Jane", "Smith", "jane@example.com", List.of()));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of(linkedUserId));
        when(userRepository.findById(linkedUserId)).thenReturn(Optional.of(linkedUser));

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                .email("jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("inactive linked user");
    }

    @Test
    void selfRegister_linkFailure_compensatesByDeletingCreatedUser() {
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000105");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000106");
        Role customerRole = new Role();
        customerRole.setName("SELF_SERVICE_CUSTOMER");

        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(customerRegistrationClient.searchPersons("Jane Smith", "jane@example.com", null)).thenReturn(List.of());
        when(peopleRegistrationClient.resolvePerson(any()))
                .thenReturn(new PeopleResolvePersonResponse(personId, false, 0, 30, List.of("CREATED"), "Jane", "Smith", "jane@example.com", List.of()));
        when(peopleRegistrationClient.getLinkedUserIds(personId)).thenReturn(List.of());
        when(roleRepository.findByName("SELF_SERVICE_CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(userId);
            return saved;
        });
        when(peopleRegistrationClient.linkUserToPerson(any())).thenThrow(new IllegalStateException("conflict"));

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                .email("jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("could not be linked");

        verify(userRepository).deleteById(userId);
    }

    @Test
    void selfRegister_crmConflictAfterPersonCreation_compensatesByDeletingCreatedPerson() {
        UUID createdPersonId = UUID.fromString("00000000-0000-0000-0000-000000000107");
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

        assertThatThrownBy(() -> service.selfRegister(SelfRegistrationRequest.builder()
                .email("jane@example.com")
                .password("secret")
                .firstName("Jane")
                .lastName("Smith")
                .build()))
                .isInstanceOf(SelfRegistrationConflictException.class)
                .hasMessageContaining("contact support");

        verify(peopleRegistrationClient).deletePerson(createdPersonId);
    }
}
