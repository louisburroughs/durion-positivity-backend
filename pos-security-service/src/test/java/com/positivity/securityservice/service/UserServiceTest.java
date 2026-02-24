package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.UserServiceImpl;

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
class UserServiceTest {

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
        UUID userId = UUID.randomUUID();

        userService.deleteUser(userId);

        verify(userRepository).deleteById(userId);
    }
}
