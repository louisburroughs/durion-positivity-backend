package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /v1/permissions/decode.
 *
 * @param permBits Base64URL-encoded BitSet from an access token
 * @param permVer  catalog version used when encoding permBits
 */
@Schema(description = "Request to decode encoded permission bits into permission code strings")
public record PermissionDecodeRequest(
        @JsonProperty("perm_bits")
        @NotNull
        @Schema(
                description = "Base64URL-encoded BitSet of permission bits taken from an access token",
                example = "AQID",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String permBits,

        @JsonProperty("perm_ver")
        @Schema(
                description = "Catalog version that was in effect when permBits was encoded",
                example = "7",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int permVer) {}
