package com.positivity.workorder.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TechnicianAssignmentMapper technician names (#2481)")
class TechnicianAssignmentMapperTest {

    private static final UUID WORKORDER = UUID.randomUUID();
    private static final UUID TECH_A = UUID.randomUUID();
    private static final UUID TECH_B = UUID.randomUUID();

    private static TechnicianAssignmentRecord record(UUID tech, boolean current) {
        return new TechnicianAssignmentRecord(
                1L, WORKORDER, tech, LocalDateTime.of(2026, 1, 1, 8, 0), "admin", null, null, null, current);
    }

    @Test
    @DisplayName("assignment response carries the resolved name, null when unresolved")
    void assignment_name() {
        var named = TechnicianAssignmentMapper.toAssignmentResponse(
                record(TECH_A, true), WorkorderStatus.APPROVED, null, "ok", Map.of(TECH_A, "Jane Doe"));
        var unnamed = TechnicianAssignmentMapper.toAssignmentResponse(
                record(TECH_A, true), WorkorderStatus.APPROVED, null, "ok", Map.of());

        assertThat(named.getTechnicianName()).isEqualTo("Jane Doe");
        assertThat(unnamed.getTechnicianName()).isNull();
    }

    @Test
    @DisplayName("reassignment response names new and previous technician")
    void reassignment_names() {
        var response = TechnicianAssignmentMapper.toReassignmentResponse(
                record(TECH_B, true),
                TECH_A,
                WorkorderStatus.APPROVED,
                "swap",
                "admin",
                Map.of(TECH_A, "Old Tech", TECH_B, "New Tech"));

        assertThat(response.getTechnicianName()).isEqualTo("New Tech");
        assertThat(response.getPreviousTechnicianName()).isEqualTo("Old Tech");
    }

    @Test
    @DisplayName("history response names current assignment and every history entry")
    void history_names() {
        var response = TechnicianAssignmentMapper.toResponseWithHistory(
                record(TECH_B, true),
                List.of(record(TECH_B, true), record(TECH_A, false)),
                WorkorderStatus.APPROVED,
                Map.of(TECH_B, "New Tech"));

        assertThat(response.getTechnicianName()).isEqualTo("New Tech");
        assertThat(response.getAssignmentHistory())
                .extracting(TechnicianAssignmentResponse.AssignmentHistoryEntry::getTechnicianName)
                .containsExactly("New Tech", null);
    }
}
