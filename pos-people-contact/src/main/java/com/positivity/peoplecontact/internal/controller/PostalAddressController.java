package com.positivity.peoplecontact.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.service.PostalAddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
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

    @Operation(
            summary = "Get a person's postal address",
            description = "Returns the person's structured postal address, or 404 when none is on file.")
    @ApiResponse(responseCode = "200", description = "Address found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No address on file for the person.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_GET", apiVersion = "1")
    @GetMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('people-contact:person:view')")
    public ResponseEntity<PostalAddressDto> getPersonAddress(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        return postalAddressService
                .getAddress(PartyType.PERSON, personId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create or replace a person's postal address",
            description = "Stores the structured, validated address. Only line1 and an ISO 3166-1 alpha-2"
                    + " countryCode are required — the shape is country-agnostic (region is free text,"
                    + " postalCode optional) so it works for any deployment locale.")
    @ApiResponse(responseCode = "200", description = "Address stored.")
    @ApiResponse(
            responseCode = "404",
            description = "Person not found.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_PUT", apiVersion = "1")
    @PutMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('people-contact:person:edit')")
    public ResponseEntity<PostalAddressDto> putPersonAddress(
            @Parameter(description = "Person id") @PathVariable UUID personId,
            @Valid @RequestBody PostalAddressDto address) {
        return ResponseEntity.ok(postalAddressService.putAddress(PartyType.PERSON, personId, address));
    }

    @Operation(
            summary = "Delete a person's postal address",
            description = "Removes the person's postal address; succeeds even when none is on file.")
    @ApiResponse(responseCode = "204", description = "Address removed (or none existed).")
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_ADDRESS_DELETE", apiVersion = "1")
    @DeleteMapping("/v1/people/{personId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('people-contact:person:edit')")
    public ResponseEntity<Void> deletePersonAddress(@Parameter(description = "Person id") @PathVariable UUID personId) {
        postalAddressService.deleteAddress(PartyType.PERSON, personId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get an organization's postal address",
            description = "Returns the organization party's structured postal address, or 404 when none is on"
                    + " file. The organization id is the party UUID owned by the CRM module.")
    @ApiResponse(responseCode = "200", description = "Address found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No address on file for the organization.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_GET", apiVersion = "1")
    @GetMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:view"})
    @PreAuthorize("hasAuthority('people-contact:organization:view')")
    public ResponseEntity<PostalAddressDto> getOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId) {
        return postalAddressService
                .getAddress(PartyType.ORGANIZATION, organizationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create or replace an organization's postal address",
            description = "Stores the structured, validated address for an organization party. The id is an"
                    + " external party reference (pos-customer commercial party) stored verbatim.")
    @ApiResponse(responseCode = "200", description = "Address stored.")
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_PUT", apiVersion = "1")
    @PutMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:edit"})
    @PreAuthorize("hasAuthority('people-contact:organization:edit')")
    public ResponseEntity<PostalAddressDto> putOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId,
            @Valid @RequestBody PostalAddressDto address) {
        return ResponseEntity.ok(postalAddressService.putAddress(PartyType.ORGANIZATION, organizationId, address));
    }

    @Operation(
            summary = "Delete an organization's postal address",
            description = "Removes the organization party's postal address; succeeds even when none is on file.")
    @ApiResponse(responseCode = "204", description = "Address removed (or none existed).")
    @EmitEvent(id = "PEOPLE_CONTACT_ORG_ADDRESS_DELETE", apiVersion = "1")
    @DeleteMapping("/v1/organizations/{organizationId}/postal-address")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:organization:edit"})
    @PreAuthorize("hasAuthority('people-contact:organization:edit')")
    public ResponseEntity<Void> deleteOrganizationAddress(
            @Parameter(description = "Organization party id") @PathVariable UUID organizationId) {
        postalAddressService.deleteAddress(PartyType.ORGANIZATION, organizationId);
        return ResponseEntity.noContent().build();
    }
}
