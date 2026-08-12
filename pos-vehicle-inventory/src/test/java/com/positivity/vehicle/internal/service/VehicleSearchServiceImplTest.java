package com.positivity.vehicle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.vehicle.internal.dto.SearchVehiclesRequest;
import com.positivity.vehicle.internal.dto.SearchVehiclesResponse;
import com.positivity.vehicle.internal.dto.VehicleSummary;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link VehicleSearchServiceImpl} (CAP:091 Story #103).
 *
 * <p>
 * This search backs the service-counter typeahead, so ordering is the product:
 * an exact match must outrank a prefix match, which must outrank a contains
 * match — and contains-matching is off unless the caller opts in, because
 * substring hits on short queries flood the list with noise.
 *
 * <p>
 * The query's <em>shape</em> chooses the field being searched: 17 alphanumeric
 * characters is a full VIN, 6–16 a VIN prefix, and anything shorter falls to
 * plate and then general matching, each with its own minimum length so a
 * one-character query cannot scan the fleet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleSearchServiceImpl — ranked vehicle lookup")
class VehicleSearchServiceImplTest {

    @Mock
    private VehicleRecordRepository vehicleRepository;

    private VehicleSearchServiceImpl service() {
        return new VehicleSearchServiceImpl(vehicleRepository);
    }

    private static VehicleRecord record(String vin, String plate, String unitNumber, String description) {
        VehicleRecord record = new VehicleRecord();
        record.setVehicleId(UUID.randomUUID());
        record.setVin(vin);
        record.setLicensePlate(plate);
        record.setUnitNumber(unitNumber);
        record.setDescription(description);
        record.setMake("Toyota");
        record.setModel("Tacoma");
        record.setYear(2024);
        return record;
    }

    private static SearchVehiclesRequest request(String query, int limit, Boolean contains) {
        return SearchVehiclesRequest.builder()
                .query(query)
                .limit(limit)
                .enableContainsMatching(contains)
                .build();
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("orders results exact, then prefix, then contains")
        void ranksByTier() {
            when(vehicleRepository.searchByQuery("1HGCM8"))
                    .thenReturn(List.of(
                            record("XX1HGCM8YYYYYYYYY", null, "U1", "d"),
                            record("1HGCM82633A004352", null, "U2", "d"),
                            record("1HGCM8", null, "U3", "d")));

            SearchVehiclesResponse response = service().search(request("1hgcm8", 25, true));

            // The clerk reads top-down; a contains hit above the exact one buries the right answer.
            assertThat(response.getResults())
                    .extracting(VehicleSummary::getVin)
                    .containsExactly("1HGCM8", "1HGCM82633A004352", "XX1HGCM8YYYYYYYYY");
        }

        @Test
        @DisplayName("excludes contains matches unless the caller opts in")
        void containsIsOptIn() {
            when(vehicleRepository.searchByQuery("1HGCM8"))
                    .thenReturn(List.of(
                            record("XX1HGCM8YYYYYYYYY", null, "U1", "d"),
                            record("1HGCM82633A004352", null, "U2", "d")));

            SearchVehiclesResponse strict = service().search(request("1HGCM8", 25, false));
            assertThat(strict.getResults()).extracting(VehicleSummary::getVin).containsExactly("1HGCM82633A004352");

            SearchVehiclesResponse defaulted = service().search(request("1HGCM8", 25, null));
            // Null means the caller did not opt in — same as false, never as true.
            assertThat(defaulted.getResults()).hasSize(1);
        }

        @Test
        @DisplayName("caps the page at 50 and reports what was cut")
        void limitAndHasMore() {
            List<VehicleRecord> sixty = new java.util.ArrayList<>();
            for (int i = 0; i < 60; i++) {
                sixty.add(record("1HGCM8%011d".formatted(i), null, "U" + i, "d"));
            }
            when(vehicleRepository.searchByQuery("1HGCM8")).thenReturn(sixty);

            SearchVehiclesResponse response = service().search(request("1HGCM8", 100, false));

            assertThat(response.getResults()).hasSize(50);
            assertThat(response.getTotalCount()).isEqualTo(60);
            // hasMore is what tells the UI to say "refine your search" instead of "that's all".
            assertThat(response.getHasMore()).isTrue();
        }

        @Test
        @DisplayName("echoes the caller's original query, not the normalized one")
        void echoesOriginalQuery() {
            when(vehicleRepository.searchByQuery(anyString())).thenReturn(List.of());

            assertThat(service().search(request("  1hgcm8  ", 25, false)).getQuery())
                    .isEqualTo("  1hgcm8  ");
        }
    }

    @Nested
    @DisplayName("query shape")
    class QueryShape {

        @Test
        @DisplayName("a 17-character alphanumeric query is a full-VIN search")
        void fullVin() {
            when(vehicleRepository.searchByQuery("1HGCM82633A004352"))
                    .thenReturn(List.of(record("1HGCM82633A004352", null, "U1", "d")));

            assertThat(service().search(request("1HGCM82633A004352", 25, false)).getResults())
                    .hasSize(1);
        }

        @Test
        @DisplayName("a plate-shaped query searches the plate, not the VIN")
        void plateQuery() {
            // "AB-123" contains a hyphen, so it cannot be a VIN prefix; it matches on the plate.
            when(vehicleRepository.searchByQuery("AB-123"))
                    .thenReturn(List.of(
                            record("1HGCM82633A004352", "AB-123", "U1", "d"),
                            record("2HGCM82633A004352", "ZZ-999", "U2", "d")));

            assertThat(service().search(request("ab-123", 25, false)).getResults())
                    .extracting(VehicleSummary::getLicensePlate)
                    .containsExactly("AB-123");
        }

        @Test
        @DisplayName("a plate search survives records that have no plate at all")
        void plateSearchToleratesMissingPlates() {
            when(vehicleRepository.searchByQuery("AB-123"))
                    .thenReturn(List.of(
                            record("1HGCM82633A004352", null, "U1", "d"),
                            record("2HGCM82633A004352", "AB-123", "U2", "d")));

            // A fleet vehicle without a plate must not NPE the whole search.
            assertThat(service().search(request("AB-123", 25, false)).getResults())
                    .hasSize(1);
        }

        @Test
        @DisplayName("rejects an empty or too-short query instead of scanning the fleet")
        void shortQueriesRejected() {
            assertThatThrownBy(() -> service().search(request("   ", 25, false)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service().search(request("AB", 25, false)))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(vehicleRepository);
        }
    }
}
