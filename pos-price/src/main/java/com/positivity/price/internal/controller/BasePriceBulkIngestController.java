package com.positivity.price.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.BasePriceBulkIngestRecord;
import com.positivity.price.service.BasePriceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/v1/price")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('pricing:base_price:create')")
@Tag(name = "Price Bulk Ingest API", description = "Bulk import base price records")
public class BasePriceBulkIngestController extends AbstractBulkIngestController<BasePriceBulkIngestRecord> {

  private final BasePriceService basePriceService;

  @Override
  @PostMapping("/bulk-ingest")
  @PreAuthorize("hasAuthority('pricing:base_price:create')")
  @EmitEvent(id = "PRICE_BULK_INGEST", apiVersion = "1")
  public ResponseEntity<BulkIngestResponse> bulkIngest(
      @Valid @RequestBody @NonNull BulkIngestRequest<BasePriceBulkIngestRecord> request) {
    return super.bulkIngest(request);
  }

  @Override
  protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<BasePriceBulkIngestRecord> request) {
    List<BulkIngestResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    for (int i = 0; i < request.getRecords().size(); i++) {
      BasePriceBulkIngestRecord ingestRecord = request.getRecords().get(i);
      try {
        UUID productId = UUID.fromString(ingestRecord.getProductId());
        BigDecimal msrp = new BigDecimal(ingestRecord.getMsrp());
        Instant effectiveFrom = Instant.parse(ingestRecord.getEffectiveFrom());
        UUID savedProductId = basePriceService.saveBasePrice(productId, msrp, ingestRecord.getCurrency(),
            effectiveFrom);
        results.add(BulkIngestResult.builder()
            .rowIndex(i)
            .entityId(savedProductId)
            .success(true)
            .build());
        successCount++;
      } catch (Exception exception) {
        log.warn("Failed to ingest price record at row {}: {}", i, exception.getMessage(), exception);
        results.add(BulkIngestResult.builder()
            .rowIndex(i)
            .success(false)
            .errorCode("PRICE_INGEST_FAILED")
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

  private String errorMessage(@NonNull Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? "Price ingest failed" : message;
  }
}