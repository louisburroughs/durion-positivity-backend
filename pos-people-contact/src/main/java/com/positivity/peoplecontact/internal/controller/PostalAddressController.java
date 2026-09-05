package com.positivity.peoplecontact.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.internal.security.PeopleContactPermissions;
import com.positivity.peoplecontact.internal.service.PostalAddressService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured international postal addresses for person and organization parties (FI-4, #1135).
 * pos-people-contact is the postal-address authority for both party kinds; organization ids are
 * external party references (pos-customer commercial parties) stored verbatim.
 */
@Tag(name = "Postal Address API", description = "Structured postal addresses for person and organization parties")
@RestController
@RequiredArgsConstructor
public class PostalAddressController {

    private final PostalAddressService postalAddressService;

    @Operation(operationId = "getPersonPostalAddress", summary = "Get a Person's Postal Address", description = """
                    Returns the single structured postal address on file for a person; pos-people-contact is the \
                    postal-address authority for person parties (FI-4).
                    Use this tool when reading a person's mailing address; use getOrganizationPostalAddress \
                    instead for CRM organization parties.
                    Preconditions: an address must already have been stored for the person with \
                    putPersonPostalAddress.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_PERSON_ADDRESS_GET audit event; no state changes.
                    Returns 404 when no address is on file for the person.
                    """)
    @ApiResponse(responseCode = "200", description = "Address found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No address on file for the person.",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_GET", apiVersion = "1")
    @GetMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_VIEW + "')")
    public ResponseEntity<PostalAddressDto> getPersonAddress(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        return postalAddressService
                .getAddress(PartyType.PERSON, personId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "putPersonPostalAddress",
            summary = "Create or Replace Person Postal Address",
            description = """
                    Creates or replaces the single structured postal address for a person; the shape is \
                    country-agnostic, with free-text region and an optional postalCode.
                    Use this tool for person mailing addresses; use putOrganizationPostalAddress instead for CRM \
                    organization parties, and do not use replaceContactPoints, which manages email and phone \
                    channels.
                    Preconditions: the person record must exist; any previously stored address is overwritten in \
                    place.
                    Required inputs: line1 and an ISO 3166-1 alpha-2 countryCode (stored upper-case); line2, \
                    city, region and postalCode are optional free text, trimmed, with blanks stored as null.
                    Emits a PEOPLE_CONTACT_PERSON_ADDRESS_PUT event and queues a person.updated identity fact \
                    carrying the new address.
                    Returns 200 with the stored address, 404 when the person does not exist, and 422 when line1 \
                    is blank or countryCode is not a valid ISO 3166-1 alpha-2 code.
                    """)
    @ApiResponse(responseCode = "200", description = "Address stored.")
    @ApiResponse(
            responseCode = "404",
            description = "Person not found.",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "line1 missing or countryCode not a valid ISO 3166-1 alpha-2 code.",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_PUT", apiVersion = "1")
    @PutMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_EDIT + "')")
    public ResponseEntity<PostalAddressDto> putPersonAddress(
            @Parameter(description = "Person id") @PathVariable UUID personId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Country-agnostic structured postal address that replaces any address"
                                    + " already on file for the person.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Japanese address", value = """
                                                                    {"line1":"1-2-3 Ginza",
                                                                     "line2":"Chuo-ku",
                                                                     "city":"Tokyo",
                                                                     "region":"Tokyo",
                                                                     "postalCode":"104-0061",
                                                                     "countryCode":"JP"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostalAddressDto address) {
        return ResponseEntity.ok(postalAddressService.putAddress(PartyType.PERSON, personId, address));
    }

    @Operation(
            operationId = "deletePersonPostalAddress",
            summary = "Delete a Person's Postal Address",
            description = """
                    Removes the postal address on file for a person; the operation is idempotent and succeeds \
                    when none exists.
                    Use this tool to clear a person's mailing address; do not use deletePerson, which removes \
                    the entire identity record.
                    Preconditions: none; a missing address is treated as already deleted.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_PERSON_ADDRESS_DELETE event, and queues a person.updated identity \
                    fact only when an address actually existed.
                    Returns 204 whether or not an address was on file.
                    """)
    @ApiResponse(responseCode = "204", description = "Address removed (or none existed).")
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_DELETE", apiVersion = "1")
    @DeleteMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_EDIT + "')")
    public ResponseEntity<Void> deletePersonAddress(@Parameter(description = "Person id") @PathVariable UUID personId) {
        postalAddressService.deleteAddress(PartyType.PERSON, personId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "getOrganizationPostalAddress",
            summary = "Get an Organization's Postal Address",
            description = """
                    Returns the structured postal address on file for a CRM organization party; the organization \
                    id is an external pos-customer party reference stored verbatim.
                    Use this tool when reading an organization's mailing address; use getPersonPostalAddress \
                    instead for person parties.
                    Preconditions: an address must already have been stored for the organization with \
                    putOrganizationPostalAddress.
                    Required inputs: organizationId (the pos-customer commercial party UUID) as a path \
                    parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_ORG_ADDRESS_GET audit event; no state changes.
                    Returns 404 when no address is on file for the organization.
                    """)
    @ApiResponse(responseCode = "200", description = "Address found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No address on file for the organization.",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_GET", apiVersion = "1")
    @GetMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ORGANIZATION_VIEW + "')")
    public ResponseEntity<PostalAddressDto> getOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId) {
        return postalAddressService
                .getAddress(PartyType.ORGANIZATION, organizationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "putOrganizationPostalAddress",
            summary = "Create or Replace Organization Postal Address",
            description = """
                    Creates or replaces the single structured postal address for a CRM organization party.
                    Use this tool for organization mailing addresses; use putPersonPostalAddress instead for \
                    person parties.
                    Preconditions: none in this module; the organization id is an external pos-customer party \
                    reference stored verbatim without an existence check.
                    Required inputs: organizationId (UUID) as a path parameter, plus line1 and an ISO 3166-1 \
                    alpha-2 countryCode in the body; line2, city, region and postalCode are optional free text.
                    Emits a PEOPLE_CONTACT_ORG_ADDRESS_PUT event and an organization-address-updated fact for \
                    downstream consumers.
                    Returns 200 with the stored address, and 422 when line1 is blank or countryCode is not a \
                    valid ISO 3166-1 alpha-2 code.
                    """)
    @ApiResponse(responseCode = "200", description = "Address stored.")
    @ApiResponse(
            responseCode = "422",
            description = "line1 missing or countryCode not a valid ISO 3166-1 alpha-2 code.",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_PUT", apiVersion = "1")
    @PutMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ORGANIZATION_EDIT + "')")
    public ResponseEntity<PostalAddressDto> putOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Country-agnostic structured postal address that replaces any address"
                                    + " already on file for the organization party.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "US business address", value = """
                                                                    {"line1":"400 Commerce Way",
                                                                     "line2":"Suite 210",
                                                                     "city":"Austin",
                                                                     "region":"TX",
                                                                     "postalCode":"78701",
                                                                     "countryCode":"US"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostalAddressDto address) {
        return ResponseEntity.ok(postalAddressService.putAddress(PartyType.ORGANIZATION, organizationId, address));
    }

    @Operation(
            operationId = "deleteOrganizationPostalAddress",
            summary = "Delete an Organization's Postal Address",
            description = """
                    Removes the postal address on file for a CRM organization party; the operation is idempotent \
                    and succeeds when none exists.
                    Use this tool to clear an organization's mailing address; use deletePersonPostalAddress \
                    instead for person parties.
                    Preconditions: none; a missing address is treated as already deleted.
                    Required inputs: organizationId (UUID) as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_ORG_ADDRESS_DELETE event, and an organization-address-removed fact \
                    is published only when an address actually existed.
                    Returns 204 whether or not an address was on file.
                    """)
    @ApiResponse(responseCode = "204", description = "Address removed (or none existed).")
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_DELETE", apiVersion = "1")
    @DeleteMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ORGANIZATION_EDIT + "')")
    public ResponseEntity<Void> deleteOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId) {
        postalAddressService.deleteAddress(PartyType.ORGANIZATION, organizationId);
        return ResponseEntity.noContent().build();
    }
}
