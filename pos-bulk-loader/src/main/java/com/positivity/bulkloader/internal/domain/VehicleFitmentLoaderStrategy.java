package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class VehicleFitmentLoaderStrategy implements DomainLoaderStrategy<VehicleFitmentRecord> {

  @Override
  public DomainType getDomainType() {
    return DomainType.VEHICLE_FITMENT;
  }

  @Override
  public VehicleFitmentRecord mapRow(@NonNull Map<String, String> row) {
    VehicleFitmentRecord fitmentRecord = new VehicleFitmentRecord();
    fitmentRecord.setPartNumberId(row.get("partNumberId"));
    fitmentRecord.setManufacturerName(row.get("manufacturerName"));
    fitmentRecord.setMakeName(row.get("makeName"));
    fitmentRecord.setModelName(row.get("modelName"));
    fitmentRecord.setVehicleTypeName(row.get("vehicleTypeName"));
    fitmentRecord.setVehicleYear(row.get("vehicleYear"));
    fitmentRecord.setEngineType(row.get("engineType"));
    fitmentRecord.setSubmodel(row.get("submodel"));
    fitmentRecord.setNotes(row.get("notes"));
    return fitmentRecord;
  }

  @Override
  public List<String> validate(@NonNull VehicleFitmentRecord item) {
    List<String> errors = new ArrayList<>();
    if (item.getPartNumberId() == null || item.getPartNumberId().isBlank()) {
      errors.add("partNumberId is required");
    } else {
      try {
        Long.parseLong(item.getPartNumberId().trim());
      } catch (NumberFormatException _) {
        errors.add("partNumberId must be a valid number");
      }
    }
    return errors;
  }
}