package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.MechanicStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "HR-synchronized mechanic roster entry")
public class MechanicRosterEntryResponse {

    private UUID mechanicId;
    private UUID personId;
    private String firstName;
    private String lastName;
    private MechanicStatus status;
    private LocalDate hireDate;
    private LocalDate terminationDate;
    private Instant lastSyncedAt;
    /**
     * Every credential the person holds, each with its status on the roster's reference date —
     * a facility-local date for the location roster (CAP-328; DECISION-SHOPMGMT-015). Expired,
     * revoked and superseded credentials are listed with that status rather than dropped.
     */
    private List<TechnicianCredentialResponse> credentials;
}
