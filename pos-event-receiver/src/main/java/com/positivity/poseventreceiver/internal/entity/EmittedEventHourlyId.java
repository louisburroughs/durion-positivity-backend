package com.positivity.poseventreceiver.internal.entity;

import java.io.Serializable;
import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@EqualsAndHashCode
public class EmittedEventHourlyId implements Serializable {

  private static final long serialVersionUID = 1L;

  private Instant bucket;
  private String eventType;
}
