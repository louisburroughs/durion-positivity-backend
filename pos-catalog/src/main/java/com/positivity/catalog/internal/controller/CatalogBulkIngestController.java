package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.CatalogBulkIngestRecord;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.ProductMasterDataService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"catalog:product:create"})
@RequestMapping("/v1/catalog")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('" + CatalogPermissions.PRODUCT_CREATE + "')")
@Tag(name = "Catalog Bulk Ingest API", description = "Bulk import catalog products")
public class CatalogBulkIngestController extends AbstractBulkIngestController<CatalogBulkIngestRecord> {

    private static final String DEFAULT_UNIT_OF_MEASURE = "EA";
    private final ProductMasterDataService productMasterDataService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + CatalogPermissions.PRODUCT_CREATE + "')")
    @Operation(operationId = "bulkIngestCatalogProducts", summary = "Bulk Import Catalog Products", description = """
            Imports a batch of catalog products in one call, creating each record through the same governed \
            path as createProduct and reporting a per-row success or failure verdict.
            Use this tool to load many products at once; do not use createProduct, which registers a single \
            product and surfaces its errors as HTTP statuses rather than row results.
            Preconditions: each row's SKU must not already exist (case-insensitive), or that row fails with \
            CATALOG_INGEST_FAILED while the rest of the batch proceeds.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            unitOfMeasure defaults to EA, description defaults to the name, and mpn falls back to the sku \
            then the name. manufacturerName, manufacturerBrand, countryOfOrigin, and type are carried onto \
            the product (the manufacturer fields also travel on the product fact for replica consumers), \
            while price, categoryName and subcategoryName are accepted but ignored until pricing and \
            category-by-name resolution land.
            Emits a CATALOG_BULK_INGEST event; each successful row also publishes a product fact and \
            invalidates the product-detail cache.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            """)
    @EmitEvent(id = "CATALOG_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch of product records to create for one ingest job scoped to a" + " location.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two-product batch", value = """
                                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "records":[
                                                                       {"sku":"TIRE-AT-2657017","name":"All-Terrain Tire 265/70R17",
                                                                        "upc":"036121960222","mpn":"AT3-26570R17","unitOfMeasure":"EA"},
                                                                       {"sku":"WIP-22-PREM","name":"Premium Wiper Blade 22in"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<CatalogBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CatalogBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            CatalogBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                var created = productMasterDataService.createProduct(toProductCreateRequest(ingestRecord));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest catalog record at row {}: {}", i, exception.getMessage(), exception);
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

    private ProductCreateRequestDto toProductCreateRequest(@NonNull CatalogBulkIngestRecord ingestRecord) {
        // price, categoryName, and subcategoryName are still not supported: catalog
        // pricing and category-by-name resolution remain future waves.
        if (ingestRecord.getPrice() != null
                || ingestRecord.getCategoryName() != null
                || ingestRecord.getSubcategoryName() != null) {
            log.warn(
                    "CatalogBulkIngestController: price/categoryName/subcategoryName are ignored — not yet supported by catalog service");
        }
        ProductCreateRequestDto request = new ProductCreateRequestDto();
        request.setSku(ingestRecord.getSku());
        request.setUpc(ingestRecord.getUpc());
        request.setName(ingestRecord.getName());
        request.setDescription(firstNonBlank(ingestRecord.getDescription(), ingestRecord.getName()));
        request.setUnitOfMeasure(firstNonBlank(ingestRecord.getUnitOfMeasure(), DEFAULT_UNIT_OF_MEASURE));
        request.setMpn(
                firstNonBlank(ingestRecord.getMpn(), firstNonBlank(ingestRecord.getSku(), ingestRecord.getName())));
        request.setManufacturerName(ingestRecord.getManufacturerName());
        request.setManufacturerBrand(ingestRecord.getManufacturerBrand());
        request.setCountryOfOrigin(ingestRecord.getCountryOfOrigin());
        request.setType(ingestRecord.getType());
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
