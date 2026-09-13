package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.catalog.PostgresSliceTestBase;
import com.positivity.catalog.internal.entity.PriceBookEntity;
import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What migration {@code V3__price_book_rule_status_drop_not_applicable.sql} is for: the database
 * refuses a price-book rule status outside {@code ACTIVE} and {@code INACTIVE}.
 *
 * <h2>Why this cannot be written through the repository</h2>
 *
 * {@code PriceBookRuleStatus} no longer has a constant for the removed value, so no JPA write can
 * express the row this test needs. It therefore inserts through native SQL, which is also the only
 * way the value could reach the table in production — a hand-written data fix or an import — and so
 * is exactly the case the constraint exists to stop.
 *
 * <p>Without this, nothing pins the migration's effect. The module's other PostgreSQL tests prove
 * only that Flyway applies V3 and that the two surviving statuses still round-trip; a V3 that
 * dropped the old constraint and added one still admitting the removed literal would pass them all.
 *
 * <p>The accepted case is asserted alongside the rejected one deliberately. The two inserts differ
 * in the status column and nothing else, so a failure caused by some other column — a NOT NULL, the
 * foreign key, row-level security — would fail both and be visible, rather than leaving the
 * rejection test passing for a reason that has nothing to do with the constraint under test.
 *
 * <p>Requires Docker.
 */
@DisplayName("price_book_rule_status_check after V3")
class PriceBookRuleStatusConstraintTest extends PostgresSliceTestBase {

    private static final String REMOVED_STATUS = "NOT_APPLICABLE_MISSING_BASE";

    @Autowired
    private PriceBookRepository priceBooks;

    @Autowired
    private EntityManager entityManager;

    private UUID priceBookId;

    @BeforeEach
    void createBook() {
        PriceBookEntity entity = new PriceBookEntity();
        entity.setName("status-constraint-" + UUID.randomUUID());
        entity.setScope(PriceBookScope.COMPANY_DEFAULT);
        entity.setStatus(PriceBookStatus.ACTIVE);
        entity.setDefault(false);
        priceBookId = priceBooks.saveAndFlush(entity).getPriceBookId();
    }

    @Test
    void rejectsTheRemovedStatus() {
        assertThatThrownBy(() -> insertRuleWithStatus(REMOVED_STATUS))
                .as("the check constraint must refuse a status the enum no longer has")
                .hasStackTraceContaining("price_book_rule_status_check");
    }

    @Test
    void stillAcceptsAStatusThatSurvived() {
        assertThatCode(() -> insertRuleWithStatus("ACTIVE"))
                .as("the narrowed constraint must still admit the statuses the application writes")
                .doesNotThrowAnyException();
    }

    private void insertRuleWithStatus(String status) {
        entityManager
                .createNativeQuery("""
                        INSERT INTO price_book_rule (
                            rule_id, price_book_id, created_by_user_id, priority,
                            created_at, updated_at, effective_start_at,
                            target_type, condition_type, status, pricing_logic)
                        VALUES (
                            gen_random_uuid(), :priceBookId, :userId, 0,
                            now(), now(), now(),
                            'SKU', 'NONE', :status, '{"type":"MARKUP","value":10}')
                        """)
                .setParameter("priceBookId", priceBookId)
                .setParameter("userId", UUID.fromString("01900000-0000-7000-8000-0000000000c1"))
                .setParameter("status", status)
                .executeUpdate();
        entityManager.flush();
    }
}
