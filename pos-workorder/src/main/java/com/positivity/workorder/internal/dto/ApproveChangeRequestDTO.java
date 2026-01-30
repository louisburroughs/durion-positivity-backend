package com.positivity.workorder.internal.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveChangeRequestDTO {
    private Long approvedBy;
    private String approvalNote;
}
