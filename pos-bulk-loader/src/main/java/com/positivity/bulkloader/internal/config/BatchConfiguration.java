package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.config.BulkIngestWriterFactory.JobParams;
import com.positivity.bulkloader.internal.config.BulkIngestWriterFactory.Target;
import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import com.positivity.bulkloader.internal.domain.BayLoaderRecord;
import com.positivity.bulkloader.internal.domain.BayLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CommercialCustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CommercialCustomerRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.CycleCountPlanLoaderRecord;
import com.positivity.bulkloader.internal.domain.CycleCountPlanLoaderStrategy;
import com.positivity.bulkloader.internal.domain.InventoryStockCountLoaderStrategy;
import com.positivity.bulkloader.internal.domain.InventoryStockCountRecord;
import com.positivity.bulkloader.internal.domain.LocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.LocationRecord;
import com.positivity.bulkloader.internal.domain.MechanicSkillLoaderRecord;
import com.positivity.bulkloader.internal.domain.MechanicSkillLoaderStrategy;
import com.positivity.bulkloader.internal.domain.MobileUnitLoaderRecord;
import com.positivity.bulkloader.internal.domain.MobileUnitLoaderStrategy;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.PutawayRuleLoaderRecord;
import com.positivity.bulkloader.internal.domain.PutawayRuleLoaderStrategy;
import com.positivity.bulkloader.internal.domain.SecurityUserLoaderRecord;
import com.positivity.bulkloader.internal.domain.SecurityUserLoaderStrategy;
import com.positivity.bulkloader.internal.domain.StaffingAssignmentLoaderRecord;
import com.positivity.bulkloader.internal.domain.StaffingAssignmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.StorageLocationLoaderRecord;
import com.positivity.bulkloader.internal.domain.StorageLocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.UserPersonLinkLoaderRecord;
import com.positivity.bulkloader.internal.domain.UserPersonLinkLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleBulkRecord;
import com.positivity.bulkloader.internal.domain.VehicleFitmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleFitmentRecord;
import com.positivity.bulkloader.internal.domain.VehicleLoaderStrategy;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The per-domain Spring Batch beans of the bulk loader.
 *
 * <p>Everything the domains share — building the job, step, header-driven reader and validating
 * processor, and posting a chunk to its owning service with the per-row audit trail — lives in
 * {@link BulkLoadJobFactory} and {@link BulkIngestWriterFactory}. What remains here is the part
 * Spring has to see by name: four beans per domain, each a single call, plus the payload
 * projections for the three domains whose ingest DTOs need typed values the loader records carry
 * as text.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class BatchConfiguration {

    private final BulkLoadJobFactory jobFactory;
    private final BulkIngestWriterFactory writerFactory;
    private final CatalogLoaderStrategy catalogLoaderStrategy;
    private final CustomerLoaderStrategy customerLoaderStrategy;
    private final CommercialCustomerLoaderStrategy commercialCustomerLoaderStrategy;
    private final LocationLoaderStrategy locationLoaderStrategy;
    private final PersonLoaderStrategy personLoaderStrategy;
    private final BasePriceLoaderStrategy basePriceLoaderStrategy;
    private final VehicleLoaderStrategy vehicleLoaderStrategy;
    private final VehicleFitmentLoaderStrategy vehicleFitmentLoaderStrategy;
    private final InventoryStockCountLoaderStrategy inventoryStockCountLoaderStrategy;
    private final StorageLocationLoaderStrategy storageLocationLoaderStrategy;
    private final BayLoaderStrategy bayLoaderStrategy;
    private final MobileUnitLoaderStrategy mobileUnitLoaderStrategy;
    private final StaffingAssignmentLoaderStrategy staffingAssignmentLoaderStrategy;
    private final PutawayRuleLoaderStrategy putawayRuleLoaderStrategy;
    private final CycleCountPlanLoaderStrategy cycleCountPlanLoaderStrategy;
    private final SecurityUserLoaderStrategy securityUserLoaderStrategy;
    private final UserPersonLinkLoaderStrategy userPersonLinkLoaderStrategy;
    private final MechanicSkillLoaderStrategy mechanicSkillLoaderStrategy;

    // Eureka service ids resolved through the load-balanced builder (#641); the ingest
    // writers address sibling services by discovery instead of host:port base URLs.
    @Value("${pos.catalog.service-id:catalog}")
    private String catalogServiceId;

    @Value("${pos.customer.service-id:customer}")
    private String customerServiceId;

    @Value("${pos.location.service-id:location}")
    private String locationServiceId;

    @Value("${pos.people.service-id:people}")
    private String peopleServiceId;

    @Value("${pos.price.service-id:price}")
    private String priceServiceId;

    @Value("${pos.vehicle-inventory.service-id:vehicle-inventory}")
    private String vehicleInventoryServiceId;

    @Value("${pos.vehicle-fitment.service-id:vehicle-fitment}")
    private String vehicleFitmentServiceId;

    @Value("${pos.inventory.service-id:inventory}")
    private String inventoryServiceId;

    @Value("${pos.security.service-id:security-service}")
    private String securityServiceId;

    @Value("${pos.shop-manager.service-id:shop-manager}")
    private String shopManagerServiceId;

    @Bean
    public Job catalogBulkLoadJob(Step catalogBulkLoadStep) {
        return jobFactory.job("catalogBulkLoadJob", catalogBulkLoadStep);
    }

    @Bean
    public Step catalogBulkLoadStep(
            ItemStreamReader<CatalogProductRecord> catalogReader,
            ItemProcessor<CatalogProductRecord, NumberedRecord<CatalogProductRecord>> catalogItemProcessor,
            ItemWriter<NumberedRecord<CatalogProductRecord>> catalogBulkIngestWriter) {
        return jobFactory.step("catalogBulkLoadStep", catalogReader, catalogItemProcessor, catalogBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<CatalogProductRecord> catalogReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(catalogLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<CatalogProductRecord, NumberedRecord<CatalogProductRecord>> catalogItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                catalogLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<CatalogProductRecord>> catalogBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "catalogBulkIngestWriter",
                        DomainType.CATALOG_PRODUCT,
                        catalogServiceId,
                        "/v1/catalog/bulk-ingest",
                        "catalog:product:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId));
    }

    @Bean
    public Job customerBulkLoadJob(Step customerBulkLoadStep) {
        return jobFactory.job("customerBulkLoadJob", customerBulkLoadStep);
    }

    @Bean
    public Step customerBulkLoadStep(
            ItemStreamReader<CustomerPersonRecord> customerReader,
            ItemProcessor<CustomerPersonRecord, NumberedRecord<CustomerPersonRecord>> customerItemProcessor,
            ItemWriter<NumberedRecord<CustomerPersonRecord>> customerBulkIngestWriter) {
        return jobFactory.step("customerBulkLoadStep", customerReader, customerItemProcessor, customerBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<CustomerPersonRecord> customerReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(customerLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<CustomerPersonRecord, NumberedRecord<CustomerPersonRecord>> customerItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                customerLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<CustomerPersonRecord>> customerBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "customerBulkIngestWriter",
                        DomainType.CUSTOMER,
                        customerServiceId,
                        "/v1/customer/bulk-ingest",
                        "crm:party:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId));
    }

    @Bean
    public Job commercialCustomerBulkLoadJob(Step commercialCustomerBulkLoadStep) {
        return jobFactory.job("commercialCustomerBulkLoadJob", commercialCustomerBulkLoadStep);
    }

    @Bean
    public Step commercialCustomerBulkLoadStep(
            ItemStreamReader<CommercialCustomerRecord> commercialCustomerReader,
            ItemProcessor<CommercialCustomerRecord, NumberedRecord<CommercialCustomerRecord>>
                    commercialCustomerItemProcessor,
            ItemWriter<NumberedRecord<CommercialCustomerRecord>> commercialCustomerBulkIngestWriter) {
        return jobFactory.step(
                "commercialCustomerBulkLoadStep",
                commercialCustomerReader,
                commercialCustomerItemProcessor,
                commercialCustomerBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<CommercialCustomerRecord> commercialCustomerReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(commercialCustomerLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<CommercialCustomerRecord, NumberedRecord<CommercialCustomerRecord>>
            commercialCustomerItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                commercialCustomerLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<CommercialCustomerRecord>> commercialCustomerBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "commercialCustomerBulkIngestWriter",
                        DomainType.COMMERCIAL_CUSTOMER,
                        customerServiceId,
                        "/v1/customer/commercial/bulk-ingest",
                        "crm:party:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId));
    }

    @Bean
    public Job locationBulkLoadJob(Step locationBulkLoadStep) {
        return jobFactory.job("locationBulkLoadJob", locationBulkLoadStep);
    }

    @Bean
    public Step locationBulkLoadStep(
            ItemStreamReader<LocationRecord> locationReader,
            ItemProcessor<LocationRecord, NumberedRecord<LocationRecord>> locationItemProcessor,
            ItemWriter<NumberedRecord<LocationRecord>> locationBulkIngestWriter) {
        return jobFactory.step("locationBulkLoadStep", locationReader, locationItemProcessor, locationBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<LocationRecord> locationReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(locationLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<LocationRecord, NumberedRecord<LocationRecord>> locationItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                locationLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<LocationRecord>> locationBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "locationBulkIngestWriter",
                        DomainType.LOCATION,
                        locationServiceId,
                        "/v1/locations/bulk-ingest",
                        "location:write"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapLocationPayloads);
    }

    @Bean
    public Job peopleBulkLoadJob(Step peopleBulkLoadStep) {
        return jobFactory.job("peopleBulkLoadJob", peopleBulkLoadStep);
    }

    @Bean
    public Step peopleBulkLoadStep(
            ItemStreamReader<PersonRecord> peopleReader,
            ItemProcessor<PersonRecord, NumberedRecord<PersonRecord>> peopleItemProcessor,
            ItemWriter<NumberedRecord<PersonRecord>> peopleBulkIngestWriter) {
        return jobFactory.step("peopleBulkLoadStep", peopleReader, peopleItemProcessor, peopleBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<PersonRecord> peopleReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(personLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<PersonRecord, NumberedRecord<PersonRecord>> peopleItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                personLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<PersonRecord>> peopleBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "peopleBulkIngestWriter",
                        DomainType.PERSON,
                        peopleServiceId,
                        "/v1/people/bulk-ingest",
                        "people:employee:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId));
    }

    @Bean
    public Job priceBulkLoadJob(Step priceBulkLoadStep) {
        return jobFactory.job("priceBulkLoadJob", priceBulkLoadStep);
    }

    @Bean
    public Step priceBulkLoadStep(
            ItemStreamReader<BasePriceRecord> priceReader,
            ItemProcessor<BasePriceRecord, NumberedRecord<BasePriceRecord>> priceItemProcessor,
            ItemWriter<NumberedRecord<BasePriceRecord>> priceBulkIngestWriter) {
        return jobFactory.step("priceBulkLoadStep", priceReader, priceItemProcessor, priceBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<BasePriceRecord> priceReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(basePriceLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<BasePriceRecord, NumberedRecord<BasePriceRecord>> priceItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                basePriceLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<BasePriceRecord>> priceBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "priceBulkIngestWriter",
                        DomainType.BASE_PRICE,
                        priceServiceId,
                        "/v1/price/bulk-ingest",
                        "pricing:base_price:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId));
    }

    @Bean
    public Job vehicleBulkLoadJob(Step vehicleBulkLoadStep) {
        return jobFactory.job("vehicleBulkLoadJob", vehicleBulkLoadStep);
    }

    @Bean
    public Step vehicleBulkLoadStep(
            ItemStreamReader<VehicleBulkRecord> vehicleReader,
            ItemProcessor<VehicleBulkRecord, NumberedRecord<VehicleBulkRecord>> vehicleItemProcessor,
            ItemWriter<NumberedRecord<VehicleBulkRecord>> vehicleBulkIngestWriter) {
        return jobFactory.step("vehicleBulkLoadStep", vehicleReader, vehicleItemProcessor, vehicleBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<VehicleBulkRecord> vehicleReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(vehicleLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<VehicleBulkRecord, NumberedRecord<VehicleBulkRecord>> vehicleItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                vehicleLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<VehicleBulkRecord>> vehicleBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "vehicleBulkIngestWriter",
                        DomainType.VEHICLE,
                        vehicleInventoryServiceId,
                        "/v1/vehicles/bulk-ingest",
                        "vehicle-inventory:registry:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapVehiclePayloads);
    }

    @Bean
    public Job vehicleFitmentBulkLoadJob(Step vehicleFitmentBulkLoadStep) {
        return jobFactory.job("vehicleFitmentBulkLoadJob", vehicleFitmentBulkLoadStep);
    }

    @Bean
    public Step vehicleFitmentBulkLoadStep(
            ItemStreamReader<VehicleFitmentRecord> vehicleFitmentReader,
            ItemProcessor<VehicleFitmentRecord, NumberedRecord<VehicleFitmentRecord>> vehicleFitmentItemProcessor,
            ItemWriter<NumberedRecord<VehicleFitmentRecord>> vehicleFitmentBulkIngestWriter) {
        return jobFactory.step(
                "vehicleFitmentBulkLoadStep",
                vehicleFitmentReader,
                vehicleFitmentItemProcessor,
                vehicleFitmentBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<VehicleFitmentRecord> vehicleFitmentReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(vehicleFitmentLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<VehicleFitmentRecord, NumberedRecord<VehicleFitmentRecord>> vehicleFitmentItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                vehicleFitmentLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<VehicleFitmentRecord>> vehicleFitmentBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "vehicleFitmentBulkIngestWriter",
                        DomainType.VEHICLE_FITMENT,
                        vehicleFitmentServiceId,
                        "/v1/fitments/bulk-ingest",
                        "vehicle-fitment:hint:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapFitmentPayloads);
    }

    @Bean
    public Job inventoryStockCountBulkLoadJob(Step inventoryStockCountBulkLoadStep) {
        return jobFactory.job("inventoryStockCountBulkLoadJob", inventoryStockCountBulkLoadStep);
    }

    @Bean
    public Step inventoryStockCountBulkLoadStep(
            ItemStreamReader<InventoryStockCountRecord> inventoryStockCountReader,
            ItemProcessor<InventoryStockCountRecord, NumberedRecord<InventoryStockCountRecord>>
                    inventoryStockCountItemProcessor,
            ItemWriter<NumberedRecord<InventoryStockCountRecord>> inventoryStockCountBulkIngestWriter) {
        return jobFactory.step(
                "inventoryStockCountBulkLoadStep",
                inventoryStockCountReader,
                inventoryStockCountItemProcessor,
                inventoryStockCountBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<InventoryStockCountRecord> inventoryStockCountReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(inventoryStockCountLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<InventoryStockCountRecord, NumberedRecord<InventoryStockCountRecord>>
            inventoryStockCountItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                inventoryStockCountLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<InventoryStockCountRecord>> inventoryStockCountBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "inventoryStockCountBulkIngestWriter",
                        DomainType.INVENTORY_STOCK_COUNT,
                        inventoryServiceId,
                        "/v1/inventory/opening-stock/bulk-ingest",
                        "inventory:adjustment:create,inventory:adjustment:approve"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapOpeningStockPayloads);
    }

    @Bean
    public Job storageLocationBulkLoadJob(Step storageLocationBulkLoadStep) {
        return jobFactory.job("storageLocationBulkLoadJob", storageLocationBulkLoadStep);
    }

    @Bean
    public Step storageLocationBulkLoadStep(
            ItemStreamReader<StorageLocationLoaderRecord> storageLocationReader,
            ItemProcessor<StorageLocationLoaderRecord, NumberedRecord<StorageLocationLoaderRecord>>
                    storageLocationItemProcessor,
            ItemWriter<NumberedRecord<StorageLocationLoaderRecord>> storageLocationBulkIngestWriter) {
        return jobFactory.step(
                "storageLocationBulkLoadStep",
                storageLocationReader,
                storageLocationItemProcessor,
                storageLocationBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<StorageLocationLoaderRecord> storageLocationReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(storageLocationLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<StorageLocationLoaderRecord, NumberedRecord<StorageLocationLoaderRecord>>
            storageLocationItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                storageLocationLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<StorageLocationLoaderRecord>> storageLocationBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "storageLocationBulkIngestWriter",
                        DomainType.STORAGE_LOCATION,
                        locationServiceId,
                        "/v1/locations/storage-locations/bulk-ingest",
                        "location:write"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapStorageLocationPayloads);
    }

    @Bean
    public Job bayBulkLoadJob(Step bayBulkLoadStep) {
        return jobFactory.job("bayBulkLoadJob", bayBulkLoadStep);
    }

    @Bean
    public Step bayBulkLoadStep(
            ItemStreamReader<BayLoaderRecord> bayReader,
            ItemProcessor<BayLoaderRecord, NumberedRecord<BayLoaderRecord>> bayItemProcessor,
            ItemWriter<NumberedRecord<BayLoaderRecord>> bayBulkIngestWriter) {
        return jobFactory.step("bayBulkLoadStep", bayReader, bayItemProcessor, bayBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<BayLoaderRecord> bayReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(bayLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<BayLoaderRecord, NumberedRecord<BayLoaderRecord>> bayItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                bayLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<BayLoaderRecord>> bayBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "bayBulkIngestWriter",
                        DomainType.BAY,
                        locationServiceId,
                        "/v1/locations/bays/bulk-ingest",
                        "location:bay:manage"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapBayPayloads);
    }

    @Bean
    public Job mobileUnitBulkLoadJob(Step mobileUnitBulkLoadStep) {
        return jobFactory.job("mobileUnitBulkLoadJob", mobileUnitBulkLoadStep);
    }

    @Bean
    public Step mobileUnitBulkLoadStep(
            ItemStreamReader<MobileUnitLoaderRecord> mobileUnitReader,
            ItemProcessor<MobileUnitLoaderRecord, NumberedRecord<MobileUnitLoaderRecord>> mobileUnitItemProcessor,
            ItemWriter<NumberedRecord<MobileUnitLoaderRecord>> mobileUnitBulkIngestWriter) {
        return jobFactory.step(
                "mobileUnitBulkLoadStep", mobileUnitReader, mobileUnitItemProcessor, mobileUnitBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<MobileUnitLoaderRecord> mobileUnitReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(mobileUnitLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<MobileUnitLoaderRecord, NumberedRecord<MobileUnitLoaderRecord>> mobileUnitItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                mobileUnitLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<MobileUnitLoaderRecord>> mobileUnitBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "mobileUnitBulkIngestWriter",
                        DomainType.MOBILE_UNIT,
                        locationServiceId,
                        "/v1/mobile-units/bulk-ingest",
                        "location:mobile-unit:manage"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapMobileUnitPayloads);
    }

    @Bean
    public Job staffingAssignmentBulkLoadJob(Step staffingAssignmentBulkLoadStep) {
        return jobFactory.job("staffingAssignmentBulkLoadJob", staffingAssignmentBulkLoadStep);
    }

    @Bean
    public Step staffingAssignmentBulkLoadStep(
            ItemStreamReader<StaffingAssignmentLoaderRecord> staffingAssignmentReader,
            ItemProcessor<StaffingAssignmentLoaderRecord, NumberedRecord<StaffingAssignmentLoaderRecord>>
                    staffingAssignmentItemProcessor,
            ItemWriter<NumberedRecord<StaffingAssignmentLoaderRecord>> staffingAssignmentBulkIngestWriter) {
        return jobFactory.step(
                "staffingAssignmentBulkLoadStep",
                staffingAssignmentReader,
                staffingAssignmentItemProcessor,
                staffingAssignmentBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<StaffingAssignmentLoaderRecord> staffingAssignmentReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(staffingAssignmentLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<StaffingAssignmentLoaderRecord, NumberedRecord<StaffingAssignmentLoaderRecord>>
            staffingAssignmentItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                staffingAssignmentLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<StaffingAssignmentLoaderRecord>> staffingAssignmentBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "staffingAssignmentBulkIngestWriter",
                        DomainType.STAFFING_ASSIGNMENT,
                        peopleServiceId,
                        "/v1/people/staffing/bulk-ingest",
                        "people:employee:edit"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapStaffingAssignmentPayloads);
    }

    @Bean
    public Job putawayRuleBulkLoadJob(Step putawayRuleBulkLoadStep) {
        return jobFactory.job("putawayRuleBulkLoadJob", putawayRuleBulkLoadStep);
    }

    @Bean
    public Step putawayRuleBulkLoadStep(
            ItemStreamReader<PutawayRuleLoaderRecord> putawayRuleReader,
            ItemProcessor<PutawayRuleLoaderRecord, NumberedRecord<PutawayRuleLoaderRecord>> putawayRuleItemProcessor,
            ItemWriter<NumberedRecord<PutawayRuleLoaderRecord>> putawayRuleBulkIngestWriter) {
        return jobFactory.step(
                "putawayRuleBulkLoadStep", putawayRuleReader, putawayRuleItemProcessor, putawayRuleBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<PutawayRuleLoaderRecord> putawayRuleReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(putawayRuleLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<PutawayRuleLoaderRecord, NumberedRecord<PutawayRuleLoaderRecord>> putawayRuleItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                putawayRuleLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<PutawayRuleLoaderRecord>> putawayRuleBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "putawayRuleBulkIngestWriter",
                        DomainType.PUTAWAY_RULE,
                        inventoryServiceId,
                        "/v1/inventory/putaway/bulk-ingest",
                        "inventory:putaway_rule:manage"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapPutawayRulePayloads);
    }

    @Bean
    public Job cycleCountPlanBulkLoadJob(Step cycleCountPlanBulkLoadStep) {
        return jobFactory.job("cycleCountPlanBulkLoadJob", cycleCountPlanBulkLoadStep);
    }

    @Bean
    public Step cycleCountPlanBulkLoadStep(
            ItemStreamReader<CycleCountPlanLoaderRecord> cycleCountPlanReader,
            ItemProcessor<CycleCountPlanLoaderRecord, NumberedRecord<CycleCountPlanLoaderRecord>>
                    cycleCountPlanItemProcessor,
            ItemWriter<NumberedRecord<CycleCountPlanLoaderRecord>> cycleCountPlanBulkIngestWriter) {
        return jobFactory.step(
                "cycleCountPlanBulkLoadStep",
                cycleCountPlanReader,
                cycleCountPlanItemProcessor,
                cycleCountPlanBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<CycleCountPlanLoaderRecord> cycleCountPlanReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(cycleCountPlanLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<CycleCountPlanLoaderRecord, NumberedRecord<CycleCountPlanLoaderRecord>>
            cycleCountPlanItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                cycleCountPlanLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<CycleCountPlanLoaderRecord>> cycleCountPlanBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "cycleCountPlanBulkIngestWriter",
                        DomainType.CYCLE_COUNT_PLAN,
                        inventoryServiceId,
                        "/v1/inventory/cycleCountPlans/bulk-ingest",
                        "inventory:cycle_count:initiate"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapCycleCountPlanPayloads);
    }

    @Bean
    public Job securityUserBulkLoadJob(Step securityUserBulkLoadStep) {
        return jobFactory.job("securityUserBulkLoadJob", securityUserBulkLoadStep);
    }

    @Bean
    public Step securityUserBulkLoadStep(
            ItemStreamReader<SecurityUserLoaderRecord> securityUserReader,
            ItemProcessor<SecurityUserLoaderRecord, NumberedRecord<SecurityUserLoaderRecord>> securityUserItemProcessor,
            ItemWriter<NumberedRecord<SecurityUserLoaderRecord>> securityUserBulkIngestWriter) {
        return jobFactory.step(
                "securityUserBulkLoadStep",
                securityUserReader,
                securityUserItemProcessor,
                securityUserBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<SecurityUserLoaderRecord> securityUserReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(securityUserLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<SecurityUserLoaderRecord, NumberedRecord<SecurityUserLoaderRecord>> securityUserItemProcessor(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                securityUserLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<SecurityUserLoaderRecord>> securityUserBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "securityUserBulkIngestWriter",
                        DomainType.SECURITY_USER,
                        securityServiceId,
                        "/v1/users/bulk-ingest",
                        "security:user:create"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapSecurityUserPayloads);
    }

    @Bean
    public Job userPersonLinkBulkLoadJob(Step userPersonLinkBulkLoadStep) {
        return jobFactory.job("userPersonLinkBulkLoadJob", userPersonLinkBulkLoadStep);
    }

    @Bean
    public Step userPersonLinkBulkLoadStep(
            ItemStreamReader<UserPersonLinkLoaderRecord> userPersonLinkReader,
            ItemProcessor<UserPersonLinkLoaderRecord, NumberedRecord<UserPersonLinkLoaderRecord>>
                    userPersonLinkItemProcessor,
            ItemWriter<NumberedRecord<UserPersonLinkLoaderRecord>> userPersonLinkBulkIngestWriter) {
        return jobFactory.step(
                "userPersonLinkBulkLoadStep",
                userPersonLinkReader,
                userPersonLinkItemProcessor,
                userPersonLinkBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<UserPersonLinkLoaderRecord> userPersonLinkReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(userPersonLinkLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<UserPersonLinkLoaderRecord, NumberedRecord<UserPersonLinkLoaderRecord>>
            userPersonLinkItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                userPersonLinkLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<UserPersonLinkLoaderRecord>> userPersonLinkBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "userPersonLinkBulkIngestWriter",
                        DomainType.USER_PERSON_LINK,
                        securityServiceId,
                        "/v1/users/person-link/bulk-ingest",
                        "security:user:edit"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapUserPersonLinkPayloads);
    }

    @Bean
    public Job mechanicSkillBulkLoadJob(Step mechanicSkillBulkLoadStep) {
        return jobFactory.job("mechanicSkillBulkLoadJob", mechanicSkillBulkLoadStep);
    }

    @Bean
    public Step mechanicSkillBulkLoadStep(
            ItemStreamReader<MechanicSkillLoaderRecord> mechanicSkillReader,
            ItemProcessor<MechanicSkillLoaderRecord, NumberedRecord<MechanicSkillLoaderRecord>>
                    mechanicSkillItemProcessor,
            ItemWriter<NumberedRecord<MechanicSkillLoaderRecord>> mechanicSkillBulkIngestWriter) {
        return jobFactory.step(
                "mechanicSkillBulkLoadStep",
                mechanicSkillReader,
                mechanicSkillItemProcessor,
                mechanicSkillBulkIngestWriter);
    }

    @Bean
    @StepScope
    public ItemStreamReader<MechanicSkillLoaderRecord> mechanicSkillReader(
            @Value("#{jobParameters['storagePath']}") String storagePath,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam) {
        return jobFactory.reader(mechanicSkillLoaderStrategy, storagePath, jobIdParam);
    }

    @Bean
    @StepScope
    public ItemProcessor<MechanicSkillLoaderRecord, NumberedRecord<MechanicSkillLoaderRecord>>
            mechanicSkillItemProcessor(
                    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
                    @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
                    @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam) {
        return jobFactory.processor(
                mechanicSkillLoaderStrategy,
                jobFactory.parseJobId(jobIdParam),
                jobFactory.resolutionContext(restClientBuilder, locationIdParam));
    }

    @Bean
    @StepScope
    public ItemWriter<NumberedRecord<MechanicSkillLoaderRecord>> mechanicSkillBulkIngestWriter(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        return writerFactory.create(
                restClientBuilder,
                new Target(
                        "mechanicSkillBulkIngestWriter",
                        DomainType.MECHANIC_SKILL,
                        shopManagerServiceId,
                        "/v1/shop-manager/mechanics/bulk-ingest",
                        "shop:schedule:edit"),
                new JobParams(jobIdParam, locationIdParam, operatorId),
                this::mapMechanicSkillPayloads);
    }

    private List<LocationWriterPayload> mapLocationPayloads(List<? extends LocationRecord> items) {
        List<LocationWriterPayload> payloads = new ArrayList<>(items.size());
        for (LocationRecord item : items) {
            payloads.add(new LocationWriterPayload(
                    item.getName(),
                    item.getCode(),
                    item.getAddressLine1(),
                    item.getAddressLine2(),
                    item.getCity(),
                    item.getStateOrProvince(),
                    item.getPostalCode(),
                    item.getCountryCode(),
                    item.getPhoneNumber(),
                    parseLocationActive(item),
                    item.getLocationTypeName(),
                    item.getTimezone()));
        }
        return payloads;
    }

    private Boolean parseLocationActive(LocationRecord item) {
        String active = item.getActive();
        if (active == null || active.isBlank()) {
            // Null lets the ingest endpoint apply its own default (active=true).
            return null;
        }
        if ("true".equalsIgnoreCase(active.trim())) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(active.trim())) {
            return Boolean.FALSE;
        }
        log.warn(
                "locationBulkIngestWriter: invalid active flag '{}' for code '{}', setting null",
                active,
                item.getCode());
        return null;
    }

    private List<VehicleWriterPayload> mapVehiclePayloads(List<? extends VehicleBulkRecord> items) {
        List<VehicleWriterPayload> payloads = new ArrayList<>(items.size());
        for (VehicleBulkRecord item : items) {
            payloads.add(new VehicleWriterPayload(
                    parseVehicleAccountId(item),
                    item.getVin(),
                    item.getUnitNumber(),
                    item.getDescription(),
                    item.getMake(),
                    item.getModel(),
                    parseVehicleYear(item),
                    item.getTrim(),
                    item.getLicensePlate(),
                    item.getLicensePlateJurisdiction()));
        }
        return payloads;
    }

    private UUID parseVehicleAccountId(VehicleBulkRecord item) {
        if (item.getAccountId() == null || item.getAccountId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(item.getAccountId());
        } catch (IllegalArgumentException _) {
            log.warn(
                    "vehicleBulkIngestWriter: invalid accountId '{}' for vin '{}', setting null",
                    item.getAccountId(),
                    item.getVin());
            return null;
        }
    }

    private Integer parseVehicleYear(VehicleBulkRecord item) {
        if (item.getYear() == null || item.getYear().isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(item.getYear());
        } catch (NumberFormatException _) {
            log.warn(
                    "vehicleBulkIngestWriter: invalid year '{}' for vin '{}', setting null",
                    item.getYear(),
                    item.getVin());
            return null;
        }
    }

    private List<FitmentWriterPayload> mapFitmentPayloads(List<? extends VehicleFitmentRecord> items) {
        List<FitmentWriterPayload> payloads = new ArrayList<>(items.size());
        for (VehicleFitmentRecord item : items) {
            payloads.add(new FitmentWriterPayload(
                    parseFitmentPartNumberId(item),
                    item.getManufacturerName(),
                    item.getMakeName(),
                    item.getModelName(),
                    item.getVehicleTypeName(),
                    item.getVehicleYear(),
                    item.getEngineType(),
                    item.getSubmodel(),
                    item.getNotes()));
        }
        return payloads;
    }

    private Long parseFitmentPartNumberId(VehicleFitmentRecord item) {
        if (item.getPartNumberId() == null || item.getPartNumberId().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(item.getPartNumberId());
        } catch (NumberFormatException _) {
            log.warn(
                    "vehicleFitmentBulkIngestWriter: invalid partNumberId '{}' for manufacturer '{}', setting null",
                    item.getPartNumberId(),
                    item.getManufacturerName());
            return null;
        }
    }

    /**
     * Projects opening-stock rows onto the ingest DTO.
     *
     * <p>The two name columns exist only so the file can be written without ids; by this point they
     * have done their job and are dropped, and the quantity becomes a number.
     */
    private List<OpeningStockWriterPayload> mapOpeningStockPayloads(List<InventoryStockCountRecord> items) {
        List<OpeningStockWriterPayload> payloads = new ArrayList<>(items.size());
        for (InventoryStockCountRecord item : items) {
            payloads.add(new OpeningStockWriterPayload(
                    item.getSku(),
                    UUID.fromString(item.getLocationId().trim()),
                    new java.math.BigDecimal(item.getQuantity().trim()),
                    item.getUnitOfMeasure(),
                    item.getReasonCode()));
        }
        return payloads;
    }

    private record OpeningStockWriterPayload(
            String sku, UUID locationId, java.math.BigDecimal quantity, String unitOfMeasure, String reasonCode) {}

    private List<SecurityUserWriterPayload> mapSecurityUserPayloads(List<SecurityUserLoaderRecord> items) {
        List<SecurityUserWriterPayload> payloads = new ArrayList<>(items.size());
        for (SecurityUserLoaderRecord item : items) {
            payloads.add(new SecurityUserWriterPayload(item.getUsername(), splitRoles(item.getRoles())));
        }
        return payloads;
    }

    /** Roles are written semicolon-separated so one CSV cell can hold several without quoting. */
    private Set<String> splitRoles(String roles) {
        Set<String> names = new LinkedHashSet<>();
        for (String role : roles.split(";")) {
            if (!role.isBlank()) {
                names.add(role.trim());
            }
        }
        return names;
    }

    private record SecurityUserWriterPayload(String username, Set<String> roles) {}

    private List<UserPersonLinkWriterPayload> mapUserPersonLinkPayloads(List<UserPersonLinkLoaderRecord> items) {
        List<UserPersonLinkWriterPayload> payloads = new ArrayList<>(items.size());
        for (UserPersonLinkLoaderRecord item : items) {
            payloads.add(new UserPersonLinkWriterPayload(
                    item.getUsername(), UUID.fromString(item.getPersonId().trim())));
        }
        return payloads;
    }

    private record UserPersonLinkWriterPayload(String username, UUID personId) {}

    private List<MechanicSkillWriterPayload> mapMechanicSkillPayloads(List<MechanicSkillLoaderRecord> items) {
        List<MechanicSkillWriterPayload> payloads = new ArrayList<>(items.size());
        for (MechanicSkillLoaderRecord item : items) {
            payloads.add(new MechanicSkillWriterPayload(
                    item.getPersonId().trim(),
                    item.getSkillCode(),
                    Integer.parseInt(item.getProficiencyLevel().trim())));
        }
        return payloads;
    }

    private record MechanicSkillWriterPayload(String personId, String skillCode, int proficiencyLevel) {}

    // ─── payload projections for the packs converted from API scripts ────────
    //
    // Each drops the business-key columns its strategy has already consumed and coerces the rest to
    // the types the ingest DTO declares. Validation ran first, so the parses here cannot fail on a
    // row that reached this point.

    private List<StorageLocationWriterPayload> mapStorageLocationPayloads(List<StorageLocationLoaderRecord> items) {
        List<StorageLocationWriterPayload> payloads = new ArrayList<>(items.size());
        for (StorageLocationLoaderRecord item : items) {
            payloads.add(new StorageLocationWriterPayload(
                    UUID.fromString(item.getSiteId().trim()),
                    item.getName(),
                    blankToNull(item.getType()),
                    blankToNull(item.getParentName()),
                    blankToNull(item.getStorageCategoryCode()),
                    parseBooleanOrNull(item.getHazardContainment()),
                    blankToNull(item.getAllowNewProduct()),
                    parseIntegerOrNull(item.getMaxUnitCount()),
                    blankToNull(item.getStatus())));
        }
        return payloads;
    }

    private record StorageLocationWriterPayload(
            UUID siteId,
            String name,
            String type,
            String parentName,
            String storageCategoryCode,
            Boolean hazardContainment,
            String allowNewProduct,
            Integer maxUnitCount,
            String status) {}

    private List<BayWriterPayload> mapBayPayloads(List<BayLoaderRecord> items) {
        List<BayWriterPayload> payloads = new ArrayList<>(items.size());
        for (BayLoaderRecord item : items) {
            payloads.add(new BayWriterPayload(
                    UUID.fromString(item.getLocationId().trim()),
                    item.getName(),
                    item.getBayType(),
                    parseIntegerOrNull(item.getMaxConcurrentVehicles()),
                    blankToNull(item.getStatus())));
        }
        return payloads;
    }

    private record BayWriterPayload(
            UUID locationId, String name, String bayType, Integer maxConcurrentVehicles, String status) {}

    private List<MobileUnitWriterPayload> mapMobileUnitPayloads(List<MobileUnitLoaderRecord> items) {
        List<MobileUnitWriterPayload> payloads = new ArrayList<>(items.size());
        for (MobileUnitLoaderRecord item : items) {
            payloads.add(new MobileUnitWriterPayload(
                    UUID.fromString(item.getBaseLocationId().trim()),
                    item.getName(),
                    blankToNull(item.getStatus()),
                    blankToNull(item.getNotes())));
        }
        return payloads;
    }

    private record MobileUnitWriterPayload(UUID baseLocationId, String name, String status, String notes) {}

    private List<StaffingAssignmentWriterPayload> mapStaffingAssignmentPayloads(
            List<StaffingAssignmentLoaderRecord> items) {
        List<StaffingAssignmentWriterPayload> payloads = new ArrayList<>(items.size());
        for (StaffingAssignmentLoaderRecord item : items) {
            payloads.add(new StaffingAssignmentWriterPayload(
                    item.getEmployeeNumber(),
                    UUID.fromString(item.getLocationId().trim()),
                    item.getRole(),
                    parseBooleanOrNull(item.getPrimary()),
                    blankToNull(item.getEffectiveFrom()),
                    blankToNull(item.getEffectiveTo())));
        }
        return payloads;
    }

    private record StaffingAssignmentWriterPayload(
            String employeeNumber,
            UUID locationId,
            String role,
            Boolean primary,
            String effectiveFrom,
            String effectiveTo) {}

    private List<PutawayRuleWriterPayload> mapPutawayRulePayloads(List<PutawayRuleLoaderRecord> items) {
        List<PutawayRuleWriterPayload> payloads = new ArrayList<>(items.size());
        for (PutawayRuleLoaderRecord item : items) {
            payloads.add(new PutawayRuleWriterPayload(
                    parseIntegerOrNull(item.getPriority()),
                    item.getMatchType().trim(),
                    parseUuidOrNull(item.getMatchValue()),
                    UUID.fromString(item.getDestinationLocationId().trim()),
                    blankToNull(item.getDestinationStrategy()),
                    parseBooleanOrNull(item.getIsEnabled())));
        }
        return payloads;
    }

    private record PutawayRuleWriterPayload(
            Integer priority,
            String matchType,
            UUID matchValue,
            UUID destinationLocationId,
            String destinationStrategy,
            Boolean isEnabled) {}

    private List<CycleCountPlanWriterPayload> mapCycleCountPlanPayloads(List<CycleCountPlanLoaderRecord> items) {
        List<CycleCountPlanWriterPayload> payloads = new ArrayList<>(items.size());
        for (CycleCountPlanLoaderRecord item : items) {
            List<UUID> zoneIds = new ArrayList<>();
            for (String zoneId : item.getZoneIds().split(",")) {
                if (!zoneId.isBlank()) {
                    zoneIds.add(UUID.fromString(zoneId.trim()));
                }
            }
            payloads.add(new CycleCountPlanWriterPayload(
                    UUID.fromString(item.getLocationId().trim()),
                    item.getPlanName(),
                    zoneIds,
                    blankToNull(item.getScheduledDate()),
                    parseIntegerOrNull(item.getScheduledDaysOut())));
        }
        return payloads;
    }

    private record CycleCountPlanWriterPayload(
            UUID locationId, String planName, List<UUID> zoneIds, String scheduledDate, Integer scheduledDaysOut) {}

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Boolean parseBooleanOrNull(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.trim());
    }

    private Integer parseIntegerOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private UUID parseUuidOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
    }

    private record LocationWriterPayload(
            String name,
            String code,
            String addressLine1,
            String addressLine2,
            String city,
            String stateOrProvince,
            String postalCode,
            String countryCode,
            String phoneNumber,
            Boolean active,
            String locationTypeName,
            String timezone) {}

    private record VehicleWriterPayload(
            UUID accountId,
            String vin,
            String unitNumber,
            String description,
            String make,
            String model,
            Integer year,
            String trim,
            String licensePlate,
            String licensePlateJurisdiction) {}

    private record FitmentWriterPayload(
            Long partNumberId,
            String manufacturerName,
            String makeName,
            String modelName,
            String vehicleTypeName,
            String vehicleYear,
            String engineType,
            String submodel,
            String notes) {}
}
