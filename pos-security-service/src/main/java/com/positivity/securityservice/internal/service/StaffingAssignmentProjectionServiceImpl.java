package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.securityservice.internal.repository.ExtStaffingAssignmentReplicaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code ext_people_staffing_assignment} (ADR-0061 §1, #1867).
 *
 * <p>The node cap is an <em>assertion</em> that the hierarchy was modelled correctly (ADR-0061 §2:
 * hierarchy is expected to keep counts at one or two). A breach is a configuration error surfaced
 * via WARN plus {@code security.location-scope.assigned-nodes.cap-exceeded}; the set is returned
 * in full because truncating it would deny access that was granted.
 */
@Slf4j
@Service
public class StaffingAssignmentProjectionServiceImpl implements StaffingAssignmentProjectionService {

    static final String CAP_EXCEEDED_METRIC = "security.location-scope.assigned-nodes.cap-exceeded";

    private final ExtStaffingAssignmentReplicaRepository repository;
    private final int assignedNodeCap;
    private final Counter capExceededCounter;

    public StaffingAssignmentProjectionServiceImpl(
            ExtStaffingAssignmentReplicaRepository repository,
            @Value("${pos.security-service.location-scope.assigned-node-cap:8}") int assignedNodeCap,
            ObjectProvider<MeterRegistry> meterRegistry) {
        if (assignedNodeCap < 1) {
            throw new IllegalArgumentException(
                    "pos.security-service.location-scope.assigned-node-cap must be >= 1 but was " + assignedNodeCap);
        }
        this.repository = repository;
        this.assignedNodeCap = assignedNodeCap;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.capExceededCounter = registry == null
                ? null
                : Counter.builder(CAP_EXCEEDED_METRIC)
                        .description("Persons whose effective assigned-node set exceeded the configured cap"
                                + " (ADR-0061 hierarchy-modelling assertion; the set is never truncated)")
                        .tag("owner", PeopleEventsListener.OWNER)
                        .register(registry);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<UUID> assignedLocationIds(@NonNull UUID personId, @NonNull LocalDate asOf) {
        List<UUID> nodes = repository.findActiveEffectiveOn(personId, asOf).stream()
                .map(ExtStaffingAssignmentReplica::getLocationId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (nodes.size() > assignedNodeCap) {
            if (capExceededCounter != null) {
                capExceededCounter.increment();
            }
            log.warn(
                    "Assigned-node cap exceeded personId={} asOf={} nodes={} cap={} — returning the full set;"
                            + " the location hierarchy is likely mis-modelled (ADR-0061 §2)",
                    personId,
                    asOf,
                    nodes.size(),
                    assignedNodeCap);
        }
        return nodes;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<LocalDate> earliestEffectiveTo(@NonNull UUID personId, @NonNull LocalDate asOf) {
        return repository.findActiveEffectiveOn(personId, asOf).stream()
                .map(ExtStaffingAssignmentReplica::getEffectiveTo)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
    }
}
