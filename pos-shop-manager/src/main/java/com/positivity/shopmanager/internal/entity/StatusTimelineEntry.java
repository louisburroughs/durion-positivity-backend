package com.positivity.shopmanager.internal.entity;

import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusTimelineEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private AppointmentStatus status;

    @Column(name = "change_timestamp", nullable = false)
    private Instant changeTimestamp;

    @Column(name = "source_event_id", nullable = false, columnDefinition = "UUID")
    private UUID sourceEventId;
}
