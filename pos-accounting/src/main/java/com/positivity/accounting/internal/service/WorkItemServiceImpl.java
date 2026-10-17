package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemsResponse;
import com.positivity.accounting.internal.enums.WorkItemArea;
import com.positivity.accounting.internal.enums.WorkItemType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the to-do list on every request from the four area sources, with no table (CAP:550 S19, #2515): the items
 * the caller may act on, counted per area before the area filter and paging, sorted oldest first and paged by an
 * opaque cursor ({@link WorkItemPaging}). A projection table is a follow-up if a load test shows this read above its
 * budget; it is not part of this story.
 *
 * <p>The gauge {@value #GAUGE}{@code {type}} holds, for each type, the count the last read showed its caller. It is a
 * per-caller figure and so a sample, not a tenant total.
 */
@Service
@Transactional(readOnly = true)
public class WorkItemServiceImpl implements WorkItemService {

    /** The gauge of the caller-visible count per item type. */
    public static final String GAUGE = "accounting.work_items.count";

    private final BillWorkItemSource bills;
    private final PaymentWorkItemSource payments;
    private final CashWorkItemSource cash;
    private final BankWorkItemSource bank;
    private final WorkItemPaging paging;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Clock clock;
    private final Map<WorkItemType, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    public WorkItemServiceImpl(
            BillWorkItemSource bills,
            PaymentWorkItemSource payments,
            CashWorkItemSource cash,
            BankWorkItemSource bank,
            WorkItemPaging paging,
            AccountingCalendarZoneResolver zoneResolver,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.bills = bills;
        this.payments = payments;
        this.cash = cash;
        this.bank = bank;
        this.paging = paging;
        this.zoneResolver = zoneResolver;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        for (WorkItemType type : WorkItemType.values()) {
            AtomicLong value = new AtomicLong();
            gaugeValues.put(type, value);
            if (registry != null) {
                Gauge.builder(GAUGE, value, AtomicLong::get)
                        .tag("type", type.name())
                        .description("Work items of the type the last read showed its caller")
                        .register(registry);
            }
        }
    }

    @Override
    public @NonNull WorkItemsResponse list(@Nullable WorkItemArea area, @Nullable String cursor, @Nullable Integer limit) {
        int size = paging.limit(limit);
        String scope = area == null ? "ALL" : area.name();
        WorkItemPaging.Key after = paging.decode(cursor, scope);

        LocalDate today = zoneResolver.today();
        List<WorkItem> visible = new ArrayList<>();
        visible.addAll(bills.items(today));
        visible.addAll(payments.items());
        visible.addAll(cash.items(today));
        visible.addAll(bank.items());

        Map<WorkItemArea, Integer> counts = new EnumMap<>(WorkItemArea.class);
        for (WorkItemArea each : WorkItemArea.values()) {
            counts.put(each, 0);
        }
        Map<WorkItemType, Long> perType = new EnumMap<>(WorkItemType.class);
        for (WorkItem item : visible) {
            counts.merge(item.area(), 1, Integer::sum);
            perType.merge(item.type(), 1L, Long::sum);
        }
        gaugeValues.forEach((type, value) -> value.set(perType.getOrDefault(type, 0L)));

        List<WorkItem> listed =
                area == null ? visible : visible.stream().filter(item -> item.area() == area).toList();
        WorkItemPaging.Slice<WorkItem> page =
                paging.slice(listed, WorkItem::occurredOn, WorkItem::itemId, scope, after, size);
        return new WorkItemsResponse(page.items(), page.nextCursor(), counts, Instant.now(clock));
    }
}
