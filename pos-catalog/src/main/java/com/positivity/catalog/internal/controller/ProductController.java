package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogSearchResultDto;
import com.positivity.catalog.internal.dto.EffectiveLocationPriceResponseDto;
import com.positivity.catalog.internal.dto.GuardrailPolicyUpsertRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideCreateRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideDecisionRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.dto.ProductCodeMatch;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.dto.ProductTrackingLevelUpdateRequestDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.service.CatalogService;
import com.positivity.catalog.service.LocationPriceOverrideService;
import com.positivity.catalog.service.ProductCodeLookupService;
import com.positivity.catalog.service.ProductDetailService;
import com.positivity.catalog.service.ProductLifecycleService;
import com.positivity.catalog.service.ProductMasterDataService;
import com.positivity.catalog.service.ProductSearchService;
import com.positivity.events.EmitEvent;
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
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/v1/products")
@Tag(name = "Products API", description = "API for products, lifecycle, and pricing")
public class ProductController {

    private final CatalogService catalogService;
    private final ProductDetailService productDetailService;
    private final ProductLifecycleService productLifecycleService;
    private final LocationPriceOverrideService locationPriceOverrideService;
    private final ProductMasterDataService productMasterDataService;
    private final ProductSearchService productSearchService;
    private final ProductCodeLookupService productCodeLookupService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/pricing/guardrail-policies")
    @Operation(
            operationId = "upsertLocationGuardrailPolicy",
            summary = "Upsert Location Guardrail Policy",
            description = """
            Creates or updates the LOCATION-scoped guardrail policy that createLocationPriceOverride enforces \
            for discount, margin and auto-approval limits.
            Use this tool to set pricing guardrails for a location before overrides are created there; do not \
            use createLocationPriceOverride, which applies a price and is rejected until a policy exists.
            Preconditions: none; when a policy already exists for the scopeId its limits are overwritten, \
            otherwise a new policy row is created.
            Required inputs: scopeId (the location UUID), minMarginPercent, maxDiscountPercent and \
            autoApprovalThresholdPercent, all mandatory.
            Emits a CATALOG_GUARDRAIL_POLICY_UPSERT event; existing overrides are not re-evaluated, the new \
            limits apply only to overrides created afterwards.
            Returns 400 when any of the four fields is missing; the 200 response body carries only the \
            locationId, not the stored limits.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Guardrail policy upserted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid policy payload")
    @EmitEvent(id = "CATALOG_GUARDRAIL_POLICY_UPSERT", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> upsertLocationGuardrailPolicy(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Guardrail limits for one location: minimum margin, maximum discount and"
                                    + " the discount threshold under which overrides auto-approve.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = GuardrailPolicyUpsertRequestDto.class),
                                            examples = @ExampleObject(name = "Location policy", value = """
                                                                    {"scopeId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "minMarginPercent":15.0,
                                                                     "maxDiscountPercent":25.0,
                                                                     "autoApprovalThresholdPercent":10.0}
                                                                    """)))
                    @Valid
                    @RequestBody
                    GuardrailPolicyUpsertRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.upsertLocationGuardrailPolicy(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/pricing/location-overrides")
    @Operation(
            operationId = "createLocationPriceOverride",
            summary = "Create Location Price Override",
            description = """
            Creates a location-specific price override for one product, enforcing the location's guardrail \
            policy; discounts at or below the auto-approval threshold activate immediately, larger discounts \
            are stored as PENDING_APPROVAL with an approval request assigned to a deterministic approver.
            Use this tool to discount a product at one location; do not use upsertLocationGuardrailPolicy, \
            which sets the limits themselves, and use approveLocationPriceOverride to activate a pending one.
            Preconditions: the product must exist and a LOCATION guardrail policy must already exist for the \
            locationId; any currently ACTIVE override for the same location and product is set INACTIVE.
            Required inputs: locationId, productId, createdByUserId (UUIDs), positive basePrice and \
            overridePrice with overridePrice not exceeding basePrice; cost is optional and enables the \
            margin check when present.
            Emits a CATALOG_LOCATION_OVERRIDE_CREATE event and invalidates the product-detail cache for that \
            location.
            Returns 404 when the product does not exist, and 400 when no guardrail policy exists for the \
            location, the discount exceeds maxDiscountPercent, or the margin falls below minMarginPercent; \
            callers must read the returned status to learn whether the override is ACTIVE or PENDING_APPROVAL.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Override created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Guardrail validation failed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_CREATE", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> createLocationPriceOverride(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Override pricing for one product at one location; cost is optional and"
                                    + " enables the minimum-margin guardrail check.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    LocationPriceOverrideCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Discounted price", value = """
                                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d",
                                                                     "basePrice":100.00,"cost":60.00,"overridePrice":92.50}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LocationPriceOverrideCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationPriceOverrideService.createOverride(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/pricing/effective-price/{locationId}/{productId}")
    @Operation(operationId = "getEffectiveLocationPrice", summary = "Get Effective Location Price", description = """
            Resolves the price a location currently charges for a product from its override records: the newest \
            ACTIVE override wins, otherwise a PENDING_APPROVAL override reports its basePrice as the effective \
            price with status PENDING_APPROVAL.
            Use this tool to check what an override has done to a product's price at one location; do not use \
            getProductDetailView, which returns the full consolidated pricing and availability view.
            Preconditions: at least one ACTIVE or PENDING_APPROVAL override must exist for the pair; a product \
            with no override history at the location has no answer here.
            Required inputs: locationId and productId (UUIDs) as path parameters; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no ACTIVE or PENDING_APPROVAL override exists for the location and product pair, \
            even if the product itself exists.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Effective price returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EffectiveLocationPriceResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "No pricing context found")
    public ResponseEntity<EffectiveLocationPriceResponseDto> getEffectiveLocationPrice(
            @Parameter(description = "Location ID", required = true) @PathVariable UUID locationId,
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(locationPriceOverrideService.getEffectivePrice(locationId, productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "pricing:override:approve"})
    @PostMapping("/pricing/location-overrides/{overrideId}/approve")
    @Operation(
            operationId = "approveLocationPriceOverride",
            summary = "Approve Pending Price Override",
            description = """
            Approves a PENDING_APPROVAL location price override, setting it ACTIVE and closing its approval \
            request as APPROVED.
            Use this tool to grant a pending override; do not use rejectLocationPriceOverride, which \
            terminally declines it instead.
            Preconditions: the override must exist, be in PENDING_APPROVAL status, have an open approval \
            request, and the supplied version must match the override's current version.
            Required inputs: overrideId (UUID) path parameter plus version (long) and actorUserId (UUID) in \
            the body; rejection fields are ignored on approval.
            Emits a CATALOG_LOCATION_OVERRIDE_APPROVE event and invalidates the product-detail cache for the \
            override's location.
            Returns 404 when the override or its approval request cannot be found, 400 when the override is \
            not in PENDING_APPROVAL status, and 409 when the supplied version does not match the current one.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Override approved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid approval request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_APPROVE", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> approveLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Approval decision carrying the acting user and the override's current"
                                    + " version for optimistic-lock verification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    LocationPriceOverrideDecisionRequestDto.class),
                                            examples = @ExampleObject(name = "Approve", value = """
                                                                    {"version":0,
                                                                     "actorUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.approveOverride(overrideId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "pricing:override:approve"})
    @PostMapping("/pricing/location-overrides/{overrideId}/reject")
    @Operation(
            operationId = "rejectLocationPriceOverride",
            summary = "Reject Pending Price Override",
            description = """
            Rejects a PENDING_APPROVAL location price override, recording who rejected it and why, and closing \
            its approval request as REJECTED; the decision is terminal, a rejected override cannot be revived.
            Use this tool to decline a pending override; do not use approveLocationPriceOverride, which \
            activates it instead.
            Preconditions: the override must exist, be in PENDING_APPROVAL status, have an open approval \
            request, and the supplied version must match the override's current version.
            Required inputs: overrideId (UUID) path parameter plus version (long), actorUserId (UUID), and \
            non-blank rejectionReasonCode and rejectionNotes in the body.
            Emits a CATALOG_LOCATION_OVERRIDE_REJECT event and invalidates the product-detail cache for the \
            override's location.
            Returns 404 when the override or its approval request cannot be found, 400 when the override is \
            not in PENDING_APPROVAL status or the rejection reason or notes are blank, and 409 when the \
            supplied version does not match the current one.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Override rejected",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid rejection request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_REJECT", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> rejectLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection decision carrying the acting user, the override's current"
                                    + " version, and a mandatory reason code with notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    LocationPriceOverrideDecisionRequestDto.class),
                                            examples = @ExampleObject(name = "Reject below margin", value = """
                                                                    {"version":0,
                                                                     "actorUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "rejectionReasonCode":"MARGIN_TOO_LOW",
                                                                     "rejectionNotes":"Margin would fall below store target"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.rejectOverride(overrideId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/search")
    @Operation(operationId = "searchCatalogProducts", summary = "Search Catalog Products", description = """
            Searches products with an optional free-text query over name and description plus exact \
            case-insensitive filters for brand, category and SKU, paged by an opaque cursor.
            Use this tool to find products by partial text or filters; use getProductById instead when the id \
            is known, and listProductsByName only for exact whole-name matches.
            Preconditions: none; a malformed or missing cursor silently restarts at the first page rather \
            than failing.
            Required inputs: all parameters are optional; limit defaults to 20 and is clamped to 1-100, and \
            detailed defaults to false — pass detailed=true to enrich each row with lifecycle state, its \
            effective instant and the active MSRP, with null price fields for products lacking an active MSRP.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty items array when nothing matches, so an empty result is not an error \
            condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Search results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CatalogSearchResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameter (e.g., non-numeric limit)")
    public ResponseEntity<CatalogSearchResultDto> searchProducts(
            @Parameter(description = "Free-text search query (matches product name and description)")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Filter by manufacturer brand (exact, case-insensitive)")
                    @RequestParam(required = false)
                    String brand,
            @Parameter(description = "Filter by category name (exact, case-insensitive)")
                    @RequestParam(required = false)
                    String category,
            @Parameter(description = "Filter by SKU (exact match, case-insensitive)") @RequestParam(required = false)
                    String sku,
            @Parameter(description = "Pagination cursor from previous response") @RequestParam(required = false)
                    String cursor,
            @Parameter(
                            description = "Maximum number of results (1–100)",
                            schema = @Schema(type = "integer", format = "int32", defaultValue = "20"))
                    @RequestParam(defaultValue = "20")
                    int limit,
            @Parameter(
                            description =
                                    "When true, enrich each row with lifecycle state, effective instant, and active MSRP",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(defaultValue = "false")
                    boolean detailed) {
        return ResponseEntity.ok(productSearchService.searchProducts(q, brand, category, sku, cursor, limit, detailed));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @PostMapping
    @Operation(operationId = "createProduct", summary = "Create Product Master Record", description = """
            Creates a product master record with an immutable SKU, status ACTIVE, and uniqueness enforced on \
            SKU and on the manufacturerId plus mpn pair.
            Use this tool for governed product-master entry; do not use createCatalogItem, which is the \
            lightweight catalog-item path with no duplicate checks, and do not use bulkIngestCatalogProducts, \
            which loads many products in one call.
            Preconditions: no product may already use the SKU (case-insensitive), and when manufacturerId is \
            supplied no product may already pair it with the same mpn; a supplied categoryId must resolve.
            Required inputs: name, description, unitOfMeasure, sku and mpn, all non-blank; manufacturerId, \
            categoryId, upc and attributes are optional, and a upc also becomes the productCode with type UPC.
            Emits a CATALOG_PRODUCT_CREATED event, publishes a product fact for downstream replicas, and \
            invalidates the product-detail cache.
            Returns 409 when the SKU or the manufacturerId plus mpn pair already exists, and 400 when the \
            supplied categoryId does not resolve.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Product created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Business conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_CREATED", apiVersion = "1")
    public ResponseEntity<ProductDto> createProduct(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Product master data to register; sku becomes immutable after creation and"
                                    + " upc, when supplied, also becomes the productCode.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductCreateRequestDto.class),
                                            examples = @ExampleObject(name = "New tire product", value = """
                                                                    {"name":"All-Terrain Tire 265/70R17",
                                                                     "description":"All-terrain light truck tire, 265/70R17",
                                                                     "unitOfMeasure":"EA",
                                                                     "sku":"TIRE-AT-2657017",
                                                                     "mpn":"AT3-26570R17",
                                                                     "upc":"036121960222",
                                                                     "manufacturerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "categoryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ProductCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productMasterDataService.createProduct(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{productId}")
    @Operation(operationId = "updateProduct", summary = "Update Product Master Record", description = """
            Replaces the mutable master-data fields of a product — name, description, unit of measure, \
            manufacturer, category, UPC and attributes — while the SKU stays immutable.
            Use this tool to correct product master data; do not use updateProductLifecycle, which changes \
            selling state, and do not use updateProductTrackingLevel, which changes stock tracking.
            Preconditions: the product must exist, and a sku field in the body must either be omitted or \
            match the stored SKU exactly.
            Required inputs: productId (UUID) path parameter plus non-blank name, description, unitOfMeasure \
            and mpn; omitted optional fields such as upc and categoryId are cleared, not preserved.
            Emits a CATALOG_PRODUCT_UPDATED event, publishes a product fact for downstream replicas, and \
            invalidates the product-detail cache.
            Returns 404 when the product does not exist, 400 when the body tries to change the SKU or names a \
            categoryId that does not resolve, and 409 when the manufacturerId plus mpn pair collides with \
            another product.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Product updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Business conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_UPDATED", apiVersion = "1")
    public ResponseEntity<ProductDto> updateProduct(
            @Parameter(description = "ID of the product to update", required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement master-data fields; sku may be omitted or must equal the"
                                    + " stored value, and omitted optional fields are cleared.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductUpdateRequestDto.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Correct description and category",
                                                            value = """
                                                                    {"name":"All-Terrain Tire 265/70R17",
                                                                     "description":"All-terrain LT tire, 265/70R17, load range E",
                                                                     "unitOfMeasure":"EA",
                                                                     "mpn":"AT3-26570R17",
                                                                     "upc":"036121960222",
                                                                     "categoryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ProductUpdateRequestDto request) {
        return ResponseEntity.ok(productMasterDataService.updateProduct(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{productId}/tracking-level")
    @Operation(operationId = "updateProductTrackingLevel", summary = "Set Product Tracking Level", description = """
            Sets the product's stock tracking level to NONE, LOT or SERIAL, controlling whether inventory \
            tracks the product per lot or per serial number.
            Use this tool when a product's tracking granularity changes; do not use updateProduct, which \
            replaces master-data fields and does not touch the tracking level.
            Preconditions: the product must exist; no transition rules apply between levels.
            Required inputs: productId (UUID) path parameter and trackingLevel in the body, one of NONE, LOT \
            or SERIAL.
            Emits a CATALOG_PRODUCT_TRACKING_LEVEL_UPDATE event, re-publishes the product fact so downstream \
            replicas pick up the new level, and invalidates the product-detail cache.
            Returns 404 when the product does not exist, and 400 when trackingLevel is missing or not a \
            valid enum value.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Tracking level updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_TRACKING_LEVEL_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductDto> updateTrackingLevel(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The stock tracking granularity to apply to the product.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    ProductTrackingLevelUpdateRequestDto.class),
                                            examples = @ExampleObject(name = "Track by serial number", value = """
                                                                    {"trackingLevel":"SERIAL"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ProductTrackingLevelUpdateRequestDto request) {
        return ResponseEntity.ok(productMasterDataService.updateTrackingLevel(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}")
    @Operation(operationId = "getProductById", summary = "Get a Product by ID", description = """
            Returns the full product record including identity codes, manufacturer data, category, dimensions, \
            tracking level and lifecycle state.
            Use this tool when the productId is already known; use searchCatalogProducts instead to find \
            products by text or filters.
            Preconditions: the product must exist.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no product exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved product",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<ProductDto> getProductById(
            @Parameter(description = "ID of the product to be obtained") @PathVariable UUID productId) {
        return catalogService
                .getProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/by-code")
    @EmitEvent(id = "CATALOG_PRODUCT_CODE_LOOKUP", apiVersion = "1")
    @Operation(operationId = "findProductByCode", summary = "Find a Product by Exact EAN or UPC", description = """
            Resolves the single product carrying an exact product code under one code scheme, EAN or UPC, \
            which is the deterministic matching step supplier price-catalog ingestion runs before it applies \
            a vendor line to a product.
            Use this tool when a code from a vendor document or a scanner must be turned into a product id; \
            use searchCatalogProducts instead for partial text or filters, and getProductById when the id is \
            already known.
            Preconditions: EAN and UPC values are unique per scheme, so a match is either absent or unique; \
            surrounding whitespace is trimmed but no other normalisation is applied, so a code differing by a \
            leading zero is a different code and will not match.
            Required inputs: codeType (EAN or UPC) and code, both query parameters; there is no request body \
            and no fuzzy fallback — an unmatched code is reported as a miss, never as a near match.
            Emits a CATALOG_PRODUCT_CODE_LOOKUP event; no state changes.
            Returns 404 when no product carries the code, 400 when codeType is not EAN or UPC, and 409 on a \
            schema whose duplicate codes have not yet been cleaned up, in which case the value is ambiguous \
            and matching is refused rather than guessed.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The single product carrying the supplied code",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ProductCodeMatch.class)))
    @ApiResponse(responseCode = "400", description = "codeType is not a supported code scheme")
    @ApiResponse(responseCode = "404", description = "No product carries the supplied code")
    @ApiResponse(responseCode = "409", description = "The code is carried by more than one product")
    public ResponseEntity<ProductCodeMatch> findProductByCode(
            @Parameter(
                            description = "Code scheme to match within; EAN and UPC are matched independently",
                            required = true,
                            schema = @Schema(implementation = ProductCodeKind.class))
                    @RequestParam
                    ProductCodeKind codeType,
            @Parameter(
                            description = "Exact code value; trimmed, otherwise matched verbatim",
                            required = true,
                            example = "0123456789012")
                    @RequestParam
                    String code) {
        return productCodeLookupService
                .findByProductCode(codeType, code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/name/{name}")
    @Operation(operationId = "listProductsByName", summary = "List Products by Exact Name", description = """
            Returns every product whose name equals the supplied value exactly; this is a whole-name match, \
            not a substring search.
            Use this tool only when the exact product name is known; use searchCatalogProducts instead for \
            partial text, brand, category or SKU matching.
            Preconditions: none; an empty result simply means no product carries that exact name.
            Required inputs: name as a path parameter; there is no paging and no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when nothing matches, so an empty result is not an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved products",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    public List<ProductDto> getProductByName(
            @Parameter(description = "Name of the products to be obtained") @PathVariable String name) {
        return catalogService.getProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}/detail")
    @Operation(operationId = "getProductDetailView", summary = "Get Product Detail With Pricing", description = """
            Returns a consolidated product view for one location: catalog data, location-specific pricing, \
            availability and lead time, with a confidence indicator describing how complete the data is.
            Use this tool for a sales-facing view of one product at one store; use getProductById instead for \
            raw master data, and getEffectiveLocationPrice for the override-derived price alone.
            Preconditions: the product must exist; pricing and availability sources may be degraded, in which \
            case partial data is returned rather than an error.
            Required inputs: productId (UUID) path parameter and location_id (UUID) query parameter.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when the product does not exist, and 400 when location_id is missing or malformed; a \
            200 may still carry partial data, so callers should inspect the confidence field.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved product details (may be partial if some services unavailable)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductDetailView.class)))
    @ApiResponse(responseCode = "400", description = "Invalid location ID")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "500", description = "Unexpected server error while retrieving product details")
    public ResponseEntity<ProductDetailView> getProductDetailView(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @Parameter(description = "Location/store ID for location-specific data", required = true)
                    @RequestParam(name = "location_id")
                    UUID locationId) {

        log.info("Product detail view requested: productId={}, locationId={}", productId, locationId);
        if (locationId == null) {
            log.warn("Invalid location_id provided: {}", locationId);
            return ResponseEntity.badRequest().build();
        }

        ProductDetailView productDetail = productDetailService.getProductDetail(productId, locationId);
        if (productDetail == null) {
            log.warn("Product not found: productId={}", productId);
            return ResponseEntity.notFound().build();
        }

        log.debug("Product detail view generated with confidence={}", productDetail.getConfidence());
        return ResponseEntity.ok(productDetail);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/search")
    @Operation(operationId = "searchCatalogServices", summary = "Search Catalog Services", description = """
            Searches services by a case-insensitive substring of the service name, ordered by name, sized for \
            typeahead selection.
            Use this tool to find a serviceId by partial name; use getServiceById instead when the id is \
            known, and listServicesByName for exact whole-name matches.
            Preconditions: none; a blank or missing q returns an empty list rather than all services.
            Required inputs: q as the substring to match; limit is optional, defaults to 20 and is clamped \
            to 1-100.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when q is blank or nothing matches, so an empty result is not an \
            error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Matching services",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ServiceDto.class))))
    public ResponseEntity<List<ServiceDto>> searchServices(
            @Parameter(description = "Free-text query matching service name (case-insensitive substring)")
                    @RequestParam(required = false)
                    String q,
            @Parameter(
                            description = "Maximum number of results (1–100)",
                            schema = @Schema(type = "integer", format = "int32", defaultValue = "20"))
                    @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(catalogService.searchServices(q, limit));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/{serviceId}")
    @Operation(operationId = "getServiceById", summary = "Get a Service by ID", description = """
            Returns one catalog service record with its name, short description and long description.
            Use this tool when the serviceId is already known; use searchCatalogServices instead to find \
            services by partial name.
            Preconditions: the service must exist.
            Required inputs: serviceId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no service exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved service",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<ServiceDto> getServiceById(
            @Parameter(description = "ID of the service to be obtained") @PathVariable UUID serviceId) {
        return catalogService
                .getServiceById(serviceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/name/{name}")
    @Operation(operationId = "listServicesByName", summary = "List Services by Exact Name", description = """
            Returns every catalog service whose name equals the supplied value exactly; this is a whole-name \
            match, not a substring search.
            Use this tool only when the exact service name is known; use searchCatalogServices instead for \
            partial, typeahead-style matching.
            Preconditions: none; an empty result simply means no service carries that exact name.
            Required inputs: name as a path parameter; there is no paging and no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when nothing matches, so an empty result is not an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved services",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    public List<ServiceDto> getServiceByName(
            @Parameter(description = "Name of the services to be obtained") @PathVariable String name) {
        return catalogService.getServicesByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/noninventory/{productId}")
    @Operation(
            operationId = "getNonInventoryProductById",
            summary = "Get Non-Inventory Product by ID",
            description = """
            Returns one non-inventory product — an item sold without stock tracking, such as a fee or shop \
            supply — with its name and descriptions.
            Use this tool when the id is already known; use listNonInventoryProductsByName instead to find \
            non-inventory products by exact name.
            Preconditions: the non-inventory product must exist.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no non-inventory product exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved non-inventory product",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NonInventoryProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Non-inventory product not found")
    public ResponseEntity<NonInventoryProductDto> getNonInventoryProductById(
            @Parameter(description = "ID of the non-inventory product to be obtained") @PathVariable UUID productId) {
        return catalogService
                .getNonInventoryProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/noninventory/name/{name}")
    @Operation(
            operationId = "listNonInventoryProductsByName",
            summary = "List Non-Inventory Products by Name",
            description = """
            Returns every non-inventory product whose name equals the supplied value exactly; this is a \
            whole-name match, not a substring search.
            Use this tool only when the exact name is known; use getNonInventoryProductById instead when the \
            id is available, since there is no substring search for non-inventory products.
            Preconditions: none; an empty result simply means no non-inventory product carries that exact name.
            Required inputs: name as a path parameter; there is no paging and no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when nothing matches, so an empty result is not an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved non-inventory products",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NonInventoryProductDto.class)))
    public List<NonInventoryProductDto> getNonInventoryProductByName(
            @Parameter(description = "Name of the non-inventory products to be obtained") @PathVariable String name) {
        return catalogService.getNonInventoryProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW", "product:lifecycle:update"})
    @GetMapping("/{productId}/lifecycle")
    @Operation(operationId = "getProductLifecycle", summary = "Get Product Lifecycle State", description = """
            Returns a product's lifecycle state — ACTIVE, INACTIVE or DISCONTINUED, defaulting to ACTIVE when \
            never set — together with its effective instant, last-change audit fields and ordered replacement \
            options.
            Use this tool to inspect selling state before a transition; do not use updateProductLifecycle, \
            which changes the state, and use listProductReplacements when only the replacements are needed.
            Preconditions: the product must exist.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            Emits a CATALOG_PRODUCT_LIFECYCLE_GET audit event; no state changes.
            Returns 404 when no product exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved lifecycle state",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_GET", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> getProductLifecycle(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getLifecycle(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW", "product:lifecycle:update"})
    @GetMapping("/{productId}/replacements")
    @Operation(operationId = "listProductReplacements", summary = "List Replacement Products", description = """
            Returns the non-deleted replacement options recorded for a product, ordered by priority, each \
            with its replacement product id, notes and effective instant.
            Use this tool to see what supersedes a discontinued product; do not use addProductReplacement, \
            which records a new option, and use getPartSubstitutes to resolve the full substitute product \
            records instead of the option rows.
            Preconditions: the product must exist; replacements are normally present only on DISCONTINUED \
            products.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no product exists for the supplied id, and 200 with an empty array when the \
            product has no replacements.
            """)
    @ApiResponse(responseCode = "200", description = "Replacements listed")
    public ResponseEntity<List<ProductLifecycleResponse.ReplacementOption>> getReplacements(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getReplacementProducts(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT", "product:lifecycle:update"})
    @PutMapping("/{productId}/lifecycle")
    @Operation(operationId = "updateProductLifecycle", summary = "Set Product Lifecycle State", description = """
            Transitions a product's lifecycle state to ACTIVE, INACTIVE or DISCONTINUED with an effective \
            instant; discontinuation is one-way, a DISCONTINUED product can never be reactivated and callers \
            must record a replacement via addProductReplacement instead.
            Use this tool to change selling state; do not use updateProduct, which edits master data, and do \
            not use deleteCatalogItem, which removes the row outright.
            Preconditions: the product must exist and must not already be in the requested state; any \
            transition into DISCONTINUED requires the product:lifecycle:override_discontinued authority and a \
            non-blank overrideReason.
            Required inputs: productId (UUID) path parameter plus lifecycleState and either effectiveAt \
            (instant) or effectiveDate (date, resolved to UTC start of day); effectiveAt more than two \
            seconds in the past is rejected.
            Emits a CATALOG_PRODUCT_LIFECYCLE_UPDATE event, publishes a product fact for downstream replicas, \
            and invalidates the product-detail cache.
            Returns 404 when the product does not exist, 409 when attempting to leave DISCONTINUED, 403 when \
            the discontinued-override authority is missing, and 400 when the state is unchanged, the \
            effective time is absent or in the past, or overrideReason is missing for a discontinuation.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Lifecycle state updated successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Missing override permission")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Lifecycle business rule conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> setLifecycleState(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Target lifecycle state and when it takes effect; overrideReason is"
                                    + " mandatory for transitions into DISCONTINUED.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductLifecycleUpdateRequest.class),
                                            examples = @ExampleObject(name = "Discontinue at year end", value = """
                                                                    {"lifecycleState":"DISCONTINUED",
                                                                     "effectiveDate":"2026-12-31",
                                                                     "overrideReason":"Manufacturer ended production"}
                                                                    """)))
                    @RequestBody
                    ProductLifecycleUpdateRequest request) {
        return ResponseEntity.ok(productLifecycleService.updateLifecycle(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT", "product:lifecycle:update"})
    @PostMapping("/{productId}/replacements")
    @Operation(operationId = "addProductReplacement", summary = "Add Replacement Product", description = """
            Records a replacement suggestion on a discontinued product, pointing buyers at the product that \
            supersedes it, ordered among other options by priorityOrder.
            Use this tool after discontinuing a product via updateProductLifecycle; do not use \
            listProductReplacements, which only reads the recorded options.
            Preconditions: the original product must exist and be in lifecycle state DISCONTINUED, and the \
            replacement product must itself exist and differ from the original.
            Required inputs: productId (UUID) path parameter plus replacementProductId (UUID) and \
            priorityOrder greater than zero; notes are optional and effectiveAt defaults to now when omitted.
            Emits a CATALOG_PRODUCT_REPLACEMENT_ADD event and invalidates the product-detail cache for the \
            original product.
            Returns 404 when the original or replacement product does not exist, 409 when the original \
            product is not DISCONTINUED, and 400 when the replacement equals the original or priorityOrder \
            is not positive.
            """)
    @ApiResponse(responseCode = "201", description = "Replacement added successfully")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_REPLACEMENT_ADD", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse.ReplacementOption> addReplacementProduct(
            @Parameter(description = "ID of discontinued product", required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement suggestion pointing at the superseding product, with its"
                                    + " ranking among other options.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductReplacementRequest.class),
                                            examples = @ExampleObject(name = "Primary replacement", value = """
                                                                    {"replacementProductId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "priorityOrder":1,
                                                                     "notes":"Direct successor model"}
                                                                    """)))
                    @RequestBody
                    ProductReplacementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productLifecycleService.addReplacement(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}/substitutes")
    @Operation(operationId = "getPartSubstitutes", summary = "Get Substitute Parts", description = """
            Returns the full product records of a product's recorded replacements, resolved from its \
            replacement options in priority order with duplicates and dangling references dropped.
            Use this tool when selling and a substitute product's details are needed directly; use \
            listProductReplacements instead for the raw option rows with priority and notes.
            Preconditions: the product must exist; substitutes appear only after replacements were recorded \
            via addProductReplacement.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no product exists for the supplied id, and 200 with an empty array when no \
            replacement products resolve.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Substitute parts returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<List<ProductDto>> getPartSubstitutes(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        List<ProductDto> substitutes = productLifecycleService.getReplacementProducts(productId).stream()
                .map(ProductLifecycleResponse.ReplacementOption::getReplacementProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(catalogService::getProductById)
                .flatMap(java.util.Optional::stream)
                .toList();
        return ResponseEntity.ok(substitutes);
    }
}
