package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
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

    @Value("${pos.catalog.base-url:http://localhost:8082}")
    private String catalogBaseUrl;

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
}
