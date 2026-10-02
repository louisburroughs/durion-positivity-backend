package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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

    @Schema(
            description = "The mechanic's People-domain person id (ADR-0015 §7 I7) - the value createAssignment "
                    + "takes as mechanicPersonId. Use this to identify the mechanic across services.",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID mechanicPersonId;

    @Schema(
            description = "Internal shop-manager mechanic record id (a local surrogate key). Not a person id and "
                    + "not a stable cross-service identifier; do not send it to other services.",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = REQUIRED)
    private UUID mechanicRecordId;

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
