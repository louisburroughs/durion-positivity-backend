package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.SkillDto;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.SkillRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The skill registry read (CAP-328): the vocabulary every credential and skill requirement names. */
@RestController
@RequestMapping("/v1/people/skills")
@RequiredArgsConstructor
@Tag(name = "Skill Registry API", description = "Platform skill registry and vendor cross-references")
public class SkillController {

    private final SkillRegistryService skillRegistryService;

    @GetMapping
    @EmitEvent(id = "PEOPLE_SKILL_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:skill:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.SKILL_VIEW + "')")
    @Operation(operationId = "listSkills", summary = "List the Skill Registry", description = """
                    Returns every active skill in the platform registry with the vendor codes (ASE) that map \
                    onto it and the FHWA GVWR class range it certifies work on.
                    Use this tool to name a skill when reading or entering a technician's credentials, or when \
                    reading a service's skill requirement; do not try to create or edit skills through the API, \
                    the registry is seeded reference data shared by every tenant.
                    Preconditions: the caller holds people:skill:view.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, ordered by code, and 403 when the caller lacks the authority.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The active registry.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SkillDto.class))))
    public ResponseEntity<List<SkillDto>> listSkills() {
        return ResponseEntity.ok(skillRegistryService.listActive());
    }
}
