package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.ReplaceMechanicSkillsRequest;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.service.MechanicSyncService;
import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
@Tag(name = "Mechanic Skills API", description = "Operator maintenance of mechanic skill enrichment")
public class MechanicSkillController {

    private final MechanicSyncService mechanicSyncService;

    @Operation(operationId = "replaceMechanicSkills", summary = "Replace a Mechanic's Skill Set", description = """
                    Replaces the full skill set of the mechanic linked to a person with the supplied codes and \
                    proficiency levels.
                    Use this tool for operator maintenance of skills, which are shop-manager-owned enrichment the \
                    HR feed never carries; the mechanic record itself is projected from people.events.v1 and is \
                    not editable here.
                    Preconditions: the caller must hold shop:schedule:edit and a mechanic must exist for the \
                    personId (technicians are projected from ACTIVE TECHNICIAN staffing assignments).
                    Required inputs: personId as a path parameter and skills, a non-empty array where each entry \
                    has skillCode and proficiencyLevel between 1 and 5; the array replaces all current skills.
                    Emits a SHOP_MECHANIC_SKILLS_REPLACE event and records a mechanic audit-log entry; the \
                    HR-feed sync version is not touched.
                    Returns 204 on success, 404 when no mechanic exists for the person, and 400 when the body is \
                    invalid.
                    """)
    @ApiResponse(responseCode = "204", description = "Skill set replaced.")
    @ApiResponse(responseCode = "404", description = "No mechanic for that person.")
    @EmitEvent(id = "SHOP_MECHANIC_SKILLS_REPLACE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:schedule:edit"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_EDIT + "')")
    @PutMapping("/mechanics/by-person/{personId}/skills")
    public ResponseEntity<Void> replaceSkills(
            @PathVariable @NonNull String personId, @Valid @RequestBody @NonNull ReplaceMechanicSkillsRequest request) {
        mechanicSyncService.replaceSkills(
                personId,
                request.skills().stream()
                        .map(s -> HrMechanicEvent.Payload.Skill.builder()
                                .skillCode(s.skillCode())
                                .proficiencyLevel(s.proficiencyLevel())
                                .build())
                        .toList());
        return ResponseEntity.noContent().build();
    }
}
