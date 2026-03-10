package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.UserServiceImpl;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void createUser_assignsRolesAndEncodedPassword() {
        Role role = new Role();
        role.setName("ADMIN");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("secret")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDto result = userService.createUser("alice", "secret", Set.of("ADMIN"));

        assertThat(result.getUsername()).isEqualTo("alice");
        assertThat(result.getRoles()).contains("ADMIN");
    }

    @Test
    void createUser_duplicateUsername_throws() {
        Set<String> roleNames = Set.of("ADMIN");
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("alice", "secret", roleNames))
                .isInstanceOf(IllegalArgumentException.class)
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
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDto result = userService.assignRoles("alice", Set.of("ADMIN"));

        assertThat(result.getRoles()).contains("ADMIN");
    }

    @Test
    void deleteUser_delegatesToRepository() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        userService.deleteUser(userId);

        verify(userRepository).deleteById(userId);
    }

    @Test
    void createUser_roleNotFound_throws() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(roleRepository.findByName("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser("bob", "pass", Set.of("UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class)
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

        UserUpdateRequest req = new UserUpdateRequest();
        req.setUsername("newname");
        req.setPassword("newpass");
        req.setRoles(Set.of("MANAGER"));

        UserDto result = userService.updateUser(id, req);

        assertThat(result.getUsername()).isEqualTo("newname");
        assertThat(result.getRoles()).contains("MANAGER");
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
        assertThatThrownBy(() -> userService.updateUser(id, req))
                .isInstanceOf(IllegalArgumentException.class)
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Role not found");
    }

    @Test
    void assignRoles_userNotFound_throws() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.assignRoles("ghost", Set.of("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void assignRoles_roleNotFound_throws() {
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(roleRepository.findByName("GHOST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.assignRoles("alice", Set.of("GHOST")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Role not found");
    }
}
