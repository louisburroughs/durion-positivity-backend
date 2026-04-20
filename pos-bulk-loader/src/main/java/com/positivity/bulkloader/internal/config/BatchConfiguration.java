package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BatchConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CatalogLoaderStrategy catalogLoaderStrategy;
    private final CustomerLoaderStrategy customerLoaderStrategy;
    private final PersonLoaderStrategy personLoaderStrategy;
    private final BasePriceLoaderStrategy basePriceLoaderStrategy;

    @Value("${pos.catalog.base-url:http://localhost:8082}")
    private String catalogBaseUrl;

    @Value("${pos.customer.base-url:http://localhost:8086}")
    private String customerBaseUrl;

    @Value("${pos.people.base-url:http://localhost:8087}")
    private String peopleBaseUrl;

    @Value("${pos.price.base-url:http://localhost:8088}")
    private String priceBaseUrl;

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
    public ItemWriter<CatalogProductRecord> catalogBulkIngestWriter(RestClient.Builder restClientBuilder) {
        throw new IllegalStateException(
                "catalogBulkIngestWriter is not implemented. "
                        + "The catalog bulk load step is intentionally disabled until bulk-ingest endpoint "
                        + "integration is provided for base URL: " + catalogBaseUrl);
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
    public ItemWriter<CustomerPersonRecord> customerBulkIngestWriter(RestClient.Builder restClientBuilder) {
        throw new IllegalStateException(
                "customerBulkIngestWriter is not implemented. "
                        + "The customer bulk load step is intentionally disabled until bulk-ingest endpoint "
                        + "integration is provided for base URL: " + customerBaseUrl);
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
    public ItemWriter<PersonRecord> peopleBulkIngestWriter(RestClient.Builder restClientBuilder) {
        throw new IllegalStateException(
                "peopleBulkIngestWriter is not implemented. "
                        + "The people bulk load step is intentionally disabled until bulk-ingest endpoint "
                        + "integration is provided for base URL: " + peopleBaseUrl);
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
    public ItemWriter<BasePriceRecord> priceBulkIngestWriter(RestClient.Builder restClientBuilder) {
        throw new IllegalStateException(
                "priceBulkIngestWriter is not implemented. "
                        + "The price bulk load step is intentionally disabled until bulk-ingest endpoint "
                        + "integration is provided for base URL: " + priceBaseUrl);
    }
}
