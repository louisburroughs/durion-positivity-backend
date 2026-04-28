package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.service.ReturnService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
@Tag(name = "Returns", description = "Inventory return-to-stock endpoints")
public class ReturnController {

        private final ReturnService returnService;

        @GetMapping("/returnable-items")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:return:view" })
        @PreAuthorize("hasAuthority('inventory:return:view')")
        @Operation(operationId = "listReturnableItems", summary = "List returnable items", description = "Returns items that can be returned to stock for a workorder.", tags = {
                        "Returns" })
        @ApiResponse(responseCode = "200", description = "Returnable items returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReturnableItemDto.class)))
        @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "User lacks required return view authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<List<ReturnableItemDto>> listReturnableItems(@RequestParam UUID workorderId) {
                return ResponseEntity.ok(returnService.listReturnableItems(workorderId));
        }

        @GetMapping("/reason-codes")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:return:view" })
        @PreAuthorize("hasAuthority('inventory:return:view')")
        @Operation(operationId = "listReturnReasonCodes", summary = "List return reason codes", description = "Returns reason codes available for inventory returns.", tags = {
                        "Returns" })
        @ApiResponse(responseCode = "200", description = "Return reason codes returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReasonCodeDto.class)))
        @ApiResponse(responseCode = "403", description = "User lacks required return view authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<List<ReasonCodeDto>> listReturnReasonCodes() {
                return ResponseEntity.ok(returnService.listReturnReasonCodes());
        }

        @PostMapping("/submit-to-stock")
        @EmitEvent(id = "INVENTORY_RETURN_SUBMIT_TO_STOCK", apiVersion = "1")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:return:write" })
        @PreAuthorize("hasAuthority('inventory:return:write')")
        @Operation(operationId = "submitReturnToStock", summary = "Submit return to stock", description = "Submits inventory returns to stock.", tags = {
                        "Returns" })
        @ApiResponse(responseCode = "202", description = "Return submitted and accepted", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReturnSubmissionResultDto.class)))
        @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "User lacks required return write authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "422", description = "Return submission violates business policy", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<ReturnSubmissionResultDto> submitToStock(
                        @Valid @RequestBody ReturnSubmitRequest request) {
                ReturnSubmissionResultDto response = returnService.submitToStock(request);
                return ResponseEntity.accepted().body(response);
        }
}
