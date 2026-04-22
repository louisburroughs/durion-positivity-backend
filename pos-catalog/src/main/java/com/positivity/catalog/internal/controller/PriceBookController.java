package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.PriceBookCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookDto;
import com.positivity.catalog.internal.dto.PriceBookRuleCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookRuleDto;
import com.positivity.catalog.internal.dto.ResolvePriceRequestDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto;
import com.positivity.catalog.service.PriceBookService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/products/price-books")
@Tag(name = "Price Book API", description = "Manage price books, pricing rules, and resolution")
public class PriceBookController {

        private final PriceBookService priceBookService;

        public PriceBookController(PriceBookService priceBookService) {
                this.priceBookService = priceBookService;
        }

        @PreAuthorize("hasAuthority('catalog:price_book:write')")
        @PostMapping
        @Operation(summary = "Create price book", description = "Creates a new price book used to group and apply pricing rules.")
        @ApiResponse(responseCode = "201", description = "Price book created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
        @ApiResponse(responseCode = "400", description = "Invalid payload")
        @EmitEvent(id = "CATALOG_PRICE_BOOK_CREATE", apiVersion = "1")
        public ResponseEntity<PriceBookDto> createPriceBook(@Valid @RequestBody PriceBookCreateRequestDto request) {
                return ResponseEntity.status(HttpStatus.CREATED).body(priceBookService.createPriceBook(request));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:read')")
        @GetMapping("/{priceBookId}")
        @Operation(summary = "Get price book", description = "Retrieves a price book by ID, including its configuration metadata.")
        @ApiResponse(responseCode = "200", description = "Price book returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
        @ApiResponse(responseCode = "404", description = "Price book not found")
        public ResponseEntity<PriceBookDto> getPriceBook(@Parameter(required = true) @PathVariable UUID priceBookId) {
                return ResponseEntity.ok(priceBookService.getPriceBook(priceBookId));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:write')")
        @PutMapping("/{priceBookId}")
        @Operation(summary = "Update price book", description = "Updates mutable fields of an existing price book.")
        @ApiResponse(responseCode = "200", description = "Price book updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
        @ApiResponse(responseCode = "404", description = "Price book not found")
        @EmitEvent(id = "CATALOG_PRICE_BOOK_UPDATE", apiVersion = "1")
        public ResponseEntity<PriceBookDto> updatePriceBook(
                        @Parameter(required = true) @PathVariable UUID priceBookId,
                        @Valid @RequestBody PriceBookCreateRequestDto request) {
                return ResponseEntity.ok(priceBookService.updatePriceBook(priceBookId, request));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:write')")
        @PostMapping("/{priceBookId}/rules")
        @Operation(summary = "Create price book rule", description = "Adds a new pricing rule to a specific price book.")
        @ApiResponse(responseCode = "201", description = "Price book rule created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
        @ApiResponse(responseCode = "409", description = "Rule conflict")
        @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_CREATE", apiVersion = "1")
        public ResponseEntity<PriceBookRuleDto> createRule(
                        @Parameter(required = true) @PathVariable UUID priceBookId,
                        @Valid @RequestBody PriceBookRuleCreateRequestDto request) {
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(priceBookService.createRule(priceBookId, request));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:write')")
        @PutMapping("/{priceBookId}/rules/{ruleId}")
        @Operation(summary = "Update price book rule", description = "Updates an existing pricing rule in a price book.")
        @ApiResponse(responseCode = "200", description = "Price book rule updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
        @ApiResponse(responseCode = "409", description = "Rule conflict")
        @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_UPDATE", apiVersion = "1")
        public ResponseEntity<PriceBookRuleDto> updateRule(
                        @Parameter(required = true) @PathVariable UUID priceBookId,
                        @Parameter(required = true) @PathVariable UUID ruleId,
                        @Valid @RequestBody PriceBookRuleCreateRequestDto request) {
                return ResponseEntity.ok(priceBookService.updateRule(priceBookId, ruleId, request));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:write')")
        @DeleteMapping("/{priceBookId}/rules/{ruleId}")
        @Operation(summary = "Deactivate price book rule", description = "Deactivates a price book rule so it is no longer considered in price resolution.")
        @ApiResponse(responseCode = "204", description = "Rule deactivated")
        @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_DEACTIVATE", apiVersion = "1")
        public ResponseEntity<Void> deactivateRule(
                        @Parameter(required = true) @PathVariable UUID priceBookId,
                        @Parameter(required = true) @PathVariable UUID ruleId) {
                priceBookService.deactivateRule(priceBookId, ruleId);
                return ResponseEntity.noContent().build();
        }

        @PreAuthorize("hasAuthority('catalog:price_book:read')")
        @GetMapping("/{priceBookId}/rules")
        @Operation(summary = "List price book rules", description = "Returns all rules associated with a price book.")
        @ApiResponse(responseCode = "200", description = "Rule list returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
        public ResponseEntity<List<PriceBookRuleDto>> listRules(
                        @Parameter(required = true) @PathVariable UUID priceBookId) {
                return ResponseEntity.ok(priceBookService.listRules(priceBookId));
        }

        @PreAuthorize("hasAuthority('catalog:price_book:read')")
        @PostMapping("/resolve-price")
        @Operation(summary = "Resolve effective product price", description = "Calculates the effective price for a product using applicable price books and rules.")
        @ApiResponse(responseCode = "200", description = "Resolved price returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResolvePriceResponseDto.class)))
        @EmitEvent(id = "CATALOG_PRICE_BOOK_RESOLVE_PRICE", apiVersion = "1")
        public ResponseEntity<ResolvePriceResponseDto> resolvePrice(
                        @Valid @RequestBody ResolvePriceRequestDto request) {
                return ResponseEntity.ok(priceBookService.resolvePrice(request));
        }
}
