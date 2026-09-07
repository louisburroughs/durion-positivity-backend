package com.positivity.catalog.internal.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.positivity.catalog.internal.entity.LaborTimeSourcePolicyEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.enums.OperationCategory;
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
 *   <li>Stored {@code service_labor_standard} rows, ranked owner-first: a {@code SHOP} row owned
 *       by the asking location beats every platform row, because a shop that has priced its own
 *       work is not overruled by a published guide (#1575 Tier 0). Shop rows owned by any other
 *       location are not candidates. Then most specific vehicle match, then time-type
 *       preference, then policy precedence — which is category-aware, so "tire operations prefer
 *       the manufacturer's install time" is a policy row rather than a release.</li>
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

    /**
     * QUERY_ONLY answers, bounded two ways because both bounds are license terms (ADR-0058 §4):
     * per-entry lifetime is the source's configured TTL (a vendor answer must not outlive its
     * license window even in memory), and the size cap keeps request-derived vehicle keys —
     * every null-widened CRM variant is a distinct key — from growing the heap without limit.
     */
    private final Cache<LiveCacheKey, CachedLiveAnswer> liveCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(new Expiry<LiveCacheKey, CachedLiveAnswer>() {
                @Override
                public long expireAfterCreate(LiveCacheKey key, CachedLiveAnswer value, long currentTime) {
                    return value.ttl().toNanos();
                }

                @Override
                public long expireAfterUpdate(
                        LiveCacheKey key, CachedLiveAnswer value, long currentTime, long currentDuration) {
                    return value.ttl().toNanos();
                }

                @Override
                public long expireAfterRead(
                        LiveCacheKey key, CachedLiveAnswer value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

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
            @NonNull UUID serviceId,
            @NonNull VehicleKey vehicle,
            @Nullable LaborTimeType preferredTimeType,
            @Nullable UUID locationId) {

        // The service's category selects the applicable precedence policy (R1); a service with no
        // category simply matches only the category-less policy rows.
        Optional<ServiceEntity> service = serviceRepository.findById(serviceId);
        OperationCategory category =
                service.map(ServiceEntity::getOperationCategory).orElse(null);

        // 1. Stored standards, best candidate wins.
        Optional<Candidate> stored =
                standardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(serviceId).stream()
                        .filter(row -> ownedByAsker(row, locationId))
                        .map(row -> toCandidate(row, vehicle))
                        .flatMap(Optional::stream)
                        .min(candidateOrder(preferredTimeType, policyPrecedenceIndex(), category));
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
                            time.includedOperations(),
                            LaborStandardOwnerScope.PLATFORM.name());
                }
            } catch (ProviderCallException e) {
                liveSourceFailed = true;
                log.warn("Live labor-time source {} unavailable: {}", sourceCode, e.getMessage());
            }
        }

        // 3. The vehicle-agnostic default the taxonomy carries (V17).
        Optional<LaborTimeResolution> defaultHours = service.filter(row -> row.getDefaultLaborHours() != null)
                .map(row -> new LaborTimeResolution(
                        Status.RESOLVED,
                        row.getDefaultLaborHours(),
                        LaborTimeType.DURION_STANDARD.name(),
                        "DURION",
                        "default-hours",
                        MatchGrade.DEFAULT_HOURS,
                        null,
                        List.of(),
                        LaborStandardOwnerScope.PLATFORM.name()));
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
                    row.getIncludedOpCodes() == null ? List.of() : row.getIncludedOpCodes(),
                    row.getOwnerScope().name());
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

    /**
     * A shop's own row is a candidate only for the location that owns it; every other location
     * must never see it, which is a filter rather than a ranking because a low-ranked shop row
     * would still answer when nothing else did.
     */
    private static boolean ownedByAsker(ServiceLaborStandardEntity row, @Nullable UUID locationId) {
        return row.getOwnerScope() != LaborStandardOwnerScope.SHOP
                || (locationId != null && locationId.equals(row.getOwnerLocationId()));
    }

    private Comparator<Candidate> candidateOrder(
            @Nullable LaborTimeType preferredTimeType,
            Map<String, Integer> policyPrecedence,
            @Nullable OperationCategory category) {
        // Ownership outranks everything else, vehicle specificity included: a shop's model-level
        // number for its own work beats a guide's engine-exact one, because the shop is quoting
        // what it will actually charge.
        Comparator<Candidate> byOwner =
                Comparator.comparingInt(c -> c.row().getOwnerScope() == LaborStandardOwnerScope.SHOP ? 0 : 1);
        Comparator<Candidate> bySpecificity = Comparator.comparingInt((Candidate c) -> -c.specificity());
        Comparator<Candidate> byTypePreference = Comparator.comparingInt(c -> {
            LaborTimeType type = c.row().getTimeType();
            if (preferredTimeType != null) {
                return type == preferredTimeType ? 0 : 1 + DEFAULT_TYPE_ORDER.indexOf(type);
            }
            int index = DEFAULT_TYPE_ORDER.indexOf(type);
            return index < 0 ? DEFAULT_TYPE_ORDER.size() : index;
        });
        Comparator<Candidate> byPolicy = Comparator.comparingInt(c ->
                precedenceFor(policyPrecedence, c.row().getTimeType(), c.row().getSourceCode(), category));
        return byOwner.thenComparing(bySpecificity)
                .thenComparing(byTypePreference)
                .thenComparing(byPolicy);
    }

    /**
     * A policy row naming this operation's category wins over a category-less one for the same
     * (time type, source) — that specificity is the whole point of R1: MICHELIN outranks the
     * aggregator for TIRE_SERVICE without outranking it everywhere. Falling through to the
     * category-less row and then to the provider's configured default keeps every pre-Tier-0
     * policy row meaning exactly what it meant before the column existed.
     */
    private int precedenceFor(
            Map<String, Integer> policyPrecedence,
            LaborTimeType timeType,
            String sourceCode,
            @Nullable OperationCategory category) {
        if (category != null) {
            Integer scoped = policyPrecedence.get(policyKey(timeType, sourceCode, category));
            if (scoped != null) {
                return scoped;
            }
        }
        return policyPrecedence.getOrDefault(policyKey(timeType, sourceCode, null), defaultPrecedence(sourceCode));
    }

    private Map<String, Integer> policyPrecedenceIndex() {
        return policyRepository.findByEnabledTrue().stream()
                .collect(java.util.stream.Collectors.toMap(
                        p -> policyKey(p.getTimeType(), p.getSourceCode(), p.getOperationCategory()),
                        LaborTimeSourcePolicyEntity::getPrecedence,
                        Math::min));
    }

    private static String policyKey(LaborTimeType timeType, String sourceCode, @Nullable OperationCategory category) {
        return timeType.name() + "|" + sourceCode.toUpperCase(Locale.ROOT) + "|"
                + (category == null ? "" : category.name());
    }

    private int defaultPrecedence(String sourceCode) {
        LaborTimeProviderPort port = laborTimeProviders.get(sourceCode);
        return port == null ? NO_POLICY_PRECEDENCE : port.descriptor().defaultPrecedence();
    }

    // ── QUERY_ONLY live path, TTL-bounded cache ─────────────────────────────────────────

    private record LiveCacheKey(String sourceCode, String providerCode, VehicleKey vehicle) {}

    /**
     * {@code expiresAt} (against the injected {@link Clock}) is the authoritative TTL check so
     * expiry is deterministic under test; {@code ttl} feeds Caffeine's wall-clock eviction, which
     * is the memory bound, not the freshness contract.
     */
    private record CachedLiveAnswer(Optional<ProviderLaborTime> answer, Instant expiresAt, Duration ttl) {}

    private Optional<ProviderLaborTime> liveLookup(
            LaborTimeProviderPort port, String sourceCode, String providerCode, VehicleKey vehicle) {
        LiveCacheKey key = new LiveCacheKey(sourceCode, providerCode, vehicle);
        Instant now = Instant.now(clock);
        CachedLiveAnswer cached = liveCache.getIfPresent(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.answer();
        }
        Optional<ProviderLaborTime> answer = port.getLaborTime(vehicle, providerCode);
        Duration ttl = laborTimeProviderCacheTtls.getOrDefault(sourceCode, Duration.ofMinutes(5));
        liveCache.put(key, new CachedLiveAnswer(answer, now.plus(ttl), ttl));
        return answer;
    }
}
