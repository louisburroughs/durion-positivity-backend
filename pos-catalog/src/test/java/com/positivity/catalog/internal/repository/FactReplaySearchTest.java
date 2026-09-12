package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.PostgresSliceTestBase;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The three current-state fact replays — products (#1309), services (#1306) and supplier article
 * codes (#1347) — against the real PostgreSQL baseline.
 *
 * <h2>What this defends</h2>
 *
 * All three were written as one JPQL string of {@code (:param IS NULL OR …)} clauses, which
 * PostgreSQL rejects at parse time with {@code could not determine data type of parameter $4} —
 * the {@code updatedSince} placeholder — so every call to every replay endpoint was a 500 while the
 * H2-backed tests of the same queries passed (issue #1891). Running these against PostgreSQL is
 * therefore the whole point: on H2 they would pass against the defect too.
 *
 * <p>Each replay is exercised the same four ways, because the specification replaced a query whose
 * two filters were independently optional: unfiltered, filtered by the cursor, filtered by the
 * timestamp, and bounded by the page size.
 *
 * <h2>Why the fixtures are stamped in 2030</h2>
 *
 * The Flyway baseline seeds reference products and services into this same tenant, so an unfiltered
 * replay legitimately returns rows this test did not write. Stamping the fixtures with an
 * {@code updatedAt} far past every seed lets {@code updatedSince} isolate them exactly — which also
 * puts the placeholder that used to break the query into every assertion.
 */
@DisplayName("Fact replay searches on PostgreSQL (#1891)")
class FactReplaySearchTest extends PostgresSliceTestBase {

    /** Later than anything the baseline seeds, so it selects exactly this test's fixtures. */
    private static final Instant FIXTURE_STAMP = Instant.parse("2030-01-01T00:00:00Z");

    private static final Instant AFTER_FIXTURES = Instant.parse("2030-06-01T00:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository products;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private SupplierArticleCodeRepository supplierArticleCodes;

    /**
     * Stamps a row's {@code updatedAt} directly. Auditing owns the column on every save, so a test
     * that needs a specific instant has to write it behind the entity manager and then clear the
     * persistence context so the next read sees it.
     */
    private void stampUpdatedAt(String table, UUID id, String idColumn, Instant updatedAt) {
        entityManager.flush();
        entityManager
                .createNativeQuery("UPDATE " + table + " SET updated_at = ?1 WHERE " + idColumn + " = ?2")
                .setParameter(1, updatedAt)
                .setParameter(2, id)
                .executeUpdate();
        entityManager.clear();
    }

    private static ProductEntity product(String sku) {
        ProductEntity product = new ProductEntity();
        product.setName("replay-" + sku);
        product.setSku(sku);
        return product;
    }

    private static ServiceEntity service(String name) {
        ServiceEntity service = new ServiceEntity();
        service.setName(name);
        return service;
    }

    private static SupplierArticleCodeEntity articleCode(UUID productId, String code) {
        SupplierArticleCodeEntity entity = new SupplierArticleCodeEntity();
        entity.setVendorProfileId(UUID.fromString("01900000-0000-7000-8000-0000000000f1"));
        entity.setSupplierRef("michelin-eu");
        entity.setProductId(productId);
        entity.setSupplierArticleCode(code);
        return entity;
    }

    /**
     * Runs the four assertions every replay shares over three fixtures already stamped at {@link
     * #FIXTURE_STAMP}, in ascending id order.
     */
    private <T> void assertReplayContract(List<UUID> ids, Replay<T> replay, Function<T, UUID> idOf) {
        UUID first = ids.get(0);
        UUID second = ids.get(1);
        UUID third = ids.get(2);

        assertThat(replay.find(null, FIXTURE_STAMP, PageRequest.of(0, 50)).stream()
                        .map(idOf)
                        .toList())
                .as("every fixture, oldest id first")
                .containsExactly(first, second, third);

        assertThat(replay.find(first, FIXTURE_STAMP, PageRequest.of(0, 50)).stream()
                        .map(idOf)
                        .toList())
                .as("the cursor is exclusive: the row the previous page ended on is not repeated")
                .containsExactly(second, third);

        assertThat(replay.find(null, AFTER_FIXTURES, PageRequest.of(0, 50)))
                .as("a window past every fixture selects nothing, rather than failing to parse")
                .isEmpty();

        assertThat(replay.find(null, FIXTURE_STAMP, PageRequest.of(0, 2)).stream()
                        .map(idOf)
                        .toList())
                .as("the page size bounds the replay")
                .containsExactly(first, second);

        assertThat(replay.find(null, null, PageRequest.of(0, 500)).stream()
                        .map(idOf)
                        .toList())
                .as("both filters absent contributes no predicate at all, and still reads the table")
                .contains(first, second, third);
    }

    /** The shape all three replays share, so the contract above can be asserted once. */
    @FunctionalInterface
    private interface Replay<T> {
        List<T> find(UUID afterId, Instant updatedSince, Pageable pageable);
    }

    @Nested
    @DisplayName("product replay (#1309)")
    class ProductReplay {

        @Test
        void filtersByCursorAndUpdatedSinceAndBoundsThePage() {
            List<UUID> ids = List.of(
                    products.saveAndFlush(product("REPLAY-1")).getId(),
                    products.saveAndFlush(product("REPLAY-2")).getId(),
                    products.saveAndFlush(product("REPLAY-3")).getId());
            ids.forEach(id -> stampUpdatedAt("product", id, "id", FIXTURE_STAMP));

            assertReplayContract(ids, products::findForReplay, ProductEntity::getId);
        }
    }

    @Nested
    @DisplayName("service replay (#1306)")
    class ServiceReplay {

        @Test
        void filtersByCursorAndUpdatedSinceAndBoundsThePage() {
            List<UUID> ids = List.of(
                    services.saveAndFlush(service("replay-1")).getId(),
                    services.saveAndFlush(service("replay-2")).getId(),
                    services.saveAndFlush(service("replay-3")).getId());
            ids.forEach(id -> stampUpdatedAt("service", id, "id", FIXTURE_STAMP));

            assertReplayContract(ids, services::findForReplay, ServiceEntity::getId);
        }
    }

    @Nested
    @DisplayName("supplier article code replay (#1347)")
    class SupplierArticleCodeReplay {

        @Test
        void filtersByCursorAndUpdatedSinceAndBoundsThePage() {
            UUID productId = products.saveAndFlush(product("SAC-HOST")).getId();
            List<UUID> ids = List.of(
                    supplierArticleCodes
                            .saveAndFlush(articleCode(productId, "CODE-1"))
                            .getId(),
                    supplierArticleCodes
                            .saveAndFlush(articleCode(UUID.randomUUID(), "CODE-2"))
                            .getId(),
                    supplierArticleCodes
                            .saveAndFlush(articleCode(UUID.randomUUID(), "CODE-3"))
                            .getId());
            ids.forEach(id -> stampUpdatedAt("supplier_article_code", id, "id", FIXTURE_STAMP));

            assertReplayContract(ids, supplierArticleCodes::findForReplay, SupplierArticleCodeEntity::getId);
        }
    }
}
