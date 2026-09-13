package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.ActivationTokenInvalidException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trades a bulk-provisioned account's shared starter password for a password of its own.
 *
 * <p>Accounts loaded from the alpha fixture packs are created by {@link
 * UserService#createUserAwaitingStarterExchange} holding the configured starter password, with
 * their credentials already expired and {@code awaiting_activation} set. An operator can therefore
 * tell twenty-five people how to get started without minting a token for each of them, while the
 * shared password stays incapable of authenticating anyone: {@code POST /v1/auth/login} checks
 * account state before it compares a password, so it answers {@code CREDENTIALS_EXPIRED} whatever
 * is presented. The only thing the starter password opens is this exchange, and the exchange
 * returns no token — the caller must then log in with the password they just chose.
 *
 * <p>Every failure answers the same {@link ActivationTokenInvalidException} as the token path: an
 * unknown username, a wrong starter password, an account that was never provisioned this way and
 * one already activated are indistinguishable to an unauthenticated caller, so this cannot be used
 * to discover who exists or which accounts are still unclaimed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarterPasswordExchangeService {

    private final UserRepository userRepository;
    private final LoginTenantResolver loginTenantResolver;
    private final PasswordEncoder passwordEncoder;
    private final BoundExchange boundExchange;
    private final Clock clock;

    /**
     * @param headerSlug the gateway's {@code X-Tenant-Slug}, if any
     * @param bodySlug the request body's {@code tenantSlug}, if any
     * @throws ActivationTokenInvalidException on any failure, deliberately without distinguishing
     */
    public void exchange(
            @Nullable String headerSlug,
            @Nullable String bodySlug,
            @NonNull String username,
            @NonNull String starterPassword,
            @NonNull String newPassword) {

        UUID tenantId;
        try {
            tenantId = loginTenantResolver.resolve(headerSlug, bodySlug);
        } catch (RuntimeException e) {
            // An unresolvable tenant is as uninformative as a wrong password, and for the same
            // reason: the caller is unauthenticated and must learn nothing from which one it was.
            throw new ActivationTokenInvalidException();
        }

        TenantContext.runAs(tenantId, () -> boundExchange.exchange(username, starterPassword, newPassword));
    }

    /** The transactional half, separate so the proxy is honoured across the tenant rebind. */
    @Slf4j
    @Service
    @RequiredArgsConstructor
    public static class BoundExchange {

        private final UserRepository userRepository;
        private final PasswordEncoder passwordEncoder;
        private final JwtService jwtService;

        @Transactional
        public void exchange(String username, String starterPassword, String newPassword) {
            UUID userId = userRepository
                    .findByUsername(username)
                    .map(User::getId)
                    .orElseThrow(ActivationTokenInvalidException::new);

            // Locked before anything is read for a decision, the same lock the token exchange and
            // PUT /v1/users/{id} take. Two people claiming the same account concurrently would
            // otherwise both see it unclaimed, both set a password, and the loser would be told they
            // had succeeded while holding a password the account does not have.
            User user = userRepository.findByIdForUpdate(userId).orElseThrow(ActivationTokenInvalidException::new);

            // Only an account the loader provisioned this way may be opened with a shared password.
            // A live account's password is never overwritten through this path, which is the rule the
            // token exchange enforces with USER_NOT_AWAITING_ACTIVATION.
            if (!user.isAwaitingActivation()) {
                throw new ActivationTokenInvalidException();
            }
            String expected = user.getStarterPasswordHash();
            if (expected == null || expected.isBlank() || !passwordEncoder.matches(starterPassword, expected)) {
                throw new ActivationTokenInvalidException();
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            user.setStarterPasswordHash(null);
            user.setCredentialsNonExpired(true);
            user.setCredentialsExpireAt(null);
            user.setAwaitingActivation(false);

            // Lockout bookkeeping, for the reason the token exchange records: failed attempts are
            // counted against this userId while the account is unclaimed, so without this a freshly
            // claimed account can arrive already locked out by its own rejected logins.
            user.setAccountNonLocked(true);
            user.setFailedLoginAttempts(0);
            user.setLockedAt(null);
            user.setLockedUntil(null);
            userRepository.save(user);

            // Token revocation, likewise: JwtController can mint a token for a username with no
            // awaiting-activation check, and such a token is refused only while credentialsNonExpired
            // is false. The line above sets it true, so anything minted before this commit would
            // start authenticating and bypass the exchange entirely.
            jwtService.revokeAllTokensForUser(user.getUsername());
            log.info("Starter password exchanged for a chosen password. userId={}", user.getId());
        }
    }
}
