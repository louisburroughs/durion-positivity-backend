package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.EffectiveGrantResolver.EffectiveGrants;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private EffectiveGrantResolver effectiveGrantResolver;

    @Mock
    private UserRoleGrantService userRoleGrantService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private com.positivity.securityservice.internal.service.PeopleContactCommandEmitter peopleContactCommandEmitter;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void stubResolverToReturnNoGrantsByDefault() {
        // UserServiceImpl asks the resolver for a user's effective role names; the resolver's own
        // behaviour is covered by EffectiveGrantResolverImplTest and EffectiveGrantAgreementIT.
        // Roles now live in role_assignments rather than on the User object itself (ADR-0061
        // amendment phase 2, #1914), so a test that cares about the resolved roles stubs this
        // explicitly for its own User instance (registered after this one, it takes precedence);
        // this default just keeps every other test's toDto/toAuthContext call from NPEing.
        lenient()
                .when(effectiveGrantResolver.resolve(any(User.class)))
                .thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of()));
    }

    @Test
    void createUser_assignsRolesAndEncodedPassword() {
        Role role = new Role();
        role.setName("ADMIN");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("secret")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(effectiveGrantResolver.resolve(any(User.class)))
                .thenReturn(new EffectiveGrants(Set.of(role), Set.of("ADMIN"), Set.of()));

        UserDto result = userService.createUser("alice", "secret", Set.of("ADMIN"));

        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.getRoles()).contains("ADMIN");
        verify(userRoleGrantService).grant(any(User.class), eq(role), anyString());
    }

    @Test
    void createUser_duplicateUsername_throws() {
        Set<String> roleNames = Set.of("ADMIN");
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("alice", "secret", roleNames))
                .isInstanceOf(com.positivity.securityservice.internal.exception.DuplicateUsernameException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    void assignRoles_updatesExistingUserRoles() {
        Role role = new Role();
        role.setName("ADMIN");
        User existingUser = new User();
        existingUser.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existingUser));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role));
        when(effectiveGrantResolver.resolve(existingUser))
                .thenReturn(new EffectiveGrants(Set.of(role), Set.of("ADMIN"), Set.of()));

        UserDto result = userService.assignRoles("alice", Set.of("ADMIN"));

        assertThat(result.getRoles()).contains("ADMIN");
        verify(userRoleGrantService).reconcile(eq(existingUser), eq(Set.of(role)), anyString());
    }

    @Test
    void requestPersonLink_emitsLinkCreateCommandWithUsername() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.requestPersonLink(userId, personId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1.class);
        verify(peopleContactCommandEmitter).requestLinkCreate(captor.capture());
        assertThat(captor.getValue().personId()).isEqualTo(personId);
        assertThat(captor.getValue().username()).isEqualTo("alice");
        assertThat(captor.getValue().linkType()).isEqualTo("PRIMARY");
    }

    @Test
    void requestPersonLink_unknownUser_throwsNotFound() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        userService.requestPersonLink(userId, UUID.fromString("00000000-0000-0000-0000-000000000002")))
                .isInstanceOf(com.positivity.securityservice.internal.exception.UserNotFoundException.class);
    }

    @Test
    void deleteUser_delegatesToRepository() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.deleteUser(userId);

        verify(userRepository).deleteById(userId);
    }

    @Test
    void deleteUser_unknownId_throwsAndNeverDeletes() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(userId))
                .isInstanceOf(com.positivity.securityservice.internal.exception.UserNotFoundException.class);

        verify(userRepository, org.mockito.Mockito.never()).deleteById(any());
        verify(peopleContactCommandEmitter, org.mockito.Mockito.never()).requestLinkRemove(any());
    }

    @Test
    void createUser_roleNotFound_throws() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(roleRepository.findByName("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser("bob", "pass", Set.of("UNKNOWN")))
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessageContaining("Role not found");
    }

    @Test
    void getUserByUsername_found_returnsAuthContext() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        Optional<UserAuthContext> result = userService.getUserByUsername("alice");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("alice");
    }

    @Test
    void getUserByUsername_includesEffectiveRoleAssignments() {
        // UserServiceImpl no longer computes the union itself: it asks EffectiveGrantResolver,
        // which is what carries a user_roles-vs-role_assignments grant (proven directly by
        // EffectiveGrantResolverImplTest and end-to-end by EffectiveGrantAgreementIT).
        User user = new User();
        user.setUsername("admin.alpha");
        user.setPassword("hashed");

        when(userRepository.findByUsername("admin.alpha")).thenReturn(Optional.of(user));
        when(effectiveGrantResolver.resolve(user)).thenReturn(new EffectiveGrants(Set.of(), Set.of("ADMIN"), Set.of()));

        Optional<UserAuthContext> result = userService.getUserByUsername("admin.alpha");

        assertThat(result).isPresent();
        assertThat(result.get().getRoles()).contains("ADMIN");
    }

    @Test
    void getUserByUsername_notFound_returnsEmpty() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThat(userService.getUserByUsername("nobody")).isEmpty();
    }

    @Test
    void getUserById_found_returnsDto() {
        User user = new User();
        user.setUsername("alice");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        assertThat(userService.getUserById(id)).isPresent();
    }

    @Test
    void getUserById_notFound_returnsEmpty() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(userService.getUserById(id)).isEmpty();
    }

    @Test
    void getAllUsers_returnsMappedList() {
        User u1 = new User();
        u1.setUsername("alice");
        User u2 = new User();
        u2.setUsername("bob");
        when(userRepository.findAll()).thenReturn(List.of(u1, u2));

        List<UserDto> result = userService.getAllUsers();

        assertThat(result).hasSize(2);
    }

    @Test
    void updateUser_updatesAllFields() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Role role = new Role();
        role.setName("MANAGER");
        User existing = new User();
        existing.setUsername("old");
        existing.setPassword("oldpass");

        when(userRepository.findById(id)).thenReturn(Optional.of(existing));
        when(roleRepository.findByName("MANAGER")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("newpass")).thenReturn("newEncoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(effectiveGrantResolver.resolve(existing))
                .thenReturn(new EffectiveGrants(Set.of(role), Set.of("MANAGER"), Set.of()));

        UserUpdateRequest req = new UserUpdateRequest();
        req.setUsername("newname");
        req.setPassword("newpass");
        req.setRoles(Set.of("MANAGER"));

        UserDto result = userService.updateUser(id, req);

        assertThat(result.getUsername()).isEqualTo("newname");
        assertThat(result.getRoles()).contains("MANAGER");
        verify(userRoleGrantService).reconcile(eq(existing), eq(Set.of(role)), anyString());
    }

    @Test
    void updateUser_noFieldsProvided_returnsUnchanged() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User existing = new User();
        existing.setUsername("alice");
        when(userRepository.findById(id)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserUpdateRequest req = new UserUpdateRequest();
        UserDto result = userService.updateUser(id, req);

        assertThat(result.getUsername()).isEqualTo("alice");
    }

    @Test
    void updateUser_userNotFound_throws() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        UserUpdateRequest req = new UserUpdateRequest();
        // A user reference that does not resolve is UserNotFoundException (404) on every entry
        // point, not the 400 validation type (ADR-0017 §2, #1802).
        assertThatThrownBy(() -> userService.updateUser(id, req))
                .isInstanceOf(com.positivity.securityservice.internal.exception.UserNotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void updateUser_roleNotFound_throws() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User existing = new User();
        existing.setUsername("alice");
        when(userRepository.findById(id)).thenReturn(Optional.of(existing));
        when(roleRepository.findByName("GHOST")).thenReturn(Optional.empty());

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRoles(Set.of("GHOST"));

        assertThatThrownBy(() -> userService.updateUser(id, req))
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessageContaining("Role not found");
    }

    @Test
    void assignRoles_userNotFound_throws() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.assignRoles("ghost", Set.of("ADMIN")))
                .isInstanceOf(com.positivity.securityservice.internal.exception.UserNotFoundException.class)
                .hasMessage("User not found")
                // The username is caller-supplied text and must not be echoed (ADR-0056 §1).
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                        .doesNotContain("ghost"));
    }

    @Test
    void assignRoles_roleNotFound_throws() {
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(roleRepository.findByName("GHOST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.assignRoles("alice", Set.of("GHOST")))
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessageContaining("Role not found");
    }
}
