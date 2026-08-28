package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.config.BulkIngestWriterFactory.JobParams;
import com.positivity.bulkloader.internal.config.BulkIngestWriterFactory.Target;
import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CommercialCustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CommercialCustomerRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.InventoryStockCountLoaderStrategy;
import com.positivity.bulkloader.internal.domain.InventoryStockCountRecord;
import com.positivity.bulkloader.internal.domain.LocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.LocationRecord;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.VehicleBulkRecord;
import com.positivity.bulkloader.internal.domain.VehicleFitmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleFitmentRecord;
import com.positivity.bulkloader.internal.domain.VehicleLoaderStrategy;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
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
     * have done their job and are dropped, and the quantity becomes a number. A row whose quantity
     * will not parse cannot reach here — validation rejects it first — so this stays a
     * straightforward conversion rather than another place errors can hide.
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
