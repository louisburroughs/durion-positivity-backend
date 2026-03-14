package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.AuthenticationService;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.LockoutService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
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
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LockoutService lockoutService;
    private final UserRepository userRepository;

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
                throw new LockedException("Account is locked");
            }
        }

        final Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            if (userIdForLockout != null && ex instanceof BadCredentialsException) {
                lockoutService.recordFailedAttempt(userIdForLockout);
            }
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

        // Use userId from the authenticated principal (authoritative source post-authentication);
        // this is always consistent with userIdForLockout but avoids relying on the pre-flight
        // Optional path for post-authentication bookkeeping.
        lockoutService.recordSuccessfulLogin(userId);

        Set<String> roleNames = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());

        JwtService.TokenPair pair = jwtService.generateTokenPair(username, userId, roleNames);
        return TokenPairResponse.of(pair.accessToken(), pair.refreshToken());
    }
}
