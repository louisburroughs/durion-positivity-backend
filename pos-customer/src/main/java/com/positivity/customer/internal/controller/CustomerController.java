package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.CommercialPartyServiceImpl;
import com.positivity.customer.internal.service.CustomerService;
import com.positivity.customer.internal.service.PersonPartyServiceImpl;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Customer API", description = "Operations related to customers")
@RestController
@RequestMapping("/v1/crm")
public class CustomerController {

    private static final String COMMERCIAL = "COMMERCIAL";

    private final CustomerService commercialService;
    private final CustomerService personService;

    public CustomerController(PersonPartyServiceImpl personService, CommercialPartyServiceImpl commercialService) {
        this.commercialService = commercialService;
        this.personService = personService;
    }

    @Operation(operationId = "listCustomers", summary = "List Customers By Type", description = """
                    Returns a page of customers of one party type, switching to a server-side typeahead \
                    search when a name or email filter is supplied.
                    Use this tool for the legacy flat customer listing keyed by customerType; use \
                    browseParties instead for the unified directory that merges commercial and individual \
                    customers in one result.
                    Preconditions: none; an empty page is returned when nothing matches.
                    Required inputs: none; customerType defaults to PERSON and accepts PERSON or \
                    COMMERCIAL, name and email are optional case-insensitive filters, and paging defaults \
                    to page 0, size 20, sorted by customerNumber.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page rather than an error when no customer matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Page of customers returned successfully.")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public Page<CustomerDTO> getAllCustomers(
            @Parameter(description = "Customer type filter: PERSON or COMMERCIAL", example = "PERSON")
                    @RequestParam(required = false, defaultValue = "PERSON")
                    String customerType,
            @Parameter(description = "Case-insensitive partial name filter for typeahead search", example = "doe")
                    @RequestParam(required = false)
                    String name,
            @Parameter(description = "Case-insensitive email filter for typeahead search", example = "jdoe@acme.com")
                    @RequestParam(required = false)
                    String email,
            @ParameterObject @PageableDefault(size = 20, sort = "customerNumber") Pageable pageable) {
        CustomerService service = COMMERCIAL.equalsIgnoreCase(customerType) ? commercialService : personService;

        boolean searching = (name != null && !name.isBlank()) || (email != null && !email.isBlank());
        if (searching) {
            log.info("Searching {} customers by name={} email={} with paging: {}", customerType, name, email, pageable);
            return service.searchCustomers(name, email, pageable);
        }

        log.info("Fetching customers of type: {} with paging: {}", customerType, pageable);
        return service.getAllCustomers(pageable);
    }

    @Operation(operationId = "getCustomerById", summary = "Get Customer By Id", description = """
                    Returns one customer as a flat CustomerDTO, checking commercial parties first and \
                    falling back to person parties.
                    Use this tool for the legacy flat customer view; use getParty or getSnapshotByParty \
                    instead for the richer party projections.
                    Preconditions: a commercial or person party must exist for the supplied id.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when neither a commercial nor a person party exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Customer found and returned.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<CustomerDTO> getCustomerById(
            @Parameter(description = "ID of the customer to retrieve", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        log.info("Fetching customer with id: {}", id);

        return commercialService
                .getCustomerById(id)
                .or(() -> personService.getCustomerById(id))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "createCustomer", summary = "Create Customer Record", description = """
                    Creates a customer from a flat CustomerDTO, routed by customerType to either the \
                    commercial or person party store.
                    Use this tool only for the legacy flat customer API; use createCrmCommercialAccount \
                    instead for commercial onboarding with duplicate checking, and createCrmPerson for \
                    individuals so the canonical identity lands in pos-people.
                    Preconditions: none beyond authorization; no duplicate detection is performed.
                    Required inputs: firstName and lastName (each max 100); customerType selects the store, \
                    where COMMERCIAL routes to the commercial service and anything else creates a person \
                    party, and customerNumber, primaryAddress, and vehicleVins are optional.
                    Emits a CUSTOMER_CUSTOMER_CREATE event and publishes a party-changed customer fact.
                    Returns 201 with the stored customer on success; returns 400 for a malformed JSON body or \
                    when firstName/lastName are blank or absent.
                    """)
    @ApiResponse(responseCode = "201", description = "Customer created successfully.")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_CREATE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_CREATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> createCustomer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The flat customer record to store; customerType routes it to the commercial or person store.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Individual customer", value = """
                                                                    {"firstName":"Dana",
                                                                     "lastName":"Ortiz",
                                                                     "customerType":"PERSON",
                                                                     "primaryAddress":"100 Congress Ave, Austin, TX 78701"}
                                                                    """)))
                    @RequestBody
                    CustomerDTO customer) {
        log.info("Creating new customer: {}", customer);
        CustomerService service =
                COMMERCIAL.equalsIgnoreCase(customer.getCustomerType()) ? commercialService : personService;
        CustomerDTO saved = service.createCustomer(customer);
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(operationId = "updateCustomer", summary = "Update Customer Record", description = """
                    Updates an existing customer's flat record, with the body's customerType selecting \
                    whether the commercial or person store is searched for the id.
                    Use this tool only for the legacy flat customer API; the customerType in the body must \
                    match the store the customer actually lives in, or the lookup misses, so do not use it \
                    to change a customer from PERSON to COMMERCIAL.
                    Preconditions: a party of the type named by customerType must exist for the supplied id.
                    Required inputs: id (UUID) as a path parameter and the CustomerDTO body including \
                    customerType; only fields present in the DTO mapping are applied.
                    Emits a CUSTOMER_CUSTOMER_UPDATE event and publishes a party-changed customer fact.
                    Returns 404 when no party of the selected type exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Customer updated successfully.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @PutMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_EDIT})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_EDIT + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_UPDATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> updateCustomer(
            @Parameter(description = "ID of the customer to update", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The revised customer fields; customerType must name the store the customer already lives in.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Update address", value = """
                                                                    {"firstName":"Dana",
                                                                     "lastName":"Ortiz",
                                                                     "customerType":"PERSON",
                                                                     "primaryAddress":"200 Lavaca St, Austin, TX 78701"}
                                                                    """)))
                    @RequestBody
                    CustomerDTO customer) {
        log.info("Updating customer with id: {}", id);
        CustomerService service =
                COMMERCIAL.equalsIgnoreCase(customer.getCustomerType()) ? commercialService : personService;
        return service.updateCustomer(id, customer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "deleteCustomer", summary = "Delete Customer Record", description = """
                    Hard-deletes a customer row, trying the commercial store first and then the person \
                    store, and publishes a party-deleted fact for the removed record.
                    Use this tool only when a customer record must be physically removed; do not use it for \
                    duplicates — use mergeParties instead, whose MERGED status preserves history, since this \
                    deletion is not reversible.
                    Preconditions: a commercial or person party must exist for the supplied id.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a CUSTOMER_CUSTOMER_DELETE event and publishes a party-deleted customer fact.
                    Returns 404 when neither store holds a party for the supplied id.
                    """)
    @ApiResponse(responseCode = "204", description = "Customer deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @DeleteMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_DEACTIVATE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_DEACTIVATE + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "ID of the customer to delete", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        log.info("Deleting customer with id: {}", id);
        if (commercialService.deleteCustomer(id) || personService.deleteCustomer(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
