package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Small helpers the work-item sources share (CAP:550 S19, #2515): the caller's permissions, and the item and its maps
 * built the one way, so no source writes an identifier into a reference.
 */
final class WorkItems {

    private WorkItems() {}

    /** Whether the caller holds {@code authority}; false without an authenticated caller. */
    static boolean has(@NonNull String authority) {
        return SecurityContextHelper.isAuthenticated() && SecurityContextHelper.hasAuthority(authority);
    }

    /** Whether the caller holds any of the authorities. */
    static boolean hasAny(@NonNull String... authorities) {
        for (String authority : authorities) {
            if (has(authority)) {
                return true;
            }
        }
        return false;
    }

    /** An ordered map of pairs {@code key, value, key, value...}; a pair whose value is null or blank is left out. */
    static @NonNull Map<String, String> references(@NonNull String... pairs) {
        Map<String, String> references = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String value = pairs[i + 1];
            if (value != null && !value.isBlank()) {
                references.put(pairs[i], value);
            }
        }
        return references;
    }

    /** An ordered map of routing identifiers {@code key, id, key, id...}; a null id is left out. */
    static @NonNull Map<String, UUID> routing(@NonNull Object... pairs) {
        Map<String, UUID> routing = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof UUID id) {
                routing.put((String) pairs[i], id);
            }
        }
        return routing;
    }

    /** The item {@code <TYPE>:<sourceId>}. {@code sourceId} is the source's identifier, as the item id's tail. */
    static @NonNull WorkItem item(
            @NonNull WorkItemType type,
            @NonNull String sourceId,
            @NonNull Map<String, String> references,
            @NonNull String reasonCode,
            @Nullable BigDecimal amount,
            @NonNull String status,
            @NonNull LocalDate occurredOn,
            @NonNull List<WorkItemAction> actions,
            @NonNull List<WorkItemPostingLine> postingPreview,
            @NonNull Map<String, UUID> routing) {
        return new WorkItem(
                type.name() + ":" + sourceId,
                type,
                type.area(),
                references,
                reasonCode,
                amount,
                status,
                occurredOn,
                actions,
                postingPreview,
                routing);
    }
}
