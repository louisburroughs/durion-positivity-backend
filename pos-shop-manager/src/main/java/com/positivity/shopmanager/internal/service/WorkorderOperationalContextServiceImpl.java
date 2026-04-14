package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.service.SourceEligibilityService;
import com.positivity.shopmanager.service.WorkorderOperationalContextService;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkorderOperationalContextServiceImpl implements WorkorderOperationalContextService {

    private static final String SOURCE_TYPE_WORKORDER = "WORKORDER";
    private static final String LOCATION_ID = "locationId";
    private static final String WORKORDER_ID = "workorderId";

    private final SourceEligibilityService sourceEligibilityService;

    @Override
    @NonNull
    public Map<String, Object> getOperationalContext(
            @NonNull Long locationId, @NonNull Long workorderId, @Nullable String filters) {
        Map<String, String> parsedFilters = parseFilters(filters);
        String facilityId = String.valueOf(locationId);
        String workOrderRef = String.valueOf(workorderId);
        String workorderStatus = sourceEligibilityService.getWorkOrderStatus(workOrderRef, facilityId);
        String appointmentId =
                sourceEligibilityService.getExistingAppointmentId(SOURCE_TYPE_WORKORDER, workOrderRef, facilityId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(LOCATION_ID, locationId);
        response.put(WORKORDER_ID, workorderId);
        response.put("filtersApplied", parsedFilters);

        if (!matchesFilters(parsedFilters, locationId, workorderId, workorderStatus, appointmentId)) {
            response.put("results", Collections.emptyList());
            return response;
        }

        Map<String, Object> operationalContext = new LinkedHashMap<>();
        operationalContext.put(LOCATION_ID, locationId);
        operationalContext.put(WORKORDER_ID, workorderId);
        operationalContext.put("workorderStatus", workorderStatus);
        operationalContext.put("appointmentId", appointmentId);
        operationalContext.put("appointmentLinked", appointmentId != null);

        response.put("results", List.of(operationalContext));
        return response;
    }

    private Map<String, String> parseFilters(@Nullable String filters) {
        if (filters == null || filters.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        Arrays.stream(filters.split("[,;&]"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    String[] split = token.split("[:=]", 2);
                    if (split.length == 2 && !split[0].isBlank()) {
                        parsed.put(split[0].trim(), split[1].trim());
                    }
                });
        return parsed;
    }

    private boolean matchesFilters(
            @NonNull Map<String, String> filters,
            @NonNull Long locationId,
            @NonNull Long workorderId,
            @Nullable String workorderStatus,
            @Nullable String appointmentId) {
        if (filters.isEmpty()) {
            return true;
        }

        String locationFilter = filters.get(LOCATION_ID);
        if (locationFilter != null && !locationFilter.equals(String.valueOf(locationId))) {
            return false;
        }

        String workorderFilter = filters.get(WORKORDER_ID);
        if (workorderFilter != null && !workorderFilter.equals(String.valueOf(workorderId))) {
            return false;
        }

        String statusFilter = filters.get("status");
        if (statusFilter != null && (workorderStatus == null || !statusFilter.equalsIgnoreCase(workorderStatus))) {
            return false;
        }

        String appointmentLinkedFilter = filters.get("appointmentLinked");
        if (appointmentLinkedFilter != null) {
            boolean expected = Boolean.parseBoolean(appointmentLinkedFilter);
            if (expected != (appointmentId != null)) {
                return false;
            }
        }

        String appointmentFilter = filters.get("appointmentId");
        return appointmentFilter == null || appointmentFilter.equals(appointmentId);
    }
}
