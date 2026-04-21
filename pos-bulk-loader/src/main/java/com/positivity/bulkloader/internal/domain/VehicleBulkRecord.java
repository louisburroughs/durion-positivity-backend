package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class VehicleBulkRecord {

  private String accountId;
  private String vin;
  private String unitNumber;
  private String description;
  private String make;
  private String model;
  private String year;
  private String trim;
  private String licensePlate;
  private String licensePlateJurisdiction;
}