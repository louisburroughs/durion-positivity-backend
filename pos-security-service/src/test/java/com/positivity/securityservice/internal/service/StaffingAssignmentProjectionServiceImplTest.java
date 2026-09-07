package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.securityservice.internal.repository.ExtStaffingAssignmentReplicaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link StaffingAssignmentProjectionServiceImpl} (ADR-0061 §1/§2, #1867). The
 * effective-date predicate itself is exercised against H2 in
 * {@code ExtStaffingAssignmentReplicaRepositoryTest}; these cover the shaping on top of it.
 */
class StaffingAssignmentProjectionServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 7);
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final ExtStaffingAssignmentReplicaRepository repository =
            mock(ExtStaffingAssignmentReplicaRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private StaffingAssignmentProjectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService(3);
        logs.start();
        ((Logger) LoggerFactory.getLogger(StaffingAssignmentProjectionServiceImpl.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(StaffingAssignmentProjectionServiceImpl.class)).detachAppender(logs);
    }

    @SuppressWarnings("unchecked")
    private StaffingAssignmentProjectionServiceImpl newService(int cap) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        return new StaffingAssignmentProjectionServiceImpl(repository, cap, provider);
    }

    private static ExtStaffingAssignmentReplica row(UUID locationId, LocalDate effectiveTo) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.fromString(String.format("00000000-0000-7000-8000-a%011x", SEQ.incrementAndGet())))
                .personId(PERSON_ID)
                .locationId(locationId)
                .status(ExtStaffingAssignmentReplica.STATUS_ACTIVE)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(effectiveTo)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    private static UUID node(int i) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012x", i));
    }

    private double capExceededCount() {
        var counter = meterRegistry
                .find(StaffingAssignmentProjectionServiceImpl.CAP_EXCEEDED_METRIC)
                .tag("owner", "people")
                .counter();
        return counter == null ? 0d : counter.count();
    }

    private long warnCount() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    @Test
    @DisplayName("assignedLocationIds queries the effective-at predicate and returns distinct sorted node ids")
    void assignedLocationIdsDistinctAndSorted() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF))
                .thenReturn(List.of(row(node(3), null), row(node(1), null), row(node(3), LocalDate.of(2026, 12, 1))));

        assertThat(service.assignedLocationIds(PERSON_ID, AS_OF)).containsExactly(node(1), node(3));
        assertThat(warnCount()).isZero();
        assertThat(capExceededCount()).isZero();
    }

    @Test
    @DisplayName("a person with no effective assignments resolves to an empty set (fails closed)")
    void noAssignmentsIsEmpty() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF)).thenReturn(List.of());

        assertThat(service.assignedLocationIds(PERSON_ID, AS_OF)).isEmpty();
        assertThat(service.earliestEffectiveTo(PERSON_ID, AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("earliestEffectiveTo is the minimum non-null date across mixed null/non-null rows")
    void earliestEffectiveToMixedNulls() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF))
                .thenReturn(List.of(
                        row(node(1), null),
                        row(node(2), LocalDate.of(2026, 11, 30)),
                        row(node(3), LocalDate.of(2026, 10, 15)),
                        row(node(4), null)));

        assertThat(service.earliestEffectiveTo(PERSON_ID, AS_OF)).contains(LocalDate.of(2026, 10, 15));
    }

    @Test
    @DisplayName("earliestEffectiveTo is empty when every effective assignment is open-ended")
    void earliestEffectiveToAllNull() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF))
                .thenReturn(List.of(row(node(1), null), row(node(2), null)));

        assertThat(service.earliestEffectiveTo(PERSON_ID, AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("cap breach logs WARN and increments the metric but never truncates the set")
    void capBreachWarnsWithoutTruncating() {
        List<ExtStaffingAssignmentReplica> rows =
                IntStream.rangeClosed(1, 5).mapToObj(i -> row(node(i), null)).toList();
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF)).thenReturn(rows);

        List<UUID> nodes = service.assignedLocationIds(PERSON_ID, AS_OF);

        assertThat(nodes).containsExactly(node(1), node(2), node(3), node(4), node(5));
        assertThat(warnCount()).isEqualTo(1);
        assertThat(logs.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .findFirst()
                        .orElseThrow()
                        .getFormattedMessage())
                .contains("cap exceeded")
                .contains("nodes=5")
                .contains("cap=3");
        assertThat(capExceededCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("a set exactly at the cap is not a breach")
    void atCapIsNotBreach() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF))
                .thenReturn(IntStream.rangeClosed(1, 3)
                        .mapToObj(i -> row(node(i), null))
                        .toList());

        assertThat(service.assignedLocationIds(PERSON_ID, AS_OF)).hasSize(3);
        assertThat(warnCount()).isZero();
        assertThat(capExceededCount()).isZero();
    }

    @Test
    @DisplayName("cap counts distinct nodes, not rows")
    void capCountsDistinctNodes() {
        when(repository.findActiveEffectiveOn(PERSON_ID, AS_OF))
                .thenReturn(List.of(row(node(1), null), row(node(1), null), row(node(1), null), row(node(2), null)));

        assertThat(service.assignedLocationIds(PERSON_ID, AS_OF)).containsExactly(node(1), node(2));
        assertThat(capExceededCount()).isZero();
    }

    @Test
    @DisplayName("a cap below 1 is a configuration error")
    void capBelowOneRejected() {
        assertThatThrownBy(() -> newService(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned-node-cap");
    }
}
