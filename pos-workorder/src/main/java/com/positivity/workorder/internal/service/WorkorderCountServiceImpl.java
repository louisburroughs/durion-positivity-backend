package com.positivity.workorder.internal.service;

import com.positivity.shared.dto.CountResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkorderCountServiceImpl implements WorkorderCountService {

    private final WorkorderRepository workorderRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull CountResponse countByStatuses(@Nullable Set<WorkorderStatus> statuses) {
        Set<WorkorderStatus> target = (statuses == null || statuses.isEmpty())
                ? EnumSet.allOf(WorkorderStatus.class)
                : EnumSet.copyOf(statuses);

        List<CountResponse.CountGroup> groups = workorderRepository.countGroupedByStatus(target).stream()
                .map(row -> new CountResponse.CountGroup(row.getStatus().name(), row.getCount()))
                .sorted(Comparator.comparing(CountResponse.CountGroup::key))
                .toList();

        long total = groups.stream().mapToLong(CountResponse.CountGroup::count).sum();
        return new CountResponse(total, groups);
    }
}
