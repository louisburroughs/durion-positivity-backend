package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ShortageResolutionService {

    @NonNull
    ShortageResolutionResultDto resolveShortage(@NonNull ShortageResolveRequest request);

    @NonNull
    List<ShortageOptionDto> listShortageOptions(@NonNull UUID allocationId);
}
