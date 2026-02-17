package com.positivity.people.internal.dto;

import java.time.LocalDate;

public record AttendanceReportKey(String technicianId, String locationId, LocalDate reportDate) {
}
