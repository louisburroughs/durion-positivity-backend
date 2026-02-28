package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.HrEventType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HrMechanicEvent {
    private UUID eventId;
    private HrEventType eventType;
    private String personId;
    private Integer version;
    private Instant occurredAt;
    private Payload payload;

    @Data
    @Builder
    public static class Payload {
        private String firstName;
        private String lastName;
        private LocalDate hireDate;
        private List<Skill> skills;

        @Data
        @Builder
        public static class Skill {
            private String skillCode;
            private int proficiencyLevel;
        }
    }
}
