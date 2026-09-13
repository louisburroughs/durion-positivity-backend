package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.ActivationTokenInvalidException;
import com.positivity.securityservice.internal.repository.UserRepository;
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

        @Transactional
        public void exchange(String username, String starterPassword, String newPassword) {
            User user = userRepository.findByUsername(username).orElseThrow(ActivationTokenInvalidException::new);

            // Only an account the bulk loader provisioned this way may be opened with a shared
            // password. A live account's password is never overwritten through this path, which is
            // the same rule the token exchange enforces with USER_NOT_AWAITING_ACTIVATION.
            if (!user.isAwaitingActivation()) {
                throw new ActivationTokenInvalidException();
            }
            if (!passwordEncoder.matches(starterPassword, user.getPassword())) {
                throw new ActivationTokenInvalidException();
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            user.setCredentialsNonExpired(true);
            user.setCredentialsExpireAt(null);
            user.setAwaitingActivation(false);
            userRepository.save(user);
            log.info("Starter password exchanged for a chosen password. userId={}", user.getId());
        }
    }
}
