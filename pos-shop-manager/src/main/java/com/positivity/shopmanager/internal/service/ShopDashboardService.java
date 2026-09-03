package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Assembles the single-call shop manager dashboard for one location (#1658). */
public interface ShopDashboardService {

    /**
     * Builds the dashboard for {@code locationId}.
     *
     * @param locationId the shop location; must exist
     * @param date the day the unit roster is rendered as of; null defaults to the location's local
     *     today. It scopes the unit roster only — never the open-workorder list.
     * @return bays, mobile units and open workorders in one response
     */
    @NonNull
    ShopDashboardResponse getDashboard(@NonNull UUID locationId, @Nullable LocalDate date);
}
