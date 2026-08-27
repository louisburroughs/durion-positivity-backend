package com.positivity.inventory.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * Shared plumbing for running a Flyway migration verbatim against H2 (issue #1514).
 *
 * <p>Flyway is disabled under the {@code test} profile, so no Spring-context test ever executes a
 * migration script. These tests are the only thing that proves a hand-written migration parses on
 * H2 — which matters because the {@code dev} profile runs H2 — and that its CHECK constraints
 * actually reject what they claim to.
 */
final class MigrationScripts {

    private MigrationScripts() {}

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static int count(Connection connection, String table, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Splits a migration resource into executable statements: {@code --} comment lines are dropped
     * and the remainder is split on {@code ;}. The migrations this is used on deliberately contain
     * no semicolon inside a literal, so a plain split is exact.
     */
    static List<String> statements(String resource) throws IOException {
        try (InputStream in = MigrationScripts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing migration resource " + resource);
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String withoutComments = script.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + "\n" + right);
            return Arrays.stream(withoutComments.split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        }
    }
}
