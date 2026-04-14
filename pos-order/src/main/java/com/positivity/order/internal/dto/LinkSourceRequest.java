package com.positivity.order.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LinkSourceRequest {

    @NotBlank
    private String sourceType;

    @NotBlank
    private String sourceId;
}
