package com.positivity.workorder.internal.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeclineChangeRequestDTO {
    private String approvalNote;
}
