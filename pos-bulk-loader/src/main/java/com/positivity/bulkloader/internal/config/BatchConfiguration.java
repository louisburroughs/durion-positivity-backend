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
import com.positivity.bulkloader.internal.domain.LocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.LocationRecord;
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

    @Bean
    public Job catalogBulkLoadJob(Step catalogBulkLoadStep) {
        return jobFactory.job("catalogBulkLoadJob", catalogBulkLoadStep);
    }

    @Bean
    public Step catalogBulkLoadStep(
            ItemStreamReader<CatalogProductRecord> catalogReader,
            ItemProcessor<CatalogProductRecord, CatalogProductRecord> catalogItemProcessor,
            ItemWriter<CatalogProductRecord> catalogBulkIngestWriter) {
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
    public ItemProcessor<CatalogProductRecord, CatalogProductRecord> catalogItemProcessor() {
        return jobFactory.processor(catalogLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<CatalogProductRecord> catalogBulkIngestWriter(
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
            ItemProcessor<CustomerPersonRecord, CustomerPersonRecord> customerItemProcessor,
            ItemWriter<CustomerPersonRecord> customerBulkIngestWriter) {
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
    public ItemProcessor<CustomerPersonRecord, CustomerPersonRecord> customerItemProcessor() {
        return jobFactory.processor(customerLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<CustomerPersonRecord> customerBulkIngestWriter(
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
            ItemProcessor<CommercialCustomerRecord, CommercialCustomerRecord> commercialCustomerItemProcessor,
            ItemWriter<CommercialCustomerRecord> commercialCustomerBulkIngestWriter) {
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
    public ItemProcessor<CommercialCustomerRecord, CommercialCustomerRecord> commercialCustomerItemProcessor() {
        return jobFactory.processor(commercialCustomerLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<CommercialCustomerRecord> commercialCustomerBulkIngestWriter(
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
            ItemProcessor<LocationRecord, LocationRecord> locationItemProcessor,
            ItemWriter<LocationRecord> locationBulkIngestWriter) {
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
    public ItemProcessor<LocationRecord, LocationRecord> locationItemProcessor() {
        return jobFactory.processor(locationLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<LocationRecord> locationBulkIngestWriter(
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
            ItemProcessor<PersonRecord, PersonRecord> peopleItemProcessor,
            ItemWriter<PersonRecord> peopleBulkIngestWriter) {
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
    public ItemProcessor<PersonRecord, PersonRecord> peopleItemProcessor() {
        return jobFactory.processor(personLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<PersonRecord> peopleBulkIngestWriter(
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
            ItemProcessor<BasePriceRecord, BasePriceRecord> priceItemProcessor,
            ItemWriter<BasePriceRecord> priceBulkIngestWriter) {
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
    public ItemProcessor<BasePriceRecord, BasePriceRecord> priceItemProcessor() {
        return jobFactory.processor(basePriceLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<BasePriceRecord> priceBulkIngestWriter(
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
            ItemProcessor<VehicleBulkRecord, VehicleBulkRecord> vehicleItemProcessor,
            ItemWriter<VehicleBulkRecord> vehicleBulkIngestWriter) {
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
    public ItemProcessor<VehicleBulkRecord, VehicleBulkRecord> vehicleItemProcessor() {
        return jobFactory.processor(vehicleLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<VehicleBulkRecord> vehicleBulkIngestWriter(
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
            ItemProcessor<VehicleFitmentRecord, VehicleFitmentRecord> vehicleFitmentItemProcessor,
            ItemWriter<VehicleFitmentRecord> vehicleFitmentBulkIngestWriter) {
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
    public ItemProcessor<VehicleFitmentRecord, VehicleFitmentRecord> vehicleFitmentItemProcessor() {
        return jobFactory.processor(vehicleFitmentLoaderStrategy);
    }

    @Bean
    @StepScope
    public ItemWriter<VehicleFitmentRecord> vehicleFitmentBulkIngestWriter(
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

    private List<LocationWriterPayload> mapLocationPayloads(List<LocationRecord> items) {
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

    private List<VehicleWriterPayload> mapVehiclePayloads(List<VehicleBulkRecord> items) {
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

    private List<FitmentWriterPayload> mapFitmentPayloads(List<VehicleFitmentRecord> items) {
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
