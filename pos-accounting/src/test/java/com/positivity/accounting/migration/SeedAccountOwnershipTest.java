package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seed ownership (#2511 AC 15; SPEC-accounting-workspace §4.6 "Renumbered existing accounts", AW30): within
 * one tenant binding, every account code a seed file inserts is owned by exactly one seed file, so a
 * repeatable seed's upsert can never rename or retype another seed's account.
 *
 * <p>Versioned seeds count with their codes as later versioned migrations renumbered them (AW30, V11). The
 * scope is the tenant binding: V2 seeds the alpha default tenant and R__ the platform tenant's template, which
 * deliberately carries V2's generic accounts under the same codes (S37's adoption).
 */
@DisplayName("Seed account ownership (#2511 AC 15)")
class SeedAccountOwnershipTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** AW30 (V11): codes the versioned seeds wrote that a later migration renumbered. */
    private static final Map<String, String> RENUMBERED =
            Map.of("6010", "6100", "6015", "6102", "6025", "6105", "6115", "6040", "6900", "4940");

    private static final Pattern TENANT_BINDING =
            Pattern.compile("set_config\\('app\\.current_tenant',\\s*'([0-9a-f-]{36})'");
    private static final Pattern GL_ACCOUNT_INSERT =
            Pattern.compile("INSERT INTO (?:public\\.)?gl_account\\s*\\(", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every account code of a tenant binding is owned by exactly one seed file")
    void eachCodeHasOneOwner() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(MIGRATIONS)) {
            for (Path path :
                    paths.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                files.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
            }
        }

        Map<String, List<String>> owners = owners(files);

        assertThat(owners).as("the scan found the seeds' accounts").hasSizeGreaterThan(60);
        assertThat(conflicts(owners))
                .as("account codes seeded by more than one file")
                .isEmpty();
        assertThat(owners)
                .as("V2's renumbered codes and S15's accounts")
                .containsKeys(
                        "default:6100",
                        "default:6340",
                        "platform:1080",
                        "platform:3000",
                        "platform:3900",
                        "platform:6040",
                        "platform:6295",
                        "platform:6375",
                        "platform:6380",
                        "platform:4940")
                .doesNotContainKeys("default:6010", "platform:6115", "platform:6900");
    }

    @Test
    @DisplayName("a seed file adding a code another seed file owns in the same tenant fails the check")
    void aSecondOwnerIsReported() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("V2__seed_accounting.sql", """
                SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);
                INSERT INTO gl_account (version, gl_account_id, account_code, account_name) VALUES (0, 'a1', '6370', 'Office supplies');
                """);
        files.put("R__seed_other.sql", """
                SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);
                INSERT INTO gl_account (gl_account_id, account_code, account_name)
                SELECT md5(t.code)::uuid, t.code, t.name
                FROM (VALUES
                    ('6370', 'Stationery, renamed'),
                    ('6999', 'Something else')
                ) AS t(code, name)
                ON CONFLICT (tenant_id, account_code) DO UPDATE SET account_name = EXCLUDED.account_name;
                """);

        assertThat(conflicts(owners(files)))
                .containsExactly("default:6370 <- [R__seed_other.sql, V2__seed_accounting.sql]");
    }

    /** {@code <tenant binding>:<code>} to the files that insert it. */
    static Map<String, List<String>> owners(Map<String, String> files) {
        Map<String, List<String>> owners = new TreeMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String name = file.getKey();
            String sql = file.getValue();
            Matcher binding = TENANT_BINDING.matcher(sql);
            String tenant = !binding.find()
                    ? "unbound"
                    : binding.group(1).endsWith("000000000000")
                            ? "platform"
                            : binding.group(1).endsWith("000000000001") ? "default" : binding.group(1);
            boolean versioned = name.startsWith("V");
            for (String code : insertedCodes(sql)) {
                String effective = versioned ? RENUMBERED.getOrDefault(code, code) : code;
                List<String> fileOwners = owners.computeIfAbsent(tenant + ":" + effective, k -> new ArrayList<>());
                if (!fileOwners.contains(name)) {
                    fileOwners.add(name);
                }
            }
        }
        return owners;
    }

    static List<String> conflicts(Map<String, List<String>> owners) {
        List<String> conflicts = new ArrayList<>();
        owners.forEach((key, files) -> {
            if (files.size() > 1) {
                conflicts.add(key + " <- " + new TreeSet<>(files));
            }
        });
        return conflicts;
    }

    /** The account codes every {@code INSERT INTO gl_account} of {@code sql} writes. */
    static List<String> insertedCodes(String sql) {
        List<String> codes = new ArrayList<>();
        Matcher insert = GL_ACCOUNT_INSERT.matcher(sql);
        while (insert.find()) {
            int end = statementEnd(sql, insert.end());
            String statement = sql.substring(insert.start(), end);
            int fromValues = statement.toUpperCase().indexOf("FROM (VALUES");
            if (fromValues >= 0) {
                // SELECT ... FROM (VALUES (...), ...) AS t(code, ...): the column named code.
                int tuplesStart = fromValues + "FROM (VALUES".length();
                int aliasAt = statement.indexOf(") AS t(", tuplesStart);
                List<String> aliases = splitTopLevel(
                        statement.substring(aliasAt + ") AS t(".length(), statement.indexOf(')', aliasAt + 7)));
                int codeIndex = aliases.indexOf("code");
                for (String tuple : tuples(statement.substring(tuplesStart, aliasAt))) {
                    codes.add(unquote(splitTopLevel(tuple).get(codeIndex)));
                }
            } else {
                // INSERT INTO gl_account (columns) VALUES (...): the account_code column.
                int columnsEnd = statement.indexOf(')');
                List<String> columns = splitTopLevel(statement.substring(statement.indexOf('(') + 1, columnsEnd));
                int codeIndex = columns.indexOf("account_code");
                int valuesAt = statement.toUpperCase().indexOf("VALUES", columnsEnd);
                for (String tuple : tuples(statement.substring(valuesAt + "VALUES".length()))) {
                    codes.add(unquote(splitTopLevel(tuple).get(codeIndex)));
                }
            }
        }
        return codes;
    }

    private static int statementEnd(String sql, int from) {
        boolean quoted = false;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                return i;
            }
        }
        return sql.length();
    }

    /** The contents of each top-level parenthesised tuple in {@code text}. */
    private static List<String> tuples(String text) {
        List<String> tuples = new ArrayList<>();
        boolean quoted = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (!quoted && c == ')') {
                if (--depth == 0) {
                    tuples.add(text.substring(start, i));
                } else if (depth < 0) {
                    break;
                }
            }
        }
        return tuples;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString().trim());
        return parts;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("'") ? trimmed.substring(1, trimmed.indexOf('\'', 1)) : trimmed;
    }
}
