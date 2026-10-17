package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CashAttentionSources.AgedSession;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CASH work items (CAP:550 S19, #2515; SPEC-accounting-workspace §4.5, §7.1; ruling 2 of 2026-10-17): the balanced
 * undeposited sessions of each location, ready for one bank deposit, and an undeposited session past the aged
 * threshold whose drops do not balance, which only a controller's journal correction can fix. Both read the sessions
 * through {@link CashAttentionSources}, the source the cash position's needs-attention items use, and are shown to the
 * holders of {@code accounting:deposit:create}, for the locations that permission reaches. Card-only sessions have
 * nothing to deposit and are never undeposited, so they never appear.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashWorkItemSource {

    static final String RECORD_DEPOSIT = "RECORD_DEPOSIT";
    static final String REASON_READY = "READY_TO_DEPOSIT";
    static final String REASON_UNBALANCED = "UNBALANCED";
    static final String STATUS_UNDEPOSITED = "UNDEPOSITED";

    /** The item id tail of the sessions that carry no location. */
    static final String NO_LOCATION = "NO_LOCATION";

    private final CashAttentionSources sources;
    private final CashAndPayablesSettings settings;
    private final DepositService depositService;
    private final UndepositedSessionDropRepository drops;
    private final ExtLocationReplicaRepository locations;

    /** The caller's cash items on {@code today}, unordered. */
    public @NonNull List<WorkItem> items(@NonNull LocalDate today) {
        if (!WorkItems.has(AccountingPermissions.DEPOSIT_CREATE)) {
            return List.of();
        }
        List<AgedSession> reachable = sources.undepositedSessions().stream()
                .filter(aged -> reaches(aged.session().getLocationId()))
                .toList();
        if (reachable.isEmpty()) {
            return List.of();
        }
        Map<UUID, ExtLocationReplica> byLocation = locationsOf(reachable);
        Map<UUID, List<UndepositedSessionDrop>> dropsBySession = dropsOf(reachable);
        List<WorkItem> items = new ArrayList<>();

        Map<UUID, List<AgedSession>> balancedByLocation = new LinkedHashMap<>();
        for (AgedSession aged : reachable) {
            if (aged.balanced()) {
                balancedByLocation
                        .computeIfAbsent(aged.session().getLocationId(), k -> new ArrayList<>())
                        .add(aged);
            }
        }
        balancedByLocation.forEach((locationId, group) ->
                items.add(toDeposit(locationId, group, byLocation.get(locationId), dropsBySession)));

        int agedDays = settings.undepositedCashAgedDays();
        for (AgedSession aged : CashAttentionSources.aged(reachable, today, agedDays)) {
            if (!aged.balanced()) {
                items.add(unbalanced(aged, byLocation.get(aged.session().getLocationId()), dropsBySession));
            }
        }
        return items;
    }

    private WorkItem toDeposit(
            @Nullable UUID locationId,
            List<AgedSession> group,
            @Nullable ExtLocationReplica location,
            Map<UUID, List<UndepositedSessionDrop>> dropsBySession) {
        BigDecimal amount = group.stream().map(AgedSession::depositAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate oldest = group.stream()
                .map(AgedSession::businessDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        List<UUID> sessionIds =
                group.stream().map(aged -> aged.session().getSessionId()).toList();
        Map<String, String> references = new LinkedHashMap<>(locationReferences(location));
        references.putAll(sessionReferences(group, dropsBySession));
        return WorkItems.item(
                WorkItemType.CASH_TO_DEPOSIT,
                locationId == null ? NO_LOCATION : locationId.toString(),
                references,
                REASON_READY,
                amount,
                STATUS_UNDEPOSITED,
                oldest,
                List.of(WorkItemAction.allowed(RECORD_DEPOSIT)),
                preview(sessionIds),
                WorkItems.routing("locationId", locationId));
    }

    private WorkItem unbalanced(
            AgedSession aged,
            @Nullable ExtLocationReplica location,
            Map<UUID, List<UndepositedSessionDrop>> dropsBySession) {
        UndepositedSession session = aged.session();
        Map<String, String> references = new LinkedHashMap<>(locationReferences(location));
        references.putAll(sessionReferences(List.of(aged), dropsBySession));
        return WorkItems.item(
                WorkItemType.UNDEPOSITED_SESSION_UNBALANCED,
                session.getSessionId().toString(),
                references,
                REASON_UNBALANCED,
                aged.depositAmount(),
                STATUS_UNDEPOSITED,
                aged.businessDate(),
                List.of(),
                List.of(),
                WorkItems.routing("sessionId", session.getSessionId(), "locationId", session.getLocationId()));
    }

    /** The entry the selected sessions' deposit would post, from the deposit read; empty when it cannot be built. */
    private List<WorkItemPostingLine> preview(List<UUID> sessionIds) {
        try {
            UndepositedSessionsResponse.Selection selection =
                    depositService.undeposited(sessionIds, null).selection();
            if (selection == null || selection.lines() == null) {
                return List.of();
            }
            return selection.lines().stream()
                    .map(line -> new WorkItemPostingLine(
                            line.accountNumber(),
                            line.accountName(),
                            line.side() == UndepositedSessionsResponse.Side.DEBIT
                                    ? WorkItemPostingLine.Side.DEBIT
                                    : WorkItemPostingLine.Side.CREDIT,
                            line.amount()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("No deposit preview for {} session(s): {}", sessionIds.size(), e.getMessage());
            return List.of();
        }
    }

    private static Map<String, String> locationReferences(@Nullable ExtLocationReplica location) {
        return location == null
                ? Map.of()
                : WorkItems.references("location", location.getCode(), "locationName", location.getName());
    }

    private static Map<String, String> sessionReferences(
            Collection<AgedSession> sessions, Map<UUID, List<UndepositedSessionDrop>> dropsBySession) {
        Set<String> terminals = new LinkedHashSet<>();
        Set<String> bags = new LinkedHashSet<>();
        for (AgedSession aged : sessions) {
            UndepositedSession session = aged.session();
            if (session.getTerminalId() != null) {
                terminals.add(session.getTerminalId());
            }
            dropsBySession.getOrDefault(session.getSessionId(), List.of()).stream()
                    .map(UndepositedSessionDrop::getBagNumber)
                    .filter(Objects::nonNull)
                    .forEach(bags::add);
        }
        return WorkItems.references(
                "terminals", String.join(", ", terminals),
                "bagNumbers", String.join(", ", bags),
                "sessions", Integer.toString(sessions.size()));
    }

    private Map<UUID, ExtLocationReplica> locationsOf(List<AgedSession> sessions) {
        List<UUID> ids = sessions.stream()
                .map(aged -> aged.session().getLocationId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ExtLocationReplica> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            locations.findAllById(ids).forEach(location -> byId.put(location.getLocationId(), location));
        }
        return byId;
    }

    private Map<UUID, List<UndepositedSessionDrop>> dropsOf(List<AgedSession> sessions) {
        Map<UUID, UUID> sessionByRow = sessions.stream()
                .map(AgedSession::session)
                .collect(Collectors.toMap(
                        UndepositedSession::getUndepositedSessionId, UndepositedSession::getSessionId, (a, b) -> a));
        Map<UUID, List<UndepositedSessionDrop>> bySession = new HashMap<>();
        drops.findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(sessionByRow.keySet())
                .forEach(drop -> bySession
                        .computeIfAbsent(sessionByRow.get(drop.getUndepositedSessionId()), k -> new ArrayList<>())
                        .add(drop));
        return bySession;
    }

    /** {@code covers} for a stored location; a session with none is reachable only by an unscoped caller. */
    private static boolean reaches(@Nullable UUID locationId) {
        return SecurityContextHelper.locationScope()
                .covers(AccountingPermissions.DEPOSIT_CREATE, locationId == null ? "" : locationId.toString());
    }
}
