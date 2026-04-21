package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class VehicleFitmentRecord {

  private String partNumberId;
  private String manufacturerName;
  private String makeName;
  private String modelName;
  private String vehicleTypeName;
  private String vehicleYear;
  private String engineType;
  private String submodel;
  private String notes;
}