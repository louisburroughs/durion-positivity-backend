package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class VehicleLoaderStrategyTest {

    private final VehicleLoaderStrategy strategy = new VehicleLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_setsAllFields_fromRowMap() {
        Map<String, String> row = new HashMap<>();
        row.put("accountId", "00000000-0000-0000-0000-000000000001");
        row.put("vin", "1HGCM82633A004352");
        row.put("unitNumber", "UNIT-001");
        row.put("description", "2024 Honda Accord");
        row.put("make", "Honda");
        row.put("model", "Accord");
        row.put("year", "2024");
        row.put("trim", "EX-L");
        row.put("licensePlate", "ABC1234");
        row.put("licensePlateJurisdiction", "CA");

        VehicleBulkRecord result = strategy.mapRow(row);

        assertThat(result.getAccountId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(result.getVin()).isEqualTo("1HGCM82633A004352");
        assertThat(result.getUnitNumber()).isEqualTo("UNIT-001");
        assertThat(result.getDescription()).isEqualTo("2024 Honda Accord");
        assertThat(result.getMake()).isEqualTo("Honda");
        assertThat(result.getModel()).isEqualTo("Accord");
        assertThat(result.getYear()).isEqualTo("2024");
        assertThat(result.getTrim()).isEqualTo("EX-L");
        assertThat(result.getLicensePlate()).isEqualTo("ABC1234");
        assertThat(result.getLicensePlateJurisdiction()).isEqualTo("CA");
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_returnsNoErrors_whenAllRequiredFieldsPresent() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicleRecord.setVin("1HGCM82633A004352");
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_returnsError_whenAccountIdMissing() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId(null);
        vehicleRecord.setVin("1HGCM82633A004352");
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("accountId is required"));
    }

    @Test
    void validate_returnsError_whenAccountIdNotUUID() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("not-a-uuid");
        vehicleRecord.setVin("1HGCM82633A004352");
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("accountId must be a valid UUID"));
    }

    @Test
    void validate_returnsError_whenVinMissing() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicleRecord.setVin(null);
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("vin"));
    }

    @Test
    void validate_returnsError_whenVinWrongLength() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicleRecord.setVin("SHORT");
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("17"));
    }

    @Test
    void validate_returnsError_whenUnitNumberMissing() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicleRecord.setVin("1HGCM82633A004352");
        vehicleRecord.setUnitNumber(null);
        vehicleRecord.setDescription("2024 Honda Accord");

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("unitNumber"));
    }

    @Test
    void validate_returnsError_whenDescriptionMissing() {
        VehicleBulkRecord vehicleRecord = new VehicleBulkRecord();
        vehicleRecord.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicleRecord.setVin("1HGCM82633A004352");
        vehicleRecord.setUnitNumber("UNIT-001");
        vehicleRecord.setDescription(null);

        List<String> errors = strategy.validate(vehicleRecord);

        assertThat(errors).anyMatch(e -> e.contains("description"));
    }

    // ─── getDomainType ────────────────────────────────────────────────────────

    @Test
    void getDomainType_returnsVehicle() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.VEHICLE);
    }
}
