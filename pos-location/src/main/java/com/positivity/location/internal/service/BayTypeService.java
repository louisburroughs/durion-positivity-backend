package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.BayTypeResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Read model of the bay types and their default specialty services (#2247). */
public interface BayTypeService {

    /**
     * Every {@link com.positivity.location.internal.enums.BayType}, in enum order, with the caller's
     * tenant's default specialty services for it. A type with no defaults carries empty lists.
     */
    @NonNull
    List<BayTypeResponse> listBayTypes();
}
