package com.positivity.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.web.common.DataIntegrityViolations.Classification;
import com.positivity.web.common.DataIntegrityViolations.Kind;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DataIntegrityViolationsTest {

    private static DataIntegrityViolationException wrap(SQLException sqlException) {
        return new DataIntegrityViolationException(
                "could not execute statement", new RuntimeException("jpa wrapper", sqlException));
    }

    @Test
    void classifiesPostgresUniqueViolationWithConstraintName() {
        SQLException cause = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_party_customer_number\"", "23505");

        Classification classification = DataIntegrityViolations.classify(wrap(cause));

        assertThat(classification.kind()).isEqualTo(Kind.UNIQUE);
        assertThat(classification.constraintName()).isEqualTo("uq_party_customer_number");
    }

    @Test
    void classifiesH2UniqueViolationWithIndexName() {
        SQLException cause = new SQLException(
                "Unique index or primary key violation: \"PUBLIC.UQ_PARTY_CUSTOMER_NUMBER_INDEX_4 ON"
                        + " PUBLIC.PARTY(CUSTOMER_NUMBER NULLS FIRST) VALUES ( /* 1 */ 'C-1001' )\"",
                "23505");

        Classification classification = DataIntegrityViolations.classify(wrap(cause));

        assertThat(classification.kind()).isEqualTo(Kind.UNIQUE);
        assertThat(classification.constraintName()).isEqualTo("UQ_PARTY_CUSTOMER_NUMBER_INDEX_4");
    }

    @Test
    void classifiesPostgresNotNullViolationWithColumnName() {
        SQLException cause = new SQLException(
                "ERROR: null value in column \"created_by\" of relation \"purchase_order\" violates not-null"
                        + " constraint",
                "23502");

        Classification classification = DataIntegrityViolations.classify(wrap(cause));

        assertThat(classification.kind()).isEqualTo(Kind.NOT_NULL);
        assertThat(classification.columnName()).isEqualTo("created_by");
    }

    @Test
    void classifiesH2NotNullViolationWithColumnName() {
        SQLException cause =
                new SQLException("NULL not allowed for column \"CREATED_BY\"; SQL statement: insert ...", "23502");

        Classification classification = DataIntegrityViolations.classify(wrap(cause));

        assertThat(classification.kind()).isEqualTo(Kind.NOT_NULL);
        assertThat(classification.columnName()).isEqualTo("CREATED_BY");
    }

    @Test
    void classifiesCheckViolationForBothPostgresAndH2States() {
        SQLException postgres = new SQLException(
                "ERROR: new row for relation \"order_line\" violates check constraint \"chk_quantity_positive\"",
                "23514");
        SQLException h2 = new SQLException("Check constraint violation: \"CHK_QUANTITY_POSITIVE ...\"", "23513");

        assertThat(DataIntegrityViolations.classify(wrap(postgres)).kind()).isEqualTo(Kind.CHECK);
        assertThat(DataIntegrityViolations.classify(wrap(postgres)).constraintName())
                .isEqualTo("chk_quantity_positive");
        assertThat(DataIntegrityViolations.classify(wrap(h2)).kind()).isEqualTo(Kind.CHECK);
    }

    @Test
    void classifiesForeignKeyViolation() {
        SQLException postgres = new SQLException(
                "ERROR: insert or update on table \"order_line\" violates foreign key constraint"
                        + " \"fk_order_line_order\"",
                "23503");
        SQLException h2Child = new SQLException("Referential integrity constraint violation", "23506");

        assertThat(DataIntegrityViolations.classify(wrap(postgres)).kind()).isEqualTo(Kind.FOREIGN_KEY);
        assertThat(DataIntegrityViolations.classify(wrap(postgres)).constraintName())
                .isEqualTo("fk_order_line_order");
        assertThat(DataIntegrityViolations.classify(wrap(h2Child)).kind()).isEqualTo(Kind.FOREIGN_KEY);
    }

    @Test
    void classifiesOtherIntegrityStatesAsOtherIntegrity() {
        SQLException generic = new SQLException("integrity constraint violation", "23000");

        assertThat(DataIntegrityViolations.classify(wrap(generic)).kind()).isEqualTo(Kind.OTHER_INTEGRITY);
    }

    @Test
    void classifiesMissingSqlExceptionOrStateAsUnknown() {
        DataIntegrityViolationException noSqlCause =
                new DataIntegrityViolationException("no sql cause", new RuntimeException("boom"));
        SQLException noState = new SQLException("driver gave no state", (String) null);
        SQLException nonIntegrityState = new SQLException("serialization failure", "40001");

        assertThat(DataIntegrityViolations.classify(noSqlCause).kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(DataIntegrityViolations.classify(wrap(noState)).kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(DataIntegrityViolations.classify(wrap(nonIntegrityState)).kind())
                .isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void identifiesServerPopulatedAuditColumnsCaseInsensitively() {
        assertThat(DataIntegrityViolations.isServerPopulatedAuditColumn("created_by"))
                .isTrue();
        assertThat(DataIntegrityViolations.isServerPopulatedAuditColumn("CREATED_BY"))
                .isTrue();
        assertThat(DataIntegrityViolations.isServerPopulatedAuditColumn("updated_at"))
                .isTrue();
        assertThat(DataIntegrityViolations.isServerPopulatedAuditColumn("customer_number"))
                .isFalse();
        assertThat(DataIntegrityViolations.isServerPopulatedAuditColumn(null)).isFalse();
    }
}
