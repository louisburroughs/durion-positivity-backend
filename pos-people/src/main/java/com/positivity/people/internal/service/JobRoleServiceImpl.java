package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.CreateJobRoleRequest;
import com.positivity.people.internal.dto.JobRoleDto;
import com.positivity.people.internal.entity.JobRole;
import com.positivity.people.internal.exception.DuplicateJobRoleCodeException;
import com.positivity.people.internal.repository.JobRoleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobRoleServiceImpl implements JobRoleService {

    private final JobRoleRepository jobRoleRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<JobRoleDto> listActive() {
        return jobRoleRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(JobRoleServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull JobRoleDto create(@NonNull CreateJobRoleRequest request) {
        String code = request.getCode().trim();
        if (jobRoleRepository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateJobRoleCodeException(code);
        }

        JobRole jobRole = JobRole.builder()
                .code(code)
                .name(request.getName().trim())
                .description(request.getDescription())
                .active(true)
                .build();
        return toDto(jobRoleRepository.save(jobRole));
    }

    private static JobRoleDto toDto(JobRole jobRole) {
        return JobRoleDto.builder()
                .id(jobRole.getId())
                .code(jobRole.getCode())
                .name(jobRole.getName())
                .description(jobRole.getDescription())
                .active(jobRole.isActive())
                .createdAt(jobRole.getCreatedAt())
                .updatedAt(jobRole.getUpdatedAt())
                .build();
    }
}
