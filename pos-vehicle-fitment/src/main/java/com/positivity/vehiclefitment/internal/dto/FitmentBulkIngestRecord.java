package com.positivity.vehiclefitment.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FitmentBulkIngestRecord {

  @NotNull
  private Long partNumberId;

  private String manufacturerName;
  private String makeName;
  private String modelName;
  private String vehicleTypeName;
  private String vehicleYear;
  private String engineType;
  private String submodel;
  private String notes;
}
