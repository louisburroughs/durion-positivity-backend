package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/pickingLists")
@Tag(name = "Picking Lists", description = "Picking list command endpoints")
public class PickingListController {

    @PostMapping("/{id}/confirm")
    @EmitEvent(id = "INVENTORY_PICKING_LIST_CONFIRM", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:pick_list:execute')")
    @Operation(
            operationId = "confirmPickingList",
            summary = "Confirm picking list",
            description = """
                    Rejects picking-list confirmation with 501 NOT_IMPLEMENTED; this legacy pickingLists path has \
                    no backing implementation and commits nothing.
                    Use this tool for nothing; confirm individual picks with confirmPickTask on the pick-lists \
                    path and commit consumption with consumePickedItems instead — do not expect this stub to \
                    change any state.
                    Preconditions: none are evaluated.
                    Required inputs: id path parameter; any request body is ignored.
                    Emits an INVENTORY_PICKING_LIST_CONFIRM event recording the rejected attempt; no inventory \
                    state changes.
                    Returns 501 for every call.
                    """,
            tags = {"Picking Lists"})
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:pick_list:execute"})
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Picking list confirmed"),
                @ApiResponse(responseCode = "501", description = "Not implemented")
            })
    public ResponseEntity<Void> confirmPickingList(
            @Parameter(description = "Picking list identifier", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Ignored; the endpoint rejects every call with 501 before reading the body.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Ignored body", value = "{}")))
                    @RequestBody(required = false)
                    Object requestBody) {
        log.info("POST /v1/inventory/pickingLists/{}/confirm", id);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
