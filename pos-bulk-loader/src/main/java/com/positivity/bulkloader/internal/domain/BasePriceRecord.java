package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class BasePriceRecord {

  private String productId;
  private String msrp;
  private String currency;
  private String effectiveFrom;
}