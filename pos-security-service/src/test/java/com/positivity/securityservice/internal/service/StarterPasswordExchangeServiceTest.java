package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.ActivationTokenInvalidException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The starter password buys one thing and nothing else: a password of the account's own.
 *
 * <h2>What this defends</h2>
 *
 * The starter password is shared across every account a fixture pack provisions, so treating it as
 * a credential would hand one secret the run of twenty-five accounts. It is deliberately not one:
 * the account is created with its credentials expired, so login refuses it whatever is presented,
 * and this exchange is the only door it opens. These cases pin the three properties that make that
 * safe — it only works on an account the loader marked, it clears the marker so it cannot be used
 * twice, and every failure is indistinguishable from every other.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("starter password exchange")
class StarterPasswordExchangeServiceTest {

    private static final String STARTER = "starter-me";

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private StarterPasswordExchangeService.BoundExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = new StarterPasswordExchangeService.BoundExchange(userRepository, encoder, jwtService);
    }

    private User provisioned() {
        User user = new User();
        user.setId(UUID.fromString("01900000-0000-7000-8000-0000000000e1"));
        user.setUsername("marcus.webb");
        user.setPassword(encoder.encode("a-discarded-random-value-nobody-holds"));
        user.setStarterPasswordHash(encoder.encode(STARTER));
        user.setAwaitingActivation(true);
        user.setCredentialsNonExpired(false);
        return user;
    }

    private void stubLookup(User user) {
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("the starter password is never the account's password, so login cannot match it")
    void theStarterPasswordIsNotTheAccountsPassword() {
        User user = provisioned();

        assertThat(encoder.matches(STARTER, user.getPassword())).as("""
                        Spring compares the password before it checks credential expiry, so a starter \
                        password stored in the password column would answer CREDENTIALS_EXPIRED when \
                        guessed right and INVALID_CREDENTIALS otherwise — telling an unauthenticated \
                        caller both that the guess was correct and that the account is unclaimed. Held \
                        apart, login matches nothing and answers the same for every password.""").isFalse();
        assertThat(encoder.matches(STARTER, user.getStarterPasswordHash())).isTrue();
    }

    @Test
    void setsTheChosenPasswordAndClearsTheMarker() {
        User user = provisioned();
        stubLookup(user);

        exchange.exchange("marcus.webb", STARTER, "Ch0senPassw0rd!");

        assertThat(encoder.matches("Ch0senPassw0rd!", user.getPassword()))
                .as("the account now holds the password its owner chose")
                .isTrue();
        assertThat(user.isAwaitingActivation()).isFalse();
        assertThat(user.isCredentialsNonExpired()).isTrue();
        assertThat(user.getCredentialsExpireAt()).isNull();
        assertThat(user.getStarterPasswordHash())
                .as("cleared, so the shared password cannot re-open an account already claimed")
                .isNull();
        verify(jwtService).revokeAllTokensForUser("marcus.webb");
        verify(userRepository).save(user);
    }

    @Test
    void theStarterPasswordStopsWorkingOnceExchanged() {
        User user = provisioned();
        stubLookup(user);
        exchange.exchange("marcus.webb", STARTER, "Ch0senPassw0rd!");

        assertThatThrownBy(() -> exchange.exchange("marcus.webb", STARTER, "Another0ne!"))
                .as("the marker is gone, so a second claim of the same account is refused")
                .isInstanceOf(ActivationTokenInvalidException.class);
        assertThat(encoder.matches("Ch0senPassw0rd!", user.getPassword()))
                .as("and the password the owner chose is untouched")
                .isTrue();
    }

    @Test
    void refusesAnAccountTheLoaderDidNotProvision() {
        User live = provisioned();
        live.setAwaitingActivation(false);
        live.setCredentialsNonExpired(true);
        stubLookup(live);

        assertThatThrownBy(() -> exchange.exchange("marcus.webb", STARTER, "Ch0senPassw0rd!"))
                .as("a live account's password is never overwritten through this path")
                .isInstanceOf(ActivationTokenInvalidException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesAWrongStarterPassword() {
        stubLookup(provisioned());

        assertThatThrownBy(() -> exchange.exchange("marcus.webb", "not-the-starter", "Ch0senPassw0rd!"))
                .isInstanceOf(ActivationTokenInvalidException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesAnUnknownAccountWithTheSameAnswer() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchange.exchange("nobody", STARTER, "Ch0senPassw0rd!"))
                .as("an unknown username must be indistinguishable from a wrong starter password")
                .isInstanceOf(ActivationTokenInvalidException.class);
    }
}
