package com.positivity.tenant.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.tenant.internal.dto.AccountContactRequest;
import com.positivity.tenant.internal.dto.AccountContactResponse;
import com.positivity.tenant.internal.dto.AccountCreateRequest;
import com.positivity.tenant.internal.dto.AccountResponse;
import com.positivity.tenant.internal.dto.AccountUpdateRequest;
import com.positivity.tenant.internal.dto.BillingProfileRequest;
import com.positivity.tenant.internal.dto.BillingProfileResponse;
import com.positivity.tenant.internal.security.TenantPermissions;
import com.positivity.tenant.internal.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin API over accounts, contacts and billing profiles (ADR-0062 §7). Account data
 * never leaves this module: nothing here publishes a fact.
 */
@Tag(name = "Platform Account API", description = "The customer accounts that own tenants")
@RestController
@RequestMapping("/v1/platform/accounts")
@RequiredArgsConstructor
public class PlatformAccountController {

    private final AccountService accountService;

    private static final String ACCOUNT_CREATE_EXAMPLE = """
            {"legalName":"Acme Tire & Auto LLC","tradingName":"Acme Tire","taxId":"12-3456789",
             "homeCountry":"US","homeCurrency":"USD"}
            """;

    private static final String ACCOUNT_UPDATE_EXAMPLE = """
            {"tradingName":"Acme Tire & Auto","status":"ACTIVE"}
            """;

    private static final String CONTACT_EXAMPLE = """
            {"name":"Jordan Lee","role":"BILLING","email":"jordan@acme.example","phone":"+1 555 0100"}
            """;

    private static final String BILLING_PROFILE_EXAMPLE = """
            {"addressLine1":"100 Main St","city":"Springfield","region":"IL","postalCode":"62704","country":"US",
             "paymentTerms":"NET30","invoicingEmail":"billing@acme.example",
             "paymentProcessorCustomerToken":"cus_0123456789"}
            """;

