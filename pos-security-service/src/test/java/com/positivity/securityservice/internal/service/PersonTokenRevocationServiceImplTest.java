package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for person-keyed revocation and the fail-open Redis policy (ADR-0061 §4, #1874). */
class PersonTokenRevocationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f2");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenRepository jwtTokenRepository = mock(JwtTokenRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final TokenRevocationManager tokenRevocationManager = mock(TokenRevocationManager.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private PersonTokenRevocationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        service = new PersonTokenRevocationServiceImpl(
                TEST_CLOCK, userRepository, jwtTokenRepository, jwtService, tokenRevocationManager, provider);
        logs.start();
        ((Logger) LoggerFactory.getLogger(PersonTokenRevocationServiceImpl.class)).addAppender(logs);
        when(tokenRevocationManager.revokeToken(anyString(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PersonTokenRevocationServiceImpl.class)).detachAppender(logs);
    }

    private static User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPersonId(PERSON_ID);
        return user;
    }

    private JwtToken row(
            String username, String accessJti, String refreshJti, Duration accessLeft, Duration refreshLeft) {
        JwtToken row = new JwtToken();
        row.setSubject(username);
        row.setToken("access-" + accessJti);
        row.setRefreshToken("refresh-" + refreshJti);
        row.setIssuedAt(NOW.minusSeconds(60));
        row.setExpiresAt(NOW.plus(accessLeft));
        row.setRefreshExpiresAt(NOW.plus(refreshLeft));
        when(jwtService.getJtiFromToken("access-" + accessJti)).thenReturn(accessJti);
        when(jwtService.getJtiFromToken("refresh-" + refreshJti)).thenReturn(refreshJti);
        return row;
    }

    private long redisUnavailableCount() {
        return (long) meterRegistry
                .get(PersonTokenRevocationServiceImpl.REDIS_UNAVAILABLE_METRIC)
                .counter()
                .count();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName(
            "Revokes access and refresh JTIs of every live pair of every user linked to the person, then deletes the rows")
    void revokesLivePairsOfEveryLinkedUser() {
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        JwtToken b = row("alice-admin", "b-acc", "b-ref", Duration.ofMinutes(5), Duration.ofDays(6));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice"), user("alice-admin")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(a));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice-admin", NOW))
                .thenReturn(List.of(b));

        int revoked = service.revokeLiveTokens(PERSON_ID);

        assertThat(revoked).isEqualTo(2);
        verify(tokenRevocationManager).revokeToken("a-acc", 1800L);
        verify(tokenRevocationManager).revokeToken("a-ref", Duration.ofDays(7).toSeconds());
        verify(tokenRevocationManager).revokeToken("b-acc", 300L);
        verify(tokenRevocationManager).revokeToken("b-ref", Duration.ofDays(6).toSeconds());
        verify(jwtTokenRepository).deleteAll(List.of(a));
        verify(jwtTokenRepository).deleteAll(List.of(b));
        assertThat(warnings()).isEmpty();
        assertThat(redisUnavailableCount()).isZero();
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage))
                .anyMatch(m -> m.contains("personId=" + PERSON_ID) && m.contains("tokens=2"));
    }

    @Test
    @DisplayName("Only the given person's users are looked up; another person's tokens are never touched")
    void otherPersonsTokensUntouched() {
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(a));

        service.revokeLiveTokens(PERSON_ID);

        verify(userRepository).findAllByPersonId(PERSON_ID);
        verify(userRepository, never()).findAllByPersonId(OTHER_PERSON_ID);
        verifyNoMoreInteractions(userRepository);
        verify(jwtTokenRepository).findAllBySubjectAndExpiresAtAfter("alice", NOW);
        verify(jwtTokenRepository, never()).findAllBySubject(anyString());
        verify(jwtTokenRepository, never()).findAll();
    }

    @Test
    @DisplayName("Person with no linked user or no live token: nothing revoked, nothing deleted, returns 0")
    void nothingLiveIsNoOp() {
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of());
        assertThat(service.revokeLiveTokens(PERSON_ID)).isZero();

        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of());
        assertThat(service.revokeLiveTokens(PERSON_ID)).isZero();

        verifyNoInteractions(tokenRevocationManager);
        verify(jwtTokenRepository, never()).deleteAll(any());
        assertThat(redisUnavailableCount()).isZero();
    }

    @Test
    @DisplayName(
            "Expired tokens are skipped: the query is bounded by the clock, and an expired refresh JTI is not written")
    void expiredTokensSkipped() {
        JwtToken staleRefresh = row("alice", "a-acc", "a-ref", Duration.ofMinutes(10), Duration.ofSeconds(-1));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(staleRefresh));

        service.revokeLiveTokens(PERSON_ID);

        verify(jwtTokenRepository).findAllBySubjectAndExpiresAtAfter(eq("alice"), eq(NOW));
        verify(tokenRevocationManager).revokeToken("a-acc", 600L);
        verify(tokenRevocationManager, never()).revokeToken(eq("a-ref"), anyLong());
        verify(jwtTokenRepository).deleteAll(List.of(staleRefresh));
        assertThat(redisUnavailableCount()).isZero();
    }

    @Test
    @DisplayName(
            "Redis unavailable: fail-open — rows still deleted, counter incremented per JTI, WARN logged, no exception")
    void redisUnavailableFailsOpenLoudly() {
        when(tokenRevocationManager.revokeToken(anyString(), anyLong())).thenReturn(false);
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(a));

        int revoked = service.revokeLiveTokens(PERSON_ID);

        assertThat(revoked).isEqualTo(1);
        verify(jwtTokenRepository).deleteAll(List.of(a));
        assertThat(redisUnavailableCount()).isEqualTo(2L);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst().getFormattedMessage())
                .contains("Redis unavailable")
                .contains("2 JTI(s)")
                .contains("personId=" + PERSON_ID);
    }

    @Test
    @DisplayName(
            "Already-revoked pair: a second pass finds no row and writes nothing (Redis SET is an idempotent overwrite anyway)")
    void alreadyRevokedIsNoOp() {
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW))
                .thenReturn(List.of(a))
                .thenReturn(List.of());

        assertThat(service.revokeLiveTokens(PERSON_ID)).isEqualTo(1);
        assertThat(service.revokeLiveTokens(PERSON_ID)).isZero();

        verify(tokenRevocationManager, times(1)).revokeToken(eq("a-acc"), anyLong());
        verify(tokenRevocationManager, times(1)).revokeToken(eq("a-ref"), anyLong());
        verify(jwtTokenRepository, times(1)).deleteAll(any());
    }

    @Test
    @DisplayName("A stored token that no longer parses is still deleted and does not count as a Redis miss")
    void unparsableTokenStillDeleted() {
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        when(jwtService.getJtiFromToken("access-a-acc")).thenThrow(new MalformedJwtException("bad"));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(a));

        assertThat(service.revokeLiveTokens(PERSON_ID)).isEqualTo(1);

        verify(tokenRevocationManager, never()).revokeToken(eq("a-acc"), anyLong());
        verify(tokenRevocationManager).revokeToken(eq("a-ref"), anyLong());
        verify(jwtTokenRepository).deleteAll(List.of(a));
        assertThat(redisUnavailableCount()).isZero();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("Without a MeterRegistry the fail-open path still deletes rows and warns")
    void worksWithoutMeterRegistry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        PersonTokenRevocationServiceImpl bare = new PersonTokenRevocationServiceImpl(
                TEST_CLOCK, userRepository, jwtTokenRepository, jwtService, tokenRevocationManager, none);
        when(tokenRevocationManager.revokeToken(anyString(), anyLong())).thenReturn(false);
        JwtToken a = row("alice", "a-acc", "a-ref", Duration.ofMinutes(30), Duration.ofDays(7));
        when(userRepository.findAllByPersonId(PERSON_ID)).thenReturn(List.of(user("alice")));
        when(jwtTokenRepository.findAllBySubjectAndExpiresAtAfter("alice", NOW)).thenReturn(List.of(a));

        assertThat(bare.revokeLiveTokens(PERSON_ID)).isEqualTo(1);

        verify(jwtTokenRepository).deleteAll(List.of(a));
        assertThat(warnings()).hasSize(1);
    }
}
