package com.positivity.vehicle.internal.service;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.internal.dto.VehicleSummary;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for vehicle search and lookup (CAP:091 Story #103).
 * Provides ranking-based search with configurable matching strategies.
 * PUBLIC API for vehicle discovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleSearchService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 50;
    private static final int MIN_VIN_QUERY_LENGTH = 6;
    private static final int MIN_PLATE_QUERY_LENGTH = 3;
    private static final int MIN_GENERAL_QUERY_LENGTH = 3;

    private final VehicleRecordRepository vehicleRepository;

    /**
     * Searches vehicles by query with ranking-based results.
     * Ranking tiers: exact match → prefix match → contains match (if enabled)
     */
    @Transactional(readOnly = true)
    public SearchVehiclesResponse search(@NonNull SearchVehiclesRequest request) {
        log.info("Searching vehicles: query='{}', limit={}, enableContains={}",
                request.getQuery(), request.getLimit(), request.isEnableContainsMatching());

        // Validate and normalize query
        String normalizedQuery = request.getQuery().trim().toUpperCase();
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }

        // Determine search type by query length and format
        SearchType searchType = determineSearchType(normalizedQuery);
        validateQueryLength(normalizedQuery, searchType);

        // Fetch candidates from repository (broad search)
        List<VehicleSummary> candidates = vehicleRepository.searchByQuery(normalizedQuery)
                .stream()
                .map(this::toVehicleSummary)
                .toList();

        // Rank candidates by relevance
        List<VehicleSummary> ranked = rankCandidates(candidates, normalizedQuery, searchType,
                request.isEnableContainsMatching());

        // Apply limit
        int limit = Math.min(request.getLimit(), MAX_LIMIT);
        List<VehicleSummary> results = ranked.stream()
                .limit(limit)
                .toList();

        log.info("Search returned {} results (limit={})", results.size(), limit);

        return SearchVehiclesResponse.builder()
                .results(results)
                .totalCount(ranked.size())
                .hasMore(ranked.size() > limit)
                .query(request.getQuery())
                .build();
    }

    /**
     * Determines the search type based on query format.
     */
    private SearchType determineSearchType(String query) {
        // Try VIN pattern (17 chars, alphanumeric)
        if (query.length() >= MIN_VIN_QUERY_LENGTH && query.matches("[A-Z0-9]+")) {
            if (query.length() == 17) {
                return SearchType.VIN_EXACT;
            }
            return SearchType.VIN_PREFIX;
        }

        // License plate pattern (shorter, contains letters/numbers)
        if (query.length() >= MIN_PLATE_QUERY_LENGTH) {
            return SearchType.PLATE;
        }

        return SearchType.GENERAL;
    }

    /**
     * Validates query length based on search type.
     */
    private void validateQueryLength(String query, SearchType type) {
        switch (type) {
            case VIN_PREFIX, VIN_EXACT -> {
                if (query.length() < MIN_VIN_QUERY_LENGTH) {
                    throw new IllegalArgumentException(
                            "VIN query must be at least " + MIN_VIN_QUERY_LENGTH + " characters");
                }
            }
            case PLATE -> {
                if (query.length() < MIN_PLATE_QUERY_LENGTH) {
                    throw new IllegalArgumentException(
                            "License plate query must be at least " + MIN_PLATE_QUERY_LENGTH + " characters");
                }
            }
            case GENERAL -> {
                if (query.length() < MIN_GENERAL_QUERY_LENGTH) {
                    throw new IllegalArgumentException(
                            "Query must be at least " + MIN_GENERAL_QUERY_LENGTH + " characters");
                }
            }
        }
    }

    /**
     * Ranks candidates by relevance tier: exact → prefix → contains.
     */
    private List<VehicleSummary> rankCandidates(
            List<VehicleSummary> candidates,
            String query,
            SearchType type,
            Boolean enableContains) {

        List<VehicleSummary> exactMatches = new ArrayList<>();
        List<VehicleSummary> prefixMatches = new ArrayList<>();
        List<VehicleSummary> containsMatches = new ArrayList<>();

        for (VehicleSummary candidate : candidates) {
            String searchField = getSearchField(candidate, type);

            if (searchField.equals(query)) {
                exactMatches.add(candidate);
            } else if (searchField.startsWith(query)) {
                prefixMatches.add(candidate);
            } else if (Boolean.TRUE.equals(enableContains) && searchField.contains(query)) {
                containsMatches.add(candidate);
            }
        }

        // Combine results in ranking order
        List<VehicleSummary> ranked = new ArrayList<>();
        ranked.addAll(exactMatches);
        ranked.addAll(prefixMatches);
        ranked.addAll(containsMatches);

        log.debug("Ranking: exact={}, prefix={}, contains={}",
                exactMatches.size(), prefixMatches.size(), containsMatches.size());

        return ranked;
    }

    /**
     * Gets the appropriate search field for the vehicle.
     */
    private String getSearchField(VehicleSummary vehicle, SearchType type) {
        return switch (type) {
            case VIN_EXACT, VIN_PREFIX -> vehicle.getVin().toUpperCase();
            case PLATE -> (vehicle.getLicensePlate() != null ? vehicle.getLicensePlate() : "").toUpperCase();
            case GENERAL -> (vehicle.getUnitNumber().toUpperCase() + " " + vehicle.getDescription().toUpperCase());
        };
    }

    /**
     * Converts a vehicle entity to summary DTO.
     */
    private VehicleSummary toVehicleSummary(com.positivity.vehicle.internal.entity.VehicleRecord vehicle) {
        return VehicleSummary.builder()
                .vehicleId(vehicle.getVehicleId())
                .vin(vehicle.getVin())
                .unitNumber(vehicle.getUnitNumber())
                .licensePlate(vehicle.getLicensePlate())
                .description(vehicle.getDescription())
                .year(vehicle.getYear())
                .make(vehicle.getMake())
                .model(vehicle.getModel())
                .build();
    }

    enum SearchType {
        VIN_EXACT,
        VIN_PREFIX,
        PLATE,
        GENERAL
    }
}
