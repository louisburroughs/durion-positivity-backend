package com.positivity.inventory.internal.dto.reservation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromoteAllocationRequest {

    private String hardenedReason;
}
