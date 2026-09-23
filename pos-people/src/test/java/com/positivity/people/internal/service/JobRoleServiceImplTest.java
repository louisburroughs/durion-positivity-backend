package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.CreateJobRoleRequest;
import com.positivity.people.internal.dto.JobRoleDto;
import com.positivity.people.internal.entity.JobRole;
import com.positivity.people.internal.exception.DuplicateJobRoleCodeException;
import com.positivity.people.internal.repository.JobRoleRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The tenant's own job-role list (durion#2157): a read and an add, both scoped by tenant. */
@ExtendWith(MockitoExtension.class)
class JobRoleServiceImplTest {

    @Mock
    private JobRoleRepository jobRoleRepository;

    @InjectMocks
    private JobRoleServiceImpl service;

    private static JobRole jobRole(String code, String name) {
        return JobRole.builder()
                .id(UUID.nameUUIDFromBytes(code.getBytes()))
                .code(code)
                .name(name)
                .description("desc")
                .active(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("listActive: ordered by name, mapped in full")
    void listActiveMapsEveryField() {
        when(jobRoleRepository.findAllByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(jobRole("LEAD_TECH", "Lead Technician")));

        List<JobRoleDto> jobRoles = service.listActive();

        assertThat(jobRoles).hasSize(1);
        JobRoleDto dto = jobRoles.get(0);
        assertThat(dto.getCode()).isEqualTo("LEAD_TECH");
        assertThat(dto.getName()).isEqualTo("Lead Technician");
        assertThat(dto.getDescription()).isEqualTo("desc");
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    @DisplayName("create: saves a new job role and returns it")
    void createSavesAndReturns() {
        when(jobRoleRepository.existsByCodeIgnoreCase("LEAD_TECH")).thenReturn(false);
        when(jobRoleRepository.save(any(JobRole.class))).thenAnswer(invocation -> {
            JobRole toSave = invocation.getArgument(0);
            toSave.setId(UUID.nameUUIDFromBytes("LEAD_TECH".getBytes()));
            toSave.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            toSave.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return toSave;
        });

        CreateJobRoleRequest request = new CreateJobRoleRequest();
        request.setCode("  LEAD_TECH  ");
        request.setName(" Lead Technician ");

        JobRoleDto dto = service.create(request);

        ArgumentCaptor<JobRole> captor = ArgumentCaptor.forClass(JobRole.class);
        verify(jobRoleRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("LEAD_TECH");
        assertThat(captor.getValue().getName()).isEqualTo("Lead Technician");
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(dto.getCode()).isEqualTo("LEAD_TECH");
    }

    @Test
    @DisplayName("create: a code already used in this tenant fails loudly rather than colliding")
    void createRejectsADuplicateCode() {
        when(jobRoleRepository.existsByCodeIgnoreCase("LEAD_TECH")).thenReturn(true);

        CreateJobRoleRequest request = new CreateJobRoleRequest();
        request.setCode("LEAD_TECH");
        request.setName("Lead Technician");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateJobRoleCodeException.class)
                .hasMessageContaining("LEAD_TECH");
    }

    /**
     * durion#2157 code review fix: the pre-check is case-insensitive (V8__job_role_code_case
     * _insensitive_unique.sql now backs it with a matching database constraint), so a
     * differently-cased code must be rejected exactly like an exact repeat -- "lead_tech" is a
     * duplicate of an existing "LEAD_TECH" every bit as much as a second "LEAD_TECH" would be.
     * This pins the service's own lookup/rejection behaviour with a mocked repository; that the
     * database itself now refuses two rows differing only by case (job_role_tenant_code_ci_key)
     * cannot be exercised without a real Postgres and is left to CI/manual verification.
     */
    @Test
    @DisplayName("create: a code that differs only by case from an existing one is still a duplicate")
    void createRejectsADifferentlyCasedDuplicateCode() {
        when(jobRoleRepository.existsByCodeIgnoreCase("lead_tech")).thenReturn(true);

        CreateJobRoleRequest request = new CreateJobRoleRequest();
        request.setCode("lead_tech");
        request.setName("Lead Technician (lower-case)");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateJobRoleCodeException.class)
                .hasMessageContaining("lead_tech");

        verify(jobRoleRepository, never()).save(any(JobRole.class));
    }
}
