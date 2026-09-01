package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.AddWorkorderNoteRequest;
import com.positivity.workorder.internal.dto.WorkorderNoteResponse;
import com.positivity.workorder.internal.service.WorkorderNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notes about the customer, recorded against a workorder (issue #1584).
 */
@RestController
@RequestMapping("/v1/workorders/{workorderId}/notes")
@RequiredArgsConstructor
@Tag(name = "Workorder Note API", description = "Notes about the customer recorded while a workorder is worked")
public class WorkorderNoteController {

    private final WorkorderNoteService workorderNoteService;

    @PostMapping
    @EmitEvent(id = "WORKORDER_NOTE_ADD", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:note:add"})
    @PreAuthorize("hasAuthority('workorder:note:add')")
    @Operation(operationId = "addWorkorderNote", summary = "Record a Note About the Customer", description = """
                    Records a free-text note about the customer against a workorder, such as something the \
                    customer said while the job was open, and publishes it so it lands on the customer's CRM \
                    timeline.
                    Use this tool for a note about the CUSTOMER; do not use it for notes about the work itself \
                    or about a decision on it — completion notes and approval notes are fields on the workorder \
                    and the change request respectively.
                    Preconditions: the workorder must exist; the author is taken from the authenticated \
                    caller rather than the request body.
                    Required inputs: workorderId (UUID) as a path parameter and noteText (max 2000 characters); \
                    noteType is an optional free-text classification such as CUSTOMER_REQUEST.
                    Emits a WORKORDER_NOTE_ADD event and publishes the workorder.note.added.v1 fact, which \
                    pos-customer projects onto the party's timeline.
                    Returns 201 with the saved note, and 404 when the workorder does not exist.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Note recorded",
            content = @Content(schema = @Schema(implementation = WorkorderNoteResponse.class)))
    @ApiResponse(responseCode = "404", description = "Workorder not found", content = @Content)
    public ResponseEntity<WorkorderNoteResponse> addNote(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The note to record.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Customer remark",
                                                            value = """
                                                                    {"noteType":"CUSTOMER_REQUEST",
                                                                     "noteText":"Customer says the noise only happens on a cold start."}
                                                                    """)))
                    @RequestBody
                    @Valid
                    AddWorkorderNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workorderNoteService.addNote(workorderId, request, currentActor()));
    }

    @GetMapping
    @EmitEvent(id = "WORKORDER_NOTE_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:note:view"})
    @PreAuthorize("hasAuthority('workorder:note:view')")
    @Operation(operationId = "listWorkorderNotes", summary = "List a Workorder's Customer Notes", description = """
                    Returns the notes recorded about the customer on one workorder, most recent first.
                    Use this tool to read one workorder's own notes; read the customer's CRM interaction \
                    timeline instead when the question spans every workorder for that customer.
                    Preconditions: the workorder must exist.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only query.
                    Returns 200 with the notes (an empty list when none were recorded), and 404 when the \
                    workorder does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Notes retrieved")
    @ApiResponse(responseCode = "404", description = "Workorder not found", content = @Content)
    public ResponseEntity<List<WorkorderNoteResponse>> listNotes(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID workorderId) {
        return ResponseEntity.ok(workorderNoteService.listNotes(workorderId));
    }

    /** The authenticated caller, as populated by the gateway authorities filter. */
    @Nullable
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}
