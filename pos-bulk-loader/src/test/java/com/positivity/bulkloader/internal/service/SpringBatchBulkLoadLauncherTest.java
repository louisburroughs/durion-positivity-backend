package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    Job commercialCustomerBulkLoadJob;

    @Mock
    Job locationBulkLoadJob;

    @Mock
    Job peopleBulkLoadJob;

    @Mock
    Job priceBulkLoadJob;

    @Mock
    Job vehicleBulkLoadJob;

    @Mock
    Job vehicleFitmentBulkLoadJob;

    @Mock
    Job inventoryStockCountBulkLoadJob;

    @Mock
    Job convertedPackJob;

    /** The job beans as Spring would supply them: every batch job in the context, keyed by name. */
    private SpringBatchBulkLoadLauncher launcher() {
        Map<String, Job> jobsByName = new HashMap<>();
        jobsByName.put("catalogBulkLoadJob", catalogBulkLoadJob);
        jobsByName.put("customerBulkLoadJob", customerBulkLoadJob);
        jobsByName.put("commercialCustomerBulkLoadJob", commercialCustomerBulkLoadJob);
        jobsByName.put("locationBulkLoadJob", locationBulkLoadJob);
        jobsByName.put("peopleBulkLoadJob", peopleBulkLoadJob);
        jobsByName.put("priceBulkLoadJob", priceBulkLoadJob);
        jobsByName.put("vehicleBulkLoadJob", vehicleBulkLoadJob);
        jobsByName.put("vehicleFitmentBulkLoadJob", vehicleFitmentBulkLoadJob);
        jobsByName.put("inventoryStockCountBulkLoadJob", inventoryStockCountBulkLoadJob);
        // The converted packs; each only has to be present for its DomainType to dispatch.
        for (String beanName : List.of(
                "storageLocationBulkLoadJob",
                "bayBulkLoadJob",
                "mobileUnitBulkLoadJob",
                "staffingAssignmentBulkLoadJob",
                "putawayRuleBulkLoadJob",
                "cycleCountPlanBulkLoadJob",
                "securityUserBulkLoadJob",
                "userPersonLinkBulkLoadJob",
                "mechanicSkillBulkLoadJob")) {
            jobsByName.put(beanName, convertedPackJob);
        }
        return new SpringBatchBulkLoadLauncher(bulkLoadAuthorizationContext, jobOperator, jobsByName);
    }

    @Test
    void launch_whenVehicleDomain_usesVehicleJobAndStoragePathParameters() throws Exception {
        SpringBatchBulkLoadLauncher launcher = launcher();
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
        SpringBatchBulkLoadLauncher launcher = launcher();
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
    void launch_whenInventoryStockCountDomain_usesTheOpeningStockJob() throws Exception {
        // This domain was declared, advertised in the create-job docs and inferable by the content
        // sniffer, while throwing on process. It is now wired, so the test that pinned the throw
        // pins the dispatch instead.
        SpringBatchBulkLoadLauncher launcher = launcher();
        BulkLoadJob job = new BulkLoadJob();
        job.setId(UUID.fromString("00000000-0000-0000-0000-000000000022"));
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000032"));
        job.setOperatorId("operator-stock-count");
        job.setDomainType(DomainType.INVENTORY_STOCK_COUNT);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000022/stock-counts.csv");

        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(jobExecution);

        launcher.launch(job, null);

        verify(jobOperator).start(org.mockito.Mockito.same(inventoryStockCountBulkLoadJob), any(JobParameters.class));
    }

    @Test
    void launch_whenTheJobBeanIsMissing_saysWhichOne() {
        // Guards the map lookup that replaced eight constructor arguments: a domain whose job bean
        // is absent must name the bean, not fail with a bare NullPointerException.
        SpringBatchBulkLoadLauncher launcher =
                new SpringBatchBulkLoadLauncher(bulkLoadAuthorizationContext, jobOperator, Map.of());
        BulkLoadJob job = new BulkLoadJob();
        job.setId(UUID.fromString("00000000-0000-0000-0000-000000000024"));
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000034"));
        job.setOperatorId("operator-vehicle");
        job.setDomainType(DomainType.VEHICLE);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000024/vehicles.csv");

        assertThatThrownBy(() -> launcher.launch(job, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vehicleBulkLoadJob");
    }

    @Test
    void launch_whenCommercialCustomerDomain_usesCommercialCustomerJob() throws Exception {
        SpringBatchBulkLoadLauncher launcher = launcher();
        BulkLoadJob job = new BulkLoadJob();
        job.setId(UUID.fromString("00000000-0000-0000-0000-000000000026"));
        job.setDomainType(DomainType.COMMERCIAL_CUSTOMER);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000026/commercial-customers.csv");
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000036"));
        job.setOperatorId("operator-commercial");

        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(jobExecution);

        launcher.launch(job, null);

        verify(jobOperator).start(org.mockito.Mockito.same(commercialCustomerBulkLoadJob), any(JobParameters.class));
    }

    @Test
    void launch_whenLocationDomain_usesLocationJob() throws Exception {
        SpringBatchBulkLoadLauncher launcher = launcher();
        BulkLoadJob job = new BulkLoadJob();
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000025");
        job.setId(jobId);
        job.setDomainType(DomainType.LOCATION);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000025/locations.csv");
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000035"));
        job.setOperatorId("operator-location");

        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenAnswer(invocation -> {
            assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isEqualTo("Bearer token-location");
            return jobExecution;
        });

        launcher.launch(job, "Bearer token-location");

        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(org.mockito.Mockito.same(locationBulkLoadJob), parametersCaptor.capture());
        JobParameters jobParameters = parametersCaptor.getValue();
        assertThat(jobParameters.getString("jobId")).isEqualTo(jobId.toString());
        assertThat(jobParameters.getString("storagePath"))
                .isEqualTo("00000000-0000-0000-0000-000000000025/locations.csv");
        assertThat(jobParameters.getString("locationId")).isEqualTo("00000000-0000-0000-0000-000000000035");
        assertThat(jobParameters.getString("operatorId")).isEqualTo("operator-location");
        assertThat(jobParameters.getLong("launchEpochMillis")).isNotNull();
        assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isNull();
    }

    @Test
    void launch_whenJobLauncherFails_clearsAuthorizationContext() throws Exception {
        SpringBatchBulkLoadLauncher launcher = launcher();
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