    @Operation(operationId = "createAccount", summary = "Create an Account", description = """
            Creates the customer account that will own one or more tenants.
            Use this tool before createTenant for a new customer; do not use it to add a tenant to an existing \
            customer.
            Preconditions: the legal name is not already registered.
            Required inputs: legalName, homeCountry (ISO 3166-1 alpha-2) and homeCurrency (ISO 4217); tradingName \
            and taxId are optional.
            Emits an ACCOUNT_CREATE event.
            Returns 201 with the account and 409 when the legal name is taken.
            """)
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "Legal name already taken")
    @EmitEvent(id = "ACCOUNT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_CREATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:create"})
    @PostMapping
    public ResponseEntity<AccountResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Account to create: legal name, home country and currency; trading name and tax id optional.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "New customer",
                                                            value = ACCOUNT_CREATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    AccountCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(request));
    }

    @Operation(operationId = "listAccounts", summary = "List Accounts", description = """
            Lists every account with its contacts, billing profile and tenant ids, ordered by legal name.
            Use this tool to find an account id before createTenant; do not use it for one account, use getAccount instead.
            Preconditions: none beyond the platform:account:read authority.
            Required inputs: none.
            Emits an ACCOUNT_LIST event.
            Returns 200 with the unpaginated list.
            """)
    @ApiResponse(responseCode = "200", description = "Accounts listed")
    @EmitEvent(id = "ACCOUNT_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:read"})
    @GetMapping
    public ResponseEntity<List<AccountResponse>> list() {
        return ResponseEntity.ok(accountService.list());
    }

    @Operation(operationId = "getAccount", summary = "Get an Account", description = """
            Returns one account with its contacts, billing profile and the ids of the tenants it owns.
            Use this tool when the account id is known; do not use it to find an account by name, use listAccounts instead.
            Preconditions: the account exists.
            Required inputs: id (UUID) as a path parameter.
            Emits an ACCOUNT_GET event.
            Returns 200 with the account and 404 when it does not exist.
            """)
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @EmitEvent(id = "ACCOUNT_GET", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:read"})
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.get(id));
    }

    @Operation(operationId = "updateAccount", summary = "Update an Account", description = """
            Applies a partial update to an account's legal name, trading name, status, tax id, home country or \
            home currency.
            Use this tool for account descriptors; do not use it for contacts or billing details, use the contact and \
            billing-profile operations instead.
            Preconditions: the account exists; a new legal name must not be taken.
            Required inputs: id (UUID) as a path parameter and a body where every field is optional and null \
            leaves the value unchanged.
            Emits an ACCOUNT_UPDATE event.
            Returns 200 with the account, 404 when it does not exist and 409 when the legal name is taken.
            """)
    @ApiResponse(responseCode = "200", description = "Account updated")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @ApiResponse(responseCode = "409", description = "Legal name already taken")
    @EmitEvent(id = "ACCOUNT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:update"})
    @PatchMapping("/{id}")
    public ResponseEntity<AccountResponse> update(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Fields to change; every field is optional and null leaves the value unchanged.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Rename trading name",
                                                            value = ACCOUNT_UPDATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    AccountUpdateRequest request) {
        return ResponseEntity.ok(accountService.update(id, request));
    }

    @Operation(operationId = "addAccountContact", summary = "Add a Contact to an Account", description = """
            Adds an OWNER, BILLING or TECHNICAL contact to an account; an account may carry several of each role.
            Use this tool for a new person; do not use it to change an existing contact, use updateAccountContact instead.
            Preconditions: the account exists.
            Required inputs: id (UUID) as a path parameter and a body with name, role and email; phone is optional.
            Emits an ACCOUNT_CONTACT_ADD event.
            Returns 201 with the contact and 404 when the account does not exist.
            """)
    @ApiResponse(responseCode = "201", description = "Contact added")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @EmitEvent(id = "ACCOUNT_CONTACT_ADD", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:update"})
    @PostMapping("/{id}/contacts")
    public ResponseEntity<AccountContactResponse> addContact(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Contact: name, role (OWNER, BILLING or TECHNICAL), email; phone optional. A PUT replaces every field.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Billing contact", value = CONTACT_EXAMPLE)))
                    @Valid
                    @RequestBody
                    AccountContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.addContact(id, request));
    }

    @Operation(operationId = "updateAccountContact", summary = "Update an Account Contact", description = """
            Replaces a contact's name, role, email and phone.
            Use this tool to correct an existing contact; do not use it to add or drop one, use addAccountContact or \
            removeAccountContact instead.
            Preconditions: the contact exists on the account.
            Required inputs: id and contactId (UUIDs) as path parameters and a full contact body.
            Emits an ACCOUNT_CONTACT_UPDATE event.
            Returns 200 with the contact and 404 when the account or contact does not exist.
            """)
    @ApiResponse(responseCode = "200", description = "Contact updated")
    @ApiResponse(responseCode = "404", description = "Account or contact not found")
    @EmitEvent(id = "ACCOUNT_CONTACT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:update"})
    @PutMapping("/{id}/contacts/{contactId}")
    public ResponseEntity<AccountContactResponse> updateContact(
            @PathVariable UUID id,
            @PathVariable UUID contactId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Contact: name, role (OWNER, BILLING or TECHNICAL), email; phone optional. A PUT replaces every field.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Billing contact", value = CONTACT_EXAMPLE)))
                    @Valid
                    @RequestBody
                    AccountContactRequest request) {
        return ResponseEntity.ok(accountService.updateContact(id, contactId, request));
    }

    @Operation(operationId = "removeAccountContact", summary = "Remove an Account Contact", description = """
            Deletes a contact from an account.
            Use this tool when a person no longer represents the customer; use updateAccountContact to change \
            their role instead.
            Preconditions: the contact exists on the account.
            Required inputs: id and contactId (UUIDs) as path parameters.
            Emits an ACCOUNT_CONTACT_REMOVE event.
            Returns 204 and 404 when the account or contact does not exist.
            """)
    @ApiResponse(responseCode = "204", description = "Contact removed")
    @ApiResponse(responseCode = "404", description = "Account or contact not found")
    @EmitEvent(id = "ACCOUNT_CONTACT_REMOVE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:update"})
    @DeleteMapping("/{id}/contacts/{contactId}")
    public ResponseEntity<Void> removeContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        accountService.removeContact(id, contactId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "putAccountBillingProfile",
            summary = "Set an Account's Billing Profile",
            description = """
            Creates or replaces the account's single billing profile: billing address, payment terms, invoicing \
            email and the payment processor's customer token. Card numbers are never accepted or stored.
            Use this tool whenever billing details change; do not use it for the legal name or home currency, use \
            updateAccount instead. The response reports only whether a processor token is on file.
            Preconditions: the account exists.
            Required inputs: id (UUID) as a path parameter and a body with addressLine1, city, country, \
            paymentTerms and invoicingEmail; the rest is optional.
            Emits an ACCOUNT_BILLING_PROFILE_PUT event.
            Returns 200 with the billing profile and 404 when the account does not exist.
            """)
    @ApiResponse(responseCode = "200", description = "Billing profile stored")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @EmitEvent(id = "ACCOUNT_BILLING_PROFILE_PUT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.ACCOUNT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:account:update"})
    @PutMapping("/{id}/billing-profile")
    public ResponseEntity<BillingProfileResponse> putBillingProfile(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Billing address, payment terms, invoicing email and optional processor customer token; never a card number.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Net-30 profile",
                                                            value = BILLING_PROFILE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BillingProfileRequest request) {
        return ResponseEntity.ok(accountService.putBillingProfile(id, request));
    }
}
