package com.positivity.workorder.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeclineChangeRequestDTO {
    private String approvalNote;
}
