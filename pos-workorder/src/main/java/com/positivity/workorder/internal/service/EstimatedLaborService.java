package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Overlap-aware estimated labor hours for a workorder (#1569 Phase 1, sourcing plan §6.3 item
 * 3). This is what finally populates {@code WorkorderSummary.estimatedLaborHours} — and it is
 * deliberately NOT a naive sum:
 *
 * <ul>
 *   <li><b>Included operations contribute zero.</b> A line whose operation code appears in
 *       another line's guide-included list is already paid for inside that line's hours
 *       (rotors include pads); charging it again would bill the same work twice. Only a line
 *       that is itself charged can include others — resolved greedily largest-hours-first —
 *       so a zeroed line's include list never cascades (A includes B, B includes C: C stays
 *       charged) and mutual includes keep the larger line.</li>
 *   <li><b>Overlap groups share setup.</b> Lines sharing a guide overlap group (wheels come
 *       off once) contribute the group's largest time in full and each additional line at a
 *       configured fraction — the v1 simplification the plan records (§12 Q6); real per-pair
 *       overlap deductions arrive with licensed guide data in Phase 2.</li>
 * </ul>
 *
 * <p>Inputs are the promotion-time snapshots ({@code quantity} = agreed hours, guide overlap
 * metadata) plus the {@code ext_catalog_service} replica for each line's operation code, so the
 * sum keeps computing the same answer after the guide publishes a new revision. Declined and
 * cancelled lines never count.
 */
@Service
@RequiredArgsConstructor
public class EstimatedLaborService {

    private final WorkorderServiceRepository workorderServiceRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;

    /**
     * Fraction of each additional same-overlap-group line's hours that still counts. License
     * plate for the v1 overlap arithmetic: 1.0 restores the naive sum, 0.0 counts only the
     * group's largest line.
     */
    @Value("${pos.workorder.labor.overlap-additional-factor:0.5}")
    private BigDecimal overlapAdditionalFactor;

    /**
     * The overlap-aware total and what it excluded, so a response can say why the number is
     * smaller than the naive sum.
     *
     * @param estimatedHours overlap-aware total in tenths; null when no line carries hours
     * @param includedOperationCodes operation codes contributing zero because another line's
     *     guide time already includes them
     */
    public record EstimatedLabor(
            @Nullable BigDecimal estimatedHours, @NonNull List<String> includedOperationCodes) {

        public static EstimatedLabor none() {
            return new EstimatedLabor(null, List.of());
        }
    }

    @NonNull
    public EstimatedLabor estimateForWorkorder(@NonNull UUID workorderId) {
        return estimateForLines(workorderServiceRepository.findByWorkOrder_Id(workorderId));
    }

    @NonNull
    public EstimatedLabor estimateForLines(@Nullable List<WorkorderServiceLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return EstimatedLabor.none();
        }
        List<WorkorderServiceLine> counted = lines.stream()
                .filter(line -> !Boolean.TRUE.equals(line.getDeclined()))
                .filter(line -> line.getStatus() != WorkorderItemStatus.CANCELLED)
                .filter(line -> line.getQuantity() != null)
                .toList();
        if (counted.isEmpty()) {
            return EstimatedLabor.none();
        }

        Map<UUID, String> opCodesByServiceId = replicaOpCodes(counted);

        // A line is zeroed only when a line that is itself still CHARGED includes it: a
        // greedy pass in descending-hours order, so only charged lines' include lists zero
        // anyone. This settles the chained/mutual-include cases deterministically — if A
        // includes B and B includes C, zeroing B must not also zero C (nobody charged is
        // absorbing C's work), and if A and B include each other the larger line survives.
        List<WorkorderServiceLine> byHoursDesc = counted.stream()
                .sorted(Comparator.comparing(WorkorderServiceLine::getQuantity).reversed())
                .toList();
        Set<String> chargedIncludes = new HashSet<>();
        // Identity-keyed: entity equality may be id-based and ids can be unassigned pre-persist.
        Set<WorkorderServiceLine> zeroedLines = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> zeroed = new ArrayList<>();
        for (WorkorderServiceLine line : byHoursDesc) {
            String opCode =
                    line.getServiceEntityId() == null ? null : opCodesByServiceId.get(line.getServiceEntityId());
            if (opCode != null && chargedIncludes.contains(opCode)) {
                zeroedLines.add(line);
                zeroed.add(opCode);
                continue;
            }
            includedOpCodes(line).forEach(chargedIncludes::add);
        }

        Map<String, List<BigDecimal>> hoursByGroup = new LinkedHashMap<>();
        int standaloneKey = 0;
        for (WorkorderServiceLine line : counted) {
            if (zeroedLines.contains(line)) {
                continue;
            }
            String group = line.getGuideOverlapGroup() == null
                    ? "line-" + (standaloneKey++)
                    : "group-" + line.getGuideOverlapGroup();
            hoursByGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(line.getQuantity());
        }
        if (hoursByGroup.isEmpty()) {
            return new EstimatedLabor(null, List.copyOf(zeroed));
        }

        BigDecimal total = BigDecimal.ZERO;
        for (List<BigDecimal> group : hoursByGroup.values()) {
            BigDecimal max = group.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal others =
                    group.stream().reduce(BigDecimal.ZERO, BigDecimal::add).subtract(max);
            total = total.add(max).add(others.multiply(overlapAdditionalFactor));
        }
        return new EstimatedLabor(total.setScale(1, RoundingMode.HALF_UP), List.copyOf(zeroed));
    }

    private static java.util.stream.Stream<String> includedOpCodes(WorkorderServiceLine line) {
        String csv = line.getGuideIncludedOpCodes();
        if (csv == null || csv.isBlank()) {
            return java.util.stream.Stream.empty();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(code -> !code.isEmpty());
    }

    private Map<UUID, String> replicaOpCodes(List<WorkorderServiceLine> lines) {
        Set<UUID> serviceIds = lines.stream()
                .map(WorkorderServiceLine::getServiceEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byId = new HashMap<>();
        for (ExtCatalogServiceReplica replica : catalogServiceReplicaRepository.findByServiceIdIn(serviceIds)) {
            if (replica.getOperationCode() != null) {
                byId.put(replica.getServiceId(), replica.getOperationCode());
            }
        }
        return byId;
    }
}
