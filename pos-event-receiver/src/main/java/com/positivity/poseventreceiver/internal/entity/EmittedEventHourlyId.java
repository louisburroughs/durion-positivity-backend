package com.positivity.poseventreceiver.internal.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@EqualsAndHashCode
public class EmittedEventHourlyId implements Serializable {

    private static final long serialVersionUID = 2L;

    private Instant bucket;
    private UUID tenantId;
    private String eventType;
}
