package com.positivity.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer.Server;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The external mode's lifecycle against a real server: a second {@link Server}, configured as if
 * {@code POS_TEST_PG_URL} named whichever Postgres this run already has (the container, or the external
 * server), creates its per-run databases and role, they behave as the tests need, and
 * {@link Server#dropRunArtifacts()} removes all of them and nothing else. Built with {@code keep}, so the
 * only cleanup is the one under test.
 */
@DisplayName("AccountingPostgresContainer external mode — per-run databases and role, created then dropped")
class AccountingPostgresContainerExternalModeIT {

    @Test
    @DisplayName("creates the shared and isolated databases and the application role, then drops them all")
    void createsPerRunDatabasesAndRoleThenDropsThemAll() throws SQLException {
        DataSource catalog = AccountingPostgresContainer.ownerDataSource();
        Server target = AccountingPostgresContainer.SERVER;
        Server run = Server.external(target.maintenanceUrl(), target.ownerUser(), target.ownerPassword(), true);
        String shared = run.defaultDatabase();
        String isolated = run.databaseName("cleanup-probe");
        try {
            DataSource sharedAsOwner = run.ownerDataSource();
            DataSource isolatedAsOwner = run.ownerDataSource("cleanup-probe");

            assertThat(run.createdDatabases()).containsExactly(shared, isolated);
            assertThat(databasesLike(catalog, "acct_test_%")).contains(shared, isolated);
            assertThat(roleFlags(catalog, run.appRole()))
                    .as("LOGIN, no superuser, no BYPASSRLS, like init-tenancy.sh's pos_app")
                    .containsExactly(true, false, false);
            try (Connection connection = sharedAsOwner.getConnection()) {
                assertThat(connection.getCatalog()).isEqualTo(shared);
            }
            try (Connection connection = isolatedAsOwner.getConnection()) {
                assertThat(connection.getCatalog()).isEqualTo(isolated);
            }
            try (Connection connection = AccountingPostgresContainer.dataSource(
                                    run.jdbcUrlFor(shared), run.appRole(), run.appPassword())
                            .getConnection();
                    PreparedStatement statement = connection.prepareStatement("SELECT current_user");
                    ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .as("the application role can connect to the shared database")
                        .isEqualTo(run.appRole());
            }

            run.dropRunArtifacts();

            assertThat(run.createdDatabases()).isEmpty();
            assertThat(databasesLike(catalog, "acct_test_%")).doesNotContain(shared, isolated);
            assertThat(roleFlags(catalog, run.appRole()))
                    .as("the per-run role is gone")
                    .isEmpty();
            assertThat(roleFlags(catalog, target.appRole()))
                    .as("the run's own application role is untouched")
                    .isNotEmpty();
            assertThat(databasesLike(catalog, target.defaultDatabase()))
                    .as("the run's own shared database is untouched")
                    .containsExactly(target.defaultDatabase());
        } finally {
            run.dropRunArtifacts();
        }
    }

    private static List<String> databasesLike(DataSource catalog, String pattern) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = catalog.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT datname FROM pg_database WHERE datname LIKE ? ORDER BY 1")) {
            statement.setString(1, pattern);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    /** {@code [rolcanlogin, rolsuper, rolbypassrls]} of the role, or empty when it does not exist. */
    private static List<Boolean> roleFlags(DataSource catalog, String role) throws SQLException {
        try (Connection connection = catalog.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rolcanlogin, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return List.of();
                }
                return List.of(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3));
            }
        }
    }
}
