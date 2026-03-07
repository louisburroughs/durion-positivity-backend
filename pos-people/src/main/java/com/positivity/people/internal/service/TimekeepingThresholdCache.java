package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.AttendanceReportKey;
import com.positivity.people.internal.entity.TimekeepingPolicy;
import com.positivity.people.internal.enums.TimekeepingPolicyScopeType;
import com.positivity.people.internal.repository.TimekeepingPolicyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TimekeepingThresholdCache {

	private static final int DEFAULT_THRESHOLD_MINUTES = 30;

	private static final int DEFAULT_MAX_RESOLUTION_CACHE_ENTRIES = 4096;

	private final TimekeepingPolicyRepository timekeepingPolicyRepository;

	private final int maxResolutionCacheEntries;

	private final boolean preloadLocationPolicies;

	private final boolean preloadGlobalPolicies;

	public TimekeepingThresholdCache(TimekeepingPolicyRepository timekeepingPolicyRepository,
			@Value("${pos.people.reports.threshold-cache.max-resolution-cache-entries:4096}") int maxResolutionCacheEntries,
			@Value("${pos.people.reports.threshold-cache.preload-location-policies:true}") boolean preloadLocationPolicies,
			@Value("${pos.people.reports.threshold-cache.preload-global-policies:true}") boolean preloadGlobalPolicies) {
		this.timekeepingPolicyRepository = timekeepingPolicyRepository;
		this.maxResolutionCacheEntries = maxResolutionCacheEntries > 0 ? maxResolutionCacheEntries
				: DEFAULT_MAX_RESOLUTION_CACHE_ENTRIES;
		this.preloadLocationPolicies = preloadLocationPolicies;
		this.preloadGlobalPolicies = preloadGlobalPolicies;
	}

	public ThresholdResolverContext createContext(Set<AttendanceReportKey> keys, ZoneId zoneId) {
		List<TimekeepingPolicy> globalPolicies = preloadGlobalPolicies
				? timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL) : List.of();

		Map<UUID, List<TimekeepingPolicy>> locationPoliciesById = preloadLocationPolicies
				? loadLocationPolicies(extractUuidLocationIds(keys)) : Map.of();

		return new ThresholdResolverContext(zoneId, maxResolutionCacheEntries, globalPolicies, locationPoliciesById,
				preloadLocationPolicies, preloadGlobalPolicies);
	}

	private Map<UUID, List<TimekeepingPolicy>> loadLocationPolicies(Set<UUID> locationIds) {
		if (locationIds.isEmpty()) {
			return Map.of();
		}

		List<TimekeepingPolicy> policies = timekeepingPolicyRepository
			.findByScopeTypeAndScopeIdIn(TimekeepingPolicyScopeType.LOCATION, locationIds);
		Map<UUID, List<TimekeepingPolicy>> grouped = new HashMap<>();
		for (TimekeepingPolicy policy : policies) {
			UUID scopeId = policy.getScopeId();
			if (scopeId == null) {
				continue;
			}
			grouped.computeIfAbsent(scopeId, ignored -> new ArrayList<>()).add(policy);
		}
		return grouped;
	}

	private Set<UUID> extractUuidLocationIds(Collection<AttendanceReportKey> keys) {
		return keys.stream()
			.map(AttendanceReportKey::getLocationId)
			.map(this::tryParseUuid)
			.flatMap(Optional::stream)
			.collect(java.util.stream.Collectors.toSet());
	}

	private Optional<UUID> tryParseUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	public final class ThresholdResolverContext {

		private final ZoneId zoneId;

		private final List<TimekeepingPolicy> preloadedGlobalPolicies;

		private final Map<UUID, List<TimekeepingPolicy>> preloadedLocationPoliciesById;

		private final boolean preloadLocationPoliciesEnabled;

		private final boolean preloadGlobalPoliciesEnabled;

		private final LinkedHashMap<ThresholdKey, Integer> thresholdCache;

		private ThresholdResolverContext(ZoneId zoneId, int maxCacheEntries,
				List<TimekeepingPolicy> preloadedGlobalPolicies,
				Map<UUID, List<TimekeepingPolicy>> preloadedLocationPoliciesById,
				boolean preloadLocationPoliciesEnabled, boolean preloadGlobalPoliciesEnabled) {
			this.zoneId = zoneId;
			this.preloadedGlobalPolicies = preloadedGlobalPolicies;
			this.preloadedLocationPoliciesById = preloadedLocationPoliciesById;
			this.preloadLocationPoliciesEnabled = preloadLocationPoliciesEnabled;
			this.preloadGlobalPoliciesEnabled = preloadGlobalPoliciesEnabled;
			this.thresholdCache = new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<ThresholdKey, Integer> eldest) {
					return size() > maxCacheEntries;
				}
			};
		}

		public int resolveThresholdMinutes(String locationId, LocalDate reportDate) {
			ThresholdKey key = new ThresholdKey(locationId, reportDate);
			Integer cached = thresholdCache.get(key);
			if (cached != null) {
				return cached;
			}

			int resolved = computeThresholdMinutes(locationId, reportDate);
			thresholdCache.put(key, resolved);
			return resolved;
		}

		private int computeThresholdMinutes(String locationId, LocalDate reportDate) {
			Instant evaluationTime = reportDate.atStartOfDay(zoneId).toInstant();

			Optional<UUID> locationUuid = tryParseUuid(locationId);
			if (locationUuid.isPresent()) {
				List<TimekeepingPolicy> locationPolicies = preloadLocationPoliciesEnabled
						? preloadedLocationPoliciesById.getOrDefault(locationUuid.get(), List.of())
						: timekeepingPolicyRepository.findByScopeTypeAndScopeId(TimekeepingPolicyScopeType.LOCATION,
								locationUuid.get());
				Optional<TimekeepingPolicy> locationPolicy = selectEffectivePolicy(locationPolicies, evaluationTime);
				if (locationPolicy.isPresent()) {
					return sanitizeThreshold(locationPolicy.get().getJobTimeDiscrepancyThresholdMinutes());
				}
			}

			List<TimekeepingPolicy> globalPolicies = preloadGlobalPoliciesEnabled ? preloadedGlobalPolicies
					: timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL);
			return selectEffectivePolicy(globalPolicies, evaluationTime)
				.map(TimekeepingPolicy::getJobTimeDiscrepancyThresholdMinutes)
				.map(TimekeepingThresholdCache.this::sanitizeThreshold)
				.orElse(DEFAULT_THRESHOLD_MINUTES);
		}

		private Optional<TimekeepingPolicy> selectEffectivePolicy(List<TimekeepingPolicy> candidates,
				Instant evaluationTime) {
			return candidates.stream()
				.filter(policy -> isEffective(policy, evaluationTime))
				.sorted(Comparator
					.comparing(TimekeepingPolicy::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(TimekeepingPolicy::getEffectiveStartAt,
							Comparator.nullsLast(Comparator.reverseOrder())))
				.findFirst();
		}

		private boolean isEffective(TimekeepingPolicy policy, Instant evaluationTime) {
			boolean startsBeforeOrAt = policy.getEffectiveStartAt() == null
					|| !policy.getEffectiveStartAt().isAfter(evaluationTime);
			boolean endsAfterOrAt = policy.getEffectiveEndAt() == null
					|| !policy.getEffectiveEndAt().isBefore(evaluationTime);
			return startsBeforeOrAt && endsAfterOrAt;
		}

	}

	private int sanitizeThreshold(Integer threshold) {
		if (threshold == null || threshold < 0) {
			return DEFAULT_THRESHOLD_MINUTES;
		}
		return threshold;
	}

	private record ThresholdKey(String locationId, LocalDate reportDate) {
	}

}
