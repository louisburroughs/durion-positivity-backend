package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.PutawayRuleService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Putaway rule configuration (issue #1514). Rules decide which bin a received line is suggested for;
 * before this endpoint existed they could only be inserted by hand into the table.
 */
@RestController
@RequestMapping("/v1/inventory/putaway/rules")
@RequiredArgsConstructor
@Tag(name = "Putaway", description = "Putaway task generation and claiming endpoints")
public class PutawayRuleController {

    private static final String RULE_EXAMPLE = """
            {"priority":10,
             "matchType":"CATEGORY",
             "matchValue":"01960030-0000-7000-8000-000000000001",
             "destinationLocationId":"01960004-0001-7000-8000-000000000047",
             "destinationStrategy":"FIXED",
             "isEnabled":true}
            """;

    private final PutawayRuleService putawayRuleService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway_rule:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_VIEW + "')")
    @Operation(
            operationId = "listPutawayRules",
            summary = "List Putaway Rules",
            description = """
                    Returns every putaway rule, enabled or not, in the order the matcher tries them: tier \
                    precedence first (SKU, then SUBCATEGORY, then CATEGORY, then ANY), then ascending priority \
                    within a tier.
                    Use this tool to see which rule will govern an item and to find a ruleId before updating or \
                    deleting one; do not use listPutawayTasks, which returns generated work rather than \
                    configuration.
                    Preconditions: none.
                    Required inputs: none; there is no request body, paging or filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no rules are configured. That is not an error, but it \
                    does mean putaway task generation will fail for every line until at least an ANY rule exists.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "200",
            description = "Putaway rules returned in resolution order",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PutawayRuleResponse.class))))
    public ResponseEntity<List<PutawayRuleResponse>> listRules() {
        return ResponseEntity.ok(putawayRuleService.listRules());
    }

    @GetMapping("/{ruleId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway_rule:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_VIEW + "')")
    @Operation(
            operationId = "getPutawayRule",
            summary = "Get Putaway Rule",
            description = """
                    Returns one putaway rule by id, with its match tier, match value, destination and strategy.
                    Use this tool to read a single rule's current state before replacing it with \
                    updatePutawayRule; do not use it to discover which rule governs a SKU, because that \
                    depends on tier precedence across all enabled rules — list them with listPutawayRules \
                    instead, which is also how you find ids.
                    Preconditions: the rule must exist.
                    Required inputs: ruleId (UUID string) path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no rule exists for the supplied id, and 400 when ruleId is not a valid UUID.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "200",
            description = "Putaway rule returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PutawayRuleResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Putaway rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PutawayRuleResponse> getRule(
            @Parameter(description = "Putaway rule identifier", required = true) @PathVariable String ruleId) {
        return ResponseEntity.ok(putawayRuleService.getRule(ruleId));
    }

    @PostMapping
    @EmitEvent(id = "INVENTORY_PUTAWAY_RULE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway_rule:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_MANAGE + "')")
    @Operation(
            operationId = "createPutawayRule",
            summary = "Create Putaway Rule",
            description = """
                    Creates a putaway rule that routes matching received lines to a destination bin.
                    Use this tool to configure where an item class should be stored; use updatePutawayRule to \
                    change a rule that already exists, and do not use it to move stock — a rule only affects \
                    which destination future generatePutawayTasks calls suggest.
                    Preconditions: at most one enabled ANY rule may exist. ANY matches every line, so a second \
                    enabled one would be unreachable configuration and is refused with 409.
                    Required inputs: priority (at least 0), matchType (SKU, SUBCATEGORY, CATEGORY or ANY), \
                    destinationLocationId (UUID), plus matchValue — a catalog product, subcategory or category \
                    id — which is required for every matchType except ANY and must be omitted for ANY. \
                    destinationStrategy defaults to FIXED and isEnabled defaults to true.
                    Emits an INVENTORY_PUTAWAY_RULE_CREATE event; no stock moves and existing putaway tasks keep \
                    the destinations they were generated with.
                    Returns 400 when matchValue is missing for a typed rule, supplied for an ANY rule, or is not \
                    a valid UUID, and 409 when an enabled ANY rule already exists.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "201",
            description = "Putaway rule created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PutawayRuleResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "An enabled ANY rule already exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PutawayRuleResponse> createRule(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rule to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PutawayRuleRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Route tires to a tire rack",
                                                            value = RULE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    PutawayRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(putawayRuleService.createRule(request));
    }

    @PutMapping("/{ruleId}")
    @EmitEvent(id = "INVENTORY_PUTAWAY_RULE_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway_rule:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_MANAGE + "')")
    @Operation(
            operationId = "updatePutawayRule",
            summary = "Update Putaway Rule",
            description = """
                    Replaces every field of an existing putaway rule; this is a full replacement, not a patch, so \
                    an omitted destinationStrategy falls back to FIXED. The one exception is isEnabled: omitting \
                    it keeps the rule's current enabled state, so a PUT that only retunes a priority cannot \
                    silently re-enable a rule somebody deliberately disabled.
                    Use this tool to retarget, reprioritise, enable or disable a rule; do not use it to add a \
                    rule that does not exist yet — call createPutawayRule instead, and do not use it to redirect \
                    stock already on a generated task, which keeps the destination it was given.
                    Preconditions: the rule must exist, and enabling an ANY rule while another enabled ANY rule \
                    exists is refused with 409. A rule never conflicts with itself.
                    Required inputs: ruleId (UUID string) path parameter, plus the same body as \
                    createPutawayRule — priority, matchType, destinationLocationId and a matchValue required for \
                    every matchType except ANY.
                    Emits an INVENTORY_PUTAWAY_RULE_UPDATE event; no stock moves and putaway tasks already \
                    generated keep their destinations.
                    Returns 404 when the rule does not exist, 400 on the same validation failures as \
                    createPutawayRule, and 409 when another enabled ANY rule already exists.
                    """,
            tags = {"Putaway"})
    @ApiResponse(
            responseCode = "200",
            description = "Putaway rule updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PutawayRuleResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Putaway rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Another enabled ANY rule already exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PutawayRuleResponse> updateRule(
            @Parameter(description = "Putaway rule identifier", required = true) @PathVariable String ruleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rule's replacement state.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PutawayRuleRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Route tires to a tire rack",
                                                            value = RULE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    PutawayRuleRequest request) {
        return ResponseEntity.ok(putawayRuleService.updateRule(ruleId, request));
    }

    @DeleteMapping("/{ruleId}")
    @EmitEvent(id = "INVENTORY_PUTAWAY_RULE_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:putaway_rule:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_MANAGE + "')")
    @Operation(
            operationId = "deletePutawayRule",
            summary = "Delete Putaway Rule",
            description = """
                    Deletes a putaway rule permanently.
                    Use this tool to retire configuration outright; do not use it to take a rule out of service \
                    temporarily — call updatePutawayRule with isEnabled false instead, since a disabled rule is \
                    unreachable but recoverable whereas this is not.
                    Preconditions: the rule must exist. Deleting the only enabled ANY rule is permitted but \
                    removes the terminal fallback, after which generatePutawayTasks fails for any line no other \
                    rule matches.
                    Required inputs: ruleId (UUID string) path parameter; there is no request body.
                    Emits an INVENTORY_PUTAWAY_RULE_DELETE event; no stock moves and putaway tasks already \
                    generated under this rule are unaffected.
                    Returns 404 when the rule does not exist, and 400 when ruleId is not a valid UUID.
                    """,
            tags = {"Putaway"})
    @ApiResponse(responseCode = "204", description = "Putaway rule deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Putaway rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteRule(
            @Parameter(description = "Putaway rule identifier", required = true) @PathVariable String ruleId) {
        putawayRuleService.deleteRule(ruleId);
        return ResponseEntity.noContent().build();
    }
}
