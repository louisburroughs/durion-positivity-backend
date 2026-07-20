package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateCommercialAccountResponse;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyResponse;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.GetAccountTierResponse;
import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.PartyNameRef;
import com.positivity.customer.internal.dto.PartyNameResolveRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpsertBillingRulesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.observability.BusinessSpanSupport;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.AccountTierService;
import com.positivity.customer.service.PartyService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.LogSanitizer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Controller implementing CRM account-tier endpoints from the API catalog.
 *
 * Provides account management operations including tier management, party
 * creation,
 * search, merge, contacts, preferences, and vehicle operations.
 */
@Tag(
        name = "CRM Accounts",
        description =
                "Account tier management, party creation, search, merge, contacts, preferences, and vehicle operations")
@RestController
@RequestMapping("/v1/crm/accounts")
public class CrmAccountsController {

    private static final Logger log = LoggerFactory.getLogger(CrmAccountsController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-customer");
    private static final String DOMAIN = "customer-crm";
    private static final String TEAM = "customer-eng";

    private final PartyService partyService;
    private final AccountTierService accountTierService;

    public CrmAccountsController(PartyService partyService, AccountTierService accountTierService) {
        this.partyService = partyService;
        this.accountTierService = accountTierService;
    }

    @Operation(summary = "Get account tier", description = "Retrieve the tier level for a specific account")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Tier retrieved successfully",
                        content = @Content(schema = @Schema(implementation = GetAccountTierResponse.class))),
                @ApiResponse(responseCode = "404", description = "Account not found", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/{accountId}/tier")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_ACCOUNT_TIER_GET", apiVersion = "1")
    public ResponseEntity<GetAccountTierResponse> getAccountTier(
            @Parameter(description = "Account ID", required = true) @PathVariable UUID accountId) {
        log.info("Getting account tier for accountId={}", accountId);
        try {
            GetAccountTierResponse response = accountTierService.getAccountTier(accountId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException _) {
            log.warn("Account not found: {}", accountId);
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Resolve account tier",
            description = "Resolve or compute the account tier based on business rules")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Tier resolved successfully",
                        content = @Content(schema = @Schema(implementation = ResolveAccountTierResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
                @ApiResponse(responseCode = "404", description = "Account not found", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/tierResolve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_ACCOUNT_TIER_RESOLVE", apiVersion = "1")
    public ResponseEntity<ResolveAccountTierResponse> resolveAccountTier(
            @Parameter(description = "Tier resolution request", required = true) @RequestBody
                    ResolveAccountTierRequest body) {
        Span span = TRACER.spanBuilder("Resolve Account Tier").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Resolve Account Tier");
        span.setAttribute("app.operation.type", "query");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            ResolveAccountTierResponse response = accountTierService.resolveAccountTier(body);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Account not found or invalid request: {}", e.getMessage());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_FAILURE);
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    // --- Party/Commercial Account Management (Issue #176) ---

    @Operation(
            summary = "Create commercial account",
            description = "Create a new commercial party/account in the CRM system")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Account created successfully",
                        content = @Content(schema = @Schema(implementation = CreateCommercialAccountResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_CREATE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_CREATE", apiVersion = "1")
    public ResponseEntity<CreateCommercialAccountResponse> createCommercialAccount(
            @Parameter(description = "Commercial account creation request", required = false)
                    @RequestBody(required = false)
                    CreateCommercialAccountRequest body) {
        Span span = TRACER.spanBuilder("Create Party").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Create Party");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            BusinessSpanSupport.logWithTraceContext(log, "createCommercialAccount");
            CreateCommercialAccountResponse response = partyService.createCommercialAccount(body);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(summary = "Get party details", description = "Retrieve details for a specific party by ID")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Party details retrieved successfully",
                        content = @Content(schema = @Schema(implementation = GetPartyResponse.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/parties/{partyId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<GetPartyResponse> getParty(
            @Parameter(description = "Party ID", required = true) @PathVariable UUID partyId) {
        log.info("getParty partyId={}", partyId);
        GetPartyResponse response = partyService.getParty(partyId);
        return ResponseEntity.ok(response);
    }

    // --- Party Search and Merge (Issue #173) ---

    @Operation(
            summary = "Browse parties",
            description =
                    "Browse parties with paging and sorting. The service sorts by legalName ascending by default, "
                            + "appends partyId ascending as a stable tie-breaker whenever the requested sort list "
                            + "does not explicitly include partyId, and applies case-insensitive legalName sorting.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Browse results returned",
                        content = @Content(schema = @Schema(implementation = SearchPartiesResponse.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/parties")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_BROWSE", apiVersion = "1")
    public ResponseEntity<SearchPartiesResponse> browseParties(
            @Parameter(
                            description = "Pagination parameters (page, size, sort). The service uses legalName,asc "
                                    + "by default and appends partyId,asc as a stable tie-breaker whenever the "
                                    + "requested sort list does not explicitly include partyId; legalName sorting "
                                    + "is case-insensitive.")
                    @PageableDefault(
                            size = 20,
                            sort = {"legalName", "partyId"})
                    Pageable pageable,
            @Parameter(description = "Filter by name (case-insensitive contains on legal/display name)")
                    @RequestParam(required = false)
                    String name,
            @Parameter(description = "Filter by account status (ACTIVE|PENDING|SUSPENDED|INACTIVE)")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "Filter by party type (ORGANIZATION|INDIVIDUAL)") @RequestParam(required = false)
                    String partyType,
            @Parameter(description = "Filter by customer number (case-insensitive contains)")
                    @RequestParam(required = false)
                    String customerNumber,
            @Parameter(description = "Sort field: name (default) or customerNumber") @RequestParam(required = false)
                    String sortField,
            @Parameter(description = "Sort order: asc (default) or desc") @RequestParam(required = false)
                    String sortOrder) {
        log.info(
                "browseParties pageable={} name={} status={} partyType={} customerNumber={} sortField={} sortOrder={}",
                LogSanitizer.forLog(pageable),
                LogSanitizer.forLog(name),
                LogSanitizer.forLog(status),
                LogSanitizer.forLog(partyType),
                LogSanitizer.forLog(customerNumber),
                LogSanitizer.forLog(sortField),
                LogSanitizer.forLog(sortOrder));
        return ResponseEntity.ok(
                partyService.browseParties(pageable, name, status, partyType, customerNumber, sortField, sortOrder));
    }

    @Operation(summary = "Search parties", description = "Search for parties based on various criteria")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Search results returned",
                        content = @Content(schema = @Schema(implementation = SearchPartiesResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid search criteria", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties/search")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_SEARCH", apiVersion = "1")
    public ResponseEntity<SearchPartiesResponse> searchParties(
            @Parameter(description = "Search criteria", required = false) @RequestBody(required = false)
                    SearchPartiesRequest body) {
        log.info("searchParties");
        SearchPartiesResponse response = partyService.searchParties(body);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Resolve party display names",
            description = "Batch-resolve party ids to display names. Consumed server-side by sibling services "
                    + "(e.g. pos-invoice) that store only the party id and need the display name to enrich "
                    + "finder/search rows. Unknown or unresolvable ids are omitted from the response.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Resolved party id-to-name pairs",
                        content =
                                @Content(array = @ArraySchema(schema = @Schema(implementation = PartyNameRef.class)))),
                @ApiResponse(responseCode = "400", description = "Invalid resolve request", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties:resolve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_RESOLVE", apiVersion = "1")
    public ResponseEntity<List<PartyNameRef>> resolvePartyNames(@Valid @RequestBody PartyNameResolveRequest body) {
        Span span = TRACER.spanBuilder("Resolve Party").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Resolve Party");
        span.setAttribute("app.operation.type", "query");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            BusinessSpanSupport.logWithTraceContext(log, "resolvePartyNames count={}", body.partyIds().size());
            List<PartyNameRef> response = partyService.resolveNames(body.partyIds());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(summary = "Merge parties", description = "Merge multiple parties into a single party record")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Parties merged successfully",
                        content = @Content(schema = @Schema(implementation = MergePartiesResponse.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(responseCode = "400", description = "Invalid merge request", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties/{partyId}/merge")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_MERGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_MERGE + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_MERGE", apiVersion = "1")
    public ResponseEntity<MergePartiesResponse> mergeParties(
            @Parameter(description = "Target party ID", required = true) @PathVariable UUID partyId,
            @Parameter(description = "Merge request with source party IDs", required = false)
                    @RequestBody(required = false)
                    MergePartiesRequest body) {
        log.info("mergeParties partyId={}", partyId);
        MergePartiesResponse response = partyService.mergeParties(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Communication Preferences (Issue #171) ---

    @Operation(
            summary = "Get communication preferences",
            description = "Retrieve communication preferences and consent flags for a party")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Preferences retrieved successfully",
                        content =
                                @Content(schema = @Schema(implementation = GetCommunicationPreferencesResponse.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/parties/{partyId}/communicationPreferences")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    public ResponseEntity<GetCommunicationPreferencesResponse> getCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable UUID partyId) {
        log.info("getCommunicationPreferences partyId={}", partyId);
        GetCommunicationPreferencesResponse response = partyService.getCommunicationPreferences(partyId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Create or update communication preferences",
            description = "Set or update communication preferences and consent flags for a party")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Preferences updated successfully",
                        content =
                                @Content(
                                        schema =
                                                @Schema(
                                                        implementation =
                                                                UpsertCommunicationPreferencesResponse.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(responseCode = "400", description = "Invalid preference data", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties/{partyId}/communicationPreferences")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    @EmitEvent(id = "CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT", apiVersion = "1")
    public ResponseEntity<UpsertCommunicationPreferencesResponse> upsertCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable UUID partyId,
            @Parameter(description = "Communication preferences to set", required = false)
                    @RequestBody(required = false)
                    UpsertCommunicationPreferencesRequest body) {
        log.info("upsertCommunicationPreferences partyId={}", partyId);
        UpsertCommunicationPreferencesResponse response = partyService.upsertCommunicationPreferences(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Vehicle Management (Issue #169) ---

    @Operation(summary = "Create vehicle for party", description = "Associate a new vehicle with a party/customer")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Vehicle created successfully",
                        content = @Content(schema = @Schema(implementation = CreateVehicleForPartyResponse.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(responseCode = "400", description = "Invalid vehicle data", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @PostMapping("/parties/{partyId}/vehicles")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.VEHICLE_CREATE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_VEHICLE_CREATE", apiVersion = "1")
    public ResponseEntity<CreateVehicleForPartyResponse> createVehicleForParty(
            @Parameter(description = "Party ID", required = true) @PathVariable UUID partyId,
            @Parameter(description = "Vehicle creation request", required = false) @RequestBody(required = false)
                    CreateVehicleForPartyRequest body) {
        log.info("createVehicleForParty partyId={}", partyId);
        CreateVehicleForPartyResponse response = partyService.createVehicleForParty(partyId, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "checkPartyDuplicates",
            summary = "Check for duplicate commercial parties",
            description =
                    "Search for existing parties with a similar legal name to detect potential duplicates before creating a new commercial account.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Duplicate check completed successfully",
                        content = @Content(schema = @Schema(implementation = DuplicateCheckResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request - legalName too short or blank",
                        content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/parties/duplicate-check")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_SEARCH})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_SEARCH + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_DUPLICATE_CHECK", apiVersion = "1")
    public ResponseEntity<DuplicateCheckResponse> checkPartyDuplicates(
            @Parameter(
                            description = "Legal name to check for duplicates",
                            required = true,
                            example = "Acme Corporation")
                    @RequestParam
                    @jakarta.validation.constraints.Size(min = 2)
                    @jakarta.validation.constraints.NotBlank
                    String legalName) {
        DuplicateCheckResponse response = partyService.checkPartyDuplicates(legalName);
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "upsertBillingRules",
            summary = "Upsert billing rules for a party",
            description = "Create or update the billing rules configuration for a commercial party.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Billing rules updated successfully",
                        content = @Content(schema = @Schema(implementation = BillingRuleRef.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
            })
    @PutMapping("/parties/{partyId}/billing-rules")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.BILLING_RULES_EDIT})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.BILLING_RULES_EDIT + "')")
    @EmitEvent(id = "CUSTOMER_BILLING_RULES_UPSERT", apiVersion = "1")
    public ResponseEntity<BillingRuleRef> upsertBillingRules(
            @Parameter(description = "Party ID", required = true, example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                    @PathVariable
                    UUID partyId,
            @RequestBody @jakarta.validation.Valid UpsertBillingRulesRequest request) {
        BillingRuleRef result = partyService.upsertBillingRulesForParty(partyId, request);
        return ResponseEntity.ok(result);
    }
}
