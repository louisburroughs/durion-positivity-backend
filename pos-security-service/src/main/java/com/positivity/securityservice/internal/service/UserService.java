package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

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

    /**
     * Creates a user that cannot sign in until activated (ADR-0062 §7, WS2b-3): the password is
     * generated here and returned to no one, exactly as in {@link #createUserWithGeneratedPassword},
     * and the credentials are marked expired. A login attempt fails on the unmatchable password
     * with the same 401 as any wrong password, so the account's state is not observable from
     * outside. {@code POST /v1/auth/activate} with an operator-minted activation token sets the
     * first password and clears the flag.
     */
    @NonNull
    UserDto createUserAwaitingActivation(@NonNull String username, @NonNull Set<String> roleNames);

    /**
     * Creates a user whose first credential is the configured starter password.
     *
     * <p>For accounts provisioned in bulk from a fixture pack, where an operator has to be able to
     * tell people how to get in without a per-user token being minted for each of them. The starter
     * password is shared and therefore never a credential in its own right: the account is created
     * awaiting activation with its credentials already expired, so
     * {@code POST /v1/auth/login} refuses it, and the only thing the starter password opens is
     * {@code POST /v1/auth/activate-starter}, which trades it for a real password and clears the
     * marker. A starter password can never become a session.
     *
     * <p>With no starter password configured this behaves exactly like
     * {@link #createUserAwaitingActivation}: the account is created with a generated password
     * nobody holds, and only an operator-minted activation token can open it.
     */
    UserDto createUserAwaitingStarterExchange(@NonNull String username, @NonNull Set<String> roleNames);

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

    /**
     * The bound a reissued access token's {@code exp} clamps to (ADR-0061 §4 amendment,
     * 2026-09-09, #1914 phase 3): the earliest {@code effectiveEndDate} among {@code userId}'s
     * currently effective role assignments, if any is bounded. Internal-only — not surfaced
     * through any controller response; used by the login and refresh token-issuance paths.
     *
     * @param userId the user whose effective assignments are checked
     * @return empty when the user does not exist, or holds no bounded effective assignment
     */
    Optional<Instant> getGrantsExpireAt(UUID userId);
}
