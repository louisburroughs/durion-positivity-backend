package com.positivity.people.internal.dto;

import java.time.Instant;

public record AttendanceDiscrepancyReportResponse(
                Instant generatedAt,
                long approvedCount,
                long pendingApprovalCount,
                long rejectedCount) {
}
