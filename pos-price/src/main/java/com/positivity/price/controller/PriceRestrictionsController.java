package com.positivity.price.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Price Restrictions", description = "Price restriction evaluation and override operations")
@RestController
@RequestMapping("/v1/price")
public class PriceRestrictionsController {

    private static final Logger log = LoggerFactory.getLogger(PriceRestrictionsController.class);

    @Operation(summary = "Evaluate price restrictions", description = "Evaluate whether a price is within allowed restrictions.")
    @ApiResponses({
            @ApiResponse(responseCode = "501", description = "Not yet implemented."),
            @ApiResponse(responseCode = "400", description = "Invalid request body."),
            @ApiResponse(responseCode = "500", description = "Internal server error.")
    })
    @PostMapping("/restrictions:evaluate")
    public ResponseEntity<Object> evaluateRestrictions(@RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/price/restrictions:evaluate");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Operation(summary = "Override price restrictions", description = "Override price restrictions for a specific context or condition.")
    @ApiResponses({
            @ApiResponse(responseCode = "501", description = "Not yet implemented."),
            @ApiResponse(responseCode = "400", description = "Invalid request body."),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions to override restrictions."),
            @ApiResponse(responseCode = "500", description = "Internal server error.")
    })
    @PostMapping("/restrictions:override")
    public ResponseEntity<Object> overrideRestrictions(@RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/price/restrictions:override");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
