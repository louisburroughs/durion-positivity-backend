package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.LaborStandardConflictDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Surfaces two active labor standards from different sources that would answer for the same
 * vehicle and time type but disagree (#1569 residual R2, sourcing plan Phase 3 item 3).
 *
 * <h2>Why "overlapping keys", not "the same key"</h2>
 *
 * The plan phrased this as same operation + same vehicle + different sources. That comparison
 * cannot fire against the current schema: {@code ux_sls_active_key} covers
 * {@code (service, time_type, owner, vehicle key)} with <em>no source column</em>, so two STORE
 * sources cannot hold the same time type at the same vehicle key at all — the second import's
 * insert collides (the gap the Phase 2 scale pass closes).
 *
 * <p>What sources really do is publish at <em>different levels of specificity</em>: a tyre
 * manufacturer states one wildcard time for an operation while an aggregator states a time per
 * year/make/model. Those rows never collide, and they are exactly what disagrees in practice —
 * a 2019 Civic resolves the aggregator's number while every other vehicle resolves the
 * manufacturer's, and nobody sees that the two differ. So a conflict here is a pair whose
 * vehicle keys <em>overlap</em>: every field one row states, the other either states identically
 * or leaves wild.
 *
 * <p>This is a curation report, not an error. Resolution still answers, deterministically, by
 * precedence; the report says the answer is contested and by how much.
 */
@Service
@RequiredArgsConstructor
public class LaborStandardConflictServiceImpl implements LaborStandardConflictService {

    private final ServiceLaborStandardRepository standardRepository;
    private final ServiceRepository serviceRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<LaborStandardConflictDto> findConflicts(@NonNull BigDecimal thresholdHours) {
        List<ServiceLaborStandardEntity> active = standardRepository.findBySupersededAtIsNullOrderByServiceIdAsc();

        // (service, time type) is the only grouping that matters: rows of different time types are
        // meant to differ — warranty time is not retail time — so comparing them would report the
        // domain working as designed as if it were a problem.
        Map<String, List<ServiceLaborStandardEntity>> byOperation = new HashMap<>();
        for (ServiceLaborStandardEntity row : active) {
            byOperation
                    .computeIfAbsent(
                            row.getServiceId() + "|" + row.getTimeType().name(), key -> new ArrayList<>())
                    .add(row);
        }

        Map<UUID, String> operationCodes = new HashMap<>();
        List<LaborStandardConflictDto> conflicts = new ArrayList<>();
        for (List<ServiceLaborStandardEntity> rows : byOperation.values()) {
            for (int i = 0; i < rows.size(); i++) {
                for (int j = i + 1; j < rows.size(); j++) {
                    ServiceLaborStandardEntity left = rows.get(i);
                    ServiceLaborStandardEntity right = rows.get(j);
                    if (left.getSourceCode().equalsIgnoreCase(right.getSourceCode())) {
                        continue;
                    }
                    if (!keysOverlap(left, right)) {
                        continue;
                    }
                    BigDecimal difference =
                            left.getLaborHours().subtract(right.getLaborHours()).abs();
                    if (difference.compareTo(thresholdHours) <= 0) {
                        continue;
                    }
                    conflicts.add(toDto(left, right, difference, operationCodes));
                }
            }
        }
        conflicts.sort((a, b) -> b.getDifferenceHours().compareTo(a.getDifferenceHours()));
        return conflicts;
    }

    /**
     * Two rows overlap when, field by field, either states the same value or one of them is wild.
     * A row stating {@code Honda} and a row stating {@code Toyota} describe different vehicles and
     * never both answer; a wildcard row and a {@code Honda} row both answer for a Honda.
     */
    private static boolean keysOverlap(ServiceLaborStandardEntity left, ServiceLaborStandardEntity right) {
        return fieldOverlaps(left.getVehicleYear(), right.getVehicleYear())
                && fieldOverlaps(left.getMake(), right.getMake())
                && fieldOverlaps(left.getModel(), right.getModel())
                && fieldOverlaps(left.getSubmodel(), right.getSubmodel())
                && fieldOverlaps(left.getEngineCode(), right.getEngineCode());
    }

    private static boolean fieldOverlaps(@Nullable String left, @Nullable String right) {
        return left == null || right == null || Objects.equals(left, right);
    }

    private LaborStandardConflictDto toDto(
            ServiceLaborStandardEntity left,
            ServiceLaborStandardEntity right,
            BigDecimal difference,
            Map<UUID, String> operationCodes) {
        LaborStandardConflictDto dto = new LaborStandardConflictDto();
        dto.setServiceId(left.getServiceId());
        dto.setOperationCode(operationCodes.computeIfAbsent(
                left.getServiceId(),
                id -> serviceRepository
                        .findById(id)
                        .map(ServiceEntity::getOperationCode)
                        .orElse(null)));
        dto.setTimeType(left.getTimeType().name());
        // The narrower of the two keys is the one a reader needs: it names the vehicles where the
        // disagreement actually bites, rather than the wildcard that merely also covers them.
        dto.setVehicleKey(renderKey(narrower(left, right)));
        dto.setSourceCode(left.getSourceCode());
        dto.setLaborHours(left.getLaborHours());
        dto.setStandardId(left.getId());
        dto.setOtherSourceCode(right.getSourceCode());
        dto.setOtherLaborHours(right.getLaborHours());
        dto.setOtherStandardId(right.getId());
        dto.setDifferenceHours(difference);
        return dto;
    }

    private static ServiceLaborStandardEntity narrower(
            ServiceLaborStandardEntity left, ServiceLaborStandardEntity right) {
        return specificity(left) >= specificity(right) ? left : right;
    }

    private static int specificity(ServiceLaborStandardEntity row) {
        return (row.getVehicleYear() != null ? 1 : 0)
                + (row.getMake() != null ? 1 : 0)
                + (row.getModel() != null ? 1 : 0)
                + (row.getSubmodel() != null ? 1 : 0)
                + (row.getEngineCode() != null ? 1 : 0);
    }

    private static String renderKey(ServiceLaborStandardEntity row) {
        return String.join(
                "|",
                orWild(row.getVehicleYear()),
                orWild(row.getMake()),
                orWild(row.getModel()),
                orWild(row.getSubmodel()),
                orWild(row.getEngineCode()));
    }

    private static String orWild(@Nullable String value) {
        return value == null ? "*" : value;
    }
}
