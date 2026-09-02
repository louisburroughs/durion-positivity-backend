package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.LaborTimeSourcePolicyEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.repository.LaborTimeSourcePolicyRepository;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceOperationXrefRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import com.positivity.catalog.internal.service.LaborTimeResolution.MatchGrade;
import com.positivity.catalog.internal.service.LaborTimeResolution.Status;
import com.positivity.catalog.internal.spi.LaborTimeProviderPort;
import com.positivity.catalog.internal.spi.ProviderCallException;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor.LicenseMode;
import com.positivity.catalog.internal.spi.model.ProviderLaborTime;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labor-time resolution (#1569 Phase 1, sourcing plan §3.4).
 *
 * <h2>Order of answers</h2>
 *
 * <ol>
 *   <li>Stored {@code service_labor_standard} rows: most specific vehicle match first, then
 *       policy precedence, then time-type preference.</li>
 *   <li>QUERY_ONLY live sources through the SPI, under a per-source TTL cache whose lifetime is
 *       a license term, never persisted beyond it (ADR-0058 §4).</li>
 *   <li>The service's scalar {@code default_labor_hours} — deliberately last and graded
 *       {@code DEFAULT_HOURS}: it models a shop charging the same hours for every vehicle.</li>
 *   <li>A typed miss. {@code SOURCE_UNAVAILABLE} only when a live source failed AND nothing
 *       else answered; a clean "nobody publishes this" is {@code NO_TIME_AVAILABLE}.</li>
 * </ol>
 *
 * <p>Vehicle matching: a row matches when every field the ROW states equals the request's field
 * (row null = wildcard; a row stating a field the request leaves null does not match — a
 * request that doesn't know the engine must not receive an engine-specific time). Phase 1
 * scale note: candidates load per service id, fine at reference-catalog volume; the Phase 2
 * scale pass moves matching into the query.
 */
@Slf4j
@Service
public class LaborTimeResolutionServiceImpl implements LaborTimeResolutionService {

    /** Retail-first default ordering when the caller states no preference (sourcing plan §3.4). */
    private static final List<LaborTimeType> DEFAULT_TYPE_ORDER = List.of(
            LaborTimeType.MANUFACTURER_INSTALL,
            LaborTimeType.RETAIL_FLAT_RATE,
            LaborTimeType.DURION_STANDARD,
            LaborTimeType.OEM_WARRANTY);

    private static final int NO_POLICY_PRECEDENCE = 1_000;

    private final ServiceLaborStandardRepository standardRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceOperationXrefRepository xrefRepository;
    private final LaborTimeSourcePolicyRepository policyRepository;
    private final Map<String, LaborTimeProviderPort> laborTimeProviders;
    private final Map<String, Duration> laborTimeProviderCacheTtls;
    private final Clock clock;

    private final Map<LiveCacheKey, CachedLiveAnswer> liveCache = new ConcurrentHashMap<>();

    public LaborTimeResolutionServiceImpl(
            ServiceLaborStandardRepository standardRepository,
            ServiceRepository serviceRepository,
            ServiceOperationXrefRepository xrefRepository,
            LaborTimeSourcePolicyRepository policyRepository,
            Map<String, LaborTimeProviderPort> laborTimeProviders,
            Map<String, Duration> laborTimeProviderCacheTtls,
            Clock clock) {
        this.standardRepository = standardRepository;
        this.serviceRepository = serviceRepository;
        this.xrefRepository = xrefRepository;
        this.policyRepository = policyRepository;
        this.laborTimeProviders = laborTimeProviders;
        this.laborTimeProviderCacheTtls = laborTimeProviderCacheTtls;
        this.clock = clock;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public LaborTimeResolution resolve(
            @NonNull UUID serviceId, @NonNull VehicleKey vehicle, @Nullable LaborTimeType preferredTimeType) {

        // 1. Stored standards, best candidate wins.
        Optional<Candidate> stored =
                standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(serviceId).stream()
                        .map(row -> toCandidate(row, vehicle))
                        .flatMap(Optional::stream)
                        .min(candidateOrder(preferredTimeType, policyPrecedenceIndex()));
        if (stored.isPresent()) {
            return stored.get().toResolution();
        }

        // 2. QUERY_ONLY live sources, cached under license-term TTLs.
        boolean liveSourceFailed = false;
        for (LaborTimeProviderPort port : laborTimeProviders.values()) {
            if (port.descriptor().licenseMode() != LicenseMode.QUERY_ONLY) {
                continue;
            }
            String sourceCode = port.descriptor().sourceCode();
            Optional<String> providerCode = xrefRepository
                    .findBySourceCodeAndServiceId(sourceCode, serviceId)
                    .map(x -> x.getProviderOpCode());
            if (providerCode.isEmpty()) {
                continue;
            }
            try {
                Optional<ProviderLaborTime> live = liveLookup(port, sourceCode, providerCode.get(), vehicle);
                if (live.isPresent()) {
                    ProviderLaborTime time = live.get();
                    return new LaborTimeResolution(
                            Status.RESOLVED,
                            time.hours(),
                            time.timeType(),
                            sourceCode,
                            time.sourceRevision(),
                            // A live guide answered for this exact query; the vendor decides
                            // internal widening, so the platform reports it as exact.
                            MatchGrade.EXACT,
                            time.overlapGroup(),
                            time.includedOperations());
                }
            } catch (ProviderCallException e) {
                liveSourceFailed = true;
                log.warn("Live labor-time source {} unavailable: {}", sourceCode, e.getMessage());
            }
        }

        // 3. The vehicle-agnostic default the taxonomy carries (V17).
        Optional<LaborTimeResolution> defaultHours = serviceRepository
                .findById(serviceId)
                .filter(service -> service.getDefaultLaborHours() != null)
                .map(service -> new LaborTimeResolution(
                        Status.RESOLVED,
                        service.getDefaultLaborHours(),
                        LaborTimeType.DURION_STANDARD.name(),
                        "DURION",
                        "default-hours",
                        MatchGrade.DEFAULT_HOURS,
                        null,
                        List.of()));
        if (defaultHours.isPresent()) {
            return defaultHours.get();
        }

        // 4. Typed miss.
        return LaborTimeResolution.miss(liveSourceFailed ? Status.SOURCE_UNAVAILABLE : Status.NO_TIME_AVAILABLE);
    }

    // ── Stored-row matching ────────────────────────────────────────────────────────────

    private record Candidate(ServiceLaborStandardEntity row, MatchGrade grade, int specificity) {

        LaborTimeResolution toResolution() {
            return new LaborTimeResolution(
                    Status.RESOLVED,
                    row.getLaborHours(),
                    row.getTimeType().name(),
                    row.getSourceCode(),
                    row.getSourceRevision(),
                    grade,
                    row.getOverlapGroup(),
                    row.getIncludedOpCodes() == null ? List.of() : row.getIncludedOpCodes());
        }
    }

    private static Optional<Candidate> toCandidate(ServiceLaborStandardEntity row, VehicleKey vehicle) {
        if (!fieldMatches(row.getVehicleYear(), vehicle.vehicleYear())
                || !fieldMatches(row.getMake(), vehicle.make())
                || !fieldMatches(row.getModel(), vehicle.model())
                || !fieldMatches(row.getSubmodel(), vehicle.submodel())
                || !fieldMatches(row.getEngineCode(), vehicle.engineCode())) {
            return Optional.empty();
        }
        int specificity = (row.getVehicleYear() != null ? 1 : 0)
                + (row.getMake() != null ? 1 : 0)
                + (row.getModel() != null ? 1 : 0)
                + (row.getSubmodel() != null ? 1 : 0)
                + (row.getEngineCode() != null ? 1 : 0);
        MatchGrade grade;
        if (row.getSubmodel() != null && row.getEngineCode() != null) {
            grade = MatchGrade.EXACT;
        } else if (row.getVehicleYear() != null && row.getMake() != null && row.getModel() != null) {
            grade = MatchGrade.ENGINE_WILDCARD;
        } else {
            grade = MatchGrade.MODEL_LEVEL;
        }
        return Optional.of(new Candidate(row, grade, specificity));
    }

    /** Row null = wildcard; a row stating a field the request left unknown does not match. */
    private static boolean fieldMatches(@Nullable String rowValue, @Nullable String requestValue) {
        return rowValue == null || Objects.equals(rowValue, requestValue);
    }

    private Comparator<Candidate> candidateOrder(
            @Nullable LaborTimeType preferredTimeType, Map<String, Integer> policyPrecedence) {
        Comparator<Candidate> bySpecificity = Comparator.comparingInt((Candidate c) -> -c.specificity());
        Comparator<Candidate> byTypePreference = Comparator.comparingInt(c -> {
            LaborTimeType type = c.row().getTimeType();
            if (preferredTimeType != null) {
                return type == preferredTimeType ? 0 : 1 + DEFAULT_TYPE_ORDER.indexOf(type);
            }
            int index = DEFAULT_TYPE_ORDER.indexOf(type);
            return index < 0 ? DEFAULT_TYPE_ORDER.size() : index;
        });
        Comparator<Candidate> byPolicy = Comparator.comparingInt(c -> policyPrecedence.getOrDefault(
                policyKey(c.row().getTimeType(), c.row().getSourceCode()),
                defaultPrecedence(c.row().getSourceCode())));
        return bySpecificity.thenComparing(byTypePreference).thenComparing(byPolicy);
    }

    private Map<String, Integer> policyPrecedenceIndex() {
        return policyRepository.findByEnabledTrue().stream()
                .collect(java.util.stream.Collectors.toMap(
                        p -> policyKey(p.getTimeType(), p.getSourceCode()),
                        LaborTimeSourcePolicyEntity::getPrecedence,
                        Math::min));
    }

    private static String policyKey(LaborTimeType timeType, String sourceCode) {
        return timeType.name() + "|" + sourceCode.toUpperCase(Locale.ROOT);
    }

    private int defaultPrecedence(String sourceCode) {
        LaborTimeProviderPort port = laborTimeProviders.get(sourceCode);
        return port == null ? NO_POLICY_PRECEDENCE : port.descriptor().defaultPrecedence();
    }

    // ── QUERY_ONLY live path, TTL-bounded cache ─────────────────────────────────────────

    private record LiveCacheKey(String sourceCode, String providerCode, VehicleKey vehicle) {}

    private record CachedLiveAnswer(Optional<ProviderLaborTime> answer, Instant expiresAt) {}

    private Optional<ProviderLaborTime> liveLookup(
            LaborTimeProviderPort port, String sourceCode, String providerCode, VehicleKey vehicle) {
        LiveCacheKey key = new LiveCacheKey(sourceCode, providerCode, vehicle);
        Instant now = Instant.now(clock);
        CachedLiveAnswer cached = liveCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.answer();
        }
        Optional<ProviderLaborTime> answer = port.getLaborTime(vehicle, providerCode);
        Duration ttl = laborTimeProviderCacheTtls.getOrDefault(sourceCode, Duration.ofMinutes(5));
        liveCache.put(key, new CachedLiveAnswer(answer, now.plus(ttl)));
        return answer;
    }
}
