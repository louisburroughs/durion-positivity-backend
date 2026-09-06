package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.DuplicateUsernameException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.security.SecureRandom;
import java.util.Base64;
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GENERATED_PASSWORD_BYTES = 32;

    /**
     * Prefix of the {@link RoleNotFoundException} message for a named role that does not resolve —
     * 404 {@code ROLE_NOT_FOUND} on every entry point, the same answer the role-management
     * endpoints give (ADR-0017 §2 "one condition, one status", #1802 / #1808 review).
     */
    private static final String ROLE_NOT_FOUND_PREFIX = "Role not found: ";

    private final UserRepository userRepository;

    private final com.positivity.securityservice.internal.service.PeopleContactCommandEmitter
            peopleContactCommandEmitter;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserDto createUserWithGeneratedPassword(String username, Set<String> roleNames) {
        // 32 bytes from a cryptographically strong source, hashed by createUser like any other
        // password and then dropped: it is never returned, logged, or persisted in plaintext.
        byte[] entropy = new byte[GENERATED_PASSWORD_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return createUser(username, generated, roleNames);
    }

    @Override
    public UserDto createUser(String username, String password, Set<String> roleNames) {
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateUsernameException("Username already exists");
        }
        Set<Role> roles = new HashSet<>();
        for (String roleName : roleNames) {
            Role role = roleRepository
                    .findByName(roleName)
                    .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleName));
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
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException("User not found: " + id));
        // The user-person link is owned by pos-people-contact (amended ADR-0043, #876): queue its
        // removal in the same transaction so the link (and every consumer's projection of it)
        // follows the account out.
        peopleContactCommandEmitter.requestLinkRemove(
                new com.positivity.domainevents.peoplecontact.UserPersonLinkRemoveRequestedV1(user.getUsername()));
        userRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void requestPersonLink(UUID userId, UUID personId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        // The link is owned by pos-people-contact (amended ADR-0043 §2): request it over the
        // command channel; users.person_id is a projection written only by the link-fact
        // consumer, so this method never touches it directly.
        peopleContactCommandEmitter.requestLinkCreate(
                new com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1(
                        personId, user.getUsername(), "PRIMARY", "Linked by operator"));
    }

    @Override
    @Transactional
    public UserDto assignRoles(String username, Set<String> roleNames) {
        // A username that does not resolve is a referenced resource that does not exist, so it
        // answers 404 USER_NOT_FOUND like every other user reference (ADR-0017 §2, #1802). The
        // message is kept generic — the username is unvalidated caller text and naming it would
        // let a caller enumerate accounts (ADR-0056 §1, #1715). Role names below are different:
        // they are a fixed catalogue, so RoleNotFoundException echoes the one that did not resolve.
        User user =
                userRepository.findByUsername(username).orElseThrow(() -> new UserNotFoundException("User not found"));
        Set<Role> roles = new HashSet<>();
        for (String roleName : roleNames) {
            Role role = roleRepository
                    .findByName(roleName)
                    .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleName));
            roles.add(role);
        }
        user.setRoles(roles);
        return toDto(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDto updateUser(UUID id, UserUpdateRequest request) {
        User existingUser =
                userRepository.findById(id).orElseThrow(() -> new UserNotFoundException("User not found: " + id));

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
                        .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleName));
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
