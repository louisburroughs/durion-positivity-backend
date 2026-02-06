package com.positivity.workorder.internal.dto;

import java.util.UUID;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveChangeRequestDTO {
    private UUID approvedBy;
    private String approvalNote;
}
