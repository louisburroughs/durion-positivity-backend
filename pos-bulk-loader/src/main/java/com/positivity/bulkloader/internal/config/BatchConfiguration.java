package com.positivity.bulkloader.internal.config;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import com.positivity.bulkloader.internal.domain.VehicleBulkRecord;
import com.positivity.bulkloader.internal.domain.VehicleFitmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleFitmentRecord;
import com.positivity.bulkloader.internal.domain.VehicleLoaderStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BatchConfiguration {

    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_USER = "X-User";
    private static final String BULK_LOADER_SERVICE_USER = "bulk-loader-service";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CatalogLoaderStrategy catalogLoaderStrategy;
    private final CustomerLoaderStrategy customerLoaderStrategy;
    private final PersonLoaderStrategy personLoaderStrategy;
    private final BasePriceLoaderStrategy basePriceLoaderStrategy;
    private final VehicleLoaderStrategy vehicleLoaderStrategy;
    private final VehicleFitmentLoaderStrategy vehicleFitmentLoaderStrategy;

    @Value("${pos.catalog.base-url:http://localhost:8082}")
    private String catalogBaseUrl;

    @Value("${pos.customer.base-url:http://localhost:8086}")
    private String customerBaseUrl;

    @Value("${pos.people.base-url:http://localhost:8087}")
    private String peopleBaseUrl;

    @Value("${pos.price.base-url:http://localhost:8088}")
    private String priceBaseUrl;

    @Value("${pos.vehicle-inventory.base-url:http://localhost:8091}")
    private String vehicleInventoryBaseUrl;

    @Value("${pos.vehicle-fitment.base-url:http://localhost:8092}")
    private String vehicleFitmentBaseUrl;

    @Value("${bulk-loader.storage.local-root:/tmp/bulk-loader}")
    private String storageRoot;

    @Bean
    public Job catalogBulkLoadJob(Step catalogBulkLoadStep) {
        return new JobBuilder("catalogBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(catalogBulkLoadStep)
                .build();
    }

    @Bean
    public Step catalogBulkLoadStep(
            FlatFileItemReader<CatalogProductRecord> catalogCsvReader,
            ItemProcessor<CatalogProductRecord, CatalogProductRecord> catalogItemProcessor,
            ItemWriter<CatalogProductRecord> catalogBulkIngestWriter) {
        return new StepBuilder("catalogBulkLoadStep", jobRepository)
                .<CatalogProductRecord, CatalogProductRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(catalogCsvReader)
                .processor(catalogItemProcessor)
                .writer(catalogBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<CatalogProductRecord> catalogCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<CatalogProductRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(CatalogProductRecord.class);
        return new FlatFileItemReaderBuilder<CatalogProductRecord>()
                .name("catalogCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names("sku", "upc", "name", "description", "categoryName", "subcategoryName", "price")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<CatalogProductRecord, CatalogProductRecord> catalogItemProcessor() {
        return item -> {
            List<String> errors = catalogLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Catalog record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<CatalogProductRecord> catalogBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(catalogBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("catalogBulkIngestWriter", jobIdParam, locationIdParam,
                    chunk.size());
            if (context == null) {
                return;
            }

            BulkIngestRequest<CatalogProductRecord> request = buildBulkIngestRequest(context, operatorId,
                    new ArrayList<>(chunk.getItems()));

            try {
                client.post()
                        .uri("/v1/catalog/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "catalog:product:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "catalogBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    @Bean
    public Job customerBulkLoadJob(Step customerBulkLoadStep) {
        return new JobBuilder("customerBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(customerBulkLoadStep)
                .build();
    }

    @Bean
    public Step customerBulkLoadStep(
            FlatFileItemReader<CustomerPersonRecord> customerCsvReader,
            ItemProcessor<CustomerPersonRecord, CustomerPersonRecord> customerItemProcessor,
            ItemWriter<CustomerPersonRecord> customerBulkIngestWriter) {
        return new StepBuilder("customerBulkLoadStep", jobRepository)
                .<CustomerPersonRecord, CustomerPersonRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(customerCsvReader)
                .processor(customerItemProcessor)
                .writer(customerBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<CustomerPersonRecord> customerCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<CustomerPersonRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(CustomerPersonRecord.class);
        return new FlatFileItemReaderBuilder<CustomerPersonRecord>()
                .name("customerCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names("firstName", "lastName", "email", "phoneNumber", "primaryAddress", "customerNumber")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<CustomerPersonRecord, CustomerPersonRecord> customerItemProcessor() {
        return item -> {
            List<String> errors = customerLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Customer record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<CustomerPersonRecord> customerBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(customerBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("customerBulkIngestWriter", jobIdParam, locationIdParam,
                    chunk.size());
            if (context == null) {
                return;
            }

            BulkIngestRequest<CustomerPersonRecord> request = buildBulkIngestRequest(context, operatorId,
                    new ArrayList<>(chunk.getItems()));

            try {
                client.post()
                        .uri("/v1/customer/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "crm:party:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "customerBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    @Bean
    public Job peopleBulkLoadJob(Step peopleBulkLoadStep) {
        return new JobBuilder("peopleBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(peopleBulkLoadStep)
                .build();
    }

    @Bean
    public Step peopleBulkLoadStep(
            FlatFileItemReader<PersonRecord> peopleCsvReader,
            ItemProcessor<PersonRecord, PersonRecord> peopleItemProcessor,
            ItemWriter<PersonRecord> peopleBulkIngestWriter) {
        return new StepBuilder("peopleBulkLoadStep", jobRepository)
                .<PersonRecord, PersonRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(peopleCsvReader)
                .processor(peopleItemProcessor)
                .writer(peopleBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<PersonRecord> peopleCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<PersonRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(PersonRecord.class);
        return new FlatFileItemReaderBuilder<PersonRecord>()
                .name("peopleCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names("legalName", "preferredName", "employeeNumber", "hireDate", "primaryEmail", "primaryPhone")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<PersonRecord, PersonRecord> peopleItemProcessor() {
        return item -> {
            List<String> errors = personLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Person record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<PersonRecord> peopleBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(peopleBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("peopleBulkIngestWriter", jobIdParam, locationIdParam, chunk.size());
            if (context == null) {
                return;
            }

            BulkIngestRequest<PersonRecord> request = buildBulkIngestRequest(context, operatorId,
                    new ArrayList<>(chunk.getItems()));

            try {
                client.post()
                        .uri("/v1/people/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "people:employee:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "peopleBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    @Bean
    public Job priceBulkLoadJob(Step priceBulkLoadStep) {
        return new JobBuilder("priceBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(priceBulkLoadStep)
                .build();
    }

    @Bean
    public Step priceBulkLoadStep(
            FlatFileItemReader<BasePriceRecord> priceCsvReader,
            ItemProcessor<BasePriceRecord, BasePriceRecord> priceItemProcessor,
            ItemWriter<BasePriceRecord> priceBulkIngestWriter) {
        return new StepBuilder("priceBulkLoadStep", jobRepository)
                .<BasePriceRecord, BasePriceRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(priceCsvReader)
                .processor(priceItemProcessor)
                .writer(priceBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<BasePriceRecord> priceCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<BasePriceRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(BasePriceRecord.class);
        return new FlatFileItemReaderBuilder<BasePriceRecord>()
                .name("priceCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names("productId", "msrp", "currency", "effectiveFrom")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<BasePriceRecord, BasePriceRecord> priceItemProcessor() {
        return item -> {
            List<String> errors = basePriceLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Base price record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<BasePriceRecord> priceBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(priceBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("priceBulkIngestWriter", jobIdParam, locationIdParam, chunk.size());
            if (context == null) {
                return;
            }

            BulkIngestRequest<BasePriceRecord> request = buildBulkIngestRequest(context, operatorId,
                    new ArrayList<>(chunk.getItems()));

            try {
                client.post()
                        .uri("/v1/price/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "pricing:base_price:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "priceBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    @Bean
    public Job vehicleBulkLoadJob(Step vehicleBulkLoadStep) {
        return new JobBuilder("vehicleBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(vehicleBulkLoadStep)
                .build();
    }

    @Bean
    public Step vehicleBulkLoadStep(
            FlatFileItemReader<VehicleBulkRecord> vehicleCsvReader,
            ItemProcessor<VehicleBulkRecord, VehicleBulkRecord> vehicleItemProcessor,
            ItemWriter<VehicleBulkRecord> vehicleBulkIngestWriter) {
        return new StepBuilder("vehicleBulkLoadStep", jobRepository)
                .<VehicleBulkRecord, VehicleBulkRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(vehicleCsvReader)
                .processor(vehicleItemProcessor)
                .writer(vehicleBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<VehicleBulkRecord> vehicleCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<VehicleBulkRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(VehicleBulkRecord.class);
        return new FlatFileItemReaderBuilder<VehicleBulkRecord>()
                .name("vehicleCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names(
                        "accountId",
                        "vin",
                        "unitNumber",
                        "description",
                        "make",
                        "model",
                        "year",
                        "trim",
                        "licensePlate",
                        "licensePlateJurisdiction")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<VehicleBulkRecord, VehicleBulkRecord> vehicleItemProcessor() {
        return item -> {
            List<String> errors = vehicleLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Vehicle record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<VehicleBulkRecord> vehicleBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(vehicleInventoryBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("vehicleBulkIngestWriter", jobIdParam, locationIdParam,
                    chunk.size());
            if (context == null) {
                return;
            }

            List<VehicleWriterPayload> payloads = mapVehiclePayloads(chunk.getItems());
            BulkIngestRequest<VehicleWriterPayload> request = buildBulkIngestRequest(context, operatorId, payloads);

            try {
                client.post()
                        .uri("/v1/vehicles/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "vehicle-inventory:registry:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "vehicleBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    @Bean
    public Job vehicleFitmentBulkLoadJob(Step vehicleFitmentBulkLoadStep) {
        return new JobBuilder("vehicleFitmentBulkLoadJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(vehicleFitmentBulkLoadStep)
                .build();
    }

    @Bean
    public Step vehicleFitmentBulkLoadStep(
            FlatFileItemReader<VehicleFitmentRecord> vehicleFitmentCsvReader,
            ItemProcessor<VehicleFitmentRecord, VehicleFitmentRecord> vehicleFitmentItemProcessor,
            ItemWriter<VehicleFitmentRecord> vehicleFitmentBulkIngestWriter) {
        return new StepBuilder("vehicleFitmentBulkLoadStep", jobRepository)
                .<VehicleFitmentRecord, VehicleFitmentRecord>chunk(500)
                .transactionManager(transactionManager)
                .reader(vehicleFitmentCsvReader)
                .processor(vehicleFitmentItemProcessor)
                .writer(vehicleFitmentBulkIngestWriter)
                .faultTolerant()
                .skipLimit(Integer.MAX_VALUE)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<VehicleFitmentRecord> vehicleFitmentCsvReader(
            @Value("#{jobParameters['storagePath']}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath job parameter must not be null or blank");
        }
        java.nio.file.Path base = java.nio.file.Path.of(storageRoot).normalize().toAbsolutePath();
        java.nio.file.Path resolved = base.resolve(storagePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage path: attempted path traversal");
        }

        BeanWrapperFieldSetMapper<VehicleFitmentRecord> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(VehicleFitmentRecord.class);
        return new FlatFileItemReaderBuilder<VehicleFitmentRecord>()
                .name("vehicleFitmentCsvReader")
                .resource(new FileSystemResource(resolved))
                .delimited()
                .names(
                        "partNumberId",
                        "manufacturerName",
                        "makeName",
                        "modelName",
                        "vehicleTypeName",
                        "vehicleYear",
                        "engineType",
                        "submodel",
                        "notes")
                .fieldSetMapper(mapper)
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<VehicleFitmentRecord, VehicleFitmentRecord> vehicleFitmentItemProcessor() {
        return item -> {
            List<String> errors = vehicleFitmentLoaderStrategy.validate(item);
            if (!errors.isEmpty()) {
                log.warn("Vehicle fitment record validation failed: {}", errors);
                return null;
            }
            return item;
        };
    }

    @Bean
    @StepScope
    public ItemWriter<VehicleFitmentRecord> vehicleFitmentBulkIngestWriter(
            RestClient.Builder restClientBuilder,
            @Value("#{jobParameters['jobId'] ?: null}") String jobIdParam,
            @Value("#{jobParameters['locationId'] ?: null}") String locationIdParam,
            @Value("#{jobParameters['operatorId'] ?: null}") String operatorId) {
        RestClient client = restClientBuilder.baseUrl(vehicleFitmentBaseUrl).build();
        return chunk -> {
            JobContext context = resolveJobContext("vehicleFitmentBulkIngestWriter", jobIdParam, locationIdParam,
                    chunk.size());
            if (context == null) {
                return;
            }

            List<FitmentWriterPayload> payloads = mapFitmentPayloads(chunk.getItems());
            BulkIngestRequest<FitmentWriterPayload> request = buildBulkIngestRequest(context, operatorId, payloads);

            try {
                client.post()
                        .uri("/v1/fitments/bulk-ingest")
                        .header(HEADER_AUTHORITIES, "vehicle-fitment:hint:create")
                        .header(HEADER_USER, sanitizeHeaderValue(operatorId, BULK_LOADER_SERVICE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error(
                        "vehicleFitmentBulkIngestWriter: HTTP call failed for chunk of {} records: {}",
                        chunk.size(),
                        e.getMessage(),
                        e);
                throw e;
            }
        };
    }

    private String sanitizeHeaderValue(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String sanitizedValue = value.replaceAll("[\r\n\t]", "").trim();
        return sanitizedValue.isBlank() ? fallback : sanitizedValue;
    }

    private JobContext resolveJobContext(String writerName, String jobIdParam, String locationIdParam, int chunkSize) {
        if (jobIdParam == null || locationIdParam == null) {
            log.warn(
                    "{}: missing jobId or locationId job parameters, skipping chunk of {} records",
                    writerName,
                    chunkSize);
            return null;
        }

        try {
            return new JobContext(UUID.fromString(jobIdParam), UUID.fromString(locationIdParam));
        } catch (IllegalArgumentException _) {
            log.warn(
                    "{}: invalid jobId/locationId values (jobId={}, locationId={}), skipping chunk of {} records",
                    writerName,
                    jobIdParam,
                    locationIdParam,
                    chunkSize);
            return null;
        }
    }

    private <T> BulkIngestRequest<T> buildBulkIngestRequest(JobContext context, String operatorId, List<T> records) {
        BulkIngestRequest<T> request = new BulkIngestRequest<>();
        request.setJobId(context.jobId());
        request.setLocationId(context.locationId());
        request.setOperatorId(operatorId);
        request.setRecords(records);
        return request;
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

    private record JobContext(UUID jobId, UUID locationId) {
    }

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
            String licensePlateJurisdiction) {
    }

    private record FitmentWriterPayload(
            Long partNumberId,
            String manufacturerName,
            String makeName,
            String modelName,
            String vehicleTypeName,
            String vehicleYear,
            String engineType,
            String submodel,
            String notes) {
    }
}
