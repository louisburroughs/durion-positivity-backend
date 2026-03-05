package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Objects;

@Schema(description = "Composite key for attendance report aggregation")
public class AttendanceReportKey {

	@Schema(description = "Technician identifier", example = "123e4567-e89b-12d3-a456-426614174000",
			requiredMode = Schema.RequiredMode.REQUIRED)
	private String technicianId;

	@Schema(description = "Location identifier", example = "22222222-2222-2222-2222-222222222222",
			requiredMode = Schema.RequiredMode.REQUIRED)
	private String locationId;

	@Schema(description = "Report local date", example = "2026-02-16", requiredMode = Schema.RequiredMode.REQUIRED)
	private LocalDate reportDate;

	public AttendanceReportKey() {
	}

	public AttendanceReportKey(String technicianId, String locationId, LocalDate reportDate) {
		this.technicianId = technicianId;
		this.locationId = locationId;
		this.reportDate = reportDate;
	}

	public String getTechnicianId() {
		return technicianId;
	}

	public void setTechnicianId(String technicianId) {
		this.technicianId = technicianId;
	}

	public String getLocationId() {
		return locationId;
	}

	public void setLocationId(String locationId) {
		this.locationId = locationId;
	}

	public LocalDate getReportDate() {
		return reportDate;
	}

	public void setReportDate(LocalDate reportDate) {
		this.reportDate = reportDate;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AttendanceReportKey that)) {
			return false;
		}
		return Objects.equals(technicianId, that.technicianId) && Objects.equals(locationId, that.locationId)
				&& Objects.equals(reportDate, that.reportDate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(technicianId, locationId, reportDate);
	}

	@Override
	public String toString() {
		return "AttendanceReportKey{" + "technicianId='" + technicianId + '\'' + ", locationId='" + locationId + '\''
				+ ", reportDate=" + reportDate + '}';
	}

}
