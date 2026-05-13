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
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class SpringBatchBulkLoadLauncherTest {

    BulkLoadAuthorizationContext bulkLoadAuthorizationContext = new BulkLoadAuthorizationContext();

    @Mock
    JobOperator jobOperator;

    @Mock
    JobExecution jobExecution;

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
                bulkLoadAuthorizationContext,
                jobOperator,
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

        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenAnswer(invocation -> {
            assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isEqualTo("Bearer token-vehicle");
            return jobExecution;
        });

        launcher.launch(job, "Bearer token-vehicle");

        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(org.mockito.Mockito.same(vehicleBulkLoadJob), parametersCaptor.capture());
        JobParameters jobParameters = parametersCaptor.getValue();
        assertThat(jobParameters.getString("jobId")).isEqualTo(jobId.toString());
        assertThat(jobParameters.getString("storagePath"))
                .isEqualTo("00000000-0000-0000-0000-000000000021/vehicles.csv");
        assertThat(jobParameters.getString("locationId")).isEqualTo("00000000-0000-0000-0000-000000000031");
        assertThat(jobParameters.getString("operatorId")).isEqualTo("operator-vehicle");
        assertThat(jobParameters.getLong("launchEpochMillis")).isNotNull();
        assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isNull();
    }

    @Test
    void launch_whenLocationIdMissing_throwsIllegalArgumentException() {
        SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
                bulkLoadAuthorizationContext,
                jobOperator,
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

        assertThatThrownBy(() -> launcher.launch(job, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locationId");
    }

    @Test
    void launch_whenUnsupportedDomain_throwsIllegalState() {
        SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
                bulkLoadAuthorizationContext,
                jobOperator,
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

        assertThatThrownBy(() -> launcher.launch(job, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Spring Batch job is configured");
    }

    @Test
    void launch_whenJobLauncherFails_clearsAuthorizationContext() throws Exception {
        SpringBatchBulkLoadLauncher launcher = new SpringBatchBulkLoadLauncher(
                bulkLoadAuthorizationContext,
                jobOperator,
                catalogBulkLoadJob,
                customerBulkLoadJob,
                peopleBulkLoadJob,
                priceBulkLoadJob,
                vehicleBulkLoadJob,
                vehicleFitmentBulkLoadJob);
        BulkLoadJob job = new BulkLoadJob();
        job.setId(UUID.fromString("00000000-0000-0000-0000-000000000024"));
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000024/catalog.csv");
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000034"));
        job.setOperatorId("operator-catalog");

        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new org.springframework.batch.core.launch.JobExecutionAlreadyRunningException(
                        "already running"));

        assertThatThrownBy(() -> launcher.launch(job, "Bearer token-catalog"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to launch Spring Batch job");
        assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isNull();
    }
}
