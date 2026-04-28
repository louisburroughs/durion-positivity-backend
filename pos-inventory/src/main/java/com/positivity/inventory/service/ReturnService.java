package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ReturnService {

    @NonNull
    List<ReturnableItemDto> listReturnableItems(@NonNull UUID workorderId);

    @NonNull
    List<ReasonCodeDto> listReturnReasonCodes();

    @NonNull
    ReturnSubmissionResultDto submitToStock(@NonNull ReturnSubmitRequest request);
}
