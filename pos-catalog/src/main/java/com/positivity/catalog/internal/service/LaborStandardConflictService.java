package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.LaborStandardConflictDto;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Cross-source disagreement surfacing for curation (#1569 residual R2, sourcing plan Phase 3 item 3). */
public interface LaborStandardConflictService {

    /**
     * @param thresholdHours report a pair only when the two published times differ by more than
     *     this many hours; the tenths granularity of book time makes anything below 0.1 noise
     */
    @NonNull
    List<LaborStandardConflictDto> findConflicts(@NonNull BigDecimal thresholdHours);
}
