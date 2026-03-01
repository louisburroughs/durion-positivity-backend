package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.service.WipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stub implementation of {@link WipService}.
 *
 * <p>
 * <strong>NOTE:</strong> Story #14 full implementation pending. Both methods
 * intentionally throw {@link UnsupportedOperationException} to make the RED
 * phase explicit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WipServiceImpl implements WipService {

    @Override
    public Page<WorkorderStatusView> getWipWorkorders(@NonNull String locationId,
            boolean multiLocation,
            @NonNull Pageable pageable) {
        throw new UnsupportedOperationException("WIPServiceImpl.getWipWorkorders: not yet implemented");
    }

    @Override
    public WorkorderStatusDetail getWipDetail(@NonNull UUID workorderId) {
        throw new UnsupportedOperationException("WIPServiceImpl.getWipDetail: not yet implemented");
    }
}
