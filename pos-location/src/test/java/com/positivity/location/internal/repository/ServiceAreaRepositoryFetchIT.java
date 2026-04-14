package com.positivity.location.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import java.util.LinkedHashSet;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:location_test_db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driverClassName=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@ActiveProfiles("test")
@Transactional
class ServiceAreaRepositoryFetchIT {

    @Autowired
    private ServiceAreaRepository serviceAreaRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void findAll_keepsPostalCodesLazyUntilAccessed() {
        ServiceAreaEntity entity = ServiceAreaEntity.builder()
                .name("North Cluster")
                .description("Cluster")
                .active(Boolean.TRUE)
                .postalCodes(new LinkedHashSet<>(List.of(
                        ServiceAreaPostalCodeValue.builder()
                                .postalCode("98101")
                                .countryCode("US")
                                .build(),
                        ServiceAreaPostalCodeValue.builder()
                                .postalCode("98102")
                                .countryCode("US")
                                .build())))
                .build();
        serviceAreaRepository.saveAndFlush(entity);
        entityManager.clear();

        ServiceAreaEntity loaded = serviceAreaRepository.findAll().getFirst();

        assertThat(Hibernate.isInitialized(loaded.getPostalCodes())).isFalse();

        assertThat(loaded.getPostalCodes()).hasSize(2);
        assertThat(Hibernate.isInitialized(loaded.getPostalCodes())).isTrue();
    }
}
