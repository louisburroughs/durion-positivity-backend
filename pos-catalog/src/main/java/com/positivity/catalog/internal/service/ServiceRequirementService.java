package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.ServiceRequirementsRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Declares the skills a service requires, per vehicle GVWR class (CAP-329). */
public interface ServiceRequirementService {

    /**
     * Replace-set the service's skill requirements and publish the service anew. An empty list
     * declares the service unconstrained (the profile header is written with no children).
     *
     * @throws com.positivity.catalog.internal.exception.CatalogNotFoundException when the service
     *     does not exist
     * @throws com.positivity.catalog.internal.exception.CatalogUnprocessableException when a skill
     *     id is not in the registry replica ({@code SKILL_UNKNOWN}), has been retired ({@code
     *     SKILL_RETIRED}), is named twice ({@code SKILL_DUPLICATE}), or its class range is malformed
     *     or lies outside the skill's own range ({@code SKILL_CLASS_RANGE_INVALID})
     */
    @NonNull
    ServiceDto setRequirements(
            @NonNull UUID serviceId, @NonNull ServiceRequirementsRequest request, @NonNull String actor);
}
