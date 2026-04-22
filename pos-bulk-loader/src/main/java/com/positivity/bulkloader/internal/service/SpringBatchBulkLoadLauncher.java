package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.service.BulkLoadBatchLauncher;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SpringBatchBulkLoadLauncher implements BulkLoadBatchLauncher {

  private final JobLauncher jobLauncher;
  private final Job catalogBulkLoadJob;
  private final Job customerBulkLoadJob;
  private final Job peopleBulkLoadJob;
  private final Job priceBulkLoadJob;
  private final Job vehicleBulkLoadJob;
  private final Job vehicleFitmentBulkLoadJob;

  public SpringBatchBulkLoadLauncher(
      JobLauncher jobLauncher,
      @Qualifier("catalogBulkLoadJob") Job catalogBulkLoadJob,
      @Qualifier("customerBulkLoadJob") Job customerBulkLoadJob,
      @Qualifier("peopleBulkLoadJob") Job peopleBulkLoadJob,
      @Qualifier("priceBulkLoadJob") Job priceBulkLoadJob,
      @Qualifier("vehicleBulkLoadJob") Job vehicleBulkLoadJob,
      @Qualifier("vehicleFitmentBulkLoadJob") Job vehicleFitmentBulkLoadJob) {
    this.jobLauncher = jobLauncher;
    this.catalogBulkLoadJob = catalogBulkLoadJob;
    this.customerBulkLoadJob = customerBulkLoadJob;
    this.peopleBulkLoadJob = peopleBulkLoadJob;
    this.priceBulkLoadJob = priceBulkLoadJob;
    this.vehicleBulkLoadJob = vehicleBulkLoadJob;
    this.vehicleFitmentBulkLoadJob = vehicleFitmentBulkLoadJob;
  }

  @Override
  public void launch(@NonNull BulkLoadJob job) {
    if (!StringUtils.hasText(job.getOriginalFilePath())) {
      throw new IllegalArgumentException("Bulk load job must include a persisted storage path before launch");
    }

    try {
      jobLauncher.run(
          resolveJob(job.getDomainType()),
          new JobParametersBuilder()
              .addString("jobId", job.getId().toString())
              .addString("storagePath", job.getOriginalFilePath())
              .addLong("launchEpochMillis", System.currentTimeMillis())
              .toJobParameters());
      log.info("Launched batch job for bulk load job {} domain {}", job.getId(), job.getDomainType());
    } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
        | InvalidJobParametersException ex) {
      throw new IllegalStateException(
          "Failed to launch Spring Batch job for bulk load job %s".formatted(job.getId()), ex);
    }
  }

  private Job resolveJob(DomainType domainType) {
    return switch (domainType) {
      case CATALOG_PRODUCT -> catalogBulkLoadJob;
      case CUSTOMER -> customerBulkLoadJob;
      case PERSON -> peopleBulkLoadJob;
      case BASE_PRICE -> priceBulkLoadJob;
      case VEHICLE -> vehicleBulkLoadJob;
      case VEHICLE_FITMENT -> vehicleFitmentBulkLoadJob;
      case INVENTORY_STOCK_COUNT, LOCATION -> throw new IllegalStateException(
          "No Spring Batch job is configured for domain type: " + domainType);
    };
  }
}