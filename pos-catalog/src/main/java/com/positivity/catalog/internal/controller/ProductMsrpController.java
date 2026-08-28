package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CreateMsrpRequestDto;
import com.positivity.catalog.internal.dto.ProductMsrpDto;
import com.positivity.catalog.internal.dto.UpdateMsrpRequestDto;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ProductMsrpService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/products/{productId}/msrp")
@Tag(name = "Product MSRP API", description = "Manage MSRP values with effective dates")
public class ProductMsrpController {

    private final Clock clock;
    private final ProductMsrpService productMsrpService;

    @PreAuthorize("hasAuthority('" + CatalogPermissions.MSRP_WRITE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:write"})
    @PostMapping
    @Operation(operationId = "createProductMsrp", summary = "Create Product MSRP", description = """
            Creates an MSRP record for a product with an effective date window; amounts are stored at four \
            decimal places and the currency is normalised to upper case.
            Use this tool to schedule a new list price period; do not use updateProductMsrp, which edits an \
            existing record, and note that only one open-ended record (no effectiveEndDate) may exist per \
            product.
            Preconditions: the product must exist, the new window must not overlap any existing MSRP window, \
            and no other open-ended record may exist when effectiveEndDate is omitted.
            Required inputs: productId (UUID) path parameter plus a positive amount, a 3-letter ISO currency \
            and effectiveStartDate; effectiveEndDate and createdByUserId are optional.
            Emits a CATALOG_MSRP_CREATE event; the record participates in active-MSRP resolution and \
            resolveProductPrice fallback from its start date.
            Returns 404 when the product does not exist, 409 when the dates overlap an existing record, and \
            400 when the amount is not positive, the currency is not a 3-letter code, the window is \
            inverted, or a second open-ended record is attempted.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "MSRP created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "409", description = "Temporal overlap conflict")
    @EmitEvent(id = "CATALOG_MSRP_CREATE", apiVersion = "1")
    public ResponseEntity<ProductMsrpDto> createMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "New MSRP window: amount, ISO currency and effective dates; omit"
                                    + " effectiveEndDate for an open-ended price.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateMsrpRequestDto.class),
                                            examples = @ExampleObject(name = "Open-ended MSRP", value = """
                                                                    {"amount":149.9900,"currency":"USD",
                                                                     "effectiveStartDate":"2026-09-01",
                                                                     "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateMsrpRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productMsrpService.createMsrp(productId, request));
    }

    @PreAuthorize("hasAuthority('" + CatalogPermissions.MSRP_WRITE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:write"})
    @PutMapping("/{msrpId}")
    @Operation(operationId = "updateProductMsrp", summary = "Update Product MSRP", description = """
            Rewrites a current or future MSRP record's amount, currency and effective window; records whose \
            window already ended are immutable history.
            Use this tool to correct or reschedule an existing record; do not use createProductMsrp, which \
            adds a new window alongside the existing ones.
            Preconditions: the record must exist, belong to the given product, and not have an \
            effectiveEndDate in the past; a version in the body must match the record's current version, and \
            the new window must not overlap other records.
            Required inputs: productId and msrpId (UUIDs) path parameters plus a positive amount, a 3-letter \
            ISO currency and effectiveStartDate; version is optional but recommended.
            Emits a CATALOG_MSRP_UPDATE event; active-MSRP resolution reflects the change immediately.
            Returns 404 when the record does not exist under that product, 409 when the version mismatches \
            or the dates overlap another record, and 400 when the record is historical or the amount, \
            currency or window is invalid.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "MSRP updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "404", description = "MSRP record not found")
    @ApiResponse(responseCode = "409", description = "Temporal overlap or optimistic locking conflict")
    @EmitEvent(id = "CATALOG_MSRP_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductMsrpDto> updateMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @Parameter(required = true) @PathVariable UUID msrpId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement amount, currency and effective window for the record;"
                                    + " include version to guard against concurrent edits.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = UpdateMsrpRequestDto.class),
                                            examples =
                                                    @ExampleObject(name = "Reprice with version guard", value = """
                                                                    {"amount":139.9900,"currency":"USD",
                                                                     "effectiveStartDate":"2026-09-01",
                                                                     "effectiveEndDate":"2026-12-31",
                                                                     "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "version":0}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateMsrpRequestDto request) {
        return ResponseEntity.ok(productMsrpService.updateMsrp(productId, msrpId, request));
    }

    @PreAuthorize("hasAuthority('" + CatalogPermissions.MSRP_READ + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:read"})
    @GetMapping("/active")
    @Operation(operationId = "getActiveProductMsrp", summary = "Get Active Product MSRP", description = """
            Returns the single MSRP record whose effective window covers the requested date.
            Use this tool to read the list price in force on a date; use listProductMsrpHistory instead to \
            see every past and scheduled record.
            Preconditions: the product must exist and an MSRP window must cover the date.
            Required inputs: productId (UUID) path parameter; asOf is an optional ISO date defaulting to \
            today.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when the product does not exist or no MSRP window covers the requested date.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Active MSRP returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "404", description = "No active MSRP for date")
    public ResponseEntity<ProductMsrpDto> getActiveMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate targetDate = asOf == null ? LocalDate.now(clock) : asOf;
        return productMsrpService
                .getActiveMsrp(productId, targetDate)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new CatalogNotFoundException("No active MSRP found for product and date."));
    }

    @PreAuthorize("hasAuthority('" + CatalogPermissions.MSRP_READ + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:read"})
    @GetMapping
    @Operation(operationId = "listProductMsrpHistory", summary = "List Product MSRP History", description = """
            Returns every MSRP record for a product — past, current and scheduled — ordered by effective \
            start date, newest first.
            Use this tool to audit price history or find an msrpId to edit; use getActiveProductMsrp instead \
            for just the record in force on a date.
            Preconditions: the product must exist.
            Required inputs: productId (UUID) as a path parameter; there is no filtering or paging.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when the product does not exist, and 200 with an empty array when it has no MSRP \
            records.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "MSRP records returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    public ResponseEntity<List<ProductMsrpDto>> listMsrp(@Parameter(required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productMsrpService.getAllMsrp(productId));
    }
}
