package com.positivity.customer.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Query semantics of {@link ServiceHistoryRepository#findServiceDueCandidates} (Story #1153):
 * only the latest completion per party/vehicle qualifies, only past the cutoff, and never once
 * a reminder keyed to that completion exists.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceHistoryRepositoryTest {

    private static final String SOURCE_PREFIX = "service-due:";
    private static final Instant CUTOFF = Instant.parse("2026-01-20T12:00:00Z");
    private static final Instant STALE = Instant.parse("2025-11-01T10:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-06-01T10:00:00Z");

    @Autowired
    private ServiceHistoryRepository serviceHistoryRepository;

    @Autowired
    private FollowUpTaskRepository followUpTaskRepository;

    @BeforeEach
    void setUp() {
        followUpTaskRepository.deleteAll();
        serviceHistoryRepository.deleteAll();
    }

    private ServiceHistory saveHistory(UUID partyId, UUID vehicleId, Instant completedAt) {
        return serviceHistoryRepository.saveAndFlush(ServiceHistory.builder()
                .partyId(partyId)
                .vehicleId(vehicleId)
                .sourceWorkorderId(UUID.randomUUID())
                .completedAt(completedAt)
                .sourceEventId(UUID.randomUUID().toString())
                .createdAt(completedAt)
                .build());
    }

    @Test
    @DisplayName("Only stale latest completions per party/vehicle are candidates")
    void selectsOnlyStaleLatestCompletions() {
        UUID servicedAgainParty = UUID.randomUUID();
        UUID dueParty = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        // Serviced again recently: the stale row is no longer the latest, so not due.
        saveHistory(servicedAgainParty, vehicle, STALE);
        saveHistory(servicedAgainParty, vehicle, RECENT);
        ServiceHistory due = saveHistory(dueParty, UUID.randomUUID(), STALE);
        ServiceHistory dueNoVehicle = saveHistory(dueParty, null, STALE);

        List<ServiceHistory> candidates =
                serviceHistoryRepository.findServiceDueCandidates(CUTOFF, SOURCE_PREFIX, PageRequest.of(0, 10));

        assertThat(candidates)
                .extracting(ServiceHistory::getServiceHistoryId)
                .containsExactlyInAnyOrder(due.getServiceHistoryId(), dueNoVehicle.getServiceHistoryId());
    }

    @Test
    @DisplayName("Latest completion is grouped per (party, vehicle), with a null-vehicle scope of its own (#1175)")
    void groupsLatestCompletionPerPartyAndVehicle() {
        UUID party = UUID.randomUUID();
        UUID vehicleA = UUID.randomUUID();
        UUID vehicleB = UUID.randomUUID();
        saveHistory(party, vehicleA, STALE);
        saveHistory(party, vehicleA, RECENT);
        saveHistory(party, vehicleB, STALE);
        saveHistory(party, null, RECENT);

        var rows = serviceHistoryRepository.findLastServiceByPartyAndVehicle(List.of(party));

        assertThat(rows).hasSize(3);
        assertThat(rows)
                .anySatisfy(row -> {
                    assertThat(row.getVehicleId()).isEqualTo(vehicleA);
                    assertThat(row.getLastCompletedAt()).isEqualTo(RECENT);
                })
                .anySatisfy(row -> {
                    assertThat(row.getVehicleId()).isEqualTo(vehicleB);
                    assertThat(row.getLastCompletedAt()).isEqualTo(STALE);
                })
                .anySatisfy(row -> {
                    assertThat(row.getVehicleId()).isNull();
                    assertThat(row.getLastCompletedAt()).isEqualTo(RECENT);
                });
    }

    @Test
    @DisplayName("A completion with an existing keyed reminder is excluded, open or closed")
    void excludesAlreadyRemindedCompletions() {
        ServiceHistory reminded = saveHistory(UUID.randomUUID(), UUID.randomUUID(), STALE);
        ServiceHistory unreminded = saveHistory(UUID.randomUUID(), UUID.randomUUID(), STALE);
        followUpTaskRepository.saveAndFlush(FollowUpTask.builder()
                .partyId(reminded.getPartyId())
                .vehicleId(reminded.getVehicleId())
                .type(FollowUpType.SERVICE_DUE_REMINDER)
                .status(FollowUpStatus.OPEN)
                .sourceEventId(SOURCE_PREFIX + reminded.getServiceHistoryId())
                .build());

        List<ServiceHistory> candidates =
                serviceHistoryRepository.findServiceDueCandidates(CUTOFF, SOURCE_PREFIX, PageRequest.of(0, 10));

        assertThat(candidates)
                .extracting(ServiceHistory::getServiceHistoryId)
                .containsExactly(unreminded.getServiceHistoryId());
    }
}
