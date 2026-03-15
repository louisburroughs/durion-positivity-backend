package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.AuthenticationService;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.LockoutService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String COUNTER_AUTH_SUCCESS = "auth.login.success";
    private static final String COUNTER_AUTH_FAILURE = "auth.login.failure";
    private static final String TAG_REASON = "reason";
    private static final String REASON_BAD_CREDENTIALS = "bad_credentials";
    private static final String REASON_ACCOUNT_LOCKED = "account_locked";
    private static final String REASON_ACCOUNT_DISABLED = "account_disabled";
    private static final String REASON_ACCOUNT_EXPIRED = "account_expired";
    private static final String REASON_CREDENTIALS_EXPIRED = "credentials_expired";

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LockoutService lockoutService;
    private final UserRepository userRepository;
    private final Counter authSuccessCounter;
    private final Counter badCredentialsCounter;
    private final Counter accountLockedCounter;
    private final Counter accountDisabledCounter;
    private final Counter accountExpiredCounter;
    private final Counter credentialsExpiredCounter;

    public AuthenticationServiceImpl(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            LockoutService lockoutService,
            UserRepository userRepository,
            MeterRegistry meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.lockoutService = lockoutService;
        this.userRepository = userRepository;
        this.authSuccessCounter = Counter.builder(COUNTER_AUTH_SUCCESS).register(meterRegistry);
        this.badCredentialsCounter = Counter.builder(COUNTER_AUTH_FAILURE).tag(TAG_REASON, REASON_BAD_CREDENTIALS).register(meterRegistry);
        this.accountLockedCounter = Counter.builder(COUNTER_AUTH_FAILURE).tag(TAG_REASON, REASON_ACCOUNT_LOCKED).register(meterRegistry);
        this.accountDisabledCounter = Counter.builder(COUNTER_AUTH_FAILURE).tag(TAG_REASON, REASON_ACCOUNT_DISABLED).register(meterRegistry);
        this.accountExpiredCounter = Counter.builder(COUNTER_AUTH_FAILURE).tag(TAG_REASON, REASON_ACCOUNT_EXPIRED).register(meterRegistry);
        this.credentialsExpiredCounter = Counter.builder(COUNTER_AUTH_FAILURE).tag(TAG_REASON, REASON_CREDENTIALS_EXPIRED).register(meterRegistry);
    }

    @Override
    @NonNull
    public TokenPairResponse login(@NonNull LoginRequest request) {
        // Pre-flight: resolve userId for lockout bookkeeping. If the user is not
        // found in the repository (e.g. unknown username), skip lockout checks —
        // AuthenticationManager will reject the credentials regardless.
        Optional<User> userEntityOpt = userRepository.findByUsername(request.username());
        final UUID userIdForLockout = userEntityOpt.map(User::getId).orElse(null);

        if (userIdForLockout != null) {
            lockoutService.unlockIfCooldownExpired(userIdForLockout);
            if (lockoutService.isLockedOut(userIdForLockout)) {
                incrementFailureCounter(REASON_ACCOUNT_LOCKED);
                throw new LockedException("Account is locked");
            }
        }

        final Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            handleAuthenticationFailure(ex, userIdForLockout);
            throw ex;
        }

        String username = authentication.getName();

        // Extract userId from principal. CustomUserDetailsService always returns
        // SecurityUserPrincipal. Any other principal type is a programming error —
        // fail fast rather than silently issuing a token bound to an untrackable identity.
        if (!(authentication.getPrincipal() instanceof CustomUserDetailsService.SecurityUserPrincipal p)) {
            throw new IllegalStateException(
                    "Unexpected principal type: " + authentication.getPrincipal().getClass().getName());
        }
        UUID userId = p.userId();

        // Post-auth bookkeeping should use the authenticated principal as source of truth.
        lockoutService.recordSuccessfulLogin(userId);

        Set<String> roleNames = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());

        JwtService.TokenPair pair = jwtService.generateTokenPair(username, userId, p.personId(), roleNames);
        incrementSuccessCounter();
        return TokenPairResponse.of(pair.accessToken(), pair.refreshToken());
    }

    private void incrementSuccessCounter() {
        authSuccessCounter.increment();
    }

    private void handleAuthenticationFailure(@NonNull AuthenticationException ex, UUID userIdForLockout) {
        if (userIdForLockout != null && ex instanceof BadCredentialsException) {
            lockoutService.recordFailedAttempt(userIdForLockout);
            incrementFailureCounter(REASON_BAD_CREDENTIALS);
        }
        if (ex instanceof DisabledException) {
            incrementFailureCounter(REASON_ACCOUNT_DISABLED);
        }
        if (ex instanceof AccountExpiredException) {
            incrementFailureCounter(REASON_ACCOUNT_EXPIRED);
        }
        if (ex instanceof CredentialsExpiredException) {
            incrementFailureCounter(REASON_CREDENTIALS_EXPIRED);
        }
    }

    private void incrementFailureCounter(@NonNull String reason) {
        switch (reason) {
            case REASON_BAD_CREDENTIALS -> badCredentialsCounter.increment();
            case REASON_ACCOUNT_LOCKED -> accountLockedCounter.increment();
            case REASON_ACCOUNT_DISABLED -> accountDisabledCounter.increment();
            case REASON_ACCOUNT_EXPIRED -> accountExpiredCounter.increment();
            case REASON_CREDENTIALS_EXPIRED -> credentialsExpiredCounter.increment();
            default -> { /* no-op for unmapped reason */ }
        }
    }
}
