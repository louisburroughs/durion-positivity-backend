package com.positivity.people.internal.client.dto;

import java.time.LocalDate;
import java.util.UUID;

public class WorkexecJobTimeTotal {

    private UUID technicianId;

    private UUID locationId;

    private LocalDate localDate;

    private Integer totalJobMinutes;

    public UUID getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(UUID technicianId) {
        this.technicianId = technicianId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public LocalDate getLocalDate() {
        return localDate;
    }

    public void setLocalDate(LocalDate localDate) {
        this.localDate = localDate;
    }

    public Integer getTotalJobMinutes() {
        return totalJobMinutes;
    }

    public void setTotalJobMinutes(Integer totalJobMinutes) {
        this.totalJobMinutes = totalJobMinutes;
    }

}
