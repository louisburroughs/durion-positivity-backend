package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ColumnMappingUpdateRequest;
import com.positivity.bulkloader.internal.entity.BulkLoadColumnMapping;
import com.positivity.bulkloader.internal.repository.BulkLoadColumnMappingRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ColumnMappingServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    BulkLoadColumnMappingRepository mappingRepository;

    @InjectMocks
    ColumnMappingServiceImpl service;

    @Test
    void getMappingsForJob_returnsMappedResponses() {
        BulkLoadColumnMapping mapping = columnMapping(MAPPING_ID, JOB_ID, "sku", "productSku");

        when(mappingRepository.findByJobId(JOB_ID)).thenReturn(List.of(mapping));

        List<ColumnMappingResponse> responses = service.getMappingsForJob(JOB_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getJobId()).isEqualTo(JOB_ID);
        assertThat(responses.get(0).getSourceColumn()).isEqualTo("sku");
        assertThat(responses.get(0).getTargetField()).isEqualTo("productSku");
    }

    @Test
    void getMappingsForJob_whenNoMappings_returnsEmptyList() {
        when(mappingRepository.findByJobId(JOB_ID)).thenReturn(List.of());

        List<ColumnMappingResponse> responses = service.getMappingsForJob(JOB_ID);

        assertThat(responses).isEmpty();
    }

    @Test
    void approveMappings_deletesOldAndSavesNew() {
        ColumnMappingUpdateRequest update = new ColumnMappingUpdateRequest();
        update.setSourceColumn("name");
        update.setTargetField("productName");

        ColumnMappingApproveRequest request = new ColumnMappingApproveRequest();
        request.setMappings(List.of(update));

        BulkLoadColumnMapping saved = columnMapping(MAPPING_ID, JOB_ID, "name", "productName");
        saved.setOverriddenByUser(true);
        saved.setConfidence(1.0);

        when(mappingRepository.save(any(BulkLoadColumnMapping.class))).thenReturn(saved);

        List<ColumnMappingResponse> responses = service.approveMappings(JOB_ID, request);

        verify(mappingRepository).deleteByJobId(JOB_ID);
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSourceColumn()).isEqualTo("name");
        assertThat(responses.get(0).getOverriddenByUser()).isTrue();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BulkLoadColumnMapping columnMapping(UUID id, UUID jobId, String source, String target) {
        BulkLoadColumnMapping m = new BulkLoadColumnMapping();
        m.setId(id);
        m.setJobId(jobId);
        m.setSourceColumn(source);
        m.setTargetField(target);
        m.setConfidence(0.9);
        m.setOverriddenByUser(false);
        return m;
    }
}
