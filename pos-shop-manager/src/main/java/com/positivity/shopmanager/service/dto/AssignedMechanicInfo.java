package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.MechanicRole;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Summary of one mechanic included in an assignment response. */
@Value
@Builder
public class AssignedMechanicInfo {
    UUID mechanicId;
    MechanicRole role;
}
