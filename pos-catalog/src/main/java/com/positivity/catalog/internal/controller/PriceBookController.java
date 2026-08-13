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
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@RequestMapping("/v1/products/price-books")
@Tag(name = "Price Book API", description = "Manage price books, pricing rules, and resolution")
public class PriceBookController {

    private final PriceBookService priceBookService;

    public PriceBookController(PriceBookService priceBookService) {
        this.priceBookService = priceBookService;
    }

    @PreAuthorize("hasAuthority('catalog:price_book:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:write"})
    @PostMapping
    @Operation(operationId = "createPriceBook", summary = "Create Price Book", description = """
            Creates a price book — a scoped container of pricing rules — with scope COMPANY_DEFAULT, LOCATION \
            or CUSTOMER_TIER and an initial status defaulting to ACTIVE.
            Use this tool to establish a rule container before adding rules; do not use createPriceBookRule, \
            which adds rules to a book that already exists.
            Preconditions: none; no uniqueness is enforced, so creating a second default book for the same \
            scope is possible and should be avoided by the caller.
            Required inputs: name and scope; scopeId is mandatory for LOCATION and CUSTOMER_TIER scopes, \
            while isDefault defaults to false and status defaults to ACTIVE.
            Emits a CATALOG_PRICE_BOOK_CREATE event; no rules exist until they are added.
            Returns 400 when name is blank, scope is missing, or scopeId is absent for a LOCATION or \
            CUSTOMER_TIER scope.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Price book created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @EmitEvent(id = "CATALOG_PRICE_BOOK_CREATE", apiVersion = "1")
    public ResponseEntity<PriceBookDto> createPriceBook(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Price book definition: a name, its scope, and for LOCATION or"
                                    + " CUSTOMER_TIER scopes the id of the location or tier it prices.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PriceBookCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Location price book", value = """
                                                                    {"name":"Downtown Store Pricing",
                                                                     "scope":"LOCATION",
                                                                     "scopeId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "isDefault":false,"status":"ACTIVE"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceBookCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceBookService.createPriceBook(request));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:read"})
    @GetMapping("/{priceBookId}")
    @Operation(operationId = "getPriceBook", summary = "Get Price Book", description = """
            Returns one price book with its name, scope, default flag, status and optimistic-lock version.
            Use this tool when the priceBookId is already known; use listPriceBookRules instead to inspect \
            the rules it contains, since there is no endpoint that lists price books.
            Preconditions: the price book must exist.
            Required inputs: priceBookId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no price book exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Price book returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
    @ApiResponse(responseCode = "404", description = "Price book not found")
    public ResponseEntity<PriceBookDto> getPriceBook(@Parameter(required = true) @PathVariable UUID priceBookId) {
        return ResponseEntity.ok(priceBookService.getPriceBook(priceBookId));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:write"})
    @PutMapping("/{priceBookId}")
    @Operation(operationId = "updatePriceBook", summary = "Update Price Book", description = """
            Updates a price book's name, scope and scopeId, and optionally its default flag and status; \
            isDefault and status are left unchanged when omitted, unlike the other fields which are replaced.
            Use this tool to rename, re-scope, deactivate or promote a book; do not use updatePriceBookRule, \
            which edits an individual rule inside the book.
            Preconditions: the price book must exist.
            Required inputs: priceBookId (UUID) path parameter plus name and scope; scopeId is mandatory for \
            LOCATION and CUSTOMER_TIER scopes.
            Emits a CATALOG_PRICE_BOOK_UPDATE event; the book's rules are untouched.
            Returns 404 when no price book exists for the supplied id, and 400 when name is blank, scope is \
            missing, or scopeId is absent for a LOCATION or CUSTOMER_TIER scope.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Price book updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookDto.class)))
    @ApiResponse(responseCode = "404", description = "Price book not found")
    @EmitEvent(id = "CATALOG_PRICE_BOOK_UPDATE", apiVersion = "1")
    public ResponseEntity<PriceBookDto> updatePriceBook(
            @Parameter(required = true) @PathVariable UUID priceBookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement name, scope and scopeId; isDefault and status only change"
                                    + " when present in the body.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PriceBookCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Deactivate a book", value = """
                                                                    {"name":"Downtown Store Pricing",
                                                                     "scope":"LOCATION",
                                                                     "scopeId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "status":"INACTIVE"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceBookCreateRequestDto request) {
        return ResponseEntity.ok(priceBookService.updatePriceBook(priceBookId, request));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:write"})
    @PostMapping("/{priceBookId}/rules")
    @Operation(operationId = "createPriceBookRule", summary = "Create Price Book Rule", description = """
            Adds an ACTIVE pricing rule to a price book, targeting a single SKU, a CATEGORY taxonomy node or \
            GLOBAL, with an effective window and an optional LOCATION or CUSTOMER_TIER condition.
            Use this tool to define a reference price; do not use resolveProductPrice, which evaluates rules, \
            and do not use updatePriceBookRule, which edits a rule that already exists.
            Preconditions: the price book must exist, and no ACTIVE rule with the same target, condition and \
            an overlapping effective window may already exist in the book.
            Required inputs: targetType (SKU, CATEGORY or GLOBAL), targetId for non-GLOBAL targets, \
            pricingLogic as JSON of the form {"amounts":{"USD":"10.00"},"defaultCurrency":"USD"}, \
            effectiveStartAt and createdByUserId; priority defaults to 0, conditionType defaults to NONE, \
            and a LOCATION conditionValue must be a UUID string.
            Emits a CATALOG_PRICE_BOOK_RULE_CREATE event; the rule participates in resolution immediately \
            once its window opens.
            Returns 404 when the price book does not exist, 409 when the rule conflicts with an existing \
            rule in overlapping dates, and 400 when required fields are missing or the effective window is \
            inverted.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Price book rule created",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
    @ApiResponse(responseCode = "409", description = "Rule conflict")
    @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_CREATE", apiVersion = "1")
    public ResponseEntity<PriceBookRuleDto> createRule(
            @Parameter(required = true) @PathVariable UUID priceBookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rule definition: target, JSON pricingLogic with per-currency amounts,"
                                    + " optional condition, priority and effective window.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PriceBookRuleCreateRequestDto.class),
                                            examples = @ExampleObject(name = "SKU rule in USD", value = """
                                                                    {"targetType":"SKU",
                                                                     "targetId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "pricingLogic":"{\\"amounts\\":{\\"USD\\":\\"129.99\\"},\\"defaultCurrency\\":\\"USD\\"}",
                                                                     "conditionType":"NONE","priority":10,
                                                                     "effectiveStartAt":"2026-09-01T00:00:00Z",
                                                                     "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceBookRuleCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceBookService.createRule(priceBookId, request));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:write"})
    @PutMapping("/{priceBookId}/rules/{ruleId}")
    @Operation(operationId = "updatePriceBookRule", summary = "Update Price Book Rule", description = """
            Replaces the target, pricing logic, condition, priority and effective window of an existing price \
            book rule, keeping its status.
            Use this tool to change a rule in place; do not use deactivatePriceBookRule, which retires the \
            rule, and do not use createPriceBookRule, which adds a new one.
            Preconditions: the rule must exist and belong to the given price book; a version supplied in the \
            body must match the rule's current version, and the change must not conflict with another rule's \
            overlapping window.
            Required inputs: priceBookId and ruleId (UUIDs) path parameters plus targetType, pricingLogic, \
            effectiveStartAt and createdByUserId; version is optional but recommended for optimistic locking.
            Emits a CATALOG_PRICE_BOOK_RULE_UPDATE event; resolution reflects the new values immediately.
            Returns 404 when the rule does not exist under that price book, 409 when the version mismatches \
            or the change conflicts with an existing rule in overlapping dates, and 400 when required fields \
            are missing or invalid.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Price book rule updated",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
    @ApiResponse(responseCode = "409", description = "Rule conflict")
    @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_UPDATE", apiVersion = "1")
    public ResponseEntity<PriceBookRuleDto> updateRule(
            @Parameter(required = true) @PathVariable UUID priceBookId,
            @Parameter(required = true) @PathVariable UUID ruleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement rule values; include version to guard against concurrent"
                                    + " edits of the same rule.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PriceBookRuleCreateRequestDto.class),
                                            examples =
                                                    @ExampleObject(name = "Reprice with version guard", value = """
                                                                    {"targetType":"SKU",
                                                                     "targetId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "pricingLogic":"{\\"amounts\\":{\\"USD\\":\\"119.99\\"},\\"defaultCurrency\\":\\"USD\\"}",
                                                                     "priority":10,
                                                                     "effectiveStartAt":"2026-09-01T00:00:00Z",
                                                                     "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "version":0}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceBookRuleCreateRequestDto request) {
        return ResponseEntity.ok(priceBookService.updateRule(priceBookId, ruleId, request));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:write"})
    @DeleteMapping("/{priceBookId}/rules/{ruleId}")
    @Operation(operationId = "deactivatePriceBookRule", summary = "Deactivate Price Book Rule", description = """
            Sets a price book rule's status to INACTIVE so price resolution stops considering it; the rule \
            row is kept, not deleted.
            Use this tool to retire a rule; do not use updatePriceBookRule, which changes its values while \
            leaving it active — there is no endpoint to reactivate a rule, so treat this as one-way.
            Preconditions: the rule must exist and belong to the given price book.
            Required inputs: priceBookId and ruleId (UUIDs) as path parameters; there is no request body.
            Emits a CATALOG_PRICE_BOOK_RULE_DEACTIVATE event; subsequent resolveProductPrice calls no longer \
            match the rule.
            Returns 204 on success, and 404 when the rule does not exist under that price book.
            """)
    @ApiResponse(responseCode = "204", description = "Rule deactivated")
    @EmitEvent(id = "CATALOG_PRICE_BOOK_RULE_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<Void> deactivateRule(
            @Parameter(required = true) @PathVariable UUID priceBookId,
            @Parameter(required = true) @PathVariable UUID ruleId) {
        priceBookService.deactivateRule(priceBookId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('catalog:price_book:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:read"})
    @GetMapping("/{priceBookId}/rules")
    @Operation(operationId = "listPriceBookRules", summary = "List Price Book Rules", description = """
            Returns every rule in a price book — ACTIVE and INACTIVE alike — with target, pricing logic, \
            condition, priority, effective window, status and version.
            Use this tool to inspect a book's rule set; use resolveProductPrice instead to evaluate which \
            rule wins for a concrete product and context.
            Preconditions: the price book must exist.
            Required inputs: priceBookId (UUID) as a path parameter; there is no filtering or paging.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no price book exists for the supplied id, and 200 with an empty array when the \
            book has no rules.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Rule list returned",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PriceBookRuleDto.class)))
    public ResponseEntity<List<PriceBookRuleDto>> listRules(
            @Parameter(required = true) @PathVariable UUID priceBookId) {
        return ResponseEntity.ok(priceBookService.listRules(priceBookId));
    }

    @PreAuthorize("hasAuthority('catalog:price_book:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:price_book:read"})
    @PostMapping("/resolve-price")
    @Operation(operationId = "resolveProductPrice", summary = "Resolve Reference Product Price", description = """
            Resolves a product's catalog reference or list price by picking one candidate price book — the \
            explicit priceBookId first, otherwise the active LOCATION book, then the active CUSTOMER_TIER \
            book, then the COMPANY_DEFAULT book — and selecting the winning ACTIVE rule by SKU over CATEGORY \
            over GLOBAL specificity, then priority; when no rule matches, the active MSRP is returned with \
            fallbackReason MSRP_FALLBACK.
            Use this tool for catalog reference pricing per ADR-0054; do not use it for transactional sell \
            prices, which pos-price owns, and do not use getEffectiveLocationPrice, which reads location \
            override records instead of price book rules.
            Preconditions: the product must exist; price books and rules are optional, since MSRP fallback \
            covers their absence.
            Required inputs: productId (UUID); priceBookId, locationId, customerTierId, customerTier, \
            currency and asOf are optional, with asOf defaulting to today and currency required only when a \
            winning rule configures multiple currencies without a defaultCurrency.
            Emits a CATALOG_PRICE_BOOK_RESOLVE_PRICE audit event; no pricing data changes.
            Returns 404 when the product or an explicitly supplied priceBookId does not exist, 400 when the \
            requested currency is not configured in the winning rule's pricingLogic, and 200 with source \
            UNAVAILABLE and fallbackReason PRICE_BASE_DATA_MISSING when neither a rule nor an MSRP applies.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Resolved price returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ResolvePriceResponseDto.class)))
    @EmitEvent(id = "CATALOG_PRICE_BOOK_RESOLVE_PRICE", apiVersion = "1")
    public ResponseEntity<ResolvePriceResponseDto> resolvePrice(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Resolution context: the product plus optional book, location, customer"
                                    + " tier, currency and as-of date that steer candidate book selection.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ResolvePriceRequestDto.class),
                                            examples =
                                                    @ExampleObject(name = "Location-context resolution", value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "currency":"USD","asOf":"2026-09-15"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ResolvePriceRequestDto request) {
        return ResponseEntity.ok(priceBookService.resolvePrice(request));
    }
}
