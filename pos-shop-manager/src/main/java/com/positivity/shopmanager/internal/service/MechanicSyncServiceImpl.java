package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.service.MechanicSyncService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class MechanicSyncServiceImpl implements MechanicSyncService {

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final HrIntegrationLogRepository hrIntegrationLogRepository;
    private final MechanicAuditLogRepository mechanicAuditLogRepository;
    private final Clock clock;

    @Override
    public void processHrEvent(@NonNull HrMechanicEvent event) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void reconcileFromHr() {
        throw new UnsupportedOperationException("not implemented");
    }
}
