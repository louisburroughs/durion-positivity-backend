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
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.AccountTierService;
import com.positivity.customer.internal.service.PartyService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.LogSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.annotations.ParameterObject;
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

    private final PartyService partyService;
    private final AccountTierService accountTierService;

    public CrmAccountsController(PartyService partyService, AccountTierService accountTierService) {
        this.partyService = partyService;
        this.accountTierService = accountTierService;
    }

    @Operation(operationId = "getAccountTier", summary = "Get Account Tier", description = """
                    Returns the currently assigned tier for a commercial account, including who assigned it, \
                    when, and whether a manual override is active; unassigned accounts report STANDARD.
                    Use this tool when the stored tier of a known commercial account is needed; do not use \
                    resolveAccountTier, which recomputes a recommended tier from revenue and contract inputs.
                    Preconditions: a commercial party must exist for the accountId; person parties have no tier.
                    Required inputs: accountId (UUID) as a path parameter; there is no request body.
                    Emits a CUSTOMER_ACCOUNT_TIER_GET audit event; no state changes occur.
                    Returns 404 when no commercial account exists for the supplied accountId.
                    """)
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

    @Operation(operationId = "resolveAccountTier", summary = "Resolve Account Tier", description = """
                    Computes the recommended tier for a commercial account from annual revenue, active contract \
                    count, and account age thresholds, and optionally applies it to the account.
                    Use this tool when a tier recommendation or recalculation is needed; do not use \
                    getAccountTier, which only reads the stored tier without recomputing it.
                    Preconditions: a commercial party must exist for the accountId; when applyTier is true, a \
                    manual tier override on the account blocks application unless forceRecalculation is also true.
                    Required inputs: accountId (UUID string); annualRevenue, activeContractCount, and \
                    accountAgeMonths are optional scoring inputs, and applyTier and forceRecalculation both \
                    default to false, so the default call is a dry run.
                    Emits a CUSTOMER_ACCOUNT_TIER_RESOLVE event; when the tier is applied the account record is \
                    updated with assignedBy SYSTEM and a customer fact is republished.
                    Returns 404 when the account does not exist or the accountId is not a valid UUID.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Tier scoring inputs for one account, plus flags controlling whether the resolved tier is applied.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Dry-run resolution", value = """
                                                                    {"accountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "annualRevenue":275000,
                                                                     "activeContractCount":3,
                                                                     "accountAgeMonths":18,
                                                                     "applyTier":false,
                                                                     "forceRecalculation":false}
                                                                    """)))
                    @RequestBody
                    ResolveAccountTierRequest body) {
        try {
            ResolveAccountTierResponse response = accountTierService.resolveAccountTier(body);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Account not found or invalid request: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // --- Party/Commercial Account Management (Issue #176) ---

    @Operation(operationId = "createCrmCommercialAccount", summary = "Create Commercial Account", description = """
                    Creates a commercial party record with status ACTIVE and a generated customer number of the \
                    form CUST-XXXXXXXX.
                    Use this tool when onboarding a new commercial customer; do not use searchParties or \
                    checkPartyDuplicates, which only look up existing parties, and run checkPartyDuplicates \
                    first to avoid creating a duplicate, because no uniqueness check is applied here.
                    Preconditions: none beyond authorization; the service performs no duplicate detection on \
                    legalName.
                    Required inputs: legalName (non-blank, max 255); partyType defaults to COMMERCIAL and must \
                    be PERSON, COMMERCIAL, or UNKNOWN when supplied; displayName, taxId, billingTermsId, and \
                    externalIdentifiers are optional.
                    Emits a CUSTOMER_PARTY_CREATE event and publishes a party-changed customer fact.
                    Returns 400 when legalName is missing or blank, or when partyType is not a recognized value.
                    """)
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
    public ResponseEntity<CreateCommercialAccountResponse> createCrmCommercialAccount(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Commercial party to create; legalName is the only mandatory field and no duplicate check is applied.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "New commercial account", value = """
                                                                    {"legalName":"Acme Fleet Services LLC",
                                                                     "displayName":"Acme Fleet",
                                                                     "taxId":"12-3456789",
                                                                     "partyType":"COMMERCIAL",
                                                                     "externalIdentifiers":{"ERP":"AC-1001"}}
                                                                    """)))
                    @RequestBody(required = false)
                    CreateCommercialAccountRequest body) {
        log.info("createCrmCommercialAccount");
        CreateCommercialAccountResponse response = partyService.createCommercialAccount(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "getParty", summary = "Get Party Details", description = """
                    Returns the identity projection of a single party, resolving commercial parties first and \
                    falling back to person parties, whose display name is resolved from the person directory.
                    Use this tool when a party id is already known; use browseParties or searchParties instead \
                    when locating a party by name, status, or customer number.
                    Preconditions: a commercial or person party must exist for the supplied partyId.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when neither a commercial nor a person party exists for the supplied partyId.
                    """)
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

    @Operation(operationId = "browseParties", summary = "Browse Customer Directory", description = """
                    Returns a paged customer directory that unifies commercial parties and standalone \
                    individual customers, with person names and contact points resolved from pos-people.
                    Use this tool when listing or typeahead-filtering customers by name, status, party type, \
                    or customer number; do not use searchParties, which filters commercial parties only by \
                    structured criteria, and use getParty instead when the party id is already known.
                    Preconditions: none; an empty page is returned when nothing matches, and a pos-people \
                    outage degrades person names to null rather than failing the request.
                    Required inputs: none; page defaults to 0 with size 20, the name filter matches legal \
                    name, display name, or customer number case-insensitively, status matches ACTIVE, \
                    INACTIVE, ON_HOLD, or MERGED, sortField is name (default) or customerNumber, and \
                    sortOrder is asc (default) or desc with partyId as a stable tie-breaker.
                    Emits a CUSTOMER_PARTY_BROWSE audit event; no state changes occur.
                    Returns 200 with an empty results array rather than an error when no party matches the \
                    filters.
                    """)
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
            @ParameterObject
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

    @Operation(operationId = "searchParties", summary = "Search Commercial Parties", description = """
                    Searches commercial party records by structured criteria such as name, tax id, party type, \
                    and status, returning all matches in a single unpaged result set.
                    Use this tool when filtering commercial parties by structured attributes like taxId; use \
                    browseParties instead for the paged, unified directory that also includes individual \
                    customers, and checkPartyDuplicates for pre-create duplicate detection by legal name.
                    Preconditions: none; an omitted or empty body matches every commercial party.
                    Required inputs: none; name, email, phone, taxId, partyType, and status are all optional \
                    filters, and the pageNumber and pageSize fields are accepted but not applied, so the full \
                    match list is always returned.
                    Emits a CUSTOMER_PARTY_SEARCH audit event; no state changes occur.
                    Returns 200 with an empty results array rather than an error when no party matches.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Optional structured filter criteria; every supplied field narrows the match and an empty body matches all commercial parties.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Filter by name and status", value = """
                                                                    {"name":"Acme","partyType":"COMMERCIAL","status":"ACTIVE"}
                                                                    """)))
                    @RequestBody(required = false)
                    SearchPartiesRequest body) {
        log.info("searchParties");
        SearchPartiesResponse response = partyService.searchParties(body);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "resolvePartyNames", summary = "Resolve Party Display Names", description = """
                    Batch-resolves party ids to display names for both commercial and person parties, for \
                    sibling services such as pos-invoice that store only the party id.
                    Use this tool when enriching rows that already carry party ids; do not use getParty, which \
                    returns the full identity projection for a single party per call.
                    Preconditions: none; unknown or unresolvable ids are silently omitted from the response \
                    rather than causing an error.
                    Required inputs: partyIds, a non-empty list of up to 200 UUIDs; null entries and \
                    duplicates are dropped before resolution.
                    Emits a CUSTOMER_PARTY_RESOLVE audit event; no state changes occur.
                    Returns 400 when partyIds is empty or exceeds 200 entries, and 200 with a possibly \
                    shorter list than requested when some ids cannot be resolved.
                    """)
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
    public ResponseEntity<List<PartyNameRef>> resolvePartyNames(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of party ids whose display names should be resolved.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two-party batch", value = """
                                                                    {"partyIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                                 "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PartyNameResolveRequest body) {
        log.info("resolvePartyNames count={}", body.partyIds().size());
        return ResponseEntity.ok(partyService.resolveNames(body.partyIds()));
    }

    @Operation(operationId = "mergeParties", summary = "Merge Duplicate Commercial Parties", description = """
                    Merges one duplicate commercial party into a surviving party: relationships are reassigned \
                    to the survivor, external identifiers and vehicle VINs are copied over, and the losing \
                    party's status is set to MERGED.
                    Use this tool when checkPartyDuplicates has confirmed two records represent the same \
                    customer; do not use createCrmCommercialAccount to work around duplicates, and note the merge \
                    is not reversible through this API.
                    Preconditions: both the surviving party (path) and losing party (body) must exist as \
                    commercial parties and must be different records.
                    Required inputs: partyId of the survivor as a path parameter, plus losingPartyId (UUID \
                    string) and a justification of up to 1000 characters in the body.
                    Emits a CUSTOMER_PARTY_MERGE event and republishes customer facts for both parties and \
                    for contacts whose account linkage moved.
                    Returns 404 when either party cannot be found, and 400 when losingPartyId or \
                    justification is missing, losingPartyId is not a valid UUID, or both ids refer to the \
                    same party.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Identifies the losing party to fold into the survivor and records the audit justification for the merge.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Merge duplicate", value = """
                                                                    {"losingPartyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "justification":"Duplicate created during fleet onboarding import"}
                                                                    """)))
                    @RequestBody(required = false)
                    MergePartiesRequest body) {
        log.info("mergeParties partyId={}", partyId);
        MergePartiesResponse response = partyService.mergeParties(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Communication Preferences (Issue #171) ---

    @Operation(
            operationId = "getAccountCommunicationPreferences",
            summary = "Get Account Communication Preferences",
            description = """
                    Returns a legacy account-scoped communication-preference projection for a commercial \
                    party in which every channel currently reports the placeholder value N/A.
                    Use this tool only for the legacy accounts-scoped path; use getCommunicationPreferences \
                    instead, which reads the persisted per-party preference record with real channel values \
                    and consent flags.
                    Preconditions: a commercial party must exist for the supplied partyId.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no commercial party exists for the supplied partyId.
                    """)
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
            operationId = "upsertAccountCommunicationPreferences",
            summary = "Upsert Account Communication Preferences",
            description = """
                    Acknowledges an account-scoped communication-preference submission for a commercial party \
                    without persisting any preference values; this legacy path only validates the party and \
                    returns SUCCESS.
                    Use this tool only for the legacy accounts-scoped path; use upsertCommunicationPreferences \
                    instead, which actually stores channel preferences and consent flags per party.
                    Preconditions: a commercial party must exist for the supplied partyId.
                    Required inputs: partyId (UUID) as a path parameter and a non-null JSON body; the \
                    preference fields themselves are accepted but not stored.
                    Emits a CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT audit event; no preference record is \
                    written.
                    Returns 404 when no commercial party exists for the supplied partyId, and 400 when the \
                    request body is missing.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Channel preference values acknowledged by this legacy endpoint; the values are validated but not stored.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Opt-in email only", value = """
                                                                    {"emailPreference":"OPT_IN",
                                                                     "smsPreference":"OPT_OUT",
                                                                     "phonePreference":"OPT_OUT",
                                                                     "updateSource":"ADMIN"}
                                                                    """)))
                    @RequestBody(required = false)
                    UpsertCommunicationPreferencesRequest body) {
        log.info("upsertCommunicationPreferences partyId={}", partyId);
        UpsertCommunicationPreferencesResponse response = partyService.upsertCommunicationPreferences(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Vehicle Management (Issue #169) ---

    @Operation(operationId = "createVehicleForParty", summary = "Associate Vehicle With Party", description = """
                    Associates a vehicle VIN with a commercial party by appending it to the party's owned VIN \
                    list.
                    Use this tool when recording that a commercial account operates a vehicle; do not use it \
                    to change vehicle details, and note the VIN list is account-level rather than a full \
                    vehicle record.
                    Preconditions: a commercial party must exist for the supplied partyId, and the VIN must \
                    not already be associated with that party.
                    Required inputs: partyId (UUID) as a path parameter and vinNumber (max 17 characters) in \
                    the body; unitNumber, description, licensePlate, and licensePlateRegion are optional.
                    Emits a CUSTOMER_VEHICLE_CREATE event and republishes the party-changed customer fact.
                    Returns 404 when the party does not exist, 409 when the VIN is already associated with \
                    the party, and 400 when vinNumber is missing or blank.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vehicle identification to associate with the party, keyed by VIN.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Fleet truck", value = """
                                                                    {"vinNumber":"1FTFW1ET5DFC10312",
                                                                     "unitNumber":"TRK-42",
                                                                     "description":"2019 Ford F-150 service truck",
                                                                     "licensePlate":"ABC1234",
                                                                     "licensePlateRegion":"TX"}
                                                                    """)))
                    @RequestBody(required = false)
                    CreateVehicleForPartyRequest body) {
        log.info("createVehicleForParty partyId={}", partyId);
        CreateVehicleForPartyResponse response = partyService.createVehicleForParty(partyId, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "checkPartyDuplicates", summary = "Check Commercial Party Duplicates", description = """
                    Checks for existing commercial parties whose legal name contains the supplied name, \
                    flagging case-insensitive exact matches as EXACT with score 1.0 and other containment \
                    matches as FUZZY with score 0.7.
                    Use this tool before createCrmCommercialAccount to avoid creating a duplicate customer; do \
                    not use searchParties for this, which does not classify match strength or surface an \
                    exactMatchPartyId.
                    Preconditions: none; the check reads existing commercial parties only.
                    Required inputs: legalName as a query parameter with at least 2 non-whitespace \
                    characters; there is no request body.
                    Emits a CUSTOMER_PARTY_DUPLICATE_CHECK audit event; no state changes occur.
                    Returns 400 when legalName is blank or shorter than 2 characters after trimming, and 200 \
                    with duplicatesFound false when no similar party exists.
                    """)
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

    @Operation(operationId = "upsertPartyBillingRules", summary = "Upsert Party Billing Rules", description = """
                    Creates or replaces the embedded billing-rules configuration on a commercial party, \
                    covering PO requirement, tax exemption, credit hold, auto-pay, payment terms, credit \
                    limit, currency, and invoice delivery method.
                    Use this tool when configuring how a commercial account is billed; do not use \
                    createCrmCommercialAccount, which only sets billingTermsId at creation and cannot change \
                    billing flags afterwards.
                    Preconditions: a commercial party must exist for the supplied partyId; the whole rules \
                    block is replaced on every call rather than patched field by field.
                    Required inputs: partyId (UUID) as a path parameter; boolean flags poRequired, taxExempt, \
                    creditHold, and autoPayEnabled default to false when omitted, and paymentTerms, \
                    creditLimit, currency, and invoiceDeliveryMethod (EMAIL, MAIL, PORTAL) are optional.
                    Emits a CUSTOMER_BILLING_RULES_UPSERT event; the rules are stored on the party record \
                    itself.
                    Returns 404 when no commercial party exists for the supplied partyId.
                    """)
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
    public ResponseEntity<BillingRuleRef> upsertPartyBillingRules(
            @Parameter(description = "Party ID", required = true, example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
                    @PathVariable
                    UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Complete billing-rules configuration that replaces the party's current rules block.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Net-30 with PO", value = """
                                                                    {"paymentTerms":"NET_30",
                                                                     "creditLimit":25000.00,
                                                                     "currency":"USD",
                                                                     "taxExempt":false,
                                                                     "poRequired":true,
                                                                     "creditHold":false,
                                                                     "autoPayEnabled":false,
                                                                     "invoiceDeliveryMethod":"EMAIL",
                                                                     "billingAddressId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d"}
                                                                    """)))
                    @RequestBody
                    @jakarta.validation.Valid
                    UpsertBillingRulesRequest request) {
        BillingRuleRef result = partyService.upsertBillingRulesForParty(partyId, request);
        return ResponseEntity.ok(result);
    }
}
