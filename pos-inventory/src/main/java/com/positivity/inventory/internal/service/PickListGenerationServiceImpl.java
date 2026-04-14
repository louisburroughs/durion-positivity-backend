package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.picklist.GeneratePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.service.PickListGenerationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class PickListGenerationServiceImpl implements PickListGenerationService {

    private final PickListRepository pickListRepository;
    private final PickTaskRepository pickTaskRepository;

    @Override
    public @NonNull PickListResponse generatePickList(@NonNull GeneratePickListRequest request) {
        throw new UnsupportedOperationException("Scaffold placeholder — not implemented");
    }

    @Override
    public @NonNull PickListResponse getPickList(@NonNull UUID pickListId) {
        throw new UnsupportedOperationException("Scaffold placeholder — not implemented");
    }
}
