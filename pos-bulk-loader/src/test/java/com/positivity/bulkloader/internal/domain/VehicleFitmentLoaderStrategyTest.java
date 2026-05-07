package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class VehicleFitmentLoaderStrategyTest {

    private final VehicleFitmentLoaderStrategy strategy = new VehicleFitmentLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_setsAllFields_fromRowMap() {
        Map<String, String> row = new HashMap<>();
        row.put("partNumberId", "12345");
        row.put("manufacturerName", "Bosch");
        row.put("makeName", "Toyota");
        row.put("modelName", "Camry");
        row.put("vehicleTypeName", "Sedan");
        row.put("vehicleYear", "2020");
        row.put("engineType", "2.5L I4");
        row.put("submodel", "SE");
        row.put("notes", "Direct fit");

        VehicleFitmentRecord result = strategy.mapRow(row);

        assertThat(result.getPartNumberId()).isEqualTo("12345");
        assertThat(result.getManufacturerName()).isEqualTo("Bosch");
        assertThat(result.getMakeName()).isEqualTo("Toyota");
        assertThat(result.getModelName()).isEqualTo("Camry");
        assertThat(result.getVehicleTypeName()).isEqualTo("Sedan");
        assertThat(result.getVehicleYear()).isEqualTo("2020");
        assertThat(result.getEngineType()).isEqualTo("2.5L I4");
        assertThat(result.getSubmodel()).isEqualTo("SE");
        assertThat(result.getNotes()).isEqualTo("Direct fit");
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_returnsNoErrors_whenPartNumberIdPresent() {
        VehicleFitmentRecord vehicleRecord = new VehicleFitmentRecord();
        vehicleRecord.setPartNumberId("99001");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_returnsError_whenPartNumberIdMissing() {
        VehicleFitmentRecord vehicleRecord = new VehicleFitmentRecord();
        vehicleRecord.setPartNumberId(null);

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("partNumberId is required"));
    }

    @Test
    void validate_returnsError_whenPartNumberIdNotNumeric() {
        VehicleFitmentRecord vehicleRecord = new VehicleFitmentRecord();
        vehicleRecord.setPartNumberId("not-a-number");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("partNumberId must be a valid number"));
    }

    // ─── getDomainType ────────────────────────────────────────────────────────

    @Test
    void getDomainType_returnsVehicleFitment() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.VEHICLE_FITMENT);
    }
}
