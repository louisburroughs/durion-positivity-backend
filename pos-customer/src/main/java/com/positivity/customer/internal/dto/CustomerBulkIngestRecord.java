package com.positivity.customer.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerBulkIngestRecord {

  @NotBlank
  private String firstName;

  @NotBlank
  private String lastName;

  private String email;

  private String phoneNumber;

  private String primaryAddress;

  private String customerNumber;
}