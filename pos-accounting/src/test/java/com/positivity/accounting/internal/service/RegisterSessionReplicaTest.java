package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtOrderRegisterSession;
import com.positivity.accounting.internal.enums.RegisterSessionStatus;
import com.positivity.accounting.internal.repository.ExtOrderRegisterSessionRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionOpenedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Register session replica (#2571, #2573)")
class RegisterSessionReplicaTest {

    private static final Instant OPENED = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-10-07T18:00:00Z");
    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000a001");

    private final ExtOrderRegisterSessionRepository repository = mock(ExtOrderRegisterSessionRepository.class);
    private final Map<UUID, ExtOrderRegisterSession> rows = new LinkedHashMap<>();
    private RegisterSessionReplica replica;

    @BeforeEach
    void setUp() {
        replica = new RegisterSessionReplica(
                repository, Clock.fixed(Instant.parse("2026-10-07T20:00:00Z"), ZoneOffset.UTC));
        when(repository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(repository.save(any())).thenAnswer(invocation -> {
            ExtOrderRegisterSession row = invocation.getArgument(0);
            rows.put(row.getSessionId(), row);
            return row;
        });
        when(repository.findFirstByTerminalIdOrderByOpenedAtDescSessionIdDesc(anyString()))
                .thenAnswer(invocation -> rows.values().stream()
                        .filter(row -> row.getTerminalId().equals(invocation.getArgument(0)))
                        .max(Comparator.comparing(ExtOrderRegisterSession::getOpenedAt)
                                .thenComparing(ExtOrderRegisterSession::getSessionId)));
    }

    @Test
    @DisplayName("an opened fact makes the terminal's session open; its closed fact closes it")
    void openedThenClosed() {
        UUID session = UUID.randomUUID();

        replica.opened(opened(session, "T-1", OPENED), 1);
        assertThat(replica.openSessionOf("T-1"))
                .map(ExtOrderRegisterSession::getSessionId)
                .contains(session);
        assertThat(rows.get(session).getLocationId()).isEqualTo(LOCATION);

        replica.closed(closed(session, "T-1", OPENED, CLOSED), 2);
        assertThat(replica.openSessionOf("T-1")).isEmpty();
        assertThat(rows.get(session).getStatus()).isEqualTo(RegisterSessionStatus.CLOSED);
        assertThat(rows.get(session).getClosedAt()).isEqualTo(CLOSED);
        assertThat(rows.get(session).getAggregateVersion()).isEqualTo(2);
        assertThat(replica.openSessionOf("T-2")).isEmpty();
    }

    @Test
    @DisplayName("a closed fact for an unknown session inserts a CLOSED row; the late opened fact cannot reopen it")
    void closedBeforeOpened() {
        UUID session = UUID.randomUUID();

        replica.closed(closed(session, "T-1", OPENED, CLOSED), 2);
        replica.opened(opened(session, "T-1", OPENED), 1);
        // Even an opened fact claiming a newer version: a session never reopens.
        replica.opened(opened(session, "T-1", OPENED), 5);

        assertThat(rows.get(session).getStatus()).isEqualTo(RegisterSessionStatus.CLOSED);
        assertThat(replica.openSessionOf("T-1")).isEmpty();
    }

    @Test
    @DisplayName("a stale opened fact is skipped; an equal version applies")
    void openedIsVersionGuarded() {
        UUID session = UUID.randomUUID();
        UUID elsewhere = UUID.fromString("019a0000-0000-7000-8000-00000000a002");
        replica.opened(opened(session, "T-1", OPENED), 3);

        replica.opened(new RegisterSessionOpenedV1(session, "T-1", elsewhere, OPENED), 2);
        assertThat(rows.get(session).getLocationId()).isEqualTo(LOCATION);
        replica.opened(new RegisterSessionOpenedV1(session, "T-1", elsewhere, OPENED), 3);
        assertThat(rows.get(session).getLocationId()).isEqualTo(elsewhere);
    }

    @Test
    @DisplayName("a newer session closed while an older row is stuck OPEN does not block: the latest-opened decides")
    void newerClosedSessionWins() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        replica.opened(opened(older, "T-1", OPENED), 1);
        replica.opened(opened(newer, "T-1", OPENED.plusSeconds(3600)), 1);
        assertThat(replica.openSessionOf("T-1"))
                .map(ExtOrderRegisterSession::getSessionId)
                .contains(newer);

        replica.closed(closed(newer, "T-1", OPENED.plusSeconds(3600), CLOSED), 2);

        assertThat(rows.get(older).getStatus()).isEqualTo(RegisterSessionStatus.OPEN);
        assertThat(replica.openSessionOf("T-1")).isEmpty();
    }

    @Test
    @DisplayName("a replayed closed fact at an older version leaves the closed row as it is")
    void staleClosedIsSkipped() {
        UUID session = UUID.randomUUID();
        replica.closed(closed(session, "T-1", OPENED, CLOSED), 4);

        replica.closed(closed(session, "T-1", OPENED, CLOSED.plusSeconds(60)), 3);

        assertThat(rows.get(session).getClosedAt()).isEqualTo(CLOSED);
        assertThat(rows.get(session).getAggregateVersion()).isEqualTo(4);
    }

    private static RegisterSessionOpenedV1 opened(UUID session, String terminal, Instant at) {
        return new RegisterSessionOpenedV1(session, terminal, LOCATION, at);
    }

    private static RegisterSessionClosedV1 closed(UUID session, String terminal, Instant opened, Instant closed) {
        return new RegisterSessionClosedV1(
                session,
                terminal,
                LOCATION,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                false,
                "USD",
                List.of(),
                BigDecimal.ZERO,
                opened,
                closed,
                List.of());
    }
}
