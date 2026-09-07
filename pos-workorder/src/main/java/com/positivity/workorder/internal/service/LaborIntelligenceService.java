package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.LaborIntelligenceRow;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.workorder.internal.repository.LaborIntelligenceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durion labor intelligence: what a shop's own finished work says about an operation
 * (#1575 Tier 0 "historical actual repair times", Tier 4 sketch; sourcing plan Phase 4).
 *
 * <p>Phase 1 gave every LABOR line a guide baseline and every workorder an estimate-vs-actual
 * variance. That variance is per workorder and vanishes when the job closes. This aggregates it
 * across jobs, which is the point #1575 makes about Tier 4: a shop's median for an operation
 * eventually says more about scheduling and staffing than the published book time does.
 *
 * <h2>What it groups by, and what it deliberately does not</h2>
 *
 * Operation and location, plus a technician breakdown — exactly the shape #1575's Tier 4 sketch
 * names (industry book time, shop median, technician median). It does <em>not</em> group by
 * vehicle class, because pos-workorder cannot: {@code ExtVehicleReplica} carries VIN, plate,
 * unit number and odometer, and no make or model. Grouping by a vehicle dimension needs that
 * data to reach this module first; inventing it here would produce confident nonsense.
 *
 * <h2>Advisory, never automatic</h2>
 *
 * {@code suggestedStandardHours} is a candidate a curator promotes by authoring it as a DURION
 * labor standard in pos-catalog. This service writes nothing and holds no client to pos-catalog
 * beyond the existing read-only labor-time edge. Below the sample threshold the suggestion is
 * withheld entirely rather than offered with a caveat: a median of three jobs is a rumour.
 *
 * <p>A technician's median counts only lines that technician worked <em>alone</em>. A line two
 * people split says nothing about either one's speed, and folding it into both their medians
 * would make the slower one look fast and the faster one look slow.
 *
 * <p>Scale note, same as the Phase 1 resolution path: the rollup is computed in Java over the
 * grouped projections. Honest at reference volume for an admin report that is not on any quote
 * path; a windowed or materialised form is the Phase 2 scale pass's problem.
 */
@Service
@RequiredArgsConstructor
public class LaborIntelligenceService {

    private static final int HOURS_SCALE = 1;
    private static final int PCT_SCALE = 1;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final LaborIntelligenceRepository laborIntelligenceRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;

    /**
     * Finished lines a candidate standard needs before it is offered at all. Five is a judgement,
     * not a statistic — it is the point at which a median stops being one unusual job.
     */
    @Value("${pos.workorder.labor-intelligence.min-samples:5}")
    private int minSamples;

    /**
     * @param operationCode narrow to one Durion operation code; null does not narrow
     * @param locationId narrow to one shop; null reports every shop separately, never pooled
     * @param minSampleOverride raise the suggestion threshold for this call; null uses the
     *     configured default. Lowering it below the default is refused — the floor exists so a
     *     caller cannot ask for a standard derived from one job.
     */
    @NonNull
    @Transactional(readOnly = true)
    public List<LaborIntelligenceRow> operations(
            @Nullable String operationCode, @Nullable UUID locationId, @Nullable Integer minSampleOverride) {
        int threshold = minSampleOverride == null ? minSamples : Math.max(minSamples, minSampleOverride);

        Map<UUID, List<BigDecimal>> technicianHoursByService = technicianMedianInputs();

        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        for (Object[] row : laborIntelligenceRepository.findLineTotals()) {
            UUID serviceId = (UUID) row[1];
            UUID rowLocationId = (UUID) row[2];
            if (locationId != null && !locationId.equals(rowLocationId)) {
                continue;
            }
            groups.computeIfAbsent(new GroupKey(serviceId, rowLocationId), key -> new Group())
                    .add((BigDecimal) row[3], (BigDecimal) row[4]);
        }
        if (groups.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> operationCodes = operationCodesFor(
                groups.keySet().stream().map(GroupKey::serviceId).distinct().toList());
        String wantedCode = operationCode == null || operationCode.isBlank()
                ? null
                : operationCode.trim().toUpperCase(Locale.ROOT);

        List<LaborIntelligenceRow> rows = new ArrayList<>();
        for (Map.Entry<GroupKey, Group> entry : groups.entrySet()) {
            UUID serviceId = entry.getKey().serviceId();
            String code = operationCodes.get(serviceId);
            if (wantedCode != null && !wantedCode.equals(code)) {
                continue;
            }
            rows.add(toRow(
                    serviceId,
                    code,
                    entry.getKey().locationId(),
                    entry.getValue(),
                    threshold,
                    technicianHoursByService.getOrDefault(serviceId, List.of())));
        }
        // Widest variance first: a curator's attention belongs where the shop and the guide
        // disagree most, not on the operations they already agree about.
        rows.sort(Comparator.comparing(
                        (LaborIntelligenceRow row) -> row.varianceHours().abs())
                .reversed());
        return rows;
    }

    // ── Technician medians ──────────────────────────────────────────────────────────────

    /**
     * Median sole-worked hours per technician, indexed by service. A line appearing once in the
     * per-technician projection is a line that technician worked alone; a line appearing twice
     * was split, and neither entry is evidence about either technician.
     */
    private Map<UUID, List<BigDecimal>> technicianMedianInputs() {
        Map<UUID, Integer> techniciansPerLine = new LinkedHashMap<>();
        List<Object[]> rows = laborIntelligenceRepository.findLineTotalsByTechnician();
        for (Object[] row : rows) {
            techniciansPerLine.merge((UUID) row[0], 1, Integer::sum);
        }

        Map<UUID, Map<UUID, List<BigDecimal>>> hoursByServiceAndTechnician = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (techniciansPerLine.getOrDefault((UUID) row[0], 0) != 1) {
                continue;
            }
            hoursByServiceAndTechnician
                    .computeIfAbsent((UUID) row[1], id -> new LinkedHashMap<>())
                    .computeIfAbsent((UUID) row[2], id -> new ArrayList<>())
                    .add((BigDecimal) row[3]);
        }

        Map<UUID, List<BigDecimal>> medians = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<UUID, List<BigDecimal>>> service : hoursByServiceAndTechnician.entrySet()) {
            List<BigDecimal> perTechnician = new ArrayList<>();
            for (List<BigDecimal> hours : service.getValue().values()) {
                if (hours.size() >= minSamples) {
                    perTechnician.add(median(hours));
                }
            }
            if (!perTechnician.isEmpty()) {
                medians.put(service.getKey(), perTechnician);
            }
        }
        return medians;
    }

    // ── Assembly ────────────────────────────────────────────────────────────────────────

    private record GroupKey(UUID serviceId, @Nullable UUID locationId) {}

    private static final class Group {
        private final List<BigDecimal> guideHours = new ArrayList<>();
        private final List<BigDecimal> actualHours = new ArrayList<>();

        void add(BigDecimal guide, BigDecimal actual) {
            guideHours.add(guide);
            actualHours.add(actual);
        }
    }

    private LaborIntelligenceRow toRow(
            UUID serviceId,
            @Nullable String operationCode,
            @Nullable UUID locationId,
            Group group,
            int threshold,
            List<BigDecimal> technicianMedians) {
        BigDecimal medianActual = median(group.actualHours);
        BigDecimal medianGuide = median(group.guideHours);
        BigDecimal variance = medianActual.subtract(medianGuide).setScale(HOURS_SCALE, RoundingMode.HALF_UP);
        BigDecimal variancePct = medianGuide.signum() == 0
                ? BigDecimal.ZERO.setScale(PCT_SCALE)
                : variance.multiply(ONE_HUNDRED).divide(medianGuide, PCT_SCALE, RoundingMode.HALF_UP);
        int sampleCount = group.actualHours.size();

        return new LaborIntelligenceRow(
                serviceId,
                operationCode,
                locationId,
                sampleCount,
                medianActual,
                mean(group.actualHours),
                medianGuide,
                variance,
                variancePct,
                sampleCount >= threshold ? medianActual : null,
                technicianMedians.size(),
                technicianMedians.stream().min(Comparator.naturalOrder()).orElse(null));
    }

    private Map<UUID, String> operationCodesFor(List<UUID> serviceIds) {
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (ExtCatalogServiceReplica replica : catalogServiceReplicaRepository.findAllById(serviceIds)) {
            if (replica.getOperationCode() != null) {
                codes.put(replica.getServiceId(), replica.getOperationCode());
            }
        }
        return codes;
    }

    /** Even-sized samples take the mean of the two middle values, the usual convention. */
    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted =
                values.stream().filter(Objects::nonNull).sorted().toList();
        int size = sorted.size();
        if (size == 0) {
            return BigDecimal.ZERO.setScale(HOURS_SCALE);
        }
        BigDecimal middle = size % 2 == 1
                ? sorted.get(size / 2)
                : sorted.get(size / 2 - 1)
                        .add(sorted.get(size / 2))
                        .divide(BigDecimal.valueOf(2), HOURS_SCALE + 2, RoundingMode.HALF_UP);
        return middle.setScale(HOURS_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(HOURS_SCALE);
        }
        BigDecimal total = values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(values.size()), HOURS_SCALE, RoundingMode.HALF_UP);
    }
}
