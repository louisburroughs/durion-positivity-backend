package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "java:S100", "java:S1192", "removal" })
class SpringBatchBulkLoadLauncherTest {

  @Mock
  JobLauncher jobLauncher;

  @Mock
  Job catalogBulkLoadJob;

  @Mock
  Job customerBulkLoadJob;

  @Mock
  Job peopleBulkLoadJob;

  @Mock
  Job priceBulkLoadJob;

  @Mock
  Job vehicleBulkLoadJob;

  @Mock
  Job vehicleFitmentBulkLoadJob;

  @Test
  void launch_whenVehicleDomain_usesVehicleJobAndStoragePathParameters() throws Exception {
    SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
        jobLauncher,
        catalogBulkLoadJob,
        customerBulkLoadJob,
        peopleBulkLoadJob,
        priceBulkLoadJob,
        vehicleBulkLoadJob,
        vehicleFitmentBulkLoadJob);
    BulkLoadJob job = new BulkLoadJob();
    UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000021");
    job.setId(jobId);
    job.setDomainType(DomainType.VEHICLE);
    job.setOriginalFilePath("00000000-0000-0000-0000-000000000021/vehicles.csv");
    job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000031"));
    job.setOperatorId("operator-vehicle");

    when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(null);

    launcher.launch(job);

    ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobLauncher).run(org.mockito.Mockito.same(vehicleBulkLoadJob), parametersCaptor.capture());
    JobParameters jobParameters = parametersCaptor.getValue();
    assertThat(jobParameters.getString("jobId")).isEqualTo(jobId.toString());
    assertThat(jobParameters.getString("storagePath"))
        .isEqualTo("00000000-0000-0000-0000-000000000021/vehicles.csv");
    assertThat(jobParameters.getString("locationId")).isEqualTo("00000000-0000-0000-0000-000000000031");
    assertThat(jobParameters.getString("operatorId")).isEqualTo("operator-vehicle");
    assertThat(jobParameters.getLong("launchEpochMillis")).isNotNull();
  }

  @Test
  void launch_whenLocationIdMissing_throwsIllegalArgumentException() {
    SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
        jobLauncher,
        catalogBulkLoadJob,
        customerBulkLoadJob,
        peopleBulkLoadJob,
        priceBulkLoadJob,
        vehicleBulkLoadJob,
        vehicleFitmentBulkLoadJob);
    BulkLoadJob job = new BulkLoadJob();
    job.setId(UUID.fromString("00000000-0000-0000-0000-000000000023"));
    job.setOperatorId("operator-vehicle");
    job.setDomainType(DomainType.VEHICLE);
    job.setOriginalFilePath("00000000-0000-0000-0000-000000000023/vehicles.csv");

    assertThatThrownBy(() -> launcher.launch(job))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("locationId");
  }

  @Test
  void launch_whenUnsupportedDomain_throwsIllegalState() {
    SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
        jobLauncher,
        catalogBulkLoadJob,
        customerBulkLoadJob,
        peopleBulkLoadJob,
        priceBulkLoadJob,
        vehicleBulkLoadJob,
        vehicleFitmentBulkLoadJob);
    BulkLoadJob job = new BulkLoadJob();
    job.setId(UUID.fromString("00000000-0000-0000-0000-000000000022"));
    job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000032"));
    job.setOperatorId("operator-location");
    job.setDomainType(DomainType.LOCATION);
    job.setOriginalFilePath("00000000-0000-0000-0000-000000000022/locations.csv");

    assertThatThrownBy(() -> launcher.launch(job))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No Spring Batch job is configured");
  }
}