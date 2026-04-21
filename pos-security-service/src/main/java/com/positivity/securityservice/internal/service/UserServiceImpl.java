package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.UserService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserDto createUser(String username, String password, Set<String> roleNames) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }
        Set<Role> roles = new HashSet<>();
        for (String roleName : roleNames) {
            Role role = roleRepository
                    .findByName(roleName)
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
            roles.add(role);
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(roles);
        return toDto(userRepository.save(user));
    }

    @Override
    public Optional<UserAuthContext> getUserByUsername(String username) {
        return userRepository.findByUsername(username).map(this::toAuthContext);
    }

    @Override
    public Optional<UserDto> getUserById(UUID id) {
        return userRepository.findById(id).map(this::toDto);
    }

    @Override
    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    public void deleteUser(UUID id) {
        userRepository.deleteById(id);
    }

    @Override
    @Transactional
    public UserDto assignRoles(String username, Set<String> roleNames) {
        User user = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Set<Role> roles = new HashSet<>();
        for (String roleName : roleNames) {
            Role role = roleRepository
                    .findByName(roleName)
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
            roles.add(role);
        }
        user.setRoles(roles);
        return toDto(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDto updateUser(UUID id, UserUpdateRequest request) {
        User existingUser =
                userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            existingUser.setUsername(request.getUsername());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            existingUser.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getRoles() != null) {
            Set<Role> roles = new HashSet<>();
            for (String roleName : request.getRoles()) {
                Role role = roleRepository
                        .findByName(roleName)
                        .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
                roles.add(role);
            }
            existingUser.setRoles(roles);
        }

        return toDto(userRepository.save(existingUser));
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .roles(resolveEffectiveRoleNames(user))
                .personId(user.getPersonId())
                .build();
    }

    private UserAuthContext toAuthContext(User user) {
        return UserAuthContext.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPassword())
                .roles(resolveEffectiveRoleNames(user))
                .build();
    }

    private Set<String> resolveEffectiveRoleNames(User user) {
        Set<String> roleNames = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        List<RoleAssignment> assignments = roleAssignmentRepository.findEffectiveAssignmentsByUser(user);
        if (assignments != null) {
            roleNames.addAll(assignments.stream()
                    .map(RoleAssignment::getRole)
                    .map(Role::getName)
                    .collect(Collectors.toSet()));
        }
        return roleNames;
    }
}
