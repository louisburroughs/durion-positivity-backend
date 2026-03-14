package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.AdminAccountStateService;
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
    }

    @Override
    @Transactional
    public void expireAccount(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setAccountNonExpired(false);
        user.setAccountExpiresAt(clock.instant());
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void expireCredentials(@NonNull UUID userId) {
        User user = findUser(userId);
        user.setCredentialsNonExpired(false);
        user.setCredentialsExpireAt(clock.instant());
        userRepository.save(user);
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

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId.toString()));
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "system";
    }
}