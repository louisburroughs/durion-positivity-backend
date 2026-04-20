package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.CatalogBulkIngestRecord;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.service.ProductMasterDataService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/catalog")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Catalog Bulk Ingest API", description = "Bulk import catalog products")
public class CatalogBulkIngestController extends AbstractBulkIngestController<CatalogBulkIngestRecord> {

    private static final String DEFAULT_UNIT_OF_MEASURE = "EA";
    private final ProductMasterDataService productMasterDataService;

    @Override
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @EmitEvent(id = "CATALOG_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @Valid @RequestBody @NonNull BulkIngestRequest<CatalogBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CatalogBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            CatalogBulkIngestRecord record = request.getRecords().get(i);
            try {
                var created = productMasterDataService.createProduct(toProductCreateRequest(record));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest catalog record at row {}: {}", i, exception.getMessage());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode("CATALOG_INGEST_FAILED")
                        .errorMessage(errorMessage(exception))
                        .build());
                failureCount++;
            }
        }

        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();
    }

    private ProductCreateRequestDto toProductCreateRequest(@NonNull CatalogBulkIngestRecord record) {
        ProductCreateRequestDto request = new ProductCreateRequestDto();
        request.setSku(record.getSku());
        request.setUpc(record.getUpc());
        request.setName(record.getName());
        request.setDescription(firstNonBlank(record.getDescription(), record.getName()));
        request.setUnitOfMeasure(DEFAULT_UNIT_OF_MEASURE);
        request.setMpn(firstNonBlank(record.getSku(), record.getName()));
        return request;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String errorMessage(@NonNull Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Catalog ingest failed" : message;
    }
}
