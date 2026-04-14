package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /v1/permissions/decode.
 *
 * @param permBits Base64URL-encoded BitSet from an access token
 * @param permVer  catalog version used when encoding permBits
 */
public record PermissionDecodeRequest(
        @JsonProperty("perm_bits") @NotNull String permBits,
        @JsonProperty("perm_ver") int permVer) {}
