package com.positivity.inboundmail.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.dto.RotateAddressRequest;
import com.positivity.inboundmail.internal.service.AddressRegistry;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The intake address registry (CAP:550 S27). Called service to service by pos-accounting with {@code
 * X-Inbound-Mail-Secret} and the caller's {@code X-Tenant-Id}; the gateway has no route here.
 */
@RestController
@RequestMapping(value = "/inbound-mail/v1/addresses", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Inbound mail addresses", description = "The per-tenant intake address that vendor bills are emailed to")
@PreAuthorize("isAuthenticated()")
public class AddressController {

    private static final String ROTATE_EXAMPLE = """
            {"requestId":"01990000-0000-7000-8000-0000000000b1"}
            """;

    private final AddressRegistry addressRegistry;

    @PostMapping
    @EmitEvent(id = "INBOUND_MAIL_ADDRESS_ISSUE", apiVersion = "1")
    @Operation(operationId = "issueInboundMailAddress", summary = "Return or issue the tenant's intake address", description = """
            Returns the caller's tenant's ACTIVE intake address, the one shops email vendor bills to, and issues one \
            first when the tenant has none. Concurrent first calls agree on a single address.
            Use this tool to show a shop where to forward bills; do not use it to replace an address, call the \
            rotation endpoint instead, which retires the old address and issues a new one.
            Preconditions: the caller holds the shared secret and sends the tenant in X-Tenant-Id.
            Required inputs: none; the body is empty.
            Emits an INBOUND_MAIL_ADDRESS_ISSUE event.
            Returns 200 with the address id, the address text and when it was issued; the address is an \
            unguessable capability, so the caller never logs it or copies it into an event.
            """)
    @Parameter(
            in = ParameterIn.HEADER,
            name = TenantHeaders.HTTP_TENANT_ID,
            description = "The caller's bound tenant; without it the edge answers 401 TENANT_REQUIRED",
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "The tenant's ACTIVE address")
    @ApiResponse(
            responseCode = "401",
            description =
                    "Missing or wrong X-Inbound-Mail-Secret, the edge's own secret unset, or no tenant (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<IssuedAddressResponse> issue() {
        return ResponseEntity.ok(addressRegistry.issueOrGet());
    }

    @PostMapping(path = "/rotation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @EmitEvent(id = "INBOUND_MAIL_ADDRESS_ROTATE", apiVersion = "1")
    @Operation(operationId = "rotateInboundMailAddress", summary = "Retire the tenant's address and issue a new one", description = """
            Retires the tenant's ACTIVE intake address and issues a new one in one transaction. Mail sent to the \
            retired address is refused from then on.
            Use this tool when an address has leaked or is being abused; do not use it to look up the current \
            address, call the issue endpoint instead, which never changes it.
            Preconditions: the caller holds the shared secret and sends the tenant in X-Tenant-Id.
            Required inputs: requestId, the caller's own UUID for this rotation; replaying the same requestId returns \
            the address that request issued and never rotates twice.
            Emits an INBOUND_MAIL_ADDRESS_ROTATE event; the reason for the rotation is kept by the caller, not sent here.
            Returns 200 with the new address id, address text and issue time.
            """)
    @Parameter(
            in = ParameterIn.HEADER,
            name = TenantHeaders.HTTP_TENANT_ID,
            description = "The caller's bound tenant; without it the edge answers 401 TENANT_REQUIRED",
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", description = "The address this rotation issued (also on a replay)")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (VALIDATION_FAILED), for example a missing requestId",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "Missing or wrong X-Inbound-Mail-Secret, the edge's own secret unset, or no tenant (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<IssuedAddressResponse> rotate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The caller's idempotency key for this rotation.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = @ExampleObject(name = "Rotation", value = ROTATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    RotateAddressRequest request) {
        return ResponseEntity.ok(addressRegistry.rotate(request.requestId()));
    }
}
