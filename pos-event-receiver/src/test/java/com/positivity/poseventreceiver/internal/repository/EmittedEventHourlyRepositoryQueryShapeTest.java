package com.positivity.poseventreceiver.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.TenantAudited;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * {@code emitted_event_hourly} has no row-level security (a TimescaleDB continuous aggregate
 * cannot carry one), so the shape of every query over it is the isolation (ADR-0062 plan WS6):
 * each declared query either names the tenant column or is the one reviewed cross-tenant rollup,
 * and no inherited finder can read across tenants.
 */
@DisplayName("EmittedEventHourlyRepository — every query names the tenant or is the reviewed rollup")
class EmittedEventHourlyRepositoryQueryShapeTest {

    private static final String TENANT_PREDICATE = "h.tenantId = :tenantId";
    private static final String TIME_PREDICATE = "h.bucket >= :since";

    @Test
    void isNotAJpaRepositoryWhoseInheritedFindersWouldReadAcrossTenants() {
        assertThat(JpaRepository.class.isAssignableFrom(EmittedEventHourlyRepository.class))
                .isFalse();
        assertThat(CrudRepository.class.isAssignableFrom(EmittedEventHourlyRepository.class))
                .isFalse();
    }

    @Test
    void everyDeclaredQueryIsBoundOnTimeAndOnTheTenantUnlessItIsTheReviewedRollup() {
        List<Method> declared = Arrays.asList(EmittedEventHourlyRepository.class.getDeclaredMethods());
        assertThat(declared).isNotEmpty();

        for (Method method : declared) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query).as("%s is a declared query", method.getName()).isNotNull();
            assertThat(query.nativeQuery())
                    .as("%s stays JPQL (no native SQL without review)", method.getName())
                    .isFalse();
            assertThat(query.value())
                    .as("%s is bounded on the bucket column", method.getName())
                    .contains(TIME_PREDICATE);

            boolean namesTenant = query.value().contains(TENANT_PREDICATE);
            boolean takesTenant = Arrays.stream(method.getParameters())
                    .map(Parameter::getType)
                    .anyMatch(UUID.class::equals);
            boolean reviewed = method.isAnnotationPresent(TenantAudited.class);
            if (reviewed) {
                assertThat(method.getAnnotation(TenantAudited.class).reason())
                        .as("%s carries the reviewer's reason", method.getName())
                        .isNotBlank();
                assertThat(namesTenant)
                        .as("%s is the cross-tenant rollup and must not pretend to be scoped", method.getName())
                        .isFalse();
            } else {
                assertThat(namesTenant && takesTenant)
                        .as("%s names the tenant column and takes the tenant as a parameter", method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void thePerTenantQueryAndTheRollupAreTheOnlyTwoReads() {
        assertThat(EmittedEventHourlyRepository.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("summarizeSince", "summarizeAcrossTenantsSince");
    }
}
