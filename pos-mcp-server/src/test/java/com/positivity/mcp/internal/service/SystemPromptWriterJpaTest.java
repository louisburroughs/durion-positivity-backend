package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.mcp.internal.config.JpaAuditingConfig;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * {@link SystemPromptWriter}'s fail-soft pair against a real JPA stack: H2, Hibernate, Spring Data's
 * own repository proxy and {@link JpaTransactionManager} (#2421).
 *
 * <p>{@link SystemPromptWriterTransactionTest} stubs the repository, so its exception never passes
 * through the repository's transactional proxy or Hibernate, the two places that mark the
 * surrounding transaction rollback-only. Here they are real: a failure the writer catches has
 * already doomed the transaction, and the commit after the catch is where it would surface.
 */
@SpringJUnitConfig(SystemPromptWriterJpaTest.JpaConfig.class)
@DisplayName("SystemPromptWriter fail-soft writes on a real JPA transaction manager (#2421)")
class SystemPromptWriterJpaTest {

    @Autowired
    private SystemPromptWriter writer;

    @Autowired
    private SystemPromptRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("upsert writes and remove deletes the row on the happy path")
    void upsertAndRemoveRoundTrip() {
        writer.upsert("ROLE_ADMIN", "persona");
        assertThat(repository.findByName("ROLE_ADMIN"))
                .get()
                .extracting(SystemPrompt::getContent)
                .isEqualTo("persona");

        writer.remove("ROLE_ADMIN");
        assertThat(repository.existsByName("ROLE_ADMIN")).isFalse();
    }

    @Test
    @DisplayName("upsert swallows a write the database rejects, rather than failing the commit after its catch")
    void upsertSwallowsARejectedWrite() {
        // system_prompt.name is varchar(120): the insert fails at flush, inside saveAndFlush.
        String tooLong = "R".repeat(121);

        assertThatCode(() -> writer.upsert(tooLong, "persona")).doesNotThrowAnyException();

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("upsert and remove swallow a failing lookup, rather than failing the commit after their catch")
    void failSoftVariantsSwallowAFailingLookup() {
        jdbc.execute("ALTER TABLE system_prompt RENAME TO system_prompt_away");
        try {
            assertThatCode(() -> writer.upsert("ROLE_ADMIN", "persona")).doesNotThrowAnyException();
            assertThatCode(() -> writer.remove("ROLE_ADMIN")).doesNotThrowAnyException();
        } finally {
            jdbc.execute("ALTER TABLE system_prompt_away RENAME TO system_prompt");
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(
            basePackageClasses = SystemPromptRepository.class,
            includeFilters =
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SystemPromptRepository.class))
    @Import({JpaAuditingConfig.class, SystemPromptWriter.class})
    static class JpaConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:system-prompt-writer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setManagedTypes(PersistenceManagedTypes.of(List.of(SystemPrompt.class.getName()), List.of()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
