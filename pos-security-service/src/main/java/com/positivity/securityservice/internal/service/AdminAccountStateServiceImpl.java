package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAccountStateServiceImpl implements AdminAccountStateService {

    private final UserRepository userRepository;
    private final Clock clock;
    private final JwtService jwtService;
    private final ImpersonationTokenRevocationService impersonationTokenRevocationService;

    @Override
    @Transactional
    public void unlock(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setAccountNonLocked(true);
        user.setFailedLoginAttempts(0);
        user.setLockedAt(null);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void enable(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setEnabled(true);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void disable(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setEnabled(false);
        user.setDisabledBy(resolveActor());
        user.setDisabledAt(clock.instant());
        userRepository.save(user);
        revokeLiveTokens(user);
    }

    @Override
    @Transactional
    public void expireAccount(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setAccountNonExpired(false);
        user.setAccountExpiresAt(clock.instant());
        userRepository.save(user);
        revokeLiveTokens(user);
    }

    @Override
    @Transactional
    public void expireCredentials(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setCredentialsNonExpired(false);
        user.setCredentialsExpireAt(clock.instant());
        userRepository.save(user);
        revokeLiveTokens(user);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AccountStateResponse getAccountState(@NonNull UUID userId) {
        User user = findUser(userId);
        return AccountStateResponse.builder()
                .userId(user.getId())
                .enabled(user.isEnabled())
                .accountNonLocked(user.isAccountNonLocked())
                .accountNonExpired(user.isAccountNonExpired())
                .credentialsNonExpired(user.isCredentialsNonExpired())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .lockedAt(user.getLockedAt())
                .lockedUntil(user.getLockedUntil())
                .disabledAt(user.getDisabledAt())
                .disabledBy(user.getDisabledBy())
                .accountExpiresAt(user.getAccountExpiresAt())
                .credentialsExpireAt(user.getCredentialsExpireAt())
                .build();
    }

    /**
     * Ends every token the user can still present. {@link JwtService#revokeAllTokensForUser} covers
     * the ones stored under their own username in the bound tenant; an impersonation token this
     * user minted as a platform operator is stored in another tenant under a synthetic subject and
     * is reached only by operator id (ADR-0062 §7, WS2b-4) — without the second call, disabling or
     * expiring an operator left their support tokens usable until their own 15-minute expiry.
     */
    private void revokeLiveTokens(User user) {
        jwtService.revokeAllTokensForUser(user.getUsername());
        impersonationTokenRevocationService.revokeForOperator(user.getId());
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "system";
    }
}
