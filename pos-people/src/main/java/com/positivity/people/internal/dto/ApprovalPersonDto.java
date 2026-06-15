package com.positivity.people.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApprovalPersonDto {

    private UUID personId;
    private String displayName;
    private String employeeNumber;
}
