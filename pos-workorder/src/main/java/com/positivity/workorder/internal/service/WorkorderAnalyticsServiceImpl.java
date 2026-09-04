package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.ReopenedWorkorderAnalyticsResponse;
import com.positivity.workorder.internal.dto.ReopenedWorkorderRow;
import com.positivity.workorder.internal.dto.TechnicianLaborAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborRow;
import com.positivity.workorder.internal.dto.WorkorderStateTransitionResponse;
import com.positivity.workorder.internal.dto.WorkorderStatusTransitionsResponse;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link WorkorderAnalyticsService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderAnalyticsServiceImpl implements WorkorderAnalyticsService {

    private static final String ACTIVE = "ACTIVE";

    /**
     * Internal safety cap on how many rows this service pulls into memory to compute one window's
     * aggregate, independent of the public {@code limit} response-truncation contract (which caps
     * the returned row count, not the computation's inputs). If a single window genuinely has more
     * activity than this, the aggregate under-counts; that is a scale problem for a future
     * pagination redesign, not something a caller-facing {@code limit} tweak can fix, so it is
     * logged rather than silently absorbed.
     */
    private static final int INTERNAL_FETCH_CAP = 20_000;

    private final WorkorderStateTransitionRepository transitionRepository;
    private final WorkorderLaborEntryRepository laborEntryRepository;
    private final ExtInvoiceReplicaRepository invoiceReplicaRepository;
    private final ExtPersonReplicaRepository personReplicaRepository;
    private final ExtUserLinkReplicaRepository userLinkReplicaRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public WorkorderStatusTransitionsResponse getStatusTransitions(
            @Nullable UUID woId,
            @Nullable WorkorderStatus from,
            @Nullable WorkorderStatus to,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate,
            int limit) {

        boolean hasWoId = woId != null;
        boolean hasRangeParams = from != null || to != null || startDate != null || endDate != null;

        if (hasWoId && hasRangeParams) {
            throw new WorkorderRequestValidationException(
                    "woId is mutually exclusive with from/to/startDate/endDate; supply one mode, not both");
        }
        if (!hasWoId && !hasRangeParams) {
            throw new WorkorderRequestValidationException(
                    "Supply either woId alone, or startDate and endDate (optionally narrowed by from/to)");
        }

        if (hasWoId) {
            List<WorkorderStateTransition> all = transitionRepository.findByWorkorder_IdOrderByTransitionedAtAsc(woId);
            return toStatusTransitionsResponse(all, limit);
        }

        if (startDate == null || endDate == null) {
            throw new WorkorderRequestValidationException(
                    "startDate and endDate are both required in range mode (from/to alone is not enough — an "
                            + "unbounded date scan is never allowed)");
        }
        if (endDate.isBefore(startDate)) {
            throw new WorkorderRequestValidationException("endDate cannot be before startDate");
        }

        List<WorkorderStateTransition> page = transitionRepository.findByTransitionedAtRangeAndStatuses(
                rangeStart(startDate), rangeEndExclusive(endDate), from, to, PageRequest.of(0, limit + 1));
        return toStatusTransitionsResponse(page, limit);
    }

    private static WorkorderStatusTransitionsResponse toStatusTransitionsResponse(
            List<WorkorderStateTransition> transitions, int limit) {
        boolean truncated = transitions.size() > limit;
        List<WorkorderStateTransitionResponse> rows = transitions.stream()
                .limit(limit)
                .map(WorkorderStateTransitionResponse::fromEntity)
                .toList();
        return WorkorderStatusTransitionsResponse.builder()
                .transitions(rows)
                .truncated(truncated)
                .limit(limit)
                .build();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public ReopenedWorkorderAnalyticsResponse getReopenedWorkorders(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int withinDays, int limit) {
        validateRange(startDate, endDate);

        var instantStart = rangeStart(startDate);
        var instantEnd = rangeEndExclusive(endDate);

        // Genuine completions anchored in [startDate, endDate]: toStatus=COMPLETED, excluding the
        // COMPLETED->COMPLETED marker rows WorkorderStateMachine.reopenCompletedWorkorder records
        // for reopen events themselves (see that method's Javadoc) — those are not completions.
        Fetched completionsFetch = fetchRange(instantStart, instantEnd, null, WorkorderStatus.COMPLETED);
        List<WorkorderStateTransition> completions = completionsFetch.rows().stream()
                .filter(t -> t.getFromStatus() != WorkorderStatus.COMPLETED)
                .toList();
        if (completions.isEmpty()) {
            return ReopenedWorkorderAnalyticsResponse.builder()
                    .rows(List.of())
                    .truncated(completionsFetch.hitInternalCap())
                    .limit(limit)
                    .build();
        }

        // Reopen markers that could pair with one of the above completions and still land inside
        // withinDays: fetched out to (endDate + withinDays) so a completion near endDate is not cut
        // short.
        Fetched markersFetch = fetchRange(
                instantStart, plusDays(instantEnd, withinDays), WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED);
        Map<UUID, List<WorkorderStateTransition>> markersByWorkorder = markersFetch.rows().stream()
                .collect(Collectors.groupingBy(
                        WorkorderStateTransition::getWorkorderId, LinkedHashMap::new, Collectors.toList()));

        Set<String> completingActors = completions.stream()
                .map(WorkorderStateTransition::getTransitionedBy)
                .collect(Collectors.toSet());
        Map<String, UUID> technicianIdByUsername = resolvePersonIdsByUsername(completingActors);

        List<ReopenedWorkorderRow> rows = new ArrayList<>();
        for (WorkorderStateTransition completion : completions) {
            UUID technicianId = resolveTechnicianId(completion.getTransitionedBy(), technicianIdByUsername);
            if (technicianId == null) {
                log.debug(
                        "Excluding reopen row(s) for workorder {}: completing actor could not be resolved to a technician",
                        completion.getWorkorderId());
                continue;
            }
            for (WorkorderStateTransition marker :
                    markersByWorkorder.getOrDefault(completion.getWorkorderId(), List.of())) {
                if (!marker.getTransitionedAt().isAfter(completion.getTransitionedAt())) {
                    continue;
                }
                if (marker.getTransitionedAt().isAfter(plusDays(completion.getTransitionedAt(), withinDays))) {
                    continue;
                }
                rows.add(ReopenedWorkorderRow.builder()
                        .technicianId(technicianId)
                        .woId(completion.getWorkorderId())
                        .completedAt(completion.getTransitionedAt())
                        .reopenedAt(marker.getTransitionedAt())
                        .build());
            }
        }

        rows.sort(
                Comparator.comparing(ReopenedWorkorderRow::getReopenedAt).thenComparing(ReopenedWorkorderRow::getWoId));

        boolean truncated = rows.size() > limit || completionsFetch.hitInternalCap() || markersFetch.hitInternalCap();
        List<ReopenedWorkorderRow> capped = rows.size() > limit ? rows.subList(0, limit) : rows;
        return ReopenedWorkorderAnalyticsResponse.builder()
                .rows(List.copyOf(capped))
                .truncated(truncated)
                .limit(limit)
                .build();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public TechnicianLaborAnalyticsResponse getTechnicianLabor(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit) {
        validateRange(startDate, endDate);

        var instantStart = rangeStart(startDate);
        var instantEnd = rangeEndExclusive(endDate);
        LocalDateTime laborStart = startDate.atStartOfDay();
        LocalDateTime laborEnd = endDate.plusDays(1).atStartOfDay();

        // 1. Work orders completed in the window, attributed to the completing technician.
        Fetched completionsFetch = fetchRange(instantStart, instantEnd, null, WorkorderStatus.COMPLETED);
        List<WorkorderStateTransition> completions = completionsFetch.rows().stream()
                .filter(t -> t.getFromStatus() != WorkorderStatus.COMPLETED)
                .toList();
        Set<String> completingActors = completions.stream()
                .map(WorkorderStateTransition::getTransitionedBy)
                .collect(Collectors.toSet());
        Map<String, UUID> technicianIdByUsername = resolvePersonIdsByUsername(completingActors);

        Map<UUID, Set<UUID>> completedWoIdsByTechnician = new LinkedHashMap<>();
        for (WorkorderStateTransition completion : completions) {
            UUID technicianId = resolveTechnicianId(completion.getTransitionedBy(), technicianIdByUsername);
            if (technicianId == null) {
                continue;
            }
            completedWoIdsByTechnician
                    .computeIfAbsent(technicianId, k -> new LinkedHashSet<>())
                    .add(completion.getWorkorderId());
        }

        // 2. Billed hours: stopped labor entries whose startTime (log time) falls in the window.
        Map<UUID, BigDecimal> billedHoursByTechnician =
                laborEntryRepository.findByEndTimeIsNotNullAndStartTimeBetween(laborStart, laborEnd).stream()
                        .collect(Collectors.groupingBy(
                                WorkorderLaborEntry::getTechnicianId,
                                Collectors.reducing(
                                        BigDecimal.ZERO, WorkorderLaborEntry::getHoursWorked, BigDecimal::add)));

        // 3. Labor revenue: ext_invoice.laborTotal for invoices of the completed work orders above,
        // attributed to the same completing technician. Null laborTotal excludes that invoice.
        Set<UUID> allCompletedWoIds = completedWoIdsByTechnician.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        Map<UUID, BigDecimal> invoiceLaborTotalByWorkorder = allCompletedWoIds.isEmpty()
                ? Map.of()
                : invoiceReplicaRepository.findByWorkorderIdIn(allCompletedWoIds).stream()
                        .filter(invoice -> invoice.getLaborTotal() != null)
                        .collect(Collectors.groupingBy(
                                ExtInvoiceReplica::getWorkorderId,
                                Collectors.reducing(
                                        BigDecimal.ZERO, ExtInvoiceReplica::getLaborTotal, BigDecimal::add)));

        Map<UUID, BigDecimal> laborRevenueByTechnician = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : completedWoIdsByTechnician.entrySet()) {
            BigDecimal revenue = entry.getValue().stream()
                    .map(woId -> invoiceLaborTotalByWorkorder.getOrDefault(woId, BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            laborRevenueByTechnician.put(entry.getKey(), revenue);
        }

        // 4. Union technicians across all three signals; a technician can appear in one dimension
        // without appearing in another (e.g. logged hours on a WO not yet completed this window).
        Set<UUID> technicianIds = new LinkedHashSet<>();
        technicianIds.addAll(completedWoIdsByTechnician.keySet());
        technicianIds.addAll(billedHoursByTechnician.keySet());
        technicianIds.addAll(laborRevenueByTechnician.keySet());

        Map<UUID, ExtPersonReplica> peopleById = personReplicaRepository.findByPersonIdIn(technicianIds).stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, p -> p));

        List<TechnicianLaborRow> allRows = technicianIds.stream()
                .map(techId -> TechnicianLaborRow.builder()
                        .technicianId(techId)
                        .name(resolveName(peopleById.get(techId)))
                        .completedWoCount(completedWoIdsByTechnician
                                .getOrDefault(techId, Set.of())
                                .size())
                        .billedHours(billedHoursByTechnician.getOrDefault(techId, BigDecimal.ZERO))
                        .laborRevenue(laborRevenueByTechnician.getOrDefault(techId, BigDecimal.ZERO))
                        .build())
                .sorted(Comparator.comparing(TechnicianLaborRow::getBilledHours)
                        .reversed()
                        .thenComparing(TechnicianLaborRow::getTechnicianId))
                .toList();

        boolean truncated = allRows.size() > limit || completionsFetch.hitInternalCap();
        List<TechnicianLaborRow> rows = allRows.stream().limit(limit).toList();
        return TechnicianLaborAnalyticsResponse.builder()
                .rows(rows)
                .truncated(truncated)
                .limit(limit)
                .build();
    }

    /**
     * A fetch's rows plus whether it hit {@link #INTERNAL_FETCH_CAP} — the caller must fold
     * {@code hitInternalCap} into the response's {@code truncated} flag itself; unlike the public
     * {@code limit} truncation, later rows dropped here never entered the aggregate computation at
     * all, so silently ignoring this flag would let a response claim to be complete when technicians
     * or completions were actually excluded (found in adversarial review, #1593-#1595).
     */
    private record Fetched(List<WorkorderStateTransition> rows, boolean hitInternalCap) {}

    private Fetched fetchRange(
            Instant start, Instant end, @Nullable WorkorderStatus from, @Nullable WorkorderStatus to) {
        List<WorkorderStateTransition> fetched = transitionRepository.findByTransitionedAtRangeAndStatuses(
                start, end, from, to, PageRequest.of(0, INTERNAL_FETCH_CAP));
        boolean hitCap = fetched.size() == INTERNAL_FETCH_CAP;
        if (hitCap) {
            log.warn(
                    "Analytics computation window hit the internal fetch cap ({}); the aggregate may "
                            + "undercount for range [{}, {})",
                    INTERNAL_FETCH_CAP,
                    start,
                    end);
        }
        return new Fetched(fetched, hitCap);
    }

    /**
     * Resolves each distinct username to its personId via the active user-link replica in a single
     * batched query; unresolved usernames are simply absent from the returned map. When more than
     * one active link exists for a username, the first one the repository returns wins (mirrors
     * {@code findFirstByUsernameAndStatus}'s per-username semantics).
     */
    private Map<String, UUID> resolvePersonIdsByUsername(Set<String> usernames) {
        List<String> knownUsernames =
                usernames.stream().filter(u -> u != null && !u.isBlank()).toList();
        if (knownUsernames.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> result = new LinkedHashMap<>();
        for (var link : userLinkReplicaRepository.findByUsernameInAndStatus(knownUsernames, ACTIVE)) {
            result.putIfAbsent(link.getUsername(), link.getPersonId());
        }
        return result;
    }

    private static @Nullable UUID resolveTechnicianId(@Nullable String actorUsername, Map<String, UUID> byUsername) {
        if (actorUsername == null || actorUsername.isBlank()) {
            return null;
        }
        return byUsername.get(actorUsername);
    }

    private static @Nullable String resolveName(@Nullable ExtPersonReplica person) {
        if (person == null) {
            return null;
        }
        String first =
                person.getFirstName() == null ? "" : person.getFirstName().trim();
        String last = person.getLastName() == null ? "" : person.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }

    private static void validateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new WorkorderRequestValidationException("endDate cannot be before startDate");
        }
    }

    private static Instant rangeStart(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant rangeEndExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant plusDays(Instant instant, int days) {
        return instant.plus(days, ChronoUnit.DAYS);
    }
}
