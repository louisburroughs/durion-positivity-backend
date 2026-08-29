package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserService {

    UserDto createUser(String username, String password, Set<String> roleNames);

    /**
     * Creates a user with a strong password generated here and returned to no one.
     *
     * <p>For provisioning accounts in bulk, where the alternative is carrying password material in
     * an uploaded file that is written to disk and never deleted. The account exists and holds its
     * roles; whoever is to use it obtains a password through the ordinary reset path, so no
     * credential is ever written down as a side effect of seeding.
     */
    UserDto createUserWithGeneratedPassword(String username, Set<String> roleNames);

    Optional<UserAuthContext> getUserByUsername(String username);

    Optional<UserDto> getUserById(UUID id);

    List<UserDto> getAllUsers();

    void deleteUser(UUID id);

    UserDto assignRoles(String username, Set<String> roleNames);

    /**
     * Request a user→person link via the people-contact command channel (ADR-0043 §2).
     * The {@code users.person_id} projection updates when the confirming link fact
     * arrives; callers see it asynchronously. Throws when the user does not exist.
     */
    void requestPersonLink(UUID userId, UUID personId);

    UserDto updateUser(UUID id, UserUpdateRequest request);
}
