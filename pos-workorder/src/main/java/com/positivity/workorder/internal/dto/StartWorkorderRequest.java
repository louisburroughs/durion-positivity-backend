package com.positivity.workorder.internal.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartWorkorderRequest {
    private UUID userId;
    private String reason;
}
