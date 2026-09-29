package com.positivity.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingPostgresContainer.Server;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link AccountingPostgresContainer} that need no database: which server the environment
 * selects, how a misconfiguration is reported, and how a JDBC URL is re-pointed at a per-run database.
 * The create-then-drop lifecycle against a real server is {@code AccountingPostgresContainerExternalModeIT}.
 */
@DisplayName("AccountingPostgresContainer — choosing and addressing the tests' Postgres")
class AccountingPostgresContainerTest {

    private static final String URL = "jdbc:postgresql://db.example:5433/postgres";

    @Nested
    @DisplayName("choosing the server from the environment")
    class ChoosingTheServer {

        @Test
        @DisplayName("without POS_TEST_PG_URL the Testcontainers container is used, with the pos_app role")
        void withoutTheUrlTheContainerIsUsed() {
            Server server = Server.fromEnvironment(Map.of());

            assertThat(server.external()).isFalse();
            assertThat(server.appRole()).isEqualTo("pos_app");
            assertThat(server.databaseName("journal-entry-numbering")).isEqualTo("journal-entry-numbering");
        }

        @Test
        @DisplayName("a blank POS_TEST_PG_URL counts as unset")
        void aBlankUrlCountsAsUnset() {
            assertThat(Server.fromEnvironment(Map.of(AccountingPostgresContainer.URL_ENV, "   "))
                            .external())
                    .isFalse();
        }

        @Test
        @DisplayName("the URL without the user or the password is rejected, naming the missing variable")
        void theUrlAloneIsRejectedNamingTheMissingVariable() {
            assertThatThrownBy(() -> Server.fromEnvironment(Map.of(AccountingPostgresContainer.URL_ENV, URL)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(AccountingPostgresContainer.USER_ENV);

            assertThatThrownBy(() -> Server.fromEnvironment(Map.of(
                            AccountingPostgresContainer.URL_ENV, URL, AccountingPostgresContainer.USER_ENV, "owner")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(AccountingPostgresContainer.PASSWORD_ENV);
        }

        @Test
        @DisplayName("a full configuration selects the external server, with per-run database and role names")
        void aFullConfigurationSelectsTheExternalServer() {
            Server server = Server.fromEnvironment(externalEnvironment());

            assertThat(server.external()).isTrue();
            assertThat(server.keep()).isFalse();
            assertThat(server.ownerUser()).isEqualTo("owner");
            assertThat(server.ownerPassword()).isEqualTo("secret");
            assertThat(server.maintenanceUrl()).isEqualTo(URL);
            assertThat(server.appRole()).matches("pos_app_test_[0-9a-f]{8}");
            assertThat(server.defaultDatabase()).matches("acct_test_[0-9a-f]{8}_main");
            assertThat(server.databaseName("journal-entry-numbering"))
                    .matches("acct_test_[0-9a-f]{8}_journal-entry-numbering");
            assertThat(server.defaultDatabaseUrl())
                    .isEqualTo("jdbc:postgresql://db.example:5433/" + server.defaultDatabase());
            assertThat(server.createdDatabases()).isEmpty();
        }

        @Test
        @DisplayName("two runs never share a token, so their databases and roles cannot collide")
        void twoRunsGetDifferentTokens() {
            Server first = Server.fromEnvironment(externalEnvironment());
            Server second = Server.fromEnvironment(externalEnvironment());

            assertThat(first.appRole()).isNotEqualTo(second.appRole());
            assertThat(first.defaultDatabase()).isNotEqualTo(second.defaultDatabase());
        }

        @Test
        @DisplayName("POS_TEST_PG_KEEP, with any value, asks to keep the per-run databases")
        void keepIsReadFromTheEnvironment() {
            Map<String, String> environment = new java.util.HashMap<>(externalEnvironment());
            environment.put(AccountingPostgresContainer.KEEP_ENV, "");

            assertThat(Server.fromEnvironment(environment).keep()).isTrue();
        }

        private Map<String, String> externalEnvironment() {
            return Map.of(
                    AccountingPostgresContainer.URL_ENV, URL,
                    AccountingPostgresContainer.USER_ENV, "owner",
                    AccountingPostgresContainer.PASSWORD_ENV, "secret");
        }
    }

    @Nested
    @DisplayName("re-pointing a JDBC URL at a per-run database")
    class RewritingTheJdbcUrl {

        @Test
        @DisplayName("replaces the database path")
        void replacesTheDatabasePath() {
            assertThat(Server.withDatabase("jdbc:postgresql://db.example:5433/postgres", "acct_test_1_main"))
                    .isEqualTo("jdbc:postgresql://db.example:5433/acct_test_1_main");
        }

        @Test
        @DisplayName("leaves a query string alone, slashes inside its values included")
        void keepsAQueryStringWhoseValuesContainSlashes() {
            assertThat(Server.withDatabase(
                            "jdbc:postgresql://db.example:5433/postgres?sslmode=verify-full&sslrootcert=/tmp/root.crt",
                            "acct_test_1_main"))
                    .isEqualTo("jdbc:postgresql://db.example:5433/acct_test_1_main"
                            + "?sslmode=verify-full&sslrootcert=/tmp/root.crt");
        }

        @Test
        @DisplayName("adds a database path to a URL that names none")
        void addsADatabaseWhenTheUrlNamesNone() {
            assertThat(Server.withDatabase("jdbc:postgresql://db.example:5433", "acct_test_1_main"))
                    .isEqualTo("jdbc:postgresql://db.example:5433/acct_test_1_main");
            assertThat(Server.withDatabase("jdbc:postgresql://db.example:5433/", "acct_test_1_main"))
                    .isEqualTo("jdbc:postgresql://db.example:5433/acct_test_1_main");
            assertThat(Server.withDatabase("jdbc:postgresql://db.example:5433?sslmode=require", "acct_test_1_main"))
                    .isEqualTo("jdbc:postgresql://db.example:5433/acct_test_1_main?sslmode=require");
        }
    }
}
