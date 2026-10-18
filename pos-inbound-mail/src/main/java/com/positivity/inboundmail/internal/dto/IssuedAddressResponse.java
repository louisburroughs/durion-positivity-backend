package com.positivity.inboundmail.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * An intake address of the caller's tenant. The {@code address} is an unguessable capability: callers
 * show it to the shop and never log it, copy it to an event or put it in a URL.
 *
 * @param addressId the registry's id for the address; what logs and events refer to instead of the text
 * @param address the full lower-case address, {@code bills-<26 base32 characters>@<intake domain>}
 * @param issuedAt when the address was issued
 */
@Schema(description = "An intake address of the caller's tenant")
public record IssuedAddressResponse(
        @Schema(description = "Registry id of the address; logs and events use it instead of the address text") @NonNull
        UUID addressId,

        @Schema(
                description = "Full lower-case intake address; a capability, never to be logged",
                example = "bills-abcdefghijklmnopqrstuvwxyz@mail.example.com")
        @NonNull
        String address,

        @Schema(description = "When the address was issued") @NonNull
        Instant issuedAt) {}
