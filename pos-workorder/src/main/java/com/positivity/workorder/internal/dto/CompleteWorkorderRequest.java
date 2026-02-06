package com.positivity.workorder.internal.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteWorkorderRequest {
    private UUID userId;
    private String completionNotes;
}
