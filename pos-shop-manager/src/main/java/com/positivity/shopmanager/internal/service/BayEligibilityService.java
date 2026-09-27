package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.repository.ExtBaySpecialtyMapReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The one bay-eligibility rule (DECISION-SHOPMGMT-021), shared by the opening search (which
 * filters against it) and appointment submit/reschedule (which refuses against it) — search,
 * submit and reschedule must never disagree.
 *
 * <p><b>Specialty (rule 4)</b> comes from the tenant's bay-type specialty map (D14.1, replicated
 * from {@code location.bay-specialty-map.updated} into {@link ExtBaySpecialtyMapReplicaRepository}),
 * never from which bays happen to be active: an operation is specialty iff the map names it for
 * some {@code BayType}, and a bay must claim (in its own {@code serviceCapabilityCodes}) every
 * specialty operation on the appointment. A specialty operation no active bay at the location
 * claims is unbookable there — it never falls back to general work. An operation the map does not
 * name is general work, open to any bay whose {@code accepts_general_work} is true (D14: a
 * {@code WASH_DETAIL} bay is the one type that is not). While the tenant's map has not arrived yet
 * (an empty replica), specialty is derived instead from whichever of the location's bays claims the
 * operation — today's pre-replica behaviour, logged once per tenant so the gap stays visible.
 *
 * <p><b>Duty class (rule 5)</b> caps a bay's {@code maxDutyClass} against the vehicle's GVWR class,
 * skipped whenever either is null.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BayEligibilityService {

    private static final Set<UUID> MAP_MISSING_WARNED = ConcurrentHashMap.newKeySet();

    private final ExtBaySpecialtyMapReplicaRepository specialtyMapRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceRepository;

    /** Why a bay was excluded — the same two axes the opening search reports separately. */
    public enum Refusal {
        NOT_EQUIPPED,
        DUTY_CLASS_EXCEEDED
    }

    /** {@code bays} filtered against {@code operationCodes} and {@code gvwrClass}, with per-bay refusals kept. */
    public record Eligibility(
            @NonNull List<ExtBayReplica> eligible,
            int active,
            int byCapability,
            int byDutyClass,
            @NonNull Map<UUID, Refusal> refusalByBay) {}

    /**
     * Filters {@code bays} (every bay at the appointment's location) against every operation on the
     * appointment and the vehicle's GVWR class. Used by the opening search to build its eligible
     * list and counts, and internally by {@link #refusalFor} for one named bay.
     */
    public @NonNull Eligibility eligibleBays(
            @NonNull List<ExtBayReplica> bays, @NonNull Set<String> operationCodes, @Nullable Integer gvwrClass) {
        Set<String> specialty = specialtyOperations(operationCodes, bays);
        List<ExtBayReplica> eligible = new ArrayList<>();
        Map<UUID, Refusal> refusalByBay = new LinkedHashMap<>();
        int byCapability = 0;
        int byDutyClass = 0;
        for (ExtBayReplica bay : bays) {
            Optional<Refusal> refusal = checkBay(bay, operationCodes, specialty, gvwrClass);
            if (refusal.isEmpty()) {
                eligible.add(bay);
                continue;
            }
            refusalByBay.put(bay.getBayId(), refusal.get());
            if (refusal.get() == Refusal.NOT_EQUIPPED) {
                byCapability++;
            } else {
                byDutyClass++;
            }
        }
        return new Eligibility(eligible, bays.size(), byCapability, byDutyClass, refusalByBay);
    }

    /**
     * Whether {@code bay} — a specific, already-resolved bay — is eligible for {@code
     * operationCodes} and {@code gvwrClass}. {@code locationBays} is every bay at the same location,
     * needed only for the empty-map fallback (see class doc); submit and reschedule pass the same
     * {@code findActiveByLocationOrdered} read the opening search uses, so the two never disagree.
     */
    public @NonNull Optional<Refusal> refusalFor(
            @NonNull ExtBayReplica bay,
            @NonNull List<ExtBayReplica> locationBays,
            @NonNull Set<String> operationCodes,
            @Nullable Integer gvwrClass) {
        Set<String> specialty = specialtyOperations(operationCodes, locationBays);
        return checkBay(bay, operationCodes, specialty, gvwrClass);
    }

    private Optional<Refusal> checkBay(
            ExtBayReplica bay,
            Set<String> operationCodes,
            Set<String> specialtyOperations,
            @Nullable Integer gvwrClass) {
        boolean claimsAllSpecialty = specialtyOperations.stream().allMatch(op -> claims(bay, op));
        boolean hasGeneralWork = operationCodes.size() > specialtyOperations.size();
        if (!claimsAllSpecialty || (hasGeneralWork && !bay.isAcceptsGeneralWork())) {
            return Optional.of(Refusal.NOT_EQUIPPED);
        }
        if (gvwrClass != null && bay.getMaxDutyClass() != null && bay.getMaxDutyClass() < gvwrClass) {
            return Optional.of(Refusal.DUTY_CLASS_EXCEEDED);
        }
        return Optional.empty();
    }

    /**
     * The operations among {@code operationCodes} that are specialty work at this location (rule
     * 4). Named by the tenant's bay-type specialty map when it has arrived; derived from which of
     * {@code bays} claims the code otherwise (logged once per tenant).
     */
    public @NonNull Set<String> specialtyOperations(
            @NonNull Set<String> operationCodes, @NonNull List<ExtBayReplica> bays) {
        if (operationCodes.isEmpty()) {
            return Set.of();
        }
        if (specialtyMapRepository.count() == 0) {
            warnMapMissingOnce();
            return operationCodes.stream()
                    .filter(op -> bays.stream().anyMatch(bay -> claims(bay, op)))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return operationCodes.stream()
                .filter(specialtyMapRepository::existsByOperationCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The catalog operation codes named by {@code serviceIds}, normalized and de-duplicated. */
    public @NonNull Set<String> operationCodesOf(@Nullable Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Set.of();
        }
        return catalogServiceRepository.findAllByServiceIdIn(serviceIds).stream()
                .map(ExtCatalogServiceReplica::getOperationCode)
                .map(SkillRequirementResolver::normalize)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static boolean claims(ExtBayReplica bay, String operation) {
        return bay.getServiceCapabilityCodes() != null
                && bay.getServiceCapabilityCodes().stream()
                        .map(SkillRequirementResolver::normalize)
                        .anyMatch(operation::equals);
    }

    /** Whether {@code bay} claims no specialty work of its own (ranking only, D14 rule 6). */
    public static boolean isGeneral(ExtBayReplica bay) {
        return bay.getServiceCapabilityCodes() == null
                || bay.getServiceCapabilityCodes().isEmpty();
    }

    private void warnMapMissingOnce() {
        TenantContext.current().ifPresent(tenantId -> {
            if (MAP_MISSING_WARNED.add(tenantId)) {
                log.warn(
                        "Bay specialty map (location.bay-specialty-map.updated) has not arrived yet for tenant {};"
                                + " bay eligibility falls back to deriving specialty from which bays claim an"
                                + " operation (DECISION-SHOPMGMT-021 rule 4)",
                        tenantId);
            }
        });
    }
}
