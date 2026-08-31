package com.positivity.securityservice.internal.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * #1612: {@link UserSelfCheck} widens a read, so what matters most is every way it says no.
 *
 * <p>It is reached from a {@code @PreAuthorize} on {@code GET /v1/users/{userId}/permissions}. A
 * false positive here would let any authenticated caller read another user's effective permission
 * set — the exact data the {@code security:permission:view} guard exists to protect.
 */
@DisplayName("UserSelfCheck (#1612)")
class UserSelfCheckTest {

    private static final UUID CALLER_ID = UUID.fromString("01990010-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ID = UUID.fromString("01990010-0000-7000-8000-0000000000a2");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserSelfCheck selfCheck = new UserSelfCheck(userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the caller asking about themselves is allowed")
    void selfIsAllowed() {
        authenticateAs("kyle.brennan");
        when(userRepository.findByUsername("kyle.brennan")).thenReturn(Optional.of(userWithId(CALLER_ID)));

        assertThat(selfCheck.isSelf(CALLER_ID)).isTrue();
    }

    @Test
    @DisplayName("the caller asking about someone else is refused")
    void otherUserIsRefused() {
        authenticateAs("kyle.brennan");
        when(userRepository.findByUsername("kyle.brennan")).thenReturn(Optional.of(userWithId(CALLER_ID)));

        assertThat(selfCheck.isSelf(OTHER_ID)).isFalse();
    }

    @Test
    @DisplayName("an unauthenticated request is refused without a lookup")
    void noAuthenticationIsRefused() {
        assertThat(selfCheck.isSelf(CALLER_ID)).isFalse();
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("an anonymous token is refused")
    void anonymousIsRefused() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(selfCheck.isSelf(CALLER_ID)).isFalse();
    }

    @Test
    @DisplayName("a username matching no user is refused")
    void unknownUsernameIsRefused() {
        authenticateAs("ghost");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(selfCheck.isSelf(CALLER_ID)).isFalse();
    }

    @Test
    @DisplayName("a blank principal is refused without a lookup")
    void blankPrincipalIsRefused() {
        authenticateAs("   ");

        assertThat(selfCheck.isSelf(CALLER_ID)).isFalse();
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("a null userId is refused without a lookup")
    void nullUserIdIsRefused() {
        authenticateAs("kyle.brennan");

        assertThat(selfCheck.isSelf(null)).isFalse();
        verify(userRepository, never()).findByUsername(anyString());
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        username, null, AuthorityUtils.createAuthorityList("PERM_mcp:chat:execute")));
    }

    private static User userWithId(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
